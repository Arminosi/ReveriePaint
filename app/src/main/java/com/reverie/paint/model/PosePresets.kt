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
    private val TUNED_PRESET_SHIFT: Map<String, Map<String, Float>> = mapOf(
        "cat" to mapOf(
            // 端坐: 之前 -0.50 会把整只猫压到地面线以下 (前腿陷进地下), 交回几何贴地
            LOAF to -0.60f,
            // 蜷缩/伏卧: 需要把身体压到贴地才有"卧"的观感, 但压太狠会让口鼻埋进地面, 比农民揣浅一档
            CURL to -0.45f,
            STRETCH to -0.10f,
        ),
    )

    fun isCat(modelType: String): Boolean = modelType.equals("cat", ignoreCase = true)

    /** 弯曲滑杆键: 0~100 (百分比), 与预设自带弯曲量叠加 */
    const val CAT_CURL_KEY = "cat_curl"

    /** 整体升降滑杆键: -100~100 -> -0.5~+0.5 (glTF +Y 向上), 用于把端坐/伏卧摆到地面 */
    const val CAT_RISE_KEY = "cat_rise"

    /** 猫猫整体升降量 (美术微调用), 与预设标定值叠加 */
    fun catRootRise(modelType: String, jointAngles: Map<String, Float>): Float {
        if (!isCat(modelType)) return 0f
        return ((jointAngles[CAT_RISE_KEY] ?: 0f) / 100f).coerceIn(-0.5f, 0.5f)
    }

    /** 猫猫蜷缩弯曲量: 预设自带 + 面板滑杆, 归一到 0..1 */
    fun catCurlBend(modelType: String, posePreset: String, jointAngles: Map<String, Float>): Float {
        if (!isCat(modelType)) return 0f
        val preset = if (posePreset.equals(CURL, ignoreCase = true)) 0.55f else 0f
        return (preset + (jointAngles[CAT_CURL_KEY] ?: 0f) / 100f).coerceIn(0f, 1f)
    }

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
        // 猫猫骨骼约定 (重绑管线 scripts/model/build_cat_glb.py)。局部 X 全部指向世界 +X，
        // 实测符号 (glTF: +Y 上, +Z 脸朝前, +X 猫右侧):
        //   Hips/Spine pitch > 0 = 前半身下沉      Head pitch > 0 = 低头
        //   前肢 shoulder/leg pitch > 0 = 爪子后摆  后肢 thigh 后移 / shank 折起 / foot 后移
        //   尾骨 pitch > 0 = 上翘, 左右摆走 roll (绕局部 Z, 绕局部 Y 只是沿骨轴扭转)
        // 数值由离线 IK 拟合得出: 目标是把四只爪子和骨盆压到同一水平面 (见 fit 脚本),
        // 拟合后 侧视图上四爪贴地、躯干角度正确, 不再是"腿在空中划水"。
        var hipP = 0f; var headP = 0f; var spineArch = 0f; var chestP = 0f; var neckP = 0f
        var flSh = 0f; var frSh = 0f; var flLeg = 0f; var frLeg = 0f
        var flPaw = 0f; var frPaw = 0f
        var hlLeg = 0f; var hrLeg = 0f
        var hlShank = 0f; var hrShank = 0f
        var hlFoot = 0f; var hrFoot = 0f
        var tailP = 35f; var tailSide = 8f

        when (posePreset.lowercase()) {
            // 端坐: 骨盆后倾下落, 躯干立起, 前肢竖直支撑, 后肢折叠于体侧, 尾巴环绕贴地
            SIT -> {
                hipP = -15f; spineArch = 10f; chestP = 15f; neckP = 5f; headP = -10f
                flSh = 12f; frSh = 12f; flLeg = -12f; frLeg = -12f
                hlLeg = -42f; hrLeg = -42f
                hlShank = 55f; hrShank = 55f
                hlFoot = -25f; hrFoot = -25f
                tailP = -30f; tailSide = 20f
            }
            // 农民揣 (卧姿): 躯干沉伏, 前肢收拢于胸下, 后肢折进腹下, 尾巴贴体
            LOAF -> {
                hipP = -12f; spineArch = -4f; chestP = 8f; headP = -6f
                flSh = 42f; frSh = 42f; flLeg = -68f; frLeg = -68f; flPaw = 26f; frPaw = 26f
                hlLeg = -52f; hrLeg = -52f
                hlShank = 70f; hrShank = 70f
                hlFoot = -22f; hrFoot = -22f
                tailP = -25f; tailSide = 30f
            }
            // 行走: 四足对角换步 (左前 + 右后 同相), 躯干轻微起伏, 尾巴平衡摆动
            WALK -> {
                hipP = 3f; spineArch = -3f; chestP = 3f; neckP = 2f; headP = 2f
                flSh = -12f; flLeg = -18f; flPaw = 10f
                frSh = 10f; frLeg = 14f; frPaw = -6f
                hlLeg = 12f; hlShank = -8f
                hrLeg = -10f; hrShank = 10f
                tailP = 25f; tailSide = 12f
            }
            // 蜷缩入睡: 后躯收拢下沉 + 脊柱向前弯成 C + 头埋进胸口 + 尾巴绕体
            CURL -> {
                hipP = -18f; spineArch = 16f; chestP = 6f; neckP = 8f; headP = 16f
                flSh = 42f; frSh = 42f; flLeg = -68f; frLeg = -68f; flPaw = 26f; frPaw = 26f
                hlLeg = -52f; hrLeg = -52f
                hlShank = 70f; hrShank = 70f
                hlFoot = -22f; hrFoot = -22f
                tailP = -18f; tailSide = 50f
            }
            // 伏击准备: 前半身压低, 后躯蓄力, 前爪前探, 尾巴水平微摆
            POUNCE -> {
                hipP = 14f; spineArch = -6f; chestP = -10f; neckP = -6f; headP = 6f
                flSh = -18f; frSh = -18f; flLeg = 8f; frLeg = 8f; flPaw = 8f; frPaw = 8f
                hlLeg = -20f; hrLeg = -20f
                hlShank = 35f; hrShank = 35f
                hlFoot = -10f; hrFoot = -10f
                tailP = 10f; tailSide = 18f
            }
            // 立身伸爪: 前肢抬起前探, 后肢蹬地, 上身立起抬头
            REAR -> {
                hipP = -20f; spineArch = -8f; chestP = -10f; neckP = -6f; headP = -12f
                flSh = -55f; frSh = -55f; flLeg = -35f; frLeg = -35f; flPaw = -10f; frPaw = -10f
                hlLeg = 10f; hrLeg = 10f
                hlShank = -10f; hrShank = -10f
                tailP = -20f; tailSide = 10f
            }
            // 回首张望: 头转向侧面, 脊柱轻微反向, 尾巴甩向另一侧
            LOOK -> {
                hipP = -4f; spineArch = -4f; chestP = -2f; neckP = 4f
                tailP = 20f; tailSide = 30f
            }
            // 伸懒腰: 前肢伸展, 胸口低伏压低, 后躯高翘, 头抬起, 尾巴翘卷
            STRETCH -> {
                hipP = 10f; spineArch = -8f; chestP = -18f; neckP = -5f; headP = 18f
                flSh = -25f; frSh = -25f; flLeg = -10f; frLeg = -10f; flPaw = 15f; frPaw = 15f
                hlLeg = 15f; hrLeg = 15f
                hlShank = -10f; hrShank = -10f
                hlFoot = 5f; hrFoot = 5f
                tailP = 40f; tailSide = 0f
            }
        }

        headP += (jointAngles["cat_head_pitch"] ?: 0f)
        var headY = (jointAngles["cat_head_yaw"] ?: 0f)
        spineArch += (jointAngles["cat_spine_arch"] ?: 0f)
        flLeg += (jointAngles["cat_fl_leg"] ?: 0f)
        frLeg += (jointAngles["cat_fr_leg"] ?: 0f)
        hlLeg += (jointAngles["cat_hl_leg"] ?: 0f)
        hrLeg += (jointAngles["cat_hr_leg"] ?: 0f)
        tailP += (jointAngles["cat_tail_pitch"] ?: 0f)
        tailSide += (jointAngles["cat_tail_yaw"] ?: 0f)

        b.joint("Hips", "hips", hipP, 0f, 0f)
        b.joint("Spine", "spine", spineArch, 0f, 0f)
        b.joint("Chest", "chest", chestP, 0f, 0f)
        b.joint("Neck", "neck", neckP, 0f, 0f)
        if (posePreset.equals(LOOK, ignoreCase = true)) headY = 45f
        b.joint("Head", "head", headP, headY, 0f)
        b.joint("l_front_shoulder", "frontleg", flSh, 0f, 0f)
        b.joint("r_front_shoulder", "R_frontleg", frSh, 0f, 0f)
        b.joint("leftFrontLeg", "LeftFrontLeg", flLeg, 0f, 0f, "l_front_leg", "frontleg0")
        b.joint("rightFrontLeg", "RightFrontLeg", frLeg, 0f, 0f, "r_front_leg", "R_frontleg0")
        b.joint("l_front_paw", "frontleg1", flPaw, 0f, 0f, "frontleg2")
        b.joint("r_front_paw", "R_frontleg1", frPaw, 0f, 0f, "R_frontleg2")
        b.joint("leftHindLeg", "LeftHindLeg", hlLeg, 0f, 0f, "l_hind_thigh", "backleg")
        b.joint("rightHindLeg", "RightHindLeg", hrLeg, 0f, 0f, "r_hind_thigh", "R_backleg")
        b.joint("l_hind_shank", "backleg0", hlShank, 0f, 0f)
        b.joint("r_hind_shank", "R_backleg0", hrShank, 0f, 0f)
        b.joint("l_hind_foot", "backleg1", hlFoot, 0f, 0f, "backleg2")
        b.joint("r_hind_foot", "R_backleg1", hrFoot, 0f, 0f, "R_backleg2")
        // 尾骨链: 第一根跟随滑杆, 后面几节按固定比例递进, 形成自然的卷曲
        b.joint("Tail1", "tail1", tailP, 0f, tailSide, "tail")
        b.joint("Tail2", "tail2", 26f + tailP * 0.25f, 0f, tailSide * 0.8f, "tailstart")
        b.joint("Tail3", "tail3", 20f + tailP * 0.2f, 0f, tailSide * 0.6f)
        b.joint("Tail4", "tail4", 14f + tailP * 0.15f, 0f, tailSide * 0.4f)
        b.joint("tail5", null, 10f + tailP * 0.1f, 0f, tailSide * 0.2f)
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
