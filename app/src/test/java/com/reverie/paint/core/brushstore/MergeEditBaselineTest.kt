/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mergeEdit 的 uiBaseline 语义（计划 PLAN_brush_parity.md §5 T4）。
 *
 * 用「Hard Charcoal 01 形态」手工夹具（jitterAngle=0.3 / jitterSize=0.3 / Size、Rotation
 * 挂 fuzzy 传感器 / spacing=0.05）构造 uiBaseline 与快照 XML（KppFixtures 资产里没有该名字，
 * 最小骨架 XML 经 PresetPropertyMap 写控件生成，PresetContext.of 可解析）。
 *
 * mergeEdit 现文语义（写断言前已核对 BrushEngineApplier.mergeEdit）：
 * - changed = diff(uiBaseline ?: effP, edited)；changed 为空时**原样返回 current**
 *   （不清理旧覆盖）；
 * - changed 非空时逐控件与 snapP 比：「与 snapP 相同则删，不同则记新值」。
 */
class MergeEditBaselineTest {

    /** 最小 kpp 骨架：有 paintopid 与 brush_definition（auto_brush），PresetContext.of 可解析 */
    private val skeleton =
        """<!DOCTYPE params><Preset paintopid="paintbrush">""" +
            """<param type="string" name="brush_definition"><![CDATA[""" +
            """<Brush type="auto_brush" spacing="0.1" useAutoSpacing="0" diameter="30" scale="1" """ +
            """BrushVersion="2" angle="0.0" brushApplication="0"/>""" +
            """]]></param></Preset>"""

    /** Hard Charcoal 01 形态的 uiBaseline（经 deriveFields 派生压感/传感器字段，与 VM 捕获同构） */
    private val charcoalBaseline: BrushParams = PresetPropertyMap.applyToParams(
        BrushParams(
            dynamicOptions = mapOf(
                "Size" to CurveValue(true, "fuzzy", "0,0;1,1;", 0.3).encode(),
                "Rotation" to CurveValue(true, "fuzzy", "0,0;1,1;", 0.3).encode(),
            ),
            spacing = 0.05,
            jitterAngle = 0.3,
            jitterSize = 0.3,
        ),
        emptyMap(),
    )

    /** 把 [p] 相对默认值的控件写进骨架 XML，得到可回读的快照 */
    private fun snapshotXml(p: BrushParams): String {
        val ctx = PresetContext.of(skeleton)
        return PresetPropertyMap.applyToXml(skeleton, PresetPropertyMap.diff(BrushParams(), p, ctx), p)
    }

    /** 快照读回（只关心控件值；fromXml 会置四个标志 true，diff 不比较标志） */
    private fun snapP(xml: String): BrushParams = PresetReadback.fromXml(xml, BrushParams())

    // ------------------------------------------------------------ 无编辑

    /** edited == uiBaseline（applyToParams 构造的相等对象）→ 不产生任何覆盖 */
    @Test
    fun `edited等于uiBaseline时不产生覆盖`() {
        val snapXml = snapshotXml(charcoalBaseline)
        val ctx = PresetContext.of(snapXml)
        // 用 diff+applyToParams 重建相等对象，避免与 uiBaseline 同引用的假相等
        val edited = PresetPropertyMap.applyToParams(
            BrushParams(),
            PresetPropertyMap.diff(BrushParams(), charcoalBaseline, ctx),
        )
        assertEquals(charcoalBaseline, edited)
        val merged = BrushEngineApplier.mergeEdit(
            snapXml, snapXml, BrushOverrideStore.Overrides(), edited,
            uiBaseline = charcoalBaseline,
        )
        assertTrue(merged.controls.isEmpty())
        assertTrue(merged.dirty.isEmpty())
    }

    // ------------------------------------------------------------ 只改 spacing

    /** 只改 spacing=0.12 → 覆盖只含 spacing 一个键，不出现 jitter / sensor（曲线）类覆盖 */
    @Test
    fun `只改spacing时覆盖只含spacing`() {
        val snapXml = snapshotXml(charcoalBaseline)
        val edited = charcoalBaseline.copy(spacing = 0.12)
        val merged = BrushEngineApplier.mergeEdit(
            snapXml, snapXml, BrushOverrideStore.Overrides(), edited,
            uiBaseline = charcoalBaseline,
        )
        assertEquals(setOf("spacing"), merged.controls.keys)
        assertEquals(0.12, merged.controls["spacing"] as Double, 1e-9)
        assertFalse("curve.Size" in merged.controls)
        assertFalse("curve.Rotation" in merged.controls)
        assertFalse("jitterAngle" in merged.controls)
        assertFalse("jitterSize" in merged.controls)
        assertFalse("rotationSensor" in merged.controls)
        assertFalse("sizeSensor" in merged.controls)
    }

    // ------------------------------------------------------------ 改回原值

    /**
     * 与 snapP 相同则删：快照 spacing=0.05、有效（含覆盖物化后）spacing=0.12，
     * uiBaseline 捕获自有效（0.12），用户改回快照值 0.05 → spacing 覆盖被删。
     */
    @Test
    fun `改回快照值时覆盖被删`() {
        val snapXml = snapshotXml(charcoalBaseline)
        val effXml = PresetPropertyMap.applyToXml(
            snapXml,
            mapOf<ControlId, Any>(ControlId.SPACING to 0.12),
            charcoalBaseline,
        )
        assertEquals(0.12, snapP(effXml).spacing, 1e-9)
        val uiBase = snapP(effXml)
        val current = BrushOverrideStore.Overrides(
            controls = mapOf("spacing" to 0.12),
            dirty = setOf("spacing"),
        )
        val merged = BrushEngineApplier.mergeEdit(
            snapXml, effXml, current, uiBase.copy(spacing = 0.05),
            uiBaseline = uiBase,
        )
        assertFalse("spacing" in merged.controls)
        assertFalse("spacing" in merged.dirty)
    }

    /**
     * 无新改动时旧覆盖原样保留：edited == uiBaseline（0.12，与 snapP 0.05 不同）→
     * changed 为空 → mergeEdit 现文原样返回 current，spacing 覆盖不被清理。
     * （「与 snapP 相同则删」只作用于 changed 里的控件，见上一用例。）
     */
    @Test
    fun `无新改动时旧覆盖原样保留`() {
        val snapXml = snapshotXml(charcoalBaseline)
        val effXml = PresetPropertyMap.applyToXml(
            snapXml,
            mapOf<ControlId, Any>(ControlId.SPACING to 0.12),
            charcoalBaseline,
        )
        val uiBase = snapP(effXml)
        val current = BrushOverrideStore.Overrides(
            controls = mapOf("spacing" to 0.12),
            dirty = setOf("spacing"),
        )
        val merged = BrushEngineApplier.mergeEdit(
            snapXml, effXml, current, uiBase,
            uiBaseline = uiBase,
        )
        assertEquals(current, merged)
    }
}
