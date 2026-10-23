/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.model.FrontBufferReadyGate.ALL
import com.reverie.paint.model.FrontBufferReadyGate.COND_GEOM_STABLE
import com.reverie.paint.model.FrontBufferReadyGate.COND_NOT_LOADING
import com.reverie.paint.model.FrontBufferReadyGate.COND_REFRESH
import com.reverie.paint.model.FrontBufferReadyGate.COND_RENDER_IDLE
import com.reverie.paint.model.FrontBufferReadyGate.COND_TILES
import com.reverie.paint.model.FrontBufferReadyGate.NOT_READY
import com.reverie.paint.model.FrontBufferReadyGate.READY_EVENTS
import com.reverie.paint.model.FrontBufferReadyGate.READY_FALLBACK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferReadyGateTest {
    private fun mask(
        tiles: Boolean = true,
        idle: Boolean = true,
        stable: Int = 2,
        hz: Float = 120f,
        target: Float = 120f,
        loading: Boolean = false,
    ) = FrontBufferReadyGate.conditionMask(tiles, idle, stable, hz, target, loading)

    @Test
    fun `all conditions met gives ALL`() {
        assertEquals(ALL, mask())
    }

    @Test
    fun `each condition clears its own bit`() {
        assertEquals(ALL and COND_TILES.inv(), mask(tiles = false))
        assertEquals(ALL and COND_RENDER_IDLE.inv(), mask(idle = false))
        assertEquals(ALL and COND_GEOM_STABLE.inv(), mask(stable = 1))
        assertEquals(ALL and COND_REFRESH.inv(), mask(hz = 60f))
        assertEquals(ALL and COND_NOT_LOADING.inv(), mask(loading = true))
    }

    @Test
    fun `geometry needs two stable frames`() {
        assertEquals(0, mask(stable = 0) and COND_GEOM_STABLE)
        assertEquals(0, mask(stable = 1) and COND_GEOM_STABLE)
        assertEquals(COND_GEOM_STABLE, mask(stable = 2) and COND_GEOM_STABLE)
        assertEquals(COND_GEOM_STABLE, mask(stable = 9) and COND_GEOM_STABLE)
    }

    @Test
    fun `refresh tolerance and unknown target`() {
        assertTrue(FrontBufferReadyGate.refreshAtTarget(119.88f, 120f))
        assertTrue(FrontBufferReadyGate.refreshAtTarget(115f, 120f))
        assertFalse(FrontBufferReadyGate.refreshAtTarget(90f, 120f))
        assertFalse(FrontBufferReadyGate.refreshAtTarget(Float.NaN, 120f))
        assertTrue(FrontBufferReadyGate.refreshAtTarget(60f, 0f))
        assertTrue(FrontBufferReadyGate.refreshAtTarget(60f, Float.NaN))
    }

    @Test
    fun `tiles final requires exact render size and valid buffer`() {
        assertTrue(FrontBufferReadyGate.tilesFinal(4096, 3072, 4096, 3072, false))
        assertFalse(FrontBufferReadyGate.tilesFinal(2048, 1536, 4096, 3072, false))
        assertFalse(FrontBufferReadyGate.tilesFinal(4096, 3072, 4096, 3072, true))
        assertFalse(FrontBufferReadyGate.tilesFinal(0, 0, 0, 0, false))
    }

    @Test
    fun `decide - fallback after quiet window regardless of mask or flag`() {
        assertEquals(READY_FALLBACK, FrontBufferReadyGate.decide(5000, 4200, 0, true))
        assertEquals(READY_FALLBACK, FrontBufferReadyGate.decide(4200, 4200, 0, false))
    }

    @Test
    fun `decide - early ready only when every condition holds and gate is on`() {
        assertEquals(READY_EVENTS, FrontBufferReadyGate.decide(1000, 4200, ALL, true))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(1000, 4200, ALL, false))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(1000, 4200, ALL and COND_REFRESH.inv(), true))
        assertFalse(FrontBufferReadyGate.isReady(NOT_READY))
        assertTrue(FrontBufferReadyGate.isReady(READY_EVENTS))
        assertTrue(FrontBufferReadyGate.isReady(READY_FALLBACK))
    }

    @Test
    fun `inferred bits are render idle and refresh`() {
        assertEquals(COND_RENDER_IDLE or COND_REFRESH, FrontBufferReadyGate.INFERRED)
    }

    @Test
    fun `stable frame counter counts consecutive identical signatures`() {
        val c = StableFrameCounter()
        assertEquals(0, c.feed(1, 2, 3, 4, 5, 6))
        assertEquals(1, c.feed(1, 2, 3, 4, 5, 6))
        assertEquals(2, c.feed(1, 2, 3, 4, 5, 6))
        assertEquals(0, c.feed(1, 2, 3, 4, 5, 7))
        assertEquals(1, c.feed(1, 2, 3, 4, 5, 7))
        assertEquals(0, c.feed(1, 2, 3, 4, 5, 7, 1))
        c.reset()
        assertEquals(0, c.stableFrames)
        assertEquals(0, c.feed(1, 2, 3, 4, 5, 7, 1))
    }
}
