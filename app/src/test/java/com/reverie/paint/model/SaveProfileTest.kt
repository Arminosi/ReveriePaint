/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveProfileTest {

    @Test
    fun `fromStats returns null when array is null or empty or too short`() {
        assertNull(SaveProfile.fromStats(null))
        assertNull(SaveProfile.fromStats(longArrayOf()))
        assertNull(SaveProfile.fromStats(longArrayOf(100L, 20L, 30L, 40L, 2L, 1024L, 2048L))) // 7 elements
    }

    @Test
    fun `fromStats returns null when totalMs is zero or negative`() {
        assertNull(SaveProfile.fromStats(longArrayOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)))
        assertNull(SaveProfile.fromStats(longArrayOf(-10L, 10L, 20L, 30L, 1L, 100L, 200L, 0L)))
    }

    @Test
    fun `fromStats parses legacy 8-element array with default zero breakdown`() {
        val legacy = longArrayOf(
            250L,   // total
            50L,    // snapshot
            120L,   // encode
            80L,    // write
            5L,     // pngCount
            10485760L, // pngBytes (10MB)
            5242880L,  // fileBytes (5MB)
            1L,     // async
        )
        val profile = SaveProfile.fromStats(legacy)
        assertNotNull(profile)
        profile!!
        assertEquals(250L, profile.totalMs)
        assertEquals(50L, profile.snapshotMs)
        assertEquals(120L, profile.encodeMs)
        assertEquals(80L, profile.writeMs)
        assertEquals(5L, profile.pngCount)
        assertEquals(10485760L, profile.pngBytes)
        assertEquals(5242880L, profile.fileBytes)
        assertTrue(profile.wasAsync)

        // 扩展指标应优雅默认为 0
        assertEquals(0L, profile.compositeMs)
        assertEquals(0L, profile.layerCloneMs)
        assertEquals(0L, profile.selMaskMs)
        assertEquals(0L, profile.convertMs)
        assertEquals(0L, profile.pngDeflateMs)
        assertEquals(0L, profile.zipWriteMs)
        assertEquals(0L, profile.closeMs)
        assertEquals(0L, profile.reusedEntries)
        assertEquals(0L, profile.reusedBytes)
    }

    @Test
    fun `fromStats parses full 17-element array with detailed breakdown`() {
        val full = longArrayOf(
            300L,   // total
            60L,    // snapshot
            150L,   // encode
            90L,    // write
            10L,    // pngCount
            20000000L, // pngBytes
            15000000L, // fileBytes
            0L,     // async
            25L,    // composite
            30L,    // layerClone
            5L,     // selMask
            45L,    // convert
            105L,   // pngDeflate
            65L,    // zipWrite
            25L,    // close
            3L,     // reusedEntries
            5000000L, // reusedBytes
        )
        val profile = SaveProfile.fromStats(full)
        assertNotNull(profile)
        profile!!
        assertEquals(300L, profile.totalMs)
        assertEquals(60L, profile.snapshotMs)
        assertEquals(150L, profile.encodeMs)
        assertEquals(90L, profile.writeMs)
        assertEquals(10L, profile.pngCount)
        assertEquals(20000000L, profile.pngBytes)
        assertEquals(15000000L, profile.fileBytes)
        assertFalse(profile.wasAsync)

        assertEquals(25L, profile.compositeMs)
        assertEquals(30L, profile.layerCloneMs)
        assertEquals(5L, profile.selMaskMs)
        assertEquals(45L, profile.convertMs)
        assertEquals(105L, profile.pngDeflateMs)
        assertEquals(65L, profile.zipWriteMs)
        assertEquals(25L, profile.closeMs)
        assertEquals(3L, profile.reusedEntries)
        assertEquals(5000000L, profile.reusedBytes)
    }

    @Test
    fun `fromStats handles extra trailing elements gracefully`() {
        val extended = LongArray(25) { it.toLong() + 1L }
        extended[SaveProfile.STAT_TOTAL] = 500L
        extended[SaveProfile.STAT_ASYNC] = 0L

        val profile = SaveProfile.fromStats(extended)
        assertNotNull(profile)
        profile!!
        assertEquals(500L, profile.totalMs)
        assertEquals(extended[SaveProfile.STAT_SNAPSHOT], profile.snapshotMs)
        assertEquals(extended[SaveProfile.STAT_CLOSE], profile.closeMs)
        assertEquals(extended[SaveProfile.STAT_REUSED_BYTES], profile.reusedBytes)
    }

    @Test
    fun `fromStats coerces negative values in breakdown elements to zero`() {
        val corrupted = longArrayOf(
            100L, -5L, -10L, -20L, 0L, 0L, 0L, 0L,
            -1L, -2L, -3L, -4L, -5L, -6L, -7L, -8L, -9L,
        )
        val profile = SaveProfile.fromStats(corrupted)
        assertNotNull(profile)
        profile!!
        assertEquals(100L, profile.totalMs)
        assertEquals(0L, profile.snapshotMs)
        assertEquals(0L, profile.compositeMs)
        assertEquals(0L, profile.convertMs)
        assertEquals(0L, profile.closeMs)
        assertEquals(0L, profile.reusedEntries)
    }
}
