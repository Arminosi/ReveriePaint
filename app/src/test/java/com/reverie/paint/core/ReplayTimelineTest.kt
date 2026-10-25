/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.STROKE_START
import com.reverie.paint.model.RecordingReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放有效时间线 (空闲压缩) 与 seek/step 推进逻辑的 JVM 单测。
 * 全部使用合成事件流, 不触碰 JNI/C++: 派发用 skipReplayEventPayload 代替。
 */
class ReplayTimelineTest {
    private class Stream {
        val buf = RecordingBuffer(64)
        var count = 0
        var rawMs = 0L

        fun move(dt: Int) {
            buf.u8(STROKE_MOVE)
            buf.varint(dt)
            buf.f32(1f)
            buf.f32(2f)
            buf.f32(0.5f)
            count++
            rawMs += dt
        }

        fun start(dt: Int) {
            buf.u8(STROKE_START)
            buf.varint(dt)
            buf.f32(1f)
            buf.f32(2f)
            buf.f32(0.5f)
            count++
            rawMs += dt
        }

        fun end(dt: Int) {
            buf.u8(STROKE_END)
            buf.varint(dt)
            count++
            rawMs += dt
        }

        fun bytes(): ByteArray = buf.data.copyOf(buf.size)
    }

    /** 模拟实测场景: 前段密集作画 -> 一段很长的空闲 -> 后段作画。 */
    private fun gapStream(gapMs: Int): Stream {
        val s = Stream()
        s.start(0)
        repeat(99) { s.move(10) } // 990ms
        s.end(10) // 1000ms
        s.start(gapMs) // 空闲后的第一笔
        repeat(99) { s.move(10) }
        s.end(10)
        return s
    }

    private class SeekResult(val currentMs: Long, val progress: Float, val events: Int, val reader: RecordingReader)

    /** 与 PaintViewModel.seekLocked 相同的推进与进度计算 (派发换成跳过 payload)。 */
    private fun seek(
        events: ByteArray,
        totalMs: Long,
        capMs: Long,
        fraction: Float,
    ): SeekResult {
        val r = RecordingReader(events)
        var n = 0
        val target = ReplayTimeline.targetMsFor(fraction, totalMs)
        val cur =
            ReplayTimeline.advance(r, 0L, target, drainAll = fraction >= 1f, capMs = capMs) { type, _ ->
                skipReplayEventPayload(type, r)
                n++
            }
        val atEnd = ReplayTimeline.isAtEnd(fraction, r.remaining())
        val p = if (atEnd) 1f else ReplayTimeline.progressOf(cur, totalMs)
        return SeekResult(cur, p, n, r)
    }

    @Test
    fun `effective dt is capped at idle cap`() {
        assertEquals(0L, ReplayTimeline.effectiveDt(0, REPLAY_IDLE_CAP_MS))
        assertEquals(16L, ReplayTimeline.effectiveDt(16, REPLAY_IDLE_CAP_MS))
        assertEquals(1000L, ReplayTimeline.effectiveDt(1000, REPLAY_IDLE_CAP_MS))
        assertEquals(1000L, ReplayTimeline.effectiveDt(1_090_482, REPLAY_IDLE_CAP_MS))
        assertEquals(1_090_482L, ReplayTimeline.effectiveDt(1_090_482, Long.MAX_VALUE))
        assertEquals(1000L, REPLAY_IDLE_CAP_MS)
    }

    @Test
    fun `effective total compresses long idle gap`() {
        val s = gapStream(600_000)
        val eff = ReplayTimeline.effectiveTotalMs(s.bytes(), 2, REPLAY_IDLE_CAP_MS, s.count)
        assertEquals(601_000L + 1000L, s.rawMs)
        assertEquals(1000L + 1000L + 1000L, eff)
        // 不压缩时等于原始总时长
        assertEquals(s.rawMs, ReplayTimeline.effectiveTotalMs(s.bytes(), 2, Long.MAX_VALUE, s.count))
    }

    @Test
    fun `effective total returns -1 on truncated stream or event count mismatch`() {
        val s = gapStream(5000)
        val bytes = s.bytes()
        assertEquals(-1L, ReplayTimeline.effectiveTotalMs(bytes.copyOf(bytes.size - 3), 2, REPLAY_IDLE_CAP_MS))
        assertEquals(-1L, ReplayTimeline.effectiveTotalMs(bytes, 2, REPLAY_IDLE_CAP_MS, s.count + 1))
        assertTrue(ReplayTimeline.effectiveTotalMs(bytes, 2, REPLAY_IDLE_CAP_MS, s.count) > 0)
    }

    @Test
    fun `old raw timeline seek into gap jumps progress to near end (regression of reported bug)`() {
        val s = gapStream(600_000)
        val res = seek(s.bytes(), s.rawMs, Long.MAX_VALUE, 0.5f)
        // 旧行为: 拖到 50% 实际已越过空闲间隔, 真实进度 ~99.8%
        assertTrue(res.progress > 0.99f)
    }

    @Test
    fun `seek into compressed gap lands near requested fraction`() {
        val s = gapStream(600_000)
        val total = ReplayTimeline.effectiveTotalMs(s.bytes(), 2, REPLAY_IDLE_CAP_MS, s.count)
        for (f in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
            val res = seek(s.bytes(), total, REPLAY_IDLE_CAP_MS, f)
            assertTrue("fraction=$f progress=${res.progress}", res.progress >= f)
            // 越过 target 的最多一个事件, 其有效 dt <= cap
            assertTrue("fraction=$f progress=${res.progress}", res.progress - f <= REPLAY_IDLE_CAP_MS.toFloat() / total + 1e-4f)
            assertTrue(res.reader.remaining() > 0)
            // 进度来自实际 currentMs, 而不是直接写 fraction
            assertEquals(ReplayTimeline.progressOf(res.currentMs, total), res.progress, 0f)
        }
    }

    @Test
    fun `resume after seek continues smoothly without jumping to end`() {
        val s = gapStream(600_000)
        val total = ReplayTimeline.effectiveTotalMs(s.bytes(), 2, REPLAY_IDLE_CAP_MS, s.count)
        val res = seek(s.bytes(), total, REPLAY_IDLE_CAP_MS, 0.5f)
        val r = res.reader
        var cur = res.currentMs
        val before = res.progress
        // 一个 16ms@1x tick
        val target = ReplayTimeline.stepTargetMs(cur, 16L, total)
        cur =
            ReplayTimeline.advance(r, cur, target, drainAll = target >= total, capMs = REPLAY_IDLE_CAP_MS) { type, _ ->
                skipReplayEventPayload(type, r)
            }
        val after = ReplayTimeline.progressOf(cur, total)
        assertTrue(r.remaining() > 0)
        assertTrue("before=$before after=$after", after - before < 0.02f)
        assertTrue(after < 1f)
    }

    @Test
    fun `seek to 0 consumes nothing and seek to 1 drains everything`() {
        val s = gapStream(600_000)
        val total = ReplayTimeline.effectiveTotalMs(s.bytes(), 2, REPLAY_IDLE_CAP_MS, s.count)
        val zero = seek(s.bytes(), total, REPLAY_IDLE_CAP_MS, 0f)
        assertEquals(0, zero.events)
        assertEquals(0f, zero.progress, 0f)
        val full = seek(s.bytes(), total, REPLAY_IDLE_CAP_MS, 1f)
        assertEquals(s.count, full.events)
        assertEquals(0, full.reader.remaining())
        assertEquals(1f, full.progress, 0f)
        assertEquals(total, full.currentMs)
    }

    @Test
    fun `final tick drains trailing zero-dt events`() {
        val s = Stream()
        s.start(0)
        s.move(100)
        s.move(0)
        s.move(0)
        s.end(0)
        val bytes = s.bytes()
        val total = ReplayTimeline.effectiveTotalMs(bytes, 2, REPLAY_IDLE_CAP_MS, s.count)
        assertEquals(100L, total)
        val r = RecordingReader(bytes)
        var cur = 0L
        var guard = 0
        while (r.remaining() > 0 && guard++ < 100) {
            val target = ReplayTimeline.stepTargetMs(cur, 16L, total)
            cur =
                ReplayTimeline.advance(r, cur, target, drainAll = target >= total, capMs = REPLAY_IDLE_CAP_MS) { type, _ ->
                    skipReplayEventPayload(type, r)
                }
        }
        assertEquals(0, r.remaining())
        assertEquals(total, cur)
    }

    @Test
    fun `zero total advances one event per tick`() {
        val s = Stream()
        s.start(0)
        s.move(0)
        s.end(0)
        val bytes = s.bytes()
        val total = ReplayTimeline.effectiveTotalMs(bytes, 2, REPLAY_IDLE_CAP_MS, s.count)
        assertEquals(0L, total)
        assertEquals(Long.MAX_VALUE, ReplayTimeline.stepTargetMs(0L, 16L, total))
        val r = RecordingReader(bytes)
        var n = 0
        ReplayTimeline.advance(r, 0L, Long.MAX_VALUE, drainAll = true, capMs = REPLAY_IDLE_CAP_MS, singleEvent = true) { type, _ ->
            skipReplayEventPayload(type, r)
            n++
        }
        assertEquals(1, n)
        assertEquals(1f, ReplayTimeline.progressOf(0L, 0L), 0f)
    }

    @Test
    fun `isAtEnd only when full fraction or stream exhausted`() {
        assertTrue(ReplayTimeline.isAtEnd(1f, 10))
        assertTrue(ReplayTimeline.isAtEnd(0.5f, 0))
        assertFalse(ReplayTimeline.isAtEnd(0.5f, 10))
    }
}
