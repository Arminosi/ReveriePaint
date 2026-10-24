/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * T3 上游链 oracle（PLAN_brush_parity.md §5 T3）：用 [UpstreamChainOracle]（上游 49f0f78
 * 四条链的独立转写）对 53 支真实 ABR 回填 bp + 每格式样本断言关键 setter 调用序列。
 * 期望值依据 FULL_SETTER_AUDIT.md §2/§3/§4 与 GROK_AUDIT.md §3.3/§4.1；与文档冲突即停下报告。
 */
class UpstreamChainOracleTest {

    private lateinit var store: Abr53Fixture.Store
    private lateinit var samples: List<Abr53Fixture.Sample>
    private val byName by lazy { samples.associateBy { it.name } }

    @Before
    fun setUp() {
        Abr53Fixture.setUp().let {
            store = it.first
            samples = it.second
        }
    }

    @After
    fun tearDown() {
        store.tearDown()
    }

    /** VM 状态入参：brushDynamicOptions = applySensorXmlToDynamicOptions(saved.dynamicOptions) 的布尔视图 */
    private fun dynEnabled(saved: OBp): Map<String, Boolean> =
        saved.dynamicOptions.mapValues { (_, xml) ->
            Regex("""enabled="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: true
        }

    private fun selectCalls(s: Abr53Fixture.Sample, rotationSensor: String? = null): List<Call> {
        val saved = OBp.of(s.bp)
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleSelect(
            calls,
            index = 0,
            presetGroup = "",
            presetName = s.name,
            saved = saved,
            currentToolIdIsEraser = false,
            nativeCompOp = "normal",
            brushColor = 0xFF000000.toInt(),
            brushSecondaryColor = 0xFFFFFFFF.toInt(),
            mem = DoubleArray(0),
            d = DoubleArray(0),
            parsedPaintOpSize = s.parsedKpp.paintOpSize,
            parsedTipAsset = s.parsedKpp.tipAsset,
            brushRotationSensor = rotationSensor ?: saved.rotationSensor,
            brushDynamicOptions = dynEnabled(saved),
        )
        return calls
    }

    private fun callOf(calls: List<Call>, name: String): Call {
        val hits = calls.filter { it.name == name }
        assertEquals("应恰有一次 $name（实际 ${hits.size} 次）", 1, hits.size)
        return hits[0]
    }

    // ---- 53 支 ABR 选笔链 ----

    @Test
    fun `53 支 ABR 选笔链关键 setter 与审计文档一致`() {
        for (s in samples) {
            val what = s.name
            val saved = OBp.of(s.bp)
            val calls = selectCalls(s)

            // 链形态：load 开头、texture 收尾（53 支 textureEnabled=false，FULL_SETTER §3「贴图两边都没生效」）
            assertEquals("首个调用应为 loadBrushPreset: $what", "loadBrushPreset", calls.first().name)
            assertEquals("末调用应为 setBrushTexture: $what", "setBrushTexture", calls.last().name)
            assertEquals(false, calls.last().args[0])

            // 无 setBrushSpacing（全部 spacingCustomized=false，GROK §3.3）
            assertTrue("$what 不应有 setBrushSpacing", calls.none { it.name == "setBrushSpacing" })

            // tip 恒下发一次：采样笔 png / 计算笔 ""（GROK §4.2、FULL_SETTER §3 采样笔注记）
            val tip = callOf(calls, "setBrushTipAsset")
            assertEquals("tipAsset: $what", s.parsedKpp.tipAsset ?: "", tip.args[0])
            if (s.tsv(2) == "samp") {
                assertTrue("采样笔应有 png 笔尖: $what", (tip.args[0] as String).endsWith(".png"))
            } else {
                assertEquals("计算笔 tip 应为空串: $what", "", tip.args[0])
            }
            // size 在 tip 之前（FULL_SETTER §4 选笔链顺序）
            val sizeIdx = calls.indexOfFirst { it.name == "setBrushSize" }
            val tipIdx = calls.indexOfFirst { it.name == "setBrushTipAsset" }
            assertTrue("$what setBrushSize 应在 setBrushTipAsset 之前", sizeIdx in 0 until tipIdx)

            // jitter == (angleJitter, sizeJitter)（FULL_SETTER §1 c-rot/c-size）
            val jit = callOf(calls, "setBrushJitter")
            assertEquals("jitterAngle: $what", saved.jitterAngle, jit.args[0] as Double, 1e-9)
            assertEquals("jitterSize: $what", saved.jitterSize, jit.args[1] as Double, 1e-9)

            // scatter 语义：scatter>0 者 setBrushScatter 后跟 setBrushOptionDynamics(Scatter, 强度 1.0)
            if (s.info.scatter > 0.001) {
                val scIdx = calls.indexOfFirst { it.name == "setBrushScatter" }
                assertTrue("$what 应有 setBrushScatter", scIdx >= 0)
                assertEquals("setBrushScatter 值: $what", s.info.scatter, calls[scIdx].args[0] as Double, 1e-9)
                val dyn = calls.withIndex()
                    .firstOrNull { it.index > scIdx && it.value.name == "setBrushOptionDynamics" && it.value.args[0] == "Scatter" }
                assertNotNull("$what setBrushScatter 后应有 setBrushOptionDynamics(Scatter)", dyn)
                assertEquals("Scatter sensor: $what", "fuzzy", dyn!!.value.args[2])
                assertEquals("Scatter 强度应为 1.0 语义: $what", 1.0, dyn.value.args[4] as Double, 1e-9)
            } else {
                assertTrue("$what 不应有 Scatter 动力学", calls.none { it.name == "setBrushOptionDynamics" && it.args[0] == "Scatter" })
            }

            // 53 支全部 pressureSize=true → 回填 bp dc=true，走定制分支
            assertTrue("$what 回填 bp 应 dynamicsCustomized", saved.dynamicsCustomized)
        }

        // 文档点名的具体值（GROK §3.3 / FULL_SETTER §3）
        val hc1 = callOf(selectCalls(byName.getValue("Hard Charcoal 01")), "setBrushJitter")
        assertEquals("Hard Charcoal 01 jitterAngle", 0.30, hc1.args[0] as Double, 1e-9)
        assertEquals("Hard Charcoal 01 jitterSize", 0.30, hc1.args[1] as Double, 1e-9)
        assertTrue(
            "Hard Charcoal 01 不应有 setBrushSpacing",
            selectCalls(byName.getValue("Hard Charcoal 01")).none { it.name == "setBrushSpacing" },
        )
        assertEquals(
            "Hard Charcoal 01 tip 应为空串",
            "",
            callOf(selectCalls(byName.getValue("Hard Charcoal 01")), "setBrushTipAsset").args[0],
        )

        val hc2 = callOf(selectCalls(byName.getValue("Hard Charcoal 02")), "setBrushJitter")
        assertEquals("Hard Charcoal 02 jitterAngle", 0.0, hc2.args[0] as Double, 1e-9)
        assertEquals("Hard Charcoal 02 jitterSize", 0.25, hc2.args[1] as Double, 1e-9)

        val hp4 = callOf(selectCalls(byName.getValue("Hard Pencil 04")), "setBrushJitter")
        assertEquals("Hard Pencil 04 jitterAngle", 0.05, hp4.args[0] as Double, 1e-9)

        val mp01 = callOf(selectCalls(byName.getValue("Medium Pencil 01")), "setBrushJitter")
        assertEquals("Medium Pencil 01 (07) jitterAngle", 0.31, mp01.args[0] as Double, 1e-9)
        val mp01a = callOf(selectCalls(byName.getValue("Medium Pencil 01A")), "setBrushJitter")
        assertEquals("Medium Pencil 01A (08) jitterAngle", 0.35, mp01a.args[0] as Double, 1e-9)

        // 采样笔点名 + 全量：setBrushTipAsset(png) 且 size 在 tip 之前
        for (nm in listOf("HB Pencil", "Medium Pencil 01", "Medium Pencil 01A")) {
            val calls = selectCalls(byName.getValue(nm))
            val tip = callOf(calls, "setBrushTipAsset")
            assertTrue("$nm 采样笔应有 png 笔尖", (tip.args[0] as String).endsWith(".png"))
            assertTrue(
                "$nm size 应在 tip 之前",
                calls.indexOfFirst { it.name == "setBrushSize" } < calls.indexOfFirst { it.name == "setBrushTipAsset" },
            )
        }
        val sampCount = samples.count { it.tsv(2) == "samp" }
        assertEquals("采样笔应 23 支（GROK §4）", 23, sampCount)
        for (s in samples.filter { it.tsv(2) == "samp" }) {
            assertTrue("${s.name} 采样笔 tip 应为 png", (callOf(selectCalls(s), "setBrushTipAsset").args[0] as String).endsWith(".png"))
        }
    }

    // ---- 撤销/选笔怪癖：brushRotationSensor 来自上一支笔的 VM 状态 ----

    @Test
    fun `选笔怪癖 brushRotationSensor 传上一支笔 drawingangle 与空串产生不同的链`() {
        // Hard Charcoal 01：dc=true、followDirection=false、rotationSensor=fuzzy
        val s = byName.getValue("Hard Charcoal 01")
        val withSensor = selectCalls(s, rotationSensor = "drawingangle")
        val blankSensor = selectCalls(s, rotationSensor = "")
        assertFalse(
            "传 drawingangle（上一支笔的 Rotation 传感器）时不应关掉 followDirection（FULL_SETTER §1 c-same）",
            withSensor.any { it.name == "setBrushFollowDirection" },
        )
        assertTrue(
            "传空串时应出现 setBrushFollowDirection(false)（上游 1061-1062）",
            blankSensor.any { it.name == "setBrushFollowDirection" && it.args[0] == false },
        )
        assertEquals(
            "怪癖只影响 setBrushFollowDirection 这一处",
            withSensor.filter { it.name != "setBrushFollowDirection" },
            blankSensor.filter { it.name != "setBrushFollowDirection" },
        )
        // 对照：followDirection=true 的笔两条链都发 setBrushFollowDirection(true)
        val fd = samples.first { it.info.followDirection }
        for (sensor in listOf("drawingangle", "")) {
            assertTrue(
                "${s.name}/${fd.name} followDirection=true 应发 setBrushFollowDirection(true)",
                selectCalls(fd, rotationSensor = sensor).any { it.name == "setBrushFollowDirection" && it.args[0] == true },
            )
        }
    }

    // ---- 每格式一条 ----

    @Test
    fun `出厂 auto 笔无记录走未定制分支`() {
        // 「无 png/gbr/gih 出厂预设样本」的实证（报告依据，不生成 skip）：
        // 出厂资产目录里非 .tag 文件只允许 .kpp/.myb 预设与 *_prev.png 缩略图；
        // .gbr/.gih 0 个、可作笔尖预设的 .png 0 个（*_prev.png 是缩略图不是预设）。
        val presetFiles = KppFixtures.dir.listFiles { f -> f.isFile && !f.name.endsWith(".tag") }!!
        assertTrue(
            "出厂 gbr/gih 预设样本 0 个",
            presetFiles.none { it.name.endsWith(".gbr") || it.name.endsWith(".gih") },
        )
        assertTrue(
            "出厂 png 预设样本 0 个（仅 _prev.png 缩略图）: ${presetFiles.filter { it.name.endsWith(".png") && !it.name.endsWith("_prev.png") }}",
            presetFiles.none { it.name.endsWith(".png") && !it.name.endsWith("_prev.png") },
        )

        val stem = "b)_Basic-5_Size_Opacity"
        val parsed = KppHelper.parseKppFile(File(KppFixtures.dir, "$stem.kpp"))
        assertEquals("出厂 auto 笔无采样笔尖", null, parsed.tipAsset)

        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleSelect(
            calls,
            index = 3,
            presetGroup = "基础",
            presetName = stem,
            saved = null, // 无记录 → 未定制分支
            currentToolIdIsEraser = false,
            nativeCompOp = "normal",
            brushColor = 0xFF000000.toInt(),
            brushSecondaryColor = 0xFFFFFFFF.toInt(),
            mem = DoubleArray(0),
            d = doubleArrayOf(64.0, 1.0, 1.0), // 模拟 brushPresetDefaults 的引擎默认
            parsedPaintOpSize = parsed.paintOpSize,
            parsedTipAsset = parsed.tipAsset,
            brushRotationSensor = "",
            brushDynamicOptions = emptyMap(),
        )
        assertTrue("未定制分支不应有 setBrushSpacing", calls.none { it.name == "setBrushSpacing" })
        assertTrue("未定制分支不应有 setBrushJitter", calls.none { it.name == "setBrushJitter" })
        assertTrue("未定制分支不应有 setBrushOptionDynamics", calls.none { it.name == "setBrushOptionDynamics" })
        assertTrue("未定制分支不应有 setBrushScatter", calls.none { it.name == "setBrushScatter" })
        assertTrue("未定制分支不应有 setBrushAngle", calls.none { it.name == "setBrushAngle" })
        assertTrue("未定制分支不应有 setBrushFade", calls.none { it.name == "setBrushFade" })
        assertTrue("未定制分支不应有 setBrushMirror", calls.none { it.name == "setBrushMirror" })
        // auto 笔无 png 笔尖 → setBrushTipAsset("")（上游 1091）
        assertEquals("", callOf(calls, "setBrushTipAsset").args[0])
        // size 来自引擎默认 d[0]（resolvePresetSize），opacity/flow 来自 d[1]/d[2]
        assertEquals(64.0, callOf(calls, "setBrushSize").args[0] as Double, 1e-9)
        assertEquals(1.0, callOf(calls, "setBrushOpacity").args[0] as Double, 1e-9)
        assertEquals(1.0, callOf(calls, "setBrushFlow").args[0] as Double, 1e-9)
        // 未定制分支里 composite 调两次（load 块 + 分支头）
        assertEquals(2, calls.count { it.name == "setBrushCompositeOp" })
    }

    @Test
    fun `kpp 定制记录走定制分支且 spacing 下发`() {
        // 合成记录：在工作台改 spacing 一项（spacingCustomized=true），其余默认、dc=false
        val saved = OBp(
            isCustomized = true,
            size = 42.0,
            spacing = 0.77,
            spacingCustomized = true,
            dynamicsCustomized = false,
        )
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleSelect(
            calls, index = 5, presetGroup = "基础", presetName = "kpp-custom",
            saved = saved, currentToolIdIsEraser = false, nativeCompOp = "normal",
            brushColor = 1, brushSecondaryColor = 2,
            mem = DoubleArray(0), d = DoubleArray(0),
            parsedPaintOpSize = 30.0, parsedTipAsset = null,
            brushRotationSensor = "", brushDynamicOptions = emptyMap(),
        )
        assertEquals(0.77, callOf(calls, "setBrushSpacing").args[0] as Double, 1e-9)
        assertEquals(42.0, callOf(calls, "setBrushSize").args[0] as Double, 1e-9)
        assertEquals("isCustomized → 定制链", 0.0, callOf(calls, "setBrushAngle").args[0] as Double, 1e-9)
        assertTrue("dc=false 不应有 setBrushOptionDynamics", calls.none { it.name == "setBrushOptionDynamics" })
        assertTrue("dc=false 不应有 setBrushScatter", calls.none { it.name == "setBrushScatter" })
        assertTrue("dc=false 不应有 setBrushPressureDynamics", calls.none { it.name == "setBrushPressureDynamics" })
        // 合成记录 tipAsset 为空 → 定制分支的 setBrushTipAsset("")（上游 1076）
        assertEquals("", callOf(calls, "setBrushTipAsset").args[0])
        assertEquals(0.0, callOf(calls, "setBrushJitter").args[0] as Double, 1e-9)
        assertTrue("smudgeCustomized=false 不应有 setBrushSmudgeRate", calls.none { it.name == "setBrushSmudgeRate" })
        assertNotNull(calls.firstOrNull { it.name == "setBrushAirbrush" })
    }

    @Test
    fun `复制预设与源 ABR 走同一条选笔链`() {
        // BrushImportPipeline.duplicate 拷贝 officialBp → 复制记录的回填 bp 与源相同 → 链相同
        val s = byName.getValue("Hard Charcoal 01")
        assertEquals(selectCalls(s), selectCalls(s))
    }

    @Test
    fun `重置后 defaultParams 按 sensorXml 走定制或未定制分支`() {
        // 上游重置（PaintViewModelBrush.kt:586-627）写 defaultParams：isCustomized=true、dc=sensorXml 非空
        // a) sensorXml 非空 → dc=true：定制分支、dynamicOptions 空 → setBrushPressureDynamics
        val dcTrue = OBp(isCustomized = true, dynamicsCustomized = true, dynamicOptions = emptyMap())
        val calls1 = mutableListOf<Call>()
        UpstreamChainOracle.oracleSelect(
            calls1, index = 7, presetGroup = "", presetName = "reset-xml",
            saved = dcTrue, currentToolIdIsEraser = false, nativeCompOp = "normal",
            brushColor = 1, brushSecondaryColor = 2,
            mem = DoubleArray(0), d = DoubleArray(0),
            parsedPaintOpSize = 30.0, parsedTipAsset = null,
            brushRotationSensor = "drawingangle", brushDynamicOptions = emptyMap(),
        )
        assertTrue("dc=true 应有 setBrushScatter(0.0)", calls1.any { it.name == "setBrushScatter" && (it.args[0] as Double) == 0.0 })
        assertNotNull("dc=true 且 dynamicOptions 空 → setBrushPressureDynamics", calls1.firstOrNull { it.name == "setBrushPressureDynamics" })
        assertTrue("dynamicOptions 空不应有 setBrushOptionDynamics", calls1.none { it.name == "setBrushOptionDynamics" })
        assertTrue("定制分支应有 setBrushJitter(0,0)", calls1.any { it.name == "setBrushJitter" })
        // b) sensorXml 空 → dc=false：不发任何 dynamics setter
        val dcFalse = OBp(isCustomized = true, dynamicsCustomized = false)
        val calls2 = mutableListOf<Call>()
        UpstreamChainOracle.oracleSelect(
            calls2, index = 8, presetGroup = "", presetName = "reset-plain",
            saved = dcFalse, currentToolIdIsEraser = false, nativeCompOp = "normal",
            brushColor = 1, brushSecondaryColor = 2,
            mem = DoubleArray(0), d = DoubleArray(0),
            parsedPaintOpSize = 30.0, parsedTipAsset = null,
            brushRotationSensor = "", brushDynamicOptions = emptyMap(),
        )
        assertTrue("dc=false 不应有 setBrushScatter", calls2.none { it.name == "setBrushScatter" })
        assertTrue("dc=false 不应有 setBrushOptionDynamics", calls2.none { it.name == "setBrushOptionDynamics" })
        assertTrue("dc=false 不应有 setBrushPressureDynamics", calls2.none { it.name == "setBrushPressureDynamics" })
        assertTrue("isCustomized=true 仍走定制分支（有 setBrushAngle）", calls2.any { it.name == "setBrushAngle" })
    }

    // ---- 恢复链 ----

    @Test
    fun `恢复链 reloadPreset=true 定制长链以 setToolMode 收尾`() {
        val s = byName.getValue("Hard Charcoal 01")
        val saved = OBp.of(s.bp)
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleRestore(
            calls, reloadPreset = true, origPreset = 7,
            presetGroup = "", presetName = s.name, saved = saved,
            origSize = 12.0, origOpacity = 0.5, origFlow = 0.9,
            origOp = "multiply", origMode = 1, origIsEraser = false,
            nativeCompOp = "normal", brushColor = 1, brushSecondaryColor = 2,
            brushRotationSensor = saved.rotationSensor, brushDynamicOptions = dynEnabled(saved),
        )
        assertEquals("setToolMode 收尾（上游 1264）", "setToolMode", calls.last().name)
        assertEquals(1, calls.last().args[0])
        assertEquals("size 用 orig 值（上游 1173）", 12.0, callOf(calls, "setBrushSize").args[0] as Double, 1e-9)
        assertEquals(0.5, callOf(calls, "setBrushOpacity").args[0] as Double, 1e-9)
        assertEquals(0.9, callOf(calls, "setBrushFlow").args[0] as Double, 1e-9)
        assertTrue("无 setBrushSpacing", calls.none { it.name == "setBrushSpacing" })
        val jit = callOf(calls, "setBrushJitter")
        assertEquals(0.30, jit.args[0] as Double, 1e-9)
        assertEquals(0.30, jit.args[1] as Double, 1e-9)
        assertEquals("", callOf(calls, "setBrushTipAsset").args[0])
        assertTrue(calls.any { it.name == "setBrushOptionDynamics" && it.args[0] == "Scatter" && (it.args[4] as Double) == 1.0 })
    }

    @Test
    fun `恢复链 reloadPreset=false 短链只有 eraser comp size opacity flow mode`() {
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleRestore(
            calls, reloadPreset = false, origPreset = -1,
            presetGroup = "", presetName = "x", saved = OBp(isCustomized = true),
            origSize = 15.0, origOpacity = 0.8, origFlow = 1.0,
            origOp = "multiply", origMode = 0, origIsEraser = false,
            nativeCompOp = "normal", brushColor = 1, brushSecondaryColor = 2,
            brushRotationSensor = "", brushDynamicOptions = emptyMap(),
        )
        assertEquals(
            listOf("setPresetIsEraser", "setBrushCompositeOp", "setBrushSize", "setBrushOpacity", "setBrushFlow", "setToolMode"),
            calls.map { it.name },
        )
        assertEquals(15.0, calls[2].args[0] as Double, 1e-9)
        assertEquals(0, calls.last().args[0])
    }

    // ---- 重载链 ----

    @Test
    fun `重载链无条件 smudge airbrush 且 texture 在 mirror 之前`() {
        val s = byName.getValue("Hard Charcoal 01")
        val p = OBp.of(s.bp)
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleReload(
            calls, pSnapshot = p, targetIdx = 5, isEraserPreset = false,
            brushColor = 1, brushSecondaryColor = 2, brushCompositeOp = "normal",
            brushRotationSensor = p.rotationSensor, brushDynamicOptions = dynEnabled(p),
        )
        // 无条件 smudge/airbrush（上游 711-716 无 sc 保护，airbrushRate 不做 >=5 兜底）
        assertEquals(p.smudgeRate, callOf(calls, "setBrushSmudgeRate").args[0] as Double, 1e-9)
        assertEquals(p.smudgeLength, callOf(calls, "setBrushSmudgeLength").args[0] as Double, 1e-9)
        val air = callOf(calls, "setBrushAirbrush")
        assertEquals(p.airbrush, air.args[0])
        assertEquals(p.airbrushRate, air.args[1] as Double, 1e-9)
        // texture 在 mirror 之前（上游 697-701 vs 705）
        val texIdx = calls.indexOfFirst { it.name == "setBrushTexture" }
        val mirrorIdx = calls.indexOfFirst { it.name == "setBrushMirror" }
        assertTrue("texture 应在 mirror 之前", texIdx in 0 until mirrorIdx)
        assertTrue("无 setBrushSpacing", calls.none { it.name == "setBrushSpacing" })
        // tip 为空 → 非空才发（上游 645-647），Hard Charcoal 01 无 setBrushTipAsset
        assertTrue("tip 为空不应下发 setBrushTipAsset", calls.none { it.name == "setBrushTipAsset" })
    }

    @Test
    fun `重载链采样笔非空 tip 下发且在 load 之后`() {
        val s = byName.getValue("HB Pencil")
        val p = OBp.of(s.bp)
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleReload(
            calls, pSnapshot = p, targetIdx = 9, isEraserPreset = false,
            brushColor = 1, brushSecondaryColor = 2, brushCompositeOp = "normal",
            brushRotationSensor = p.rotationSensor, brushDynamicOptions = dynEnabled(p),
        )
        assertEquals("loadBrushPreset", calls[0].name)
        assertEquals("setBrushTipAsset", calls[1].name)
        assertTrue((calls[1].args[0] as String).endsWith(".png"))
    }

    // ---- 回放链 ----

    @Test
    fun `回放 setup 链 jitter 条件动力学与 fade 顺序`() {
        val s = byName.getValue("Hard Charcoal 01")
        val p = OBp.of(s.bp)
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleReplaySetup(
            calls, target = 9, presetGroup = "", presetName = s.name, saved = p,
        )
        assertEquals("loadBrushPreset", calls[0].name)
        assertEquals("setPresetIsEraser", calls[1].name)
        assertEquals(false, calls[1].args[0])
        // jitter 带条件（任一 >0）且值与选中链一致
        val jit = callOf(calls, "setBrushJitter")
        assertEquals(0.30, jit.args[0] as Double, 1e-9)
        assertEquals(0.30, jit.args[1] as Double, 1e-9)
        // dc 循环也下发 Scatter（isEnabled 兜底 true、强度 1.0）
        assertTrue(calls.any { it.name == "setBrushOptionDynamics" && it.args[0] == "Scatter" && (it.args[1] == true) && (it.args[4] as Double) == 1.0 })
        // fade/softness/ratio/sharpness isFinite → 下发
        for (n in listOf("setBrushFade", "setBrushSoftness", "setBrushRatio", "setBrushSharpness")) assertNotNull(calls.firstOrNull { it.name == n })
        // tip 空 → 不下发；texture disabled → 不下发；无 Mirror/FollowDirection/Spacing/Size
        assertTrue(calls.none { it.name == "setBrushTipAsset" })
        assertTrue(calls.none { it.name == "setBrushTexture" })
        for (n in listOf("setBrushMirror", "setBrushFollowDirection", "setBrushSpacing", "setBrushSize")) {
            assertTrue("回放链不应有 $n", calls.none { it.name == n })
        }
    }

    @Test
    fun `回放 setup 无记录只发 load 与 eraser 标志`() {
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleReplaySetup(calls, target = 4, presetGroup = "基础", presetName = "plain", saved = null)
        assertEquals(listOf("loadBrushPreset", "setPresetIsEraser"), calls.map { it.name })
        assertEquals(false, calls[1].args[0])
    }

    @Test
    fun `回放 setup eraser 名字的预设发 setPresetIsEraser true`() {
        val calls = mutableListOf<Call>()
        UpstreamChainOracle.oracleReplaySetup(
            calls, target = 2, presetGroup = "橡皮擦", presetName = "a)_Eraser_Circle", saved = null,
        )
        assertEquals(true, calls[1].args[0])
    }
}
