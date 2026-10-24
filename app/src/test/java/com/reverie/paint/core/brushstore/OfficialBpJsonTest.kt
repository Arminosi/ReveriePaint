/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.LegacyBrushParamsCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方 bp JSON 编解码（计划 PLAN_brush_parity.md §5 T4）：
 * OfficialBpJson 逐字委托 [LegacyBrushParamsCodec]，decode 默认 Heal 全 false —— 与
 * loadBrushParams 在迁移标记全部完成后（PVMB 333 起现文）的真机行为一致。
 */
class OfficialBpJsonTest {

    // ------------------------------------------------------------ roundtrip

    /**
     * ABR 回填形态的全字段记录 roundtrip 恒等。
     * 注意愈合一致性：spacing=0.05 ≠ 0.1 而 decode 在 spc=false 时会把 spacing 愈合回 0.1，
     * 因此这里 spacingCustomized 必须为 true（任务书两个要求里「选前者」的裁决）。
     * airbrushRate 任务书写 30.0，但 30.0 恰是 BrushParams 默认值，与「全部数值字段取非默认值」
     * 冲突；取 45.0（decode 只把 <5 愈合回 30，45 原样存活），语义等价。
     */
    @Test
    fun `ABR 回填形态全字段记录 roundtrip 恒等`() {
        val p = BrushParams(
            dynamicOptions = mapOf(
                "Size" to CurveValue(true, "fuzzy", "0,0;1,1;", 0.3).encode(),
                "Rotation" to CurveValue(true, "pressure", "0,0;0.5,0.2;1,1;", 1.0).encode(),
            ),
            size = 25.5,
            opacity = 0.8,
            flow = 0.7,
            spacing = 0.05,
            angle = 15.0,
            scatter = 0.3,
            fade = 0.9,
            softness = 0.8,
            ratio = 1.2,
            sharpness = 0.4,
            rotation = 90.0,
            compositeOp = "multiply",
            antiAliasing = 0,
            tipShape = 1,
            randomFlipX = true,
            randomFlipY = true,
            followDirection = true,
            streamline = 0.6,
            taper = 0.4,
            textureEnabled = true,
            textureScale = 2.0,
            textureStrength = 0.8,
            textureMode = "hard",
            texturePattern = "pat.png",
            hueJitter = 0.1,
            satJitter = 0.2,
            valJitter = 0.3,
            secondaryMix = 0.25,
            pressureColorMix = true,
            pressureEnabled = false,
            pressureSize = 0.9,
            pressureOpacity = 0.85,
            pressureFlow = 0.75,
            speedSize = 0.5,
            pressureCurve = 2,
            minSizeLimit = 0.2,
            maxSizeLimit = 300.0,
            tipAsset = "tip.png",
            paintOpId = "paintbrush",
            airbrush = true,
            airbrushRate = 45.0,
            smudgeRate = 0.4,
            smudgeLength = 0.6,
            colorRate = 0.7,
            smudgeMode = 1,
            spikes = 4,
            jitterAngle = 0.3,
            jitterSize = 0.3,
            author = "Tester",
            isAuthorLocked = true,
            description = "desc",
            version = "2.0",
            isCustomized = true,
            dynamicsCustomized = true,
            smudgeCustomized = false,
            spacingCustomized = true,
            maskingEnabled = true,
            maskingCompositeOp = "normal",
            maskingSizeRatio = 0.8,
            maskingSpacing = 0.2,
            maskingTipAsset = "m.png",
            maskingTipShape = 1,
            maskingFade = 0.5,
            maskingSoftness = 0.7,
            rotationSensor = "distance",
            scatterSensor = "speed",
            sizeSensor = "fuzzy",
            opacitySensor = "distance",
            flowSensor = "speed",
        )
        assertEquals(p, OfficialBpJson.fromJson(OfficialBpJson.toJson(p)))
    }

    /** 全默认记录 roundtrip 恒等（spc=false 把默认 0.1 愈合回 0.1，无漂移） */
    @Test
    fun `全默认记录 roundtrip 恒等`() {
        val p = BrushParams()
        assertEquals(p, OfficialBpJson.fromJson(OfficialBpJson.toJson(p)))
    }

    // ------------------------------------------------------------ 愈合规则（手工构造 JSON）

    private fun bareJson(vararg pairs: Pair<String, Any>): JSONObject {
        val o = JSONObject()
        o.put("n", "X")
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    /** spc=false 时 spacing 愈合回 0.1；spc=true 保留 */
    @Test
    fun `愈合 - spc为false时spacing回0点1`() {
        assertEquals(0.1, OfficialBpJson.fromJson(bareJson("sp" to 0.5, "spc" to false)).spacing, 1e-9)
        assertEquals(0.5, OfficialBpJson.fromJson(bareJson("sp" to 0.5, "spc" to true)).spacing, 1e-9)
    }

    /** dc=false 时 scatter 愈合回 0；dc=true 且值 <2.0 保留 */
    @Test
    fun `愈合 - dc为false时scatter回0`() {
        assertEquals(0.0, OfficialBpJson.fromJson(bareJson("sc" to 1.5, "dc" to false)).scatter, 1e-9)
        assertEquals(1.5, OfficialBpJson.fromJson(bareJson("sc" to 1.5, "dc" to true)).scatter, 1e-9)
    }

    /** scatter ≥2.0 一律视为污染愈合回 0（即使 dc=true） */
    @Test
    fun `愈合 - scatter大于等于2时即使dc为true也回0`() {
        assertEquals(0.0, OfficialBpJson.fromJson(bareJson("sc" to 2.0, "dc" to true)).scatter, 1e-9)
    }

    /** airbrushRate <5 视为旧脏数据愈合回 30 */
    @Test
    fun `愈合 - airbrushRate小于5回30`() {
        assertEquals(30.0, OfficialBpJson.fromJson(bareJson("abr" to 3.0)).airbrushRate, 1e-9)
        assertEquals(45.0, OfficialBpJson.fromJson(bareJson("abr" to 45.0)).airbrushRate, 1e-9)
    }

    /**
     * compositeOp 愈合按名字的 legacy isEraser 判定（不含 Eraser 且不以 a)_ 开头 → 非橡皮
     * → cop="erase" 愈合为 "normal"；橡皮名字保留 "erase"。
     */
    @Test
    fun `愈合 - 非橡皮名字的cop为erase回normal`() {
        val plain = OfficialBpJson.fromJson(bareJson("cop" to "erase"))
        assertEquals("normal", plain.compositeOp)
        val prefixed = LegacyBrushParamsCodec.decode(JSONObject().put("n", "a)_Eraser_Hard").put("cop", "erase"))
        assertEquals("erase", prefixed.compositeOp)
        val contains = LegacyBrushParamsCodec.decode(JSONObject().put("n", "My Eraser").put("cop", "erase"))
        assertEquals("erase", contains.compositeOp)
    }

    /**
     * cus 缺失时的 legacy isCustomized 启发式（decode 现文：aut=="外部创作者 (分享)" 或
     * （aut=="ReveriePaint" 且 s==20.0）视为未定制 → false，其余 → true）。
     */
    @Test
    fun `cus缺失时走legacy isCustomized启发式`() {
        // aut="ReveriePaint" 且 s=20.0 → 出厂预设 → false（decode 现文 optString("aut","") 兜底为空串，
        // 故"完全不带 aut"的记录判定为 true，这里按现文语义断言带 aut 的形态）
        assertFalse(OfficialBpJson.fromJson(bareJson("aut" to "ReveriePaint", "s" to 20.0)).isCustomized)
        assertTrue(OfficialBpJson.fromJson(bareJson()).isCustomized)
        // 外部创作者署名 → 视为出厂未定制 → false
        assertFalse(OfficialBpJson.fromJson(bareJson("aut" to "外部创作者 (分享)")).isCustomized)
        // 出厂作者但尺寸非默认 → true；其他作者且尺寸非默认 → true
        assertTrue(OfficialBpJson.fromJson(bareJson("aut" to "ReveriePaint", "s" to 15.0)).isCustomized)
        assertTrue(OfficialBpJson.fromJson(bareJson("aut" to "Guest", "s" to 15.0)).isCustomized)
    }

    // ------------------------------------------------------------ 键集守护

    /** 从 LegacyBrushParamsCodec.encode 源码逐字抄录的落盘键列表（防键名悄悄漂移） */
    private val persistKeys = listOf(
        "n", "s", "o", "f", "sp", "ang", "sc", "fa", "so", "ra", "sh", "ro", "cop", "aa", "ts",
        "rfx", "rfy", "fd", "sl", "tp", "te", "tscl", "tstr", "tm", "txp", "hj", "sj", "vj", "sm",
        "pcm", "pe", "ps", "po", "pf", "ss", "pc", "mins", "maxs", "ta", "poid", "ab", "abr",
        "smr", "sml", "spk", "ja", "js", "aut", "autl", "desc", "ver", "cus", "dc", "scus", "spc",
        "m_en", "m_op", "m_sr", "m_sp", "m_ta", "m_ts", "m_fa", "m_so", "r_se", "sc_se", "sz_se",
        "op_se", "fl_se", "cr", "smm",
    )

    @Test
    fun `encode键集合与persistBrushParams键列表一致`() {
        val keys = OfficialBpJson.toJson(BrushParams()).let { o -> o.keys().asSequence().toList() }
        assertEquals(persistKeys.sorted(), keys.sorted())
    }

    @Test
    fun `encode在dynamicOptions非空时追加dyn_opt键`() {
        val p = BrushParams(dynamicOptions = mapOf("Size" to CurveValue(true, "fuzzy", "0,0;1,1;", 0.3).encode()))
        val keys = OfficialBpJson.toJson(p).let { o -> o.keys().asSequence().toList() }
        assertEquals((persistKeys + "dyn_opt").sorted(), keys.sorted())
    }
}
