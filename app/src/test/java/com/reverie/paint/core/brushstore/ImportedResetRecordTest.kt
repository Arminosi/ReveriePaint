/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrParser
import com.reverie.paint.core.AbrPresetConverter
import com.reverie.paint.core.BrushParams
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #136：重置时旧记录回到「导入态」的三态决策。
 *
 * 锁住四件事：
 * 1. 只有 provenance ∈ {import, abr} 才归本规则，其余（内置/migrated/retired/duplicate/create）一律 NotApplicable；
 * 2. bundle/zip 导入态是「无记录」→ Delete；单文件 kpp 导入态是「未定制元数据记录」→ 元数据必须留住；
 * 3. ABR 导入态是「官方 bp」→ Restore 取 officialBp，不取被改过的旧记录；
 * 4. 对同一个 id 反复决策结果不变（重置多少次都一样）。
 */
class ImportedResetRecordTest {

    private lateinit var root: File
    private lateinit var presetDir: File
    private val stem = KppFixtures.all.first().first

    @Before
    fun setUp() {
        root = Files.createTempDirectory("reset_record").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        PresetReadback.clearBaselineCache()
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        PresetReadback.clearBaselineCache()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------ 工具

    /** 造一个导入快照（source.json 只写 provenance，模拟最小数据） */
    private fun putImported(name: String, provenance: String?): String {
        val id = BrushIdentity.newImportedId()
        val src = JSONObject()
        if (provenance != null) src.put("provenance", provenance)
        PresetSnapshotStore.putImported(id, "kpp", KppFixtures.bytes(stem), src)
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED))
        return id
    }

    private fun assertNotApplicable(action: ImportedResetRecord.Action) {
        assertTrue("期望 NotApplicable，实际 $action", action is ImportedResetRecord.Action.NotApplicable)
    }

    private fun restoreOf(action: ImportedResetRecord.Action): BrushParams {
        assertTrue("期望 Restore，实际 $action", action is ImportedResetRecord.Action.Restore)
        return (action as ImportedResetRecord.Action.Restore).p
    }

    private val singleFileImportRecord = BrushParams(
        author = "外部创作者 (分享)",
        isAuthorLocked = true,
        description = "导入自外部创作者分享的笔刷预设",
        isCustomized = false,
        dynamicsCustomized = false,
        smudgeCustomized = false,
    )

    // ------------------------------------------------------------ 用例

    @Test
    fun builtinHasNoSourceAndIsNotApplicable() {
        val id = BrushIdentity.builtinId(stem)
        assertNull("内置预设没有 source.json", ImportedResetRecord.provenance(id))
        assertNotApplicable(ImportedResetRecord.actionFor(id, singleFileImportRecord))
    }

    @Test
    fun bundleImportWithoutRecordDeletes() {
        val id = putImported("Bundle M", BrushImportPipeline.PROVENANCE_IMPORT)
        assertEquals("import", ImportedResetRecord.provenance(id))
        assertTrue(
            "bundle 导入态 = 无记录",
            ImportedResetRecord.actionFor(id, null) is ImportedResetRecord.Action.Delete,
        )
    }

    @Test
    fun singleFileImportKeepsMetadataAndIsIdempotent() {
        val id = putImported("Single M", BrushImportPipeline.PROVENANCE_IMPORT)
        val first = restoreOf(ImportedResetRecord.actionFor(id, singleFileImportRecord))
        // 单文件 kpp 导入写的是「未定制 + 元数据」记录，重置必须保住 author/description
        assertEquals("外部创作者 (分享)", first.author)
        assertTrue(first.isAuthorLocked)
        assertEquals("导入自外部创作者分享的笔刷预设", first.description)
        assertFalse("元数据记录不是定制态", first.isCustomized)
        assertFalse("不得凭空打开动力学校验", first.dynamicsCustomized)
        assertTrue("dynamicOptions 必须为空", first.dynamicOptions.isEmpty())
        assertFalse("不算已修改", PresetReadback.appOnlyModified(first, PresetReadback.baseline(id)))

        // 重置多少次都一样：把结果再喂一次，得到同一条记录
        val second = restoreOf(ImportedResetRecord.actionFor(id, first))
        assertEquals(first, second)
    }

    @Test
    fun abrRestoresImportedOfficialBpNotEditedRecord() {
        val preset = AbrParser.AbrPresetInfo(name = "Synth Reset", pressureSize = true, pressureOpacity = true)
        val converted = AbrPresetConverter.convert(preset, "Synth Reset", null, "", emptyMap(), "Synth")
        val id = BrushImportPipeline.importAbr(
            "Synth Reset", converted.kppBytes, AbrPresetConverter.abrParamsJson(preset),
            AbrPresetConverter.CONVERTER_VERSION, "Synth.abr", presetDir,
            PresetReadback.baselineJson(converted.bp), OfficialBpJson.toJson(converted.bp),
        )
        assertEquals("abr", ImportedResetRecord.provenance(id))

        // 旧记录被用户改过（size=999、开了 dynamicOptions）：重置必须无视它，回到导入时的官方 bp
        val edited = converted.bp.copy(size = 999.0, dynamicOptions = mapOf("opacity" to "<curve>0,0;1,1;</curve>"))
        val restored = restoreOf(ImportedResetRecord.actionFor(id, edited))
        assertNotEquals(999.0, restored.size, 1e-9)
        // 整对象相等：任何字段（含 size/opacity/flow/角度/压感/动力曲线/笔尖/纹理/色彩动力学/
        // 遮罩/传感器）只要没回到导入时的官方 bp 就会红。逐字段挑着断言会漏掉将来新增的字段。
        //
        // spacing 是唯一例外：spacingCustomized=false 时解码器按设计把它归一成 0.1
        // （LegacyBrushParamsCodec:109「未定制间距一律以 kpp 为准」），往返一次必然不等。
        // 用哨兵覆盖后比较其余全部字段，spacingCustomized 本身单独断言。
        assertEquals(
            "重置必须逐字段回到导入时的官方 bp",
            converted.bp.copy(spacing = 0.0),
            restored.copy(spacing = 0.0),
        )
        assertEquals(converted.bp.spacingCustomized, restored.spacingCustomized)
        assertTrue("官方 bp 不带 dynamicOptions", restored.dynamicOptions.isEmpty())
    }

    @Test
    fun abrWithoutOfficialBpIsNotApplicable() {
        // provenance=abr 但 officialBp / abrParams / sidecar 全无（数据残缺）：不猜，交回上游
        val id = putImported("Abr No Bp", BrushImportPipeline.PROVENANCE_ABR)
        assertNotApplicable(ImportedResetRecord.actionFor(id, BrushParams(isCustomized = true)))
    }

    @Test
    fun nonWhitelistedProvenancesAreNotApplicable() {
        // 迁移/下线/复制/新建都不属于"导入态"，本次一律保持上游旧逻辑
        for (prov in listOf("migrated", "migrated_possibly_modified", "retired", "duplicate", "create")) {
            val id = putImported("Other $prov", prov)
            assertEquals(prov, ImportedResetRecord.provenance(id))
            assertNotApplicable(ImportedResetRecord.actionFor(id, BrushParams(isCustomized = true)))
        }
        // provenance 缺失也不能当成 import
        val bare = putImported("No Provenance", null)
        assertNotApplicable(ImportedResetRecord.actionFor(bare, BrushParams(isCustomized = true)))
    }
}
