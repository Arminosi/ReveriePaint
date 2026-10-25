/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrParser
import com.reverie.paint.core.AbrPresetConverter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ABR 导入的快照与重置（执行文档 W-09）：
 * 快照 = 转换后的 kpp；source.json 带 abrParams 与转换器版本；改 3 个控件后重置，有效文件 md5 == 导入时 md5。
 *
 * 真实样本：Krita 源码自带的 `brushes_by_mar_ka_d338ela.abr`（第三方作品，3.6MB，不入库）。
 * 路径取系统属性/环境变量 `ABR_SAMPLE`，缺省为工作机上的 Krita 源码目录；找不到时该用例显示为 skipped，而非假通过。
 * 另有一个不依赖外部文件的合成 ABR 用例（构造方式与 AbrParserTest 相同）。
 */
class AbrImportResetTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val records = HashMap<String, com.reverie.paint.core.BrushParams>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("abr").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stem = KppFixtures.all.first().first
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    private fun sampleFile(): File? {
        val p = System.getProperty("ABR_SAMPLE") ?: System.getenv("ABR_SAMPLE")
            ?: "/workspace/tc/krita-source/libs/brush/tests/data/brushes_by_mar_ka_d338ela.abr"
        return File(p).takeIf { it.isFile }
    }

    /** 与 VM 的 ABR 分支同样的匹配 + 转换 + 入库，返回 (name, id, 导入时字节) */
    private fun importAll(result: AbrParser.AbrParseResult, pack: String): List<Triple<String, String, ByteArray>> {
        val byUuid = result.tips.associateBy { it.uuid }
        val byIndex = result.tips.associateBy { it.index }
        val tipNames = result.tips.associate { it.uuid to "${pack}_tip_${it.index}.png" }
        val taken = BrushImportPipeline.takenNames(presetDir).mapTo(HashSet()) { it.lowercase() }
        return result.presets.mapIndexed { i, preset ->
            val tip = AbrParser.matchTipForPreset(preset, byUuid, byIndex, result.tips)
            val base = (preset.name.ifBlank { "$pack $i" }).replace(Regex("""[\\/:*?"<>|]"""), "_")
            var name = base
            var n = 2
            while (name.lowercase() in taken) name = "${base}_${n++}"
            taken += name.lowercase()
            val c = AbrPresetConverter.convert(preset, name, tip, tip?.let { tipNames[it.uuid] } ?: "", tipNames, pack)
            val id = BrushImportPipeline.importAbr(
                name, c.kppBytes, AbrPresetConverter.abrParamsJson(preset), AbrPresetConverter.CONVERTER_VERSION, "$pack.abr", presetDir,
                PresetReadback.baselineJson(c.bp),
            )
            records[name] = c.bp
            Triple(name, id, c.kppBytes)
        }
    }

    private fun checkResetRoundTrip(name: String, id: String, imported: ByteArray, preset: AbrParser.AbrPresetInfo) {
        val f = File(presetDir, "$name.kpp")
        val md5 = BrushDiagnostics.md5(imported)
        assertEquals("导入后有效文件 == 转换结果: $name", md5, BrushDiagnostics.md5(f.readBytes()))
        assertArrayEquals("快照 == 转换结果", imported, PresetSnapshotStore.read(id))
        val src = PresetSnapshotStore.source(id)!!
        assertEquals("abr", src.getString("provenance"))
        assertEquals(AbrPresetConverter.CONVERTER_VERSION, src.getInt("abrConverterVersion"))
        assertEquals(md5, src.getString("md5"))
        val ap = src.getJSONObject("abrParams")
        assertEquals(preset.name, ap.getString("name"))
        assertEquals(preset.diameter, ap.getDouble("diameter"), 1e-9)
        assertEquals(preset.spacing, ap.getDouble("spacing"), 1e-9)
        assertEquals(preset.dualBrush != null, ap.has("dualBrush"))
        assertEquals(preset.texture != null, ap.has("texture"))
        assertEquals(preset.colorDynamics != null, ap.has("colorDynamics"))
        assertEquals(BrushIdentity.Kind.IMPORTED, BrushIdentity.entryForName(name)!!.kind)
        assertTrue("导入后无覆盖", BrushOverrideStore.get(id).isEmpty())

        // 改 3 个控件
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.31, "spacing" to 0.77, "hueJitter" to 0.42)),
        )
        EffectivePresetBuilder.materialize(id, presetDir)
        assertNotEquals("编辑应改变有效文件: $name", md5, BrushDiagnostics.md5(f.readBytes()))

        BrushEditSession.reset(id, presetDir)
        assertEquals("重置后 md5 == 导入时: $name", md5, BrushDiagnostics.md5(f.readBytes()))
        assertTrue(BrushOverrideStore.get(id).isEmpty())
    }

    @Test
    fun realAbrSampleResetsToImportedBytes() {
        val file = sampleFile()
        assumeTrue("缺少真实 ABR 样本（设置 ABR_SAMPLE）", file != null)
        val result = file!!.inputStream().use { AbrParser.parse(it, basePackName = "MarKa") }
        assertTrue("真实样本应解析出 ≥30 个预设", result.presets.size >= 30)
        val imported = importAll(result, "MarKa")
        assertEquals(result.presets.size, imported.size)
        assertEquals("名字唯一", imported.size, imported.map { it.first.lowercase() }.toSet().size)
        imported.forEachIndexed { i, (name, id, bytes) -> checkResetRoundTrip(name, id, bytes, result.presets[i]) }

        // 重启：materializeAll 不改字节、identity 保留
        val before = imported.map { BrushDiagnostics.md5(File(presetDir, "${it.first}.kpp").readBytes()) }
        BrushStoreBootstrap.resetForTest()
        val stem = KppFixtures.all.first().first
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }))
        assertEquals(before, imported.map { BrushDiagnostics.md5(File(presetDir, "${it.first}.kpp").readBytes()) })
        imported.forEach { assertEquals(it.second, BrushIdentity.entryForName(it.first)!!.id) }
    }

    @Test
    fun richSyntheticPresetResetsToImportedBytes() {
        // 合成数据（非真实 ABR）：覆盖双笔刷/纹理/颜色动态/倾斜/喷枪这些真实样本里未必出现的分支
        val tip = AbrParser.AbrDecodedTip(uuid = "u-1", index = 1, width = 16, height = 16, data = ByteArray(256) { (it % 256).toByte() })
        val dualTip = AbrParser.AbrDecodedTip(uuid = "u-2", index = 2, width = 8, height = 8, data = ByteArray(64) { 200.toByte() })
        val preset = AbrParser.AbrPresetInfo(
            name = "Synthetic Rich", tipUuid = "u-1", tipIndex = 1, diameter = 48.0, spacing = 0.18, angle = 30.0, roundness = 0.6,
            scatter = 0.5, pressureSize = true, pressureOpacity = true, tiltAngle = true, sizeJitter = 0.3, angleJitter = 0.2,
            flowJitter = 0.1, minDiameterRatio = 0.2, followDirection = true, flipX = true, airbrush = true,
            dualBrush = AbrParser.AbrDualBrushInfo(tipUuid = "u-2", diameter = 20.0, spacing = 0.3, scatter = 0.2, compositeOp = "multiply"),
            texture = AbrParser.AbrTextureInfo(patternName = "Canvas", patternUuid = "p-1", scale = 1.5, depth = 0.75, mode = "overlay", invert = true),
            colorDynamics = AbrParser.AbrColorDynamicsInfo(hueJitter = 0.1, satJitter = 0.2, valJitter = 0.3, secondaryMix = 0.5),
        )
        val result = AbrParser.AbrParseResult(10, 2, listOf(tip, dualTip), listOf(preset))
        val (name, id, bytes) = importAll(result, "Synth").single()
        checkResetRoundTrip(name, id, bytes, preset)
    }

    @Test
    fun appOnlyBaselineSurvivesResetAndIsNotModified() {
        // 无压感动态的 ABR：转换器给出 pressureEnabled=false（不在 kpp 里，是 APP_ONLY）
        val noPressure = AbrParser.AbrPresetInfo(name = "Flat")
        val withPressure = AbrParser.AbrPresetInfo(name = "Pressured", pressureSize = true)
        val res = importAll(AbrParser.AbrParseResult(6, 2, emptyList(), listOf(noPressure, withPressure)), "B")
        val (flat, flatId, _) = res[0]
        val (pr, prId, _) = res[1]
        assertEquals(false, records[flat]!!.pressureEnabled)
        assertEquals(true, records[pr]!!.pressureEnabled)
        assertEquals(false, PresetSnapshotStore.source(flatId)!!.getJSONObject("appBaseline").getBoolean("pressureEnabled"))
        assertTrue(PresetSnapshotStore.source(prId)!!.getJSONObject("appBaseline").length() == 0)

        val base = PresetReadback.baseline(flatId)
        assertEquals(false, base.pressureEnabled)
        // 导入时的旧记录 = 元数据 + 基线；不算"已修改"
        val rec = PresetReadback.metadataOnly(records[flat], base)!!
        assertEquals(false, rec.pressureEnabled)
        assertEquals(records[flat]!!.author, rec.author)
        assertEquals(records[flat]!!.description, rec.description)
        assertTrue(!PresetReadback.appOnlyModified(rec, base))
        // 用户打开压感 → 已修改；重置 → 回到 false
        val edited = rec.copy(pressureEnabled = true)
        assertTrue(PresetReadback.appOnlyModified(edited, base))
        assertEquals(false, PresetReadback.metadataOnly(edited, base)!!.pressureEnabled)
        // 没有基线的预设行为不变：默认值即基线
        assertEquals(true, PresetReadback.baseline(prId).pressureEnabled)
        // 复制继承基线
        val dupId = BrushImportPipeline.duplicate(flatId, "Flat 副本", presetDir)!!
        assertEquals(false, PresetReadback.baseline(dupId).pressureEnabled)
    }

    @Test
    fun abrNameCollisionIsCaseInsensitiveAgainstBuiltins() {
        val stem = KppFixtures.all.first().first
        val preset = AbrParser.AbrPresetInfo(name = stem.uppercase())
        val result = AbrParser.AbrParseResult(6, 2, emptyList(), listOf(preset, preset))
        val names = importAll(result, "P").map { it.first }
        assertEquals(listOf("${stem.uppercase()}_2", "${stem.uppercase()}_3"), names)
        assertArrayEquals("内置不得被覆盖", KppFixtures.bytes(stem), File(presetDir, "$stem.kpp").readBytes())
    }
}
