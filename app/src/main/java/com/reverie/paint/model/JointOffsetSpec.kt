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

    /** 平移键名 (与 [PosePresets.buildTranslations] 的约定一致) */
    fun key(bone: String, axis: Int): String =
        bone + when (axis) {
            0 -> "_tx"
            1 -> "_ty"
            else -> "_tz"
        }

    /** 滑杆取值范围 (单位: 1/100 模型单位; 猫的体高约 1.14, ±20 即 ±0.2) */
    const val RANGE: Float = 20f

    /** 从 jointAngles 里读出某骨的竖直偏移 (滑杆值, 未除以 100) */
    fun offsetOf(jointAngles: Map<String, Float>, bone: String): Float =
        jointAngles[key(bone, 1)] ?: 0f

    /** 写入某骨的竖直偏移, 返回新的 jointAngles (0 时移除键, 保持 Map 精简) */
    fun withOffset(jointAngles: Map<String, Float>, bone: String, value: Float): Map<String, Float> {
        val k = key(bone, 1)
        if (value == 0f) {
            if (!jointAngles.containsKey(k)) return jointAngles
            return jointAngles - k
        }
        return jointAngles + (k to value)
    }

    /** 清掉所有平移键, 保留旋转角度 */
    fun clearOffsets(jointAngles: Map<String, Float>): Map<String, Float> =
        jointAngles.filterKeys { !it.endsWith("_tx") && !it.endsWith("_ty") && !it.endsWith("_tz") }

    /** 是否已有任一平移偏移 (用于 UI 显示"已修改") */
    fun hasOffsets(jointAngles: Map<String, Float>): Boolean =
        jointAngles.keys.any { it.endsWith("_tx") || it.endsWith("_ty") || it.endsWith("_tz") }

    /** 导出用的稳定文本 (每行 "骨名 ty=值", 便于人读与 diff) */
    fun describe(jointAngles: Map<String, Float>): String =
        jointAngles.entries
            .filter { it.key.endsWith("_ty") }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key.substringBeforeLast('_')} ty=${it.value}" }
}
