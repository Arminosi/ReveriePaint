/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingReader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 回放预设解析（执行文档 W-12），基于真实内置 kpp + 假引擎 */
class PlaybackPresetResolutionTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val stems by lazy { KppFixtures.all.map { it.first }.take(12) }

    private class FakeEngine : BrushEngineApplier.EngineFacade {
        val calls = mutableListOf<String>()
        override fun loadBrushPreset(index: Int): Boolean { calls += "load:$index"; return true }
        override fun setPresetIsEraser(isEraser: Boolean) { calls += "eraser:$isEraser" }
        override fun setBrushCompositeOp(op: String) { calls += "comp" }
        override fun setBrushColor(color: String) { calls += "color" }
        override fun setBrushSecondaryColor(color: String) { calls += "color2" }
        override fun setBrushSize(v: Double) { calls += "size" }
        override fun setBrushOpacity(v: Double) { calls += "opacity" }
        override fun setBrushFlow(v: Double) { calls += "flow" }
        override fun setBrushAngle(deg: Double) { calls += "angle" }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("playback").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } }, migrate = { true }))
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    private val storeRoot get() = BrushStoreBootstrap.storeRootDir!!

    @Test
    fun recordCachesEffectiveBytesAndEventRoundTrips() {
        val s = stems[0]
        val sel = PlaybackPresetCache.recordSelect(3, s)!!
        val eff = File(presetDir, "$s.kpp").readBytes()
        assertEquals(BrushDiagnostics.md5(eff), sel.md5)
        assertEquals(BrushIdentity.builtinId(s), sel.presetId)
        assertArrayEquals(eff, PlaybackPresetCache.cacheFile(storeRoot, sel.md5).readBytes())

        val b = RecordingBuffer()
        PlaybackPresetCache.write(b, sel)
        PlaybackPresetCache.write(b, sel.copy(index = -1))
        val r = RecordingReader(b.data.copyOf(b.size))
        assertEquals(sel, PlaybackPresetCache.read(r))
        assertEquals(sel.copy(index = -1), PlaybackPresetCache.read(r))
        assertEquals(b.size, r.pos)

        // 非 v2 管理的名字 → null（调用方记旧事件）
        assertNull(PlaybackPresetCache.recordSelect(0, "not_a_managed_preset"))
    }

    @Test
    fun resolutionFollowsEffectiveMd5ThenCacheThenFallback() {
        val s = stems[1]
        val id = BrushIdentity.builtinId(s)
        val sel = PlaybackPresetCache.recordSelect(5, s)!!
        val eng = FakeEngine()
        assertEquals(PlaybackPresetCache.Resolution.EXACT, PlaybackPresetCache.replay(eng, 5, sel, false))
        // 只加载 + 橡皮擦标志，无任何 setter
        assertEquals(listOf("load:5", "eraser:false"), eng.calls)

        // 录制后用户编辑了预设 → 有效字节变了，录制时的字节仍在缓存
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.25), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(id, presetDir)
        assertTrue(BrushDiagnostics.md5(File(presetDir, "$s.kpp").readBytes()) != sel.md5)
        assertEquals(PlaybackPresetCache.Resolution.CACHED, PlaybackPresetCache.resolve(sel))

        PlaybackPresetCache.cacheFile(storeRoot, sel.md5).delete()
        val eng2 = FakeEngine()
        assertEquals(PlaybackPresetCache.Resolution.MISSING, PlaybackPresetCache.replay(eng2, 7, sel, true))
        assertEquals(listOf("load:7", "eraser:true"), eng2.calls)

        // 重置回快照 → 有效 md5 回到录制时 → 又是 EXACT
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides())
        EffectivePresetBuilder.materialize(id, presetDir)
        assertEquals(PlaybackPresetCache.Resolution.EXACT, PlaybackPresetCache.resolve(sel))
    }

    @Test
    fun cacheIsLruBounded() {
        val dir = PlaybackPresetCache.cacheDir(storeRoot).apply { mkdirs() }
        val t = System.currentTimeMillis() - 1_000_000
        for (i in 0 until 10) File(dir, "f$i.kpp").apply { writeBytes(byteArrayOf(i.toByte())); setLastModified(t + i * 1000L) }
        PlaybackPresetCache.prune(storeRoot, max = 4)
        assertEquals(setOf("f6.kpp", "f7.kpp", "f8.kpp", "f9.kpp"), dir.list()!!.toSet())

        // 命中会刷新 mtime，不会被下一轮回收
        val sel = PlaybackPresetCache.recordSelect(0, stems[2])!!
        val hit = PlaybackPresetCache.cacheFile(storeRoot, sel.md5)
        hit.setLastModified(t - 10_000)
        BrushOverrideStore.replace(BrushIdentity.builtinId(stems[2]), BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.5), dirty = setOf("opacity")))
        EffectivePresetBuilder.materialize(BrushIdentity.builtinId(stems[2]), presetDir)
        assertEquals(PlaybackPresetCache.Resolution.CACHED, PlaybackPresetCache.resolve(sel))
        PlaybackPresetCache.prune(storeRoot, max = 4)
        assertNotNull(dir.list()!!.firstOrNull { it == hit.name })
    }
}
