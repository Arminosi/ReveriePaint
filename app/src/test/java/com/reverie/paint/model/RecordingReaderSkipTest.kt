/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingReaderSkipTest {
    @Test
    fun `skip advances position and rejects truncated or negative counts`() {
        val r = RecordingReader(byteArrayOf(1, 2, 3, 4, 5))
        r.skip(3)
        assertEquals(3, r.pos)
        assertEquals(2, r.remaining())
        assertThrows(IndexOutOfBoundsException::class.java) { r.skip(3) }
        assertThrows(IndexOutOfBoundsException::class.java) { r.skip(-1) }
    }

    @Test
    fun `skipStr consumes varint length prefix and payload`() {
        val r =
            RecordingReader(
                byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 9),
            )
        r.skipStr()
        assertEquals(4, r.pos)
        assertEquals(1, r.remaining())
        r.skip(1)
        assertEquals(0, r.remaining())
    }

    @Test
    fun `skipStr rejects truncated string`() {
        val r = RecordingReader(byteArrayOf(5, 'a'.code.toByte()))
        assertThrows(IndexOutOfBoundsException::class.java) { r.skipStr() }
    }
}
