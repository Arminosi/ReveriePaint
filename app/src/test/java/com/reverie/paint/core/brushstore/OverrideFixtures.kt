/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/** 测试用的"用户编辑"：对给定预设挑出适用的若干控件并给出与原值不同的新值 */
object OverrideFixtures {
    private val candidates: List<Pair<ControlId, Any>> = listOf(
        ControlId.OPACITY to 0.37,
        ControlId.FLOW to 0.61,
        ControlId.SPACING to 0.33,
        ControlId.SCATTER to 0.3,
        ControlId.HUE_JITTER to 0.25,
        ControlId.SMUDGE_LENGTH to 0.4,
        ControlId.CURVE_SIZE to CurveValue(true, "pressure", "0,0;0.5,0.2;1,1;", 1.0).encode(),
        ControlId.AIRBRUSH to true,
    )

    fun editsFor(xml: String, max: Int = 5): Map<ControlId, Any> {
        val ctx = PresetContext.of(xml)
        val cur = PresetPropertyMap.readValues(xml, ctx)
        val out = LinkedHashMap<ControlId, Any>()
        for ((id, v) in candidates) {
            val sp = PresetPropertyMap.spec(id)
            if (!sp.applicable(ctx) || sp.write == null) continue
            // 曲线强度沿用当前值（UI 编辑曲线时强度来自回读；Size 的强度与 jitterSize 共用 SizeValue）
            val vv = if (id.curveOption != null) {
                val c = CurveValue.parse(v as String)!!
                val curC = CurveValue.parse(cur[id] as? String)
                if (curC != null) c.copy(strength = curC.strength).encode() else v
            } else {
                v
            }
            if (PresetPropertyMap.same(id, cur[id], vv)) continue
            out[id] = vv
            if (out.size >= max) break
        }
        return out
    }

    fun overrides(edits: Map<ControlId, Any>): BrushOverrideStore.Overrides =
        BrushOverrideStore.Overrides(controls = edits.mapKeys { it.key.key }.mapValues { BrushOverrideStore.normalize(it.value) }, dirty = edits.keys.mapTo(LinkedHashSet()) { it.key })
}
