/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * 映射往返（W-03）：对 268 个真实内置 kpp，逐控件
 *  1. 写入"当前读值" → 所有控件读值不变（恒等写无副作用）；
 *  2. 写入一个不同的值 → 本控件读回该值，其它控件读值不变（无串写），
 *     只允许 [coupled] 里列出的、在 Krita 语义上本来就共享键的控件一起变。
 */
class PresetPropertyMapRoundTripTest {

    /** 同一 Krita 曲线选项上的控件（勾选/UseCurve/传感器共享），写一个可能改变另一个的读值 */
    private val coupled: Map<ControlId, Set<ControlId>> = mapOf(
        ControlId.CURVE_SIZE to setOf(ControlId.JITTER_SIZE),
        ControlId.CURVE_ROTATION to setOf(ControlId.FOLLOW_DIRECTION, ControlId.JITTER_ANGLE),
        ControlId.CURVE_SCATTER to setOf(ControlId.SCATTER),
        ControlId.CURVE_SOFTNESS to setOf(ControlId.SOFTNESS),
        ControlId.CURVE_SMUDGE_RATE to setOf(ControlId.SMUDGE_LENGTH),
        ControlId.CURVE_COLOR_RATE to setOf(ControlId.COLOR_RATE),
        ControlId.SCATTER to setOf(ControlId.CURVE_SCATTER),
        ControlId.SOFTNESS to setOf(ControlId.CURVE_SOFTNESS),
        ControlId.SMUDGE_LENGTH to setOf(ControlId.CURVE_SMUDGE_RATE),
        ControlId.COLOR_RATE to setOf(ControlId.CURVE_COLOR_RATE),
        ControlId.FOLLOW_DIRECTION to setOf(ControlId.CURVE_ROTATION, ControlId.JITTER_ANGLE),
        ControlId.JITTER_ANGLE to setOf(ControlId.CURVE_ROTATION, ControlId.FOLLOW_DIRECTION),
        ControlId.JITTER_SIZE to setOf(ControlId.CURVE_SIZE),
        ControlId.MIX to setOf(ControlId.MIX_PRESSURE),
        ControlId.MIX_PRESSURE to setOf(ControlId.MIX),
        ControlId.RANDOM_FLIP_X to setOf(ControlId.RANDOM_FLIP_Y),
        ControlId.RANDOM_FLIP_Y to setOf(ControlId.RANDOM_FLIP_X),
        // 换笔尖文件：笔尖定义整体替换（MaskGenerator 消失），角度/间距取 eff
        ControlId.TIP_ASSET to setOf(
            ControlId.SIZE, ControlId.RATIO, ControlId.FADE, ControlId.TIP_SHAPE, ControlId.SPIKES, ControlId.ANTI_ALIASING,
            ControlId.SPACING, ControlId.ANGLE,
        ),
    )

    private fun other(cur: Double, a: Double = 0.37, b: Double = 0.63) = if (abs(cur - a) < 1e-3) b else a

    /** 给每个控件挑与当前不同的合法值 */
    private fun perturb(id: ControlId, cur: Any): List<Any> = when (id) {
        ControlId.SIZE, ControlId.MASKING_ENABLED, ControlId.MASKING_COMPOSITE_OP, ControlId.MASKING_SIZE_RATIO,
        ControlId.MASKING_SPACING, ControlId.MASKING_TIP_ASSET, ControlId.MASKING_TIP_SHAPE, ControlId.MASKING_FADE,
        ControlId.PAINTOP_ID,
        -> emptyList()
        ControlId.ANGLE -> listOf(other(cur as Double, 33.0, 57.0))
        ControlId.ROTATION -> listOf(other(cur as Double, 12.0, 24.0))
        ControlId.SPIKES -> listOf(if (cur == 5) 7 else 5)
        ControlId.TIP_SHAPE, ControlId.ANTI_ALIASING, ControlId.SMUDGE_MODE -> listOf(1 - (cur as Int))
        ControlId.TEXTURE_MODE -> listOf(if (cur == 5) 9 else 5)
        ControlId.COMPOSITE_OP -> listOf(if (cur == "multiply") "normal" else "multiply")
        ControlId.TEXTURE_PATTERN -> listOf("roundtrip_pattern.png")
        ControlId.TIP_ASSET -> listOf("roundtrip_tip.png")
        ControlId.TEXTURE_SCALE -> listOf(other(cur as Double, 1.7, 0.6))
        ControlId.AIRBRUSH_RATE -> listOf(other(cur as Double, 47.0, 12.0))
        else -> when {
            id.curveOption != null -> {
                val c = CurveValue.parse(cur as String)!!
                val strength = if (id == ControlId.CURVE_SIZE) 0.7 else 1.0
                listOf(
                    c.copy(enabled = true, curve = "0,0;0.3,0.6;1,1;", strength = strength).encode(),
                    c.copy(enabled = false, curve = "0,0.1;1,0.9;", strength = strength).encode(),
                )
            }
            cur is Boolean -> listOf(!cur)
            cur is Double -> listOf(other(cur), 0.0).filter { abs(it - cur) > 1e-6 }
            else -> {
                fail("未覆盖的控件 $id ($cur)")
                emptyList()
            }
        }
    }

    private fun assertUnchanged(where: String, before: Map<ControlId, Any>, after: Map<ControlId, Any>, allowed: Set<ControlId>) {
        for ((k, v) in before) {
            if (k in allowed) continue
            val a = after[k]
            assertTrue("$where: $k 被串写 $v → $a", PresetPropertyMap.same(k, v, a))
        }
    }

    @Test
    fun assetsArePresent() {
        assertEquals(268, KppFixtures.all.size)
    }

    @Test
    fun identityWrite_isSideEffectFree() {
        for ((name, xml) in KppFixtures.all) {
            val values = PresetPropertyMap.readValues(xml)
            val eff = PresetPropertyMap.readParams(xml)
            for (sp in PresetPropertyMap.specs) {
                val w = sp.write ?: continue
                val v = values[sp.id] ?: continue
                val out = w(xml, v, eff)
                assertUnchanged("$name/${sp.id} 恒等写", values, PresetPropertyMap.readValues(out), emptySet())
            }
        }
    }

    @Test
    fun perturbedWrite_readsBack_withoutCrosstalk() {
        var writes = 0
        for ((name, xml) in KppFixtures.all) {
            val ctx = PresetContext.of(xml)
            val values = PresetPropertyMap.readValues(xml, ctx)
            val eff = PresetPropertyMap.readParams(xml)
            for (sp in PresetPropertyMap.specs) {
                val w = sp.write ?: continue
                if (!sp.applicable(ctx)) continue
                val cur = values[sp.id] ?: continue
                for (v in perturb(sp.id, cur)) {
                    val out = w(xml, v, eff)
                    val after = PresetPropertyMap.readValues(out)
                    val where = "$name/${sp.id}=$v"
                    if (sp.id == ControlId.TIP_ASSET) {
                        assertEquals(where, v, after[ControlId.TIP_ASSET])
                        assertEquals(where, eff.angle, after[ControlId.ANGLE] as Double, 1e-6)
                        assertEquals(where, eff.spacing, after[ControlId.SPACING] as Double, 1e-6)
                    } else {
                        assertTrue("$where: 读回 ${after[sp.id]}", PresetPropertyMap.same(sp.id, v, after[sp.id]))
                    }
                    assertUnchanged(where, values, after, coupled[sp.id].orEmpty() + sp.id)
                    writes++
                }
            }
        }
        assertTrue("实际写入 $writes 次", writes > 268 * 20)
    }

    @Test
    fun maskingGroup_roundTrips() {
        val withMask = KppFixtures.all.filter { KppXml.getBool(it.second, "MaskingBrush/Enabled", false) }
        assertTrue(withMask.isNotEmpty())
        for ((name, xml) in withMask) {
            val eff = PresetPropertyMap.readParams(xml).copy(maskingSizeRatio = 0.5, maskingFade = 0.3, maskingSpacing = 0.2)
            val out = PresetPropertyMap.applyToXml(xml, mapOf(ControlId.MASKING_FADE to 0.3), eff)
            val back = PresetPropertyMap.readValues(out)
            assertEquals(name, 0.5, back[ControlId.MASKING_SIZE_RATIO] as Double, 1e-6)
            assertEquals(name, 0.3, back[ControlId.MASKING_FADE] as Double, 1e-6)
            assertEquals(name, 0.2, back[ControlId.MASKING_SPACING] as Double, 1e-6)
            assertEquals(name, eff.maskingTipAsset, back[ControlId.MASKING_TIP_ASSET])
            assertEquals(name, eff.maskingCompositeOp, back[ControlId.MASKING_COMPOSITE_OP])
            assertEquals(name, true, back[ControlId.MASKING_ENABLED])
        }
    }

    @Test
    fun diffThenApply_reproducesEdits() {
        for ((name, xml) in KppFixtures.all) {
            val ctx = PresetContext.of(xml)
            val base = PresetPropertyMap.readParams(xml)
            assertTrue("$name: 出厂视图不应有差异", PresetPropertyMap.diff(base, base, ctx).isEmpty())
            val edited = base.copy(opacity = other(base.opacity), hueJitter = 0.2, textureScale = 1.7, streamline = 0.4)
            val d = PresetPropertyMap.diff(base, edited, ctx)
            val expected = buildSet {
                add(ControlId.OPACITY)
                add(ControlId.STREAMLINE)
                if (abs(base.hueJitter - 0.2) > 1e-6) add(ControlId.HUE_JITTER)
                if (abs(base.textureScale - 1.7) > 1e-6) add(ControlId.TEXTURE_SCALE)
            }
            assertEquals(name, expected, d.keys)
            val eff = PresetPropertyMap.applyToParams(base, d)
            val out = PresetPropertyMap.applyToXml(xml, d, eff)
            // streamline 是 APP_ONLY，不进 kpp：从 kpp 读回时应为基线值
            val back = PresetPropertyMap.readParams(out, base)
            assertTrue(name, PresetPropertyMap.diff(eff.copy(streamline = base.streamline), back, ctx).isEmpty())
        }
    }

    @Test
    fun everyControl_hasSpec_andStableUniqueKey() {
        assertEquals(ControlId.entries.size, PresetPropertyMap.specs.size)
        assertEquals(ControlId.entries.size, ControlId.entries.map { it.key }.toSet().size)
        ControlId.entries.forEach { assertEquals(it, ControlId.fromKey(it.key)) }
        assertEquals(BrushParams().colorRate, PresetPropertyMap.spec(ControlId.COLOR_RATE).get(BrushParams()))
    }
}
