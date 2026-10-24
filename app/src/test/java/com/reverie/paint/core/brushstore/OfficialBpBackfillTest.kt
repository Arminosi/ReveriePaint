/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrPresetConverter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * T2 官方 bp 回填（PLAN_brush_parity.md §5 T2）：53 支真实 ABR 快照上验证
 * [AbrParamsDecoder] 回环 + [OfficialBp.forId] 回填 bp 与转换器规则/平板 tsv 零差异。
 *
 * 期望值依据 FULL_SETTER_AUDIT.md §2/§3 与 GROK_AUDIT.md §3.3/§4.1（差异预设的
 * jitter/scatter 数值断言见 UpstreamChainOracleTest；本类以转换器原文与 tsv 为准）。
 * tsv 列映射**数据驱动**：对每一数值/布尔列，在全部 53 行上找与之（含比例因子）一致的
 * [com.reverie.paint.core.AbrParser.AbrPresetInfo] 字段；映射结果随测试输出打印。
 * 53 支全部零差异才算通过。
 */
class OfficialBpBackfillTest {

    private lateinit var store: Abr53Fixture.Store
    private lateinit var samples: List<Abr53Fixture.Sample>

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

    // ---- 用例 1：abrParams 解码回环逐键相等 ----

    @Test
    fun `53 支 abrParams 解码回环逐键相等`() {
        for (s in samples) {
            val round = AbrPresetConverter.abrParamsJson(AbrParamsDecoder.decode(s.abrParams))
            assertJsonEquivalent("回环 ${s.name}", s.abrParams, round, 1e-9)
        }
    }

    private fun keysOf(o: JSONObject): Set<String> = ArrayList<String>().also { set -> o.keys().forEach { set.add(it) } }.toSet()

    private fun assertJsonEquivalent(what: String, orig: JSONObject, round: JSONObject, tol: Double) {
        val origKeys = keysOf(orig)
        assertEquals("$what 键集", origKeys, keysOf(round))
        for (k in origKeys) {
            assertTrue("$what 缺键 $k", round.has(k))
            if (orig.isNull(k)) {
                assertTrue("$what 键 $k 应为 null", round.isNull(k))
                continue
            }
            val a = orig.get(k)
            val b = round.get(k)
            when (a) {
                is Boolean -> assertEquals("$what 键 $k", a, b)
                is Number ->
                    assertTrue(
                        "$what 键 $k 数值差异: $a vs $b",
                        abs(a.toDouble() - (b as Number).toDouble()) <= tol,
                    )
                is String -> assertEquals("$what 键 $k", a, b)
                is JSONObject -> assertTrue("$what 键 $k 应为对象", b is JSONObject)
                else -> throw AssertionError("$what 键 $k 类型不支持: ${a.javaClass}")
            }
            if (a is JSONObject && b is JSONObject) assertJsonEquivalent("$what 键 $k", a, b, tol)
        }
    }

    // ---- 用例 2：forId 回填 bp 与转换器规则一致 ----

    /** [com.reverie.paint.core.AbrPresetConverter.convert] 传感器/动力学部分（36-105 行）的测试内独立重写 */
    private class Rules(
        val sizeSensor: String,
        val opacitySensor: String,
        val flowSensor: String,
        val rotationSensor: String,
        val dynamicOptions: Map<String, String>,
        val hasPressureDynamics: Boolean,
        val hasDynamics: Boolean,
    )

    private fun expectedConverterRules(p: com.reverie.paint.core.AbrParser.AbrPresetInfo): Rules {
        val sizeSensor = when {
            p.tiltSize -> "declination"
            p.sizeJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val opacitySensor = when {
            p.tiltOpacity -> "declination"
            p.opacityJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val flowSensor = when {
            p.tiltFlow -> "declination"
            p.flowJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val rotationSensor = when {
            p.followDirection -> "drawingangle"
            p.tiltAngle -> "ascension"
            p.angleJitter > 0.05 -> "fuzzy"
            else -> "drawingangle"
        }

        val dynamicOptions = mutableMapOf<String, String>()
        if (p.tiltSize) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (p.sizeJitter > 0.05) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (p.tiltOpacity) {
            dynamicOptions["Opacity"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (p.opacityJitter > 0.05) {
            dynamicOptions["Opacity"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (p.tiltFlow) {
            dynamicOptions["Flow"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (p.flowJitter > 0.05) {
            dynamicOptions["Flow"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (p.followDirection) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params fanCornersStep="30" fanCornersEnabled="0" angleOffset="0" id="drawingangle"><curve>0,0;1,1;</curve></params>"""
        } else if (p.tiltAngle) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="ascension"><curve>0,0;1,1;</curve></params>"""
        } else if (p.angleJitter > 0.05) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (p.scatter > 0.001) {
            dynamicOptions["Scatter"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }

        val hasPressureDynamics = p.pressureSize || p.pressureOpacity || p.pressureFlow ||
            p.tiltSize || p.tiltOpacity || p.tiltFlow || p.tiltAngle
        val hasDynamics = hasPressureDynamics || p.scatter > 0.001 || p.followDirection ||
            p.flipX || p.flipY || p.sizeJitter > 0.001 || p.angleJitter > 0.001 ||
            p.opacityJitter > 0.001 || p.flowJitter > 0.001 || dynamicOptions.isNotEmpty() ||
            p.dualBrush != null || p.texture != null || p.colorDynamics != null
        return Rules(sizeSensor, opacitySensor, flowSensor, rotationSensor, dynamicOptions, hasPressureDynamics, hasDynamics)
    }

    @Test
    fun `53 支 forId 回填 bp 与转换器规则一致`() {
        for (s in samples) {
            val bp = requireNotNull(OfficialBp.forId(s.id)) { "forId 应非空: ${s.name}" }
            val p = s.info
            val rules = expectedConverterRules(p)
            val what = s.name

            // 四个标志（GROK_AUDIT §3.3：isCustomized=true / spc=false / sc=false / dc=hasDynamics）
            assertEquals("isCustomized: $what", true, bp.isCustomized)
            assertEquals("spacingCustomized: $what", false, bp.spacingCustomized)
            assertEquals("smudgeCustomized: $what", false, bp.smudgeCustomized)
            assertEquals("dynamicsCustomized: $what", rules.hasDynamics, bp.dynamicsCustomized)

            // 抖动（FULL_SETTER_AUDIT §1 c-rot：jitterAngle=angleJitter）
            assertEquals("jitterAngle: $what", p.angleJitter, bp.jitterAngle, 1e-9)
            assertEquals("jitterSize: $what", p.sizeJitter, bp.jitterSize, 1e-9)

            // 布尔直传
            assertEquals("followDirection: $what", p.followDirection, bp.followDirection)
            assertEquals("randomFlipX: $what", p.flipX, bp.randomFlipX)
            assertEquals("randomFlipY: $what", p.flipY, bp.randomFlipY)
            assertEquals("airbrush: $what", p.airbrush, bp.airbrush)
            assertEquals("compositeOp: $what", p.compositeOp, bp.compositeOp)

            // pressure*：converter 119-121 规则（tilt 合并）。本包 53 支 tilt 全 false，
            // 故同时等价于 abrParams 布尔本身（前提在下面断言）。
            assertFalse("tiltSize: $what", p.tiltSize)
            assertFalse("tiltOpacity: $what", p.tiltOpacity)
            assertFalse("tiltFlow: $what", p.tiltFlow)
            assertFalse("tiltAngle: $what", p.tiltAngle)
            assertEquals("pressureSize: $what", if (p.pressureSize) 1.0 else 0.0, bp.pressureSize, 1e-9)
            assertEquals("pressureOpacity: $what", if (p.pressureOpacity) 1.0 else 0.0, bp.pressureOpacity, 1e-9)
            assertEquals("pressureFlow: $what", if (p.pressureFlow) 1.0 else 0.0, bp.pressureFlow, 1e-9)
            assertEquals("pressureEnabled: $what", rules.hasPressureDynamics, bp.pressureEnabled)

            // 基础数值（converter 108-114）
            assertEquals("size: $what", p.diameter, bp.size, 1e-9)
            assertEquals("spacing: $what", p.spacing, bp.spacing, 1e-9)
            assertEquals("angle: $what", p.angle, bp.angle, 1e-9)
            assertEquals("scatter: $what", p.scatter, bp.scatter, 1e-9)
            assertEquals("ratio: $what", p.roundness, bp.ratio, 1e-9)
            assertEquals("opacity 写死 1.0: $what", 1.0, bp.opacity, 1e-9)
            assertEquals("flow 写死 1.0: $what", 1.0, bp.flow, 1e-9)

            // 传感器与动力学 XML 常量逐字对照
            assertEquals("sizeSensor: $what", rules.sizeSensor, bp.sizeSensor)
            assertEquals("opacitySensor: $what", rules.opacitySensor, bp.opacitySensor)
            assertEquals("flowSensor: $what", rules.flowSensor, bp.flowSensor)
            assertEquals("rotationSensor: $what", rules.rotationSensor, bp.rotationSensor)
            assertEquals("scatterSensor: $what", "fuzzy", bp.scatterSensor)
            assertEquals("dynamicOptions: $what", rules.dynamicOptions, bp.dynamicOptions)

            // 笔尖：快照 kpp 解析出的 tip（计算笔为 ""，GROK_AUDIT §3.3）
            val tip = s.parsedKpp.tipAsset ?: ""
            assertEquals("tipAsset: $what", tip, bp.tipAsset)
            if (s.tsv(2) == "samp") {
                assertTrue("采样笔应有 png 笔尖: $what (${bp.tipAsset})", bp.tipAsset.endsWith(".png"))
            } else {
                assertEquals("计算笔笔尖应为空: $what", "", bp.tipAsset)
            }

            // 预期副作用：sidecar 写进临时目录
            assertTrue("sidecar 应回写临时目录: $what", store.importedSidecar(s.dirName).isFile)
        }
    }

    // ---- 用例 3：平板 tsv 数据驱动列对照（53 支零差异） ----

    private val numericFields = listOf(
        "diameter", "spacing", "angle", "roundness", "hardness", "scatter",
        "sizeJitter", "angleJitter", "roundnessJitter", "opacityJitter", "flowJitter",
        "minDiameterRatio", "minRoundness", "tipIndex",
    )

    private val boolFields = listOf(
        "pressureSize", "pressureOpacity", "pressureFlow",
        "tiltSize", "tiltOpacity", "tiltFlow", "tiltAngle",
        "followDirection", "flipX", "flipY", "isComputed", "airbrush",
    )

    private fun numField(s: Abr53Fixture.Sample, f: String): Double = when (f) {
        "diameter" -> s.info.diameter
        "spacing" -> s.info.spacing
        "angle" -> s.info.angle
        "roundness" -> s.info.roundness
        "hardness" -> s.info.hardness
        "scatter" -> s.info.scatter
        "sizeJitter" -> s.info.sizeJitter
        "angleJitter" -> s.info.angleJitter
        "roundnessJitter" -> s.info.roundnessJitter
        "opacityJitter" -> s.info.opacityJitter
        "flowJitter" -> s.info.flowJitter
        "minDiameterRatio" -> s.info.minDiameterRatio
        "minRoundness" -> s.info.minRoundness
        "tipIndex" -> s.info.tipIndex.toDouble()
        else -> error("未知字段 $f")
    }

    private fun boolField(s: Abr53Fixture.Sample, f: String): Boolean = when (f) {
        "pressureSize" -> s.info.pressureSize
        "pressureOpacity" -> s.info.pressureOpacity
        "pressureFlow" -> s.info.pressureFlow
        "tiltSize" -> s.info.tiltSize
        "tiltOpacity" -> s.info.tiltOpacity
        "tiltFlow" -> s.info.tiltFlow
        "tiltAngle" -> s.info.tiltAngle
        "followDirection" -> s.info.followDirection
        "flipX" -> s.info.flipX
        "flipY" -> s.info.flipY
        "isComputed" -> s.info.isComputed
        "airbrush" -> s.info.airbrush
        else -> error("未知字段 $f")
    }

    /** 回填 bp 上与 tsv 对照的数值视图（jitter 在 bp 里叫 jitterAngle/jitterSize） */
    private fun bpNumField(s: Abr53Fixture.Sample, f: String): Double = when (f) {
        "sizeJitter" -> s.bp.jitterSize
        "angleJitter" -> s.bp.jitterAngle
        "diameter" -> s.bp.size
        "spacing" -> s.bp.spacing
        "angle" -> s.bp.angle
        "roundness" -> s.bp.ratio
        "scatter" -> s.bp.scatter
        else -> numField(s, f)
    }

    private fun bpBoolField(s: Abr53Fixture.Sample, f: String): Boolean = when (f) {
        "pressureSize" -> s.bp.pressureSize > 0.5
        "followDirection" -> s.bp.followDirection
        "flipX" -> s.bp.randomFlipX
        "flipY" -> s.bp.randomFlipY
        else -> boolField(s, f)
    }

    @Test
    fun `53 支与平板 tsv 数据驱动列对照零差异`() {
        // 行对齐 sanity：序号列（samples 按目录名序，先按 tsv 序号排回 1..53 再核对）与 samp/comp 列
        val byTsvOrder = samples.sortedBy { it.tsv(0).toDouble() }
        byTsvOrder.forEachIndexed { i, s ->
            assertEquals("tsv 序号列第 ${i + 1} 行", (i + 1).toDouble(), s.tsv(0).toDouble(), 1e-9)
            assertEquals(
                "samp/comp 列与 isComputed 不一致: ${s.name}",
                if (s.tsv(2) == "samp") !s.info.isComputed else s.info.isComputed,
                true,
            )
        }

        val columns = 0 until Abr53Fixture.TSV_COLUMNS
        val numericCols = columns.filter { c -> samples.all { it.tsv(c).toDoubleOrNull() != null } }
        val boolCols = columns.filter { c -> samples.all { it.tsv(c) == "True" || it.tsv(c) == "False" } }

        // 数值列推断：列值 == 字段值 × 因子（全部 53 行成立才算映射）
        val factors = listOf(1.0, 0.01, 100.0, 0.1, 10.0, 0.001)
        val numMatches = ArrayList<Triple<Int, String, Double>>() // (列, 字段, 因子)
        for (c in numericCols) {
            for (f in numericFields) {
                val k = factors.firstOrNull { k ->
                    samples.all { abs(it.tsv(c).toDouble() - numField(it, f) * k) <= 1e-6 }
                }
                if (k != null) numMatches += Triple(c, f, k)
            }
        }
        val boolMatches = ArrayList<Pair<Int, String>>() // (列, 字段)
        for (c in boolCols) {
            for (f in boolFields) {
                if (samples.all { (it.tsv(c) == "True") == boolField(it, f) }) boolMatches += c to f
            }
        }

        // 映射表（随测试输出打印，进报告）
        val numDesc = numMatches.joinToString(", ") { "col${it.first + 1}=${it.second}×${it.third}" }
        val boolDesc = boolMatches.joinToString(", ") { "col${it.first + 1}=${it.second}" }
        println("tsv 数值列映射: $numDesc")
        println("tsv 布尔列映射: $boolDesc")

        // 必须存在的列映射（计划要求的 jitter/follow/flip/spacing 对照）
        for (f in listOf("sizeJitter", "angleJitter", "diameter", "angle", "roundness", "scatter")) {
            val cols = numMatches.filter { it.second == f }.map { it.first }
            assertTrue("tsv 缺少 $f 数值列映射", cols.isNotEmpty())
        }
        for (f in listOf("pressureSize", "followDirection")) {
            val cols = boolMatches.filter { it.second == f }.map { it.first }
            assertTrue("tsv 缺少 $f 布尔列映射", cols.isNotEmpty())
        }
        // tsv 无 spacing 列（数据事实）：任何数值列都不与 spacing 一致；
        // spacing 对照退回 bp.spacing == abrParams.spacing（任务书括注）
        assertTrue(
            "不该有 spacing 列映射（tsv 无该列）",
            numMatches.none { it.second == "spacing" },
        )

        // flip：tsv 无独立信息量——全包 flipX/flipY 全 false；唯一一致布尔列 col15 同时
        // 与 tilt*/airbrush 一致（归属不唯一，但值零冲突）。断言一致性而非唯一归属。
        assertTrue("tsv 应有全 False 布尔列（col15）", boolMatches.any { it.first == 14 })
        assertTrue("53 支 flipX 应全 false", samples.none { it.info.flipX })
        assertTrue("53 支 flipY 应全 false", samples.none { it.info.flipY })

        // 逐列零差异：映射列 vs 回填 bp（1e-6）
        for ((c, f, k) in numMatches) {
            for (s in samples) {
                assertTrue(
                    "tsv col${c + 1}($f) 与 bp 不一致: ${s.name} tsv=${s.tsv(c)} bp=${bpNumField(s, f) * k}",
                    abs(s.tsv(c).toDouble() - bpNumField(s, f) * k) <= 1e-6,
                )
            }
        }
        for ((c, f) in boolMatches) {
            for (s in samples) {
                assertEquals(
                    "tsv col${c + 1}($f) 与 bp 不一致: ${s.name}",
                    s.tsv(c) == "True",
                    bpBoolField(s, f),
                )
            }
        }

        // spacing 对照（任务书括注）：bp.spacing == abrParams.spacing
        for (s in samples) {
            assertEquals("spacing: ${s.name}", s.info.spacing, s.bp.spacing, 1e-6)
        }

        // S/R/C 注记列与数据自洽（列语义佐证）
        for (s in samples) {
            assertEquals("S 标记: ${s.name}", s.info.sizeJitter > 0.0, s.tsv(15) == "S")
            assertEquals("R 标记: ${s.name}", s.info.angleJitter > 0.0, s.tsv(16) == "R")
            assertEquals(
                "C 标记: ${s.name}",
                s.info.scatter > 0.001 && s.info.scatter < 1.0,
                s.tsv(17) == "C",
            )
        }
    }
}
