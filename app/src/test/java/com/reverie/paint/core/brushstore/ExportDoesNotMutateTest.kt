/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushExportSource
import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import com.reverie.paint.core.KritaBundleManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 导出/分享不改写原件（执行文档 W-11），基于真实内置 kpp：
 * 导出后快照 md5、有效文件 md5 都不变；bundle 里的 kpp == 有效字节；v2 关闭时源文件同样只读。
 */
class ExportDoesNotMutateTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private lateinit var staging: File
    private val stems by lazy { KppFixtures.all.map { it.first }.take(30) }
    private val assets: (String) -> ByteArray? = { p ->
        File(KppFixtures.dir.parentFile, p).takeIf { it.isFile }?.readBytes()
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("export").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        staging = File(root, "cache/export_staging")
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun md5(f: File) = BrushDiagnostics.md5(f.readBytes())

    @Test
    fun exportingEditedAndPristineBuiltinsLeavesSnapshotsAndEffectiveFilesUntouched() {
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } }, migrate = { true }))
        val edited = stems.filterIndexed { i, _ -> i % 2 == 0 }
        for (s in edited) {
            BrushOverrideStore.replace(
                BrushIdentity.builtinId(s),
                BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.33, "spacing" to 0.6), dirty = setOf("opacity", "spacing")),
            )
            EffectivePresetBuilder.materialize(BrushIdentity.builtinId(s), presetDir)
        }
        val snapBefore = stems.associateWith { md5(PresetSnapshotStore.snapshotFile(BrushIdentity.builtinId(it))!!) }
        val effBefore = stems.associateWith { md5(File(presetDir, "$it.kpp")) }
        // 旧记录里即使有"污染"参数，v2 下也不应进入导出字节
        val bogus = BrushParams(opacity = 0.01)

        val staged = stems.map { s ->
            val b = BrushExportSource.effectiveBytes(s, presetDir, bogus, assets)!!
            assertArrayEquals("导出 == 有效字节: $s", File(presetDir, "$s.kpp").readBytes(), b)
            s to BrushExportSource.stage(s, b, staging)
        }
        val bundle = KritaBundleManager.exportBundle(
            bundleName = "Export", groupName = "G", presets = staged, tipAssets = emptyList(), customOutDir = File(root, "out"),
        )

        assertEquals(snapBefore, stems.associateWith { md5(PresetSnapshotStore.snapshotFile(BrushIdentity.builtinId(it))!!) })
        assertEquals(effBefore, stems.associateWith { md5(File(presetDir, "$it.kpp")) })
        for (s in stems) {
            val isEdited = s in edited
            assertEquals(!isEdited, effBefore[s] == BrushDiagnostics.md5(KppFixtures.bytes(s)))
        }
        ZipFile(bundle).use { z ->
            for (s in stems) {
                val e = z.getEntry("paintoppresets/$s.kpp")!!
                assertArrayEquals("bundle 内 == 有效字节: $s", File(presetDir, "$s.kpp").readBytes(), z.getInputStream(e).readBytes())
            }
        }
        assertFalse("staging 不在 paintoppresets 下", staging.canonicalPath.startsWith(presetDir.canonicalPath))
    }

    @Test
    fun legacyPathAppliesRecordInMemoryOnly() {
        // v2 未启动：源文件 + 旧记录在内存里合成；源文件逐字节不变；源文件缺失时读 asset 也不在 presetDir 落盘
        val s = stems.first()
        val src = File(presetDir, "$s.kpp").apply { writeBytes(KppFixtures.bytes(s)) }
        val params = BrushParams(opacity = 0.42, spacing = 0.8)
        val b = BrushExportSource.effectiveBytes(s, presetDir, params, assets)!!
        assertArrayEquals(KppFixtures.bytes(s), src.readBytes())
        assertArrayEquals(KppHelper.updateKppBytes(KppFixtures.bytes(s), s, params), b)
        assertNotEquals(BrushDiagnostics.md5(KppFixtures.bytes(s)), BrushDiagnostics.md5(b))

        val other = stems[1]
        val b2 = BrushExportSource.effectiveBytes(other, presetDir, null, assets)!!
        assertArrayEquals(KppFixtures.bytes(other), b2)
        assertFalse("不应把 asset 拷进 presetDir", File(presetDir, "$other.kpp").exists())
    }
}
