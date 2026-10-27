/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.util.concurrent.ConcurrentHashMap

/**
 * 3D 骨骼姿态预设与关节微调 -> 骨骼局部欧拉旋转映射表
 *
 * 纯 Kotlin 实现 (不依赖 Android / ViewModel), 便于单元测试覆盖:
 * 预设数值、角度是否被重复施加、以及兜底键的解析规则都在这里收口。
 */
object PosePresets {

    /** 姿态预设标识 */
    const val STAND = "stand"
    const val SIT = "sit"
    const val ACTION = "action"
    const val CROSSED = "crossed"
    const val HIPS = "hips"
    const val KNEEL = "kneel"
    const val LOAF = "loaf"
    const val STRETCH = "stretch"
    const val WALK = "walk"
    const val CURL = "curl"
    const val POUNCE = "pounce"
    const val REAR = "rear"
    const val LOOK = "look"

    /** 姿态面板里由语义分支消费的键: 兜底解析必须跳过, 否则同一角度会被施加两次 */
    private val CAT_SEMANTIC_KEYS = setOf(
        "cat_head_pitch", "cat_head_yaw", "cat_spine_arch",
        "cat_fl_leg", "cat_fr_leg", "cat_hl_leg", "cat_hr_leg",
        "cat_tail_pitch", "cat_tail_yaw",
    )
    private val HUMANOID_SEMANTIC_KEYS = setOf(
        "spine_pitch", "spine_yaw", "head_pitch", "head_yaw",
        "l_arm_pitch", "l_arm_yaw", "l_elbow",
        "r_arm_pitch", "r_arm_yaw", "r_elbow",
        "l_thigh_pitch", "l_thigh_yaw", "l_knee",
        "r_thigh_pitch", "r_thigh_yaw", "r_knee",
    )

    private val groundShiftCache = ConcurrentHashMap<String, Float>()

    /**
     * 美术标定过的整体升降 (glTF: +Y 向上)
     *
     * 骨架层只能旋转, 无法像根节点位移那样把骨盆压到地面 —— 端坐/农民揣要"身体贴地"就只能在整模型上补一个升降。
     * 这三条是离线渲染校准过的构图参数 (见 docs/3D-SKELETON.md), 换猫猫资产时需要重新标定;
     * 其余组合走下面的几何自动贴地。
     */
    private val TUNED_PRESET_SHIFT: Map<String, Map<String, Float>> = emptyMap()

    // 说明: 这张表保留机制但当前为空 —— 所有姿态都由下面的几何贴地处理。
    // 根骨平移 (Hips_ty) 与整模型升降等价 (骨架整体平移, 贴地又会精确抵消),
    // 所以"压低身体"只能靠折叠四肢的真实骨骼姿态, 不能再靠整体升降。

    fun isCat(modelType: String): Boolean = modelType.equals("cat", ignoreCase = true)

    /** 弯曲滑杆键: 0~100 (百分比), 与预设自带弯曲量叠加 */
    const val CAT_CURL_KEY = "cat_curl"

    /**
     * 骨骼局部平移表 (键名 <骨骼>_tx/_ty/_tz, 单位见 UI: 面板滑杆 /100)
     *
     * 这是"骨盆下沉"这类自由度: 骨架只有旋转时，端坐/伏卧只能靠整模型升降假动作补偿;
     * 有了平移就能让骨盆真正落到地面，网格相对地面形变，而不是整体挪位置。
     */
    fun buildTranslations(
        modelType: String,
        posePreset: String,
        jointAngles: Map<String, Float>,
    ): Map<String, FloatArray> {
        val map = HashMap<String, FloatArray>()
        fun put(bone: String, tx: Float, ty: Float, tz: Float) {
            if (tx == 0f && ty == 0f && tz == 0f) return
            map[bone] = floatArrayOf(tx, ty, tz)
        }
        // 注意: 不要给根骨 (Hips) 加预设平移 —— 根骨平移等于整模型平移, 贴地又会把它精确抵消,
        // 看上去"什么都没发生"; 压低身体只能靠折叠四肢的真实姿态 (见 SIT/CURL)。
        for ((key, value) in jointAngles) {
            val (bone, axis) = when {
                key.endsWith("_tx") -> key.removeSuffix("_tx") to 0
                key.endsWith("_ty") -> key.removeSuffix("_ty") to 1
                key.endsWith("_tz") -> key.removeSuffix("_tz") to 2
                else -> continue
            }
            if (bone.isEmpty()) continue
            val arr = map.getOrPut(bone) { FloatArray(3) }
            arr[axis] += value / 100f
        }
        return map
    }

    /**
     * 猫猫蜷缩弯曲量 —— **暂时停用**。
     *
     * 程序化沿身体前向做圆弧弯曲时, 截面被按 cosθ 缩放却没有对应的工作空间校正,
     * 躯干会被明显拉伸变形 (实机反馈"模型拉伸得不成样子")。资产已重绑出脊柱中段骨
     * spine_mid (见 scripts/model/add_spine_mid.py), 躯干改由真实骨骼弯折, 这条顶点弯曲路径
     * 不再需要, 保留函数只为不改变既有调用签名。
     */
    fun catCurlBend(modelType: String, posePreset: String, jointAngles: Map<String, Float>): Float = 0f

    /**
     * 计算预设姿态的整体升降量 (glTF: +Y 向上)。
     *
     * 优先用美术标定值 (骨架无法表达骨盆位移的姿态); 未标定的组合则用"预设本身"形变一次,
     * 把网格最低点对齐到绑定姿态的地面基准线 —— 结果与模型无关, 换模型不会失配。
     * 只用预设 (不含用户微调角度) 计算, 保证调节关节时模型不会上下漂移。
     */
    fun groundShiftForPreset(skinned: SkinnedMesh3D, modelType: String, posePreset: String): Float {
        if (skinned.skeleton == null) return 0f
        val preset = posePreset.lowercase()
        TUNED_PRESET_SHIFT[modelType.lowercase()]?.get(preset)?.let { return it }

        val key = "$modelType#$preset"
        groundShiftCache[key]?.let { return it }
        val euler = buildEulerMap(modelType, preset, emptyMap())
        val mesh = skinned.createDeformedMesh(
            euler,
            null,
            null,
            0f,
            catCurlBend(modelType, preset, emptyMap()),
            buildTranslations(modelType, preset, emptyMap()),
        )
        val shift = skinned.restMinY - skinned.minYOf(mesh)
        if (groundShiftCache.size > 64) groundShiftCache.clear()
        groundShiftCache[key] = shift
        return shift
    }

    /**
     * 将姿态预设与关节点微调角度组合为骨骼局部欧拉旋转映射表 [BoneName -> [Pitch, Yaw, Roll]]
     */
    fun buildEulerMap(
        modelType: String,
        posePreset: String,
        jointAngles: Map<String, Float>,
    ): Map<String, FloatArray> {
        val b = Builder()
        if (isCat(modelType)) buildCatPose(b, posePreset, jointAngles) else buildHumanoidPose(b, posePreset, jointAngles)

        // 通用骨骼直接旋转支持 (支持任意 GLB / VRM / PMX 骨骼名称)
        // 键名约定: <骨骼名>_p / <骨骼名>_pitch, <骨骼名>_y / _yaw, <骨骼名>_r / _roll
        val consumed = if (isCat(modelType)) CAT_SEMANTIC_KEYS else HUMANOID_SEMANTIC_KEYS
        for ((key, value) in jointAngles) {
            if (key in consumed) continue
            val (boneName, axis) = when {
                key.endsWith("_pitch") -> key.removeSuffix("_pitch") to 0
                key.endsWith("_yaw") -> key.removeSuffix("_yaw") to 1
                key.endsWith("_roll") -> key.removeSuffix("_roll") to 2
                key.endsWith("_p") -> key.removeSuffix("_p") to 0
                key.endsWith("_y") -> key.removeSuffix("_y") to 1
                key.endsWith("_r") -> key.removeSuffix("_r") to 2
                else -> continue
            }
            if (boneName.isEmpty()) continue
            b.addTo(boneName, axis, value)
        }
        return b.build()
    }

    // ------------------------------------------------------------------
    // 猫猫
    // ------------------------------------------------------------------

    private fun buildCatPose(b: Builder, posePreset: String, jointAngles: Map<String, Float>) {
        // 预设角度全部来自 CatPosePresets.TABLE (由 scripts/model/cat_pose_solver.py 生成)。
        // 新骨架 (2026-10 重绑): 躯干 5 段 root/spine_01..03/chest、颈 1 段、尾 6 段、四肢各 5 段。
        CatPosePresets.TABLE[posePreset.lowercase()]?.forEach { (bone, a) ->
            if (a[0] != 0f || a[1] != 0f || a[2] != 0f) {
                b.addTo(bone, 0, a[0])
                b.addTo(bone, 1, a[1])
                b.addTo(bone, 2, a[2])
            }
        }

        // 语义滑杆: 面板上的一个自由度摊到相关骨骼上, 不做"一根骨硬掰"
        fun slider(key: String): Float = jointAngles[key] ?: 0f

        slider("cat_head_pitch").let { if (it != 0f) b.addTo("head", 0, it) }
        // 新骨架的头骨朝前, 局部 Y 是脸朝向: 左右转头要绕局部 Z (roll), 绕 Y 只是歪头
        slider("cat_head_yaw").let { if (it != 0f) b.addTo("head", 2, it) }

        // 弓背/塌腰: 沿脊柱逐段递减, 形成弧线而不是折角
        val arch = slider("cat_spine_arch")
        if (arch != 0f) {
            b.addTo("spine_01", 0, arch * 0.40f)
            b.addTo("spine_02", 0, arch * 0.40f)
            b.addTo("spine_03", 0, arch * 0.30f)
            b.addTo("chest", 0, arch * 0.20f)
        }

        // 四肢: 每侧摊到两段关节
        val fl = slider("cat_fl_leg")
        if (fl != 0f) {
            b.addTo("shoulder_L", 0, fl * 0.5f)
            b.addTo("elbow_L", 0, fl * 0.5f)
        }
        val fr = slider("cat_fr_leg")
        if (fr != 0f) {
            b.addTo("shoulder_R", 0, fr * 0.5f)
            b.addTo("elbow_R", 0, fr * 0.5f)
        }
        val hl = slider("cat_hl_leg")
        if (hl != 0f) {
            b.addTo("hip_L", 0, hl * 0.5f)
            b.addTo("knee_L", 0, hl * 0.5f)
        }
        val hr = slider("cat_hr_leg")
        if (hr != 0f) {
            b.addTo("hip_R", 0, hr * 0.5f)
            b.addTo("knee_R", 0, hr * 0.5f)
        }

        // 尾巴: 6 段按衰减系数联动 (俯仰 + 左右摆走 roll, 绕骨轴扭转没用)
        val tailP = slider("cat_tail_pitch")
        val tailSide = slider("cat_tail_yaw")
        if (tailP != 0f || tailSide != 0f) {
            for (i in 0 until 5) {
                val decay = 1f - i * 0.18f
                if (tailP != 0f) b.addTo("tail_%02d".format(i + 1), 0, tailP * decay)
                if (tailSide != 0f) b.addTo("tail_%02d".format(i + 1), 2, tailSide * decay * 0.8f)
            }
        }
    }

    // ------------------------------------------------------------------
    // 人形
    // ------------------------------------------------------------------

    private fun buildHumanoidPose(b: Builder, posePreset: String, jointAngles: Map<String, Float>) {
        var spineP = 0f; var spineY = 0f
        var headP = 0f; var headY = 0f
        var lArmP = 0f; var lArmY = 0f; var lArmRoll = 0f
        var rArmP = 0f; var rArmY = 0f; var rArmRoll = 0f
        var lElbowP = 0f; var rElbowP = 0f
        var lThighP = 0f; var lThighY = 0f
        var rThighP = 0f; var rThighY = 0f
        var lKneeP = 0f; var rKneeP = 0f

        when (posePreset.lowercase()) {
            STAND -> {
                // 原生标准自然站姿：A-pose 模型无需额外偏移
            }
            SIT -> {
                // 坐姿: 大腿向前平抬 80°，小腿垂直下垂 80°，双手微屈搭放于腿前
                lThighP = 80f; lKneeP = -80f
                rThighP = 80f; rKneeP = -80f
                lArmP = 25f; lElbowP = 40f
                rArmP = 25f; rElbowP = 40f
                spineP = 5f
            }
            ACTION -> {
                // 动态奔跑姿态
                spineP = 12f; spineY = -10f
                headP = -6f; headY = 10f
                lArmP = 45f; lElbowP = 50f
                rArmP = -40f; rElbowP = 30f
                lThighP = 55f; lKneeP = -55f
                rThighP = -35f; rKneeP = -30f
            }
            CROSSED -> {
                // 双手抱胸
                spineP = -4f
                lArmP = 35f; lArmRoll = 30f; lElbowP = 85f
                rArmP = 35f; rArmRoll = -30f; rElbowP = 85f
            }
            HIPS -> {
                // 双手叉腰
                lArmP = -15f; lArmRoll = -25f; lElbowP = 75f
                rArmP = -15f; rArmRoll = 25f; rElbowP = 75f
            }
            KNEEL -> {
                // 单膝下跪
                spineP = 12f
                lThighP = 75f; lKneeP = -75f
                rThighP = -15f; rKneeP = -85f
                rArmP = 35f; rElbowP = 40f
            }
        }

        // 注意: 下面这些键已由语义分支读出, 兜底解析会跳过它们 (旧实现会再加一次, 角度翻倍)
        spineP += (jointAngles["spine_pitch"] ?: 0f)
        spineY += (jointAngles["spine_yaw"] ?: 0f)
        headP += (jointAngles["head_pitch"] ?: 0f)
        headY += (jointAngles["head_yaw"] ?: 0f)

        lArmP += (jointAngles["l_arm_pitch"] ?: 0f)
        lArmY += (jointAngles["l_arm_yaw"] ?: 0f)
        lElbowP += (jointAngles["l_elbow"] ?: 0f)

        rArmP += (jointAngles["r_arm_pitch"] ?: 0f)
        rArmY += (jointAngles["r_arm_yaw"] ?: 0f)
        rElbowP += (jointAngles["r_elbow"] ?: 0f)

        lThighP += (jointAngles["l_thigh_pitch"] ?: 0f)
        lThighY += (jointAngles["l_thigh_yaw"] ?: 0f)
        lKneeP -= (jointAngles["l_knee"] ?: 0f)

        rThighP += (jointAngles["r_thigh_pitch"] ?: 0f)
        rThighY += (jointAngles["r_thigh_yaw"] ?: 0f)
        rKneeP -= (jointAngles["r_knee"] ?: 0f)

        b.joint("head", "Head", headP, headY, 0f)
        b.joint("spine", "spine_01", spineP, spineY, 0f, "Spine")
        b.joint("chest", "spine_02", spineP * 0.5f, spineY * 0.5f, 0f, "Chest", "UpperChest")

        b.joint("leftUpperArm", "upperarm.l", lArmP, lArmY, lArmRoll, "LeftArm", "LeftUpperArm")
        b.joint("leftLowerArm", "lowerarm.l", lElbowP, 0f, 0f, "LeftForeArm", "LeftLowerArm")

        b.joint("rightUpperArm", "upperarm.r", rArmP, rArmY, rArmRoll, "RightArm", "RightUpperArm")
        b.joint("rightLowerArm", "lowerarm.r", rElbowP, 0f, 0f, "RightForeArm", "RightLowerArm")

        b.joint("leftUpperLeg", "thigh.l", lThighP, lThighY, 0f, "LeftUpLeg", "LeftUpperLeg", "LeftThigh")
        b.joint("leftLowerLeg", "calf.l", lKneeP, 0f, 0f, "LeftLeg", "LeftLowerLeg", "LeftShin")

        b.joint("rightUpperLeg", "thigh.r", rThighP, rThighY, 0f, "RightUpLeg", "RightUpperLeg", "RightThigh")
        b.joint("rightLowerLeg", "calf.r", rKneeP, 0f, 0f, "RightLeg", "RightLowerLeg", "RightShin")
    }

    /**
     * 别名共享同一个角度数组: 通用兜底键 (<bone>_p/_y/_r) 追加时才能读写到同一份数据
     */
    private class Builder {
        private val map = HashMap<String, FloatArray>(32)

        fun joint(a: String, b: String?, pitch: Float, yaw: Float, roll: Float, vararg more: String) {
            val arr = floatArrayOf(pitch, yaw, roll)
            map[a] = arr
            if (b != null && b.isNotEmpty()) map[b] = arr
            for (name in more) map[name] = arr
        }

        fun addTo(boneName: String, axis: Int, value: Float) {
            val existing = map[boneName]
            if (existing != null) {
                existing[axis] += value
            } else {
                val arr = FloatArray(3)
                arr[axis] = value
                map[boneName] = arr
            }
        }

        fun build(): Map<String, FloatArray> = map
    }
}
