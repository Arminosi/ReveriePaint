/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.model.FrontBufferHolePolicy.ARM_NORMAL
import com.reverie.paint.model.FrontBufferHolePolicy.ARM_NO_HOLES
import com.reverie.paint.model.FrontBufferHolePolicy.ARM_WITH_MASK
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_E
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_HMIN
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_OFF
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_HANDBACK
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_HOLE_REDRAW
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_PLAIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferHolePolicyTest {
    @Test
    fun parseMode() {
        assertEquals(MODE_OFF, FrontBufferHolePolicy.parseMode(0, "e"))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, null))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, "hmin"))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, ""))
        assertEquals(MODE_E, FrontBufferHolePolicy.parseMode(1, " E "))
        assertEquals(MODE_OFF, FrontBufferHolePolicy.parseMode(1, "off"))
    }

    @Test
    fun armDecision() {
        fun d(mode: Int = MODE_HMIN, vis: Boolean = true, ready: Boolean = true, cov: Float = 0.1f, other: Boolean = false) =
            FrontBufferHolePolicy.decideArm(mode, vis, ready, cov, 0.35f, other)
        assertEquals(ARM_WITH_MASK, d())
        assertEquals(ARM_NO_HOLES, d(vis = false, ready = false))
        assertEquals(ARM_NORMAL, d(ready = false))
        assertEquals(ARM_NORMAL, d(cov = 0.9f)) // 4x 像素网格铺满: 降级常规
        assertEquals(ARM_NORMAL, d(cov = Float.NaN))
        assertEquals(ARM_WITH_MASK, d(cov = 0.35f))
        assertEquals(ARM_NORMAL, d(other = true))
        assertEquals(ARM_NORMAL, d(vis = false, other = true))
        assertEquals(ARM_NO_HOLES, d(mode = MODE_OFF, ready = false, other = true))
        assertEquals(ARM_WITH_MASK, d(mode = MODE_E))
    }

    @Test
    fun packetDecisionNonHitIsPlain() {
        for (m in intArrayOf(MODE_OFF, MODE_HMIN, MODE_E)) assertEquals(PACKET_PLAIN, FrontBufferHolePolicy.decidePacket(m, false))
        assertEquals(PACKET_PLAIN, FrontBufferHolePolicy.decidePacket(MODE_OFF, true))
        assertEquals(PACKET_HOLE_REDRAW, FrontBufferHolePolicy.decidePacket(MODE_HMIN, true))
        assertEquals(PACKET_HANDBACK, FrontBufferHolePolicy.decidePacket(MODE_E, true))
    }

    @Test
    fun clearDelay() {
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(1, 0))
        assertFalse(FrontBufferHolePolicy.clearAfterCommit(1, 2))
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(3, 2))
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(1, -4))
    }

    @Test
    fun snapLinesOnlyWithAssistedSnapModes() {
        assertFalse(FrontBufferHolePolicy.snapLinesPossible(false, true, true))
        assertFalse(FrontBufferHolePolicy.snapLinesPossible(true, false, false))
        assertTrue(FrontBufferHolePolicy.snapLinesPossible(true, true, false))
        assertTrue(FrontBufferHolePolicy.snapLinesPossible(true, false, true))
    }

    @Test
    fun maskRebuildSkipsDebounceOnlyOnFirstChangeAfterQuiet() {
        // 手势抬起: 之前很久没有过期 -> 立即
        assertTrue(FrontBufferHolePolicy.buildImmediately(true, 10_000L, Long.MIN_VALUE / 2, 50L))
        assertTrue(FrontBufferHolePolicy.buildImmediately(true, 1_000L, 950L, 50L))
        // 连续变化中的一帧 (滑杆 / 动画) -> 防抖
        assertFalse(FrontBufferHolePolicy.buildImmediately(true, 1_000L, 984L, 50L))
        // 视图动画还在跑 -> 防抖
        assertFalse(FrontBufferHolePolicy.buildImmediately(false, 10_000L, 0L, 50L))
    }
}
