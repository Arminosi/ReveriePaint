/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeThrottleTest {
    @Test
    fun heldStrokeNeverThrottled() {
        // P7 / L2: 按住真墨前缓冲期间, 无论省电档位设定多大节流间隔、经过多久, 永远不节流
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 0L, held = true))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 10L, held = true))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 100L, held = true))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 11L, elapsed = 5L, held = true))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 0L, elapsed = 0L, held = true))
    }

    @Test
    fun unheldStrokeFollowsIntervalThrottle() {
        // 未按住 (普通路径 / 已松开): 遵循 minIntervalMs 节流判定
        assertTrue(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 0L, held = false))
        assertTrue(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 17L, held = false))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 18L, held = false))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 18L, elapsed = 25L, held = false))

        // minIntervalMs <= 0 (PERFORMANCE 模式): 永不节流
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = 0L, elapsed = 0L, held = false))
        assertFalse(PaintViewModel.shouldThrottleStroke(minIntervalMs = -1L, elapsed = 0L, held = false))
    }
}
