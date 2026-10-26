/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.model.FrontBufferReadyGate.ALL
import com.reverie.paint.model.FrontBufferReadyGate.COND_NOT_LOADING
import com.reverie.paint.model.FrontBufferReadyGate.COND_TILES
import com.reverie.paint.model.FrontBufferReadyGate.NOT_READY
import com.reverie.paint.model.FrontBufferReadyGate.READY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferReadyGateTest {
    @Test
    fun `conditionMask checks tiles and loading`() {
        assertEquals(ALL, FrontBufferReadyGate.conditionMask(tilesFinal = true, loading = false))
        assertEquals(COND_TILES, FrontBufferReadyGate.conditionMask(tilesFinal = true, loading = true))
        assertEquals(COND_NOT_LOADING, FrontBufferReadyGate.conditionMask(tilesFinal = false, loading = false))
        assertEquals(0, FrontBufferReadyGate.conditionMask(tilesFinal = false, loading = true))
    }

    @Test
    fun `tiles final requires exact render size and valid buffer`() {
        assertTrue(FrontBufferReadyGate.tilesFinal(4096, 3072, 4096, 3072, false))
        assertFalse(FrontBufferReadyGate.tilesFinal(2048, 1536, 4096, 3072, false))
        assertFalse(FrontBufferReadyGate.tilesFinal(4096, 3072, 4096, 3072, true))
        assertFalse(FrontBufferReadyGate.tilesFinal(0, 0, 0, 0, false))
    }

    @Test
    fun `decide - ready immediately when tilesFinal and not loading with zero quiet delay`() {
        assertEquals(READY, FrontBufferReadyGate.decide(tilesFinal = true, loading = false, gateEnabled = true))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(tilesFinal = false, loading = false, gateEnabled = true))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(tilesFinal = true, loading = true, gateEnabled = true))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(tilesFinal = false, loading = true, gateEnabled = true))

        // gateEnabled = false (调试开关关) 直接放行
        assertEquals(READY, FrontBufferReadyGate.decide(tilesFinal = false, loading = true, gateEnabled = false))

        assertTrue(FrontBufferReadyGate.isReady(READY))
        assertFalse(FrontBufferReadyGate.isReady(NOT_READY))
    }
}
