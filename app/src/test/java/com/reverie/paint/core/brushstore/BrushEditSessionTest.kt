/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 工作台编辑提交（W-05）：差分只记真正改动、LIVE 不重载、REBUILD 重载、改回原值后覆盖被剪掉 */
class BrushEditSessionTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = "builtin:$stem"

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("sess").toFile()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stems = listOf(stem, "c)_Pencil-3_Large_4B")
        assertTrue(
            BrushStoreBootstrap.run(tmp, presetDir, true, "1", { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private fun file() = File(presetDir, "$stem.kpp")
    private fun ui(): BrushParams = PresetReadback.fromEffectiveBytes(file().readBytes(), "kpp")
    private fun md5File() = BrushDiagnostics.md5(file().readBytes())
    private fun md5Snap() = BrushDiagnostics.md5(PresetSnapshotStore.read(id)!!)

    @Test
    fun `原样提交不产生覆盖也不改有效文件`() {
        val p = ui()
        val c = BrushEditSession.commit(id, p, p, presetDir)
        assertTrue(c.changedKeys.toString(), c.changedKeys.isEmpty())
        assertFalse(c.needsReload)
        assertTrue(BrushOverrideStore.get(id).isEmpty())
        assertEquals(md5Snap(), md5File())
    }

    @Test
    fun `LIVE 控件只改覆盖不重载，REBUILD 控件要求重载`() {
        val p = ui()
        val c1 = BrushEditSession.commit(id, p.copy(opacity = 0.37), p, presetDir)
        assertEquals(setOf("opacity"), c1.changedKeys)
        assertFalse(c1.needsReload)
        assertEquals(0.37, ui().opacity, 1e-6)

        val p2 = ui()
        val c2 = BrushEditSession.commit(id, p2.copy(spacing = 0.33), p2, presetDir)
        assertEquals(setOf("spacing"), c2.changedKeys)
        assertTrue(c2.needsReload)
        assertEquals(0.33, ui().spacing, 1e-6)
        assertEquals(setOf("opacity", "spacing"), BrushOverrideStore.get(id).controls.keys)
    }

    @Test
    fun `stage 累积到 flush 一次提交，改回快照值后覆盖剪空、有效文件回到快照字节`() {
        val orig = ui()
        BrushEditSession.stage(id, orig.copy(opacity = 0.37), orig)
        BrushEditSession.stage(id, orig.copy(opacity = 0.37, hueJitter = 0.25), orig)
        assertTrue(BrushEditSession.hasPending(id))
        val c = BrushEditSession.flush(id, presetDir)!!
        assertEquals(setOf("opacity", "hueJitter"), c.changedKeys.mapTo(HashSet()) { it })
        assertFalse(BrushEditSession.hasPending(id))
        assertNull(BrushEditSession.flush(id, presetDir))

        val c2 = BrushEditSession.commit(id, ui().copy(opacity = orig.opacity, hueJitter = orig.hueJitter), orig, presetDir)
        assertEquals(2, c2.changedKeys.size)
        assertTrue(BrushOverrideStore.get(id).controls.toString(), BrushOverrideStore.get(id).isEmpty())
        assertEquals(md5Snap(), md5File())
    }

    @Test
    fun `discard 丢弃待提交编辑`() {
        val p = ui()
        BrushEditSession.stage(id, p.copy(opacity = 0.37), p)
        BrushEditSession.discard(id)
        assertTrue(BrushEditSession.flushAll(presetDir).isEmpty())
        assertTrue(BrushOverrideStore.get(id).isEmpty())
    }
}
