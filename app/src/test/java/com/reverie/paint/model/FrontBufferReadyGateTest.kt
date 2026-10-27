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
    fun `decide - quiet window gates ready decision`() {
        // 静默窗口未过 (now < quietUntil): 哪怕 tilesFinal 且 not loading 也返回 NOT_READY
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(nowMs = 1000L, quietUntilMs = 4200L, tilesFinal = true, loading = false, gateEnabled = true))
        // 静默窗口已过 (now >= quietUntil): tilesFinal 且 not loading 返回 READY
        assertEquals(READY, FrontBufferReadyGate.decide(nowMs = 4200L, quietUntilMs = 4200L, tilesFinal = true, loading = false, gateEnabled = true))
        assertEquals(READY, FrontBufferReadyGate.decide(nowMs = 5000L, quietUntilMs = 4200L, tilesFinal = true, loading = false, gateEnabled = true))
        // 条件不满足仍然 NOT_READY
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(nowMs = 5000L, quietUntilMs = 4200L, tilesFinal = false, loading = false, gateEnabled = true))
        assertEquals(NOT_READY, FrontBufferReadyGate.decide(nowMs = 5000L, quietUntilMs = 4200L, tilesFinal = true, loading = true, gateEnabled = true))

        // gateEnabled = false (调试开关关) 直接放行
        assertEquals(READY, FrontBufferReadyGate.decide(nowMs = 1000L, quietUntilMs = 4200L, tilesFinal = false, loading = true, gateEnabled = false))

        assertTrue(FrontBufferReadyGate.isReady(READY))
        assertFalse(FrontBufferReadyGate.isReady(NOT_READY))
    }
}
