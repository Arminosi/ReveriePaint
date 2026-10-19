/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderScheduleModeTest {

    @Test
    fun `default mode resolves to performance`() {
        assertEquals(RenderScheduleMode.PERFORMANCE, RenderScheduleMode.DEFAULT)
        assertEquals(RenderScheduleMode.PERFORMANCE, RenderScheduleMode.fromId(0))
        assertEquals(RenderScheduleMode.PERFORMANCE, RenderScheduleMode.fromId(999))
    }

    @Test
    fun `mode properties and intervals match design`() {
        assertEquals(0L, RenderScheduleMode.PERFORMANCE.minRenderIntervalMs)
        assertEquals(11L, RenderScheduleMode.BALANCED.minRenderIntervalMs)
        assertEquals(18L, RenderScheduleMode.POWER_SAVER.minRenderIntervalMs)

        assertEquals(32, RenderScheduleMode.PERFORMANCE.maxStrokeMergeSamples)
        assertEquals(32, RenderScheduleMode.BALANCED.maxStrokeMergeSamples)
        assertEquals(48, RenderScheduleMode.POWER_SAVER.maxStrokeMergeSamples)
    }

    @Test
    fun `fromId correctly restores each mode`() {
        assertEquals(RenderScheduleMode.PERFORMANCE, RenderScheduleMode.fromId(0))
        assertEquals(RenderScheduleMode.BALANCED, RenderScheduleMode.fromId(1))
        assertEquals(RenderScheduleMode.POWER_SAVER, RenderScheduleMode.fromId(2))
    }
}
