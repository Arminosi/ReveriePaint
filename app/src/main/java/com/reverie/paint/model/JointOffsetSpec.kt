/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 关节位置微调的骨骼清单与键名规则 (纯 Kotlin, 可单测)。
 *
 * 背景: 自动绑定 (UniRig) 给出的关节点位置不总是落在网格的真实关节上,
 * 离线调了多轮仍有偏差。这里提供一个让用户在设备上自己拖的方案 ——
 * 面板选骨 + 竖直滑杆 = 给该骨骼一个局部平移 (`<骨名>_ty`, 单位 1/100 模型单位),
 * 复用 [PosePresets.buildTranslations] 已有的平移通道, 模型层零改动。
 *
 * 为什么只给 Y (竖直): 用户反馈的问题集中在"关节点太高/太低";
 * X/Z 方向的偏移在正交视角下难以判断, 需要三轴箭头编辑器, 后续再加。
 */
internal object JointOffsetSpec {

    /** 猫猫骨架的可调骨骼 (按身体部位分组, UI 按此顺序排列) */
    val CAT_BONES: List<BoneGroup> = listOf(
        BoneGroup("脊柱 / 腰背", listOf("lumbar", "spine_01", "spine_02", "spine_03", "spine_04", "spine_05", "spine_06", "head")),
        BoneGroup(
            "前肢",
            listOf(
                "scapula_L", "shoulder_L", "forearm_L", "elbow_L", "carpus_L", "toe_front_L",
                "scapula_R", "shoulder_R", "forearm_R", "elbow_R", "carpus_R", "toe_front_R",
            ),
        ),
        BoneGroup(
            "后肢",
            listOf(
                "hip_L", "knee_L", "hock_L", "meta_L", "toe_hind_L",
                "hip_R", "knee_R", "hock_R", "meta_R", "toe_hind_R",
            ),
        ),
        BoneGroup("尾巴", (1..8).map { "tail_%02d".format(it) }),
        BoneGroup("耳", listOf("ear_L", "ear_tip_L", "ear_R", "ear_tip_R")),
    )

    data class BoneGroup(val label: String, val bones: List<String>)

    /**
     * 静止位置偏移键名: `<骨名>_rx` / `<骨名>_ry`。
     *
     * 刻意与姿态平移的 `_tx/_ty/_tz` 区分开 —— 那些是"摆姿势时的位移"(会带着顶点走,
     * 网格变形), 这里改的是绑定姿态 (网格不动, 只挪旋转轴心)。
     */
    fun key(bone: String, axis: Int): String =
        bone + when (axis) {
            0 -> "_rx"
            1 -> "_ry"
            else -> "_rz"
        }

    /** 滑杆取值范围 (单位: 1/100 模型单位; 猫的体高约 1.14, ±20 即 ±0.2) */
    const val RANGE: Float = 20f

    /** 读出某骨的静止偏移 (滑杆值, 未除以 100) */
    fun offsetOf(offsets: Map<String, Float>, bone: String, axis: Int = 1): Float =
        offsets[key(bone, axis)] ?: 0f

    /** 写入某骨的静止偏移 (0 时移除键, 保持 Map 精简) */
    fun withOffset(offsets: Map<String, Float>, bone: String, value: Float, axis: Int = 1): Map<String, Float> {
        val k = key(bone, axis)
        if (value == 0f) {
            if (!offsets.containsKey(k)) return offsets
            return offsets - k
        }
        return offsets + (k to value)
    }

    /** 清掉所有静止位置偏移 (rotation 角度在 jointAngles, 不受影响) */
    fun clearOffsets(offsets: Map<String, Float>): Map<String, Float> =
        offsets.filterKeys { !it.endsWith("_rx") && !it.endsWith("_ry") && !it.endsWith("_rz") }

    /** 是否已有任一静止位置偏移 (用于 UI 显示"已修改") */
    fun hasOffsets(offsets: Map<String, Float>): Boolean =
        offsets.keys.any { it.endsWith("_rx") || it.endsWith("_ry") || it.endsWith("_rz") }

    /** 把偏移表转成 [Skeleton3D.withRestOffsets] 需要的"关节名 -> 世界位移(模型单位)" */
    fun toWorldOffsets(offsets: Map<String, Float>): Map<String, FloatArray> {
        if (offsets.isEmpty()) return emptyMap()
        val out = HashMap<String, FloatArray>(offsets.size)
        for ((k, v) in offsets) {
            val axis = when {
                k.endsWith("_rx") -> 0
                k.endsWith("_ry") -> 1
                k.endsWith("_rz") -> 2
                else -> continue
            }
            val bone = k.substring(0, k.length - 3)
            val arr = out.getOrPut(bone) { FloatArray(3) }
            arr[axis] += v / 100f
        }
        return out
    }

    /** 导出用的稳定文本 (每行 "骨名 ty=值", 便于人读与 diff) */
    fun describe(offsets: Map<String, Float>): String =
        offsets.entries
            .filter { it.key.endsWith("_rx") || it.key.endsWith("_ry") }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key.substringBeforeLast('_')} ${it.key.takeLast(2)}=${it.value}" }
}
