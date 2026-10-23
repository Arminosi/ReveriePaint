/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * 重置语义（执行文档 W-06）：
 * - BuiltinResetEqualsFreshInstall：内置预设改过再重置 == 全新安装（字节与 UI 回读都相同），全部 268 个内置 kpp；
 * - ImportResetEquivalence：导入预设重置回到导入那一刻（不是出厂、不是空白）。
 */
class ResetEquivalenceTest {
    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("reset").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
    }

    @After
    fun tearDown() {
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private val allAssets: Map<String, ByteArray> by lazy { KppFixtures.all.associate { "${it.first}.kpp" to KppFixtures.bytes(it.first) } }

    private fun install(root: File): File {
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { allAssets }, migrate = { true }))
        return presetDir
    }

    /** 模拟一次工作台编辑：回读 → 改若干控件 → 提交 */
    private fun edit(id: String, presetDir: File, file: File): Boolean {
        val xml = com.reverie.paint.core.KppHelper.readPresetXml(file.readBytes(), true)!!
        val edits = OverrideFixtures.editsFor(xml)
        if (edits.isEmpty()) return false
        val ui = PresetReadback.fromEffectiveBytes(file.readBytes())
        var p = ui
        for ((cid, v) in edits) p = PresetPropertyMap.spec(cid).set(p, v)
        val c = BrushEditSession.commit(id, p, ui, presetDir)
        return c.changedKeys.isNotEmpty()
    }

    @Test
    fun `BuiltinResetEqualsFreshInstall 全部内置预设`() {
        // 全新安装的有效字节与 UI 回读作基准
        val fresh = install(File(tmp, "fresh"))
        val freshBytes = KppFixtures.all.associate { (stem, _) -> stem to File(fresh, "$stem.kpp").readBytes() }
        BrushStoreBootstrap.resetForTest()

        val dir = install(File(tmp, "used"))
        var edited = 0
        for ((stem, _) in KppFixtures.all) {
            val id = BrushIdentity.idForName(stem)!!
            val f = File(dir, "$stem.kpp")
            if (edit(id, dir, f)) {
                edited++
                assertFalse("$stem 编辑未生效", f.readBytes().contentEquals(freshBytes.getValue(stem)))
            }
            assertTrue(BrushEditSession.reset(id, dir) != null)
            assertTrue(BrushOverrideStore.get(id).isEmpty())
            assertArrayEquals("$stem 重置后字节 != 全新安装", freshBytes.getValue(stem), f.readBytes())
            assertEquals(
                "$stem 重置后 UI 回读 != 全新安装",
                PresetReadback.fromEffectiveBytes(freshBytes.getValue(stem)),
                PresetReadback.fromEffectiveBytes(f.readBytes()),
            )
        }
        assertTrue("参与的预设太少: $edited", edited >= 260)
        // 覆盖文件持久化后重开仍为空
        BrushOverrideStore.open(BrushStoreBootstrap.storeRoot(File(tmp, "used")))
        assertTrue(BrushOverrideStore.ids().isEmpty())
    }

    @Test
    fun `ImportResetEquivalence 导入预设重置回到导入时`() {
        val dir = install(File(tmp, "imp"))
        // 用不同类型的真实 kpp 作为"导入来源"：像素、涂抹、纹理、遮罩、喷枪
        val sources = KppFixtures.all.map { it.first }.filter { s ->
            listOf("Basic", "Smudge", "Texture", "WaterC", "Airbrush", "Pencil").any { s.contains(it, ignoreCase = true) }
        }.distinctBy { s -> listOf("Basic", "Smudge", "Texture", "WaterC", "Airbrush", "Pencil").first { s.contains(it, true) } }
        assertTrue(sources.size >= 5)
        for (stem in sources) {
            val id = BrushIdentity.newImportedId()
            val name = "导入_$stem"
            val bytes = KppFixtures.bytes(stem)
            PresetSnapshotStore.putImported(id, "kpp", bytes, JSONObject().put("pack", "test.zip").put("provenance", "imported"))
            BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, "test.zip"))
            EffectivePresetBuilder.materialize(id, dir)
            val f = File(dir, "$name.kpp")
            assertArrayEquals(stem, bytes, f.readBytes())
            assertTrue("$stem 编辑未生效", edit(id, dir, f))
            assertNotEquals(BrushDiagnostics.md5(bytes), BrushDiagnostics.md5(f.readBytes()))
            BrushEditSession.reset(id, dir)
            assertArrayEquals("$stem 重置后 != 导入时", bytes, f.readBytes())
            assertTrue(BrushOverrideStore.get(id).isEmpty())
        }
    }

    @Test
    fun `重置保留元数据、丢掉 APP_ONLY 调参`() {
        val legacy = BrushParams(streamline = 0.6, taper = 0.3, author = "某人", isAuthorLocked = true, description = "d")
        assertTrue(PresetReadback.appOnlyModified(legacy))
        val kept = PresetReadback.metadataOnly(legacy)!!
        assertFalse(PresetReadback.appOnlyModified(kept))
        assertEquals("某人", kept.author)
        assertEquals(true, kept.isAuthorLocked)
        assertEquals("d", kept.description)
        assertEquals(BrushParams().streamline, kept.streamline, 0.0)
        assertNull(PresetReadback.metadataOnly(BrushParams(streamline = 0.6)))
        assertFalse(PresetReadback.appOnlyModified(null))
    }
}
