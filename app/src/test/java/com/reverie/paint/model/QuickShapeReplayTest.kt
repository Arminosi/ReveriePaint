/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class QuickShapeReplayTest {
    private fun lineOf(length: Float): QuickShapeResult = QuickShapeResult(
        type = QuickShapeType.LINE,
        points = listOf(Point2D(0f, 0f), Point2D(length, 0f)),
    )

    @Test
    fun `line longer than one step is denser than its two endpoints`() {
        val original = floatArrayOf(
            0f, 0f, 1f, 0.1f, 0.2f, 0f,
            10f, 0f, 1f, 0.1f, 0.2f, 0f,
        )
        val line = QuickShapeResult(
            type = QuickShapeType.LINE,
            points = listOf(Point2D(0f, 0f), Point2D(400f, 0f)),
        )
        val out = QuickShapeReplay.samples(original, line, perPointPressure = false, recognized = line)
        assertEquals(0, out.size % 6)
        assertTrue("Expected >= 100 samples, got ${out.size / 6}", out.size / 6 >= 100)
        assertEquals(0f, out[0], 0.01f)
        assertEquals(400f, out[out.size - 6], 0.01f)
    }

    @Test
    fun `pressure flag off writes the captured average on every sample`() {
        val original = floatArrayOf(
            0f, 0f, 0.2f, 0.1f, 0.2f, 0f,
            10f, 0f, 0.8f, 0.1f, 0.2f, 0f,
        )
        val line = QuickShapeResult(
            type = QuickShapeType.LINE,
            points = listOf(Point2D(0f, 0f), Point2D(40f, 0f)),
        )
        val out = QuickShapeReplay.samples(original, line, false, line)
        assertTrue(out.isNotEmpty())
        for (i in 0 until out.size / 6) {
            assertEquals(0.5f, out[i * 6 + 2], 0.02f)
            assertEquals(0.1f, out[i * 6 + 3], 0.001f)
        }
    }

    @Test
    fun `non multiple of six is rejected without a partial buffer`() {
        assertEquals(0, QuickShapeReplay.samples(FloatArray(5), lineOf(10f), false, lineOf(10f)).size)
        assertEquals(0, QuickShapeReplay.samples(FloatArray(11), lineOf(10f), false, lineOf(10f)).size)
    }

    @Test
    fun `rectangle corners are preserved in output samples`() {
        val corners = listOf(Point2D(0f, 0f), Point2D(100f, 0f), Point2D(100f, 100f), Point2D(0f, 100f))
        val original = floatArrayOf(
            0f, 0f, 0.5f, 0f, 0f, 0f,
            100f, 0f, 0.5f, 0f, 0f, 0f,
            100f, 100f, 0.5f, 0f, 0f, 0f,
            0f, 100f, 0.5f, 0f, 0f, 0f,
            0f, 0f, 0.5f, 0f, 0f, 0f,
        )
        val rect = QuickShapeResult(
            type = QuickShapeType.RECTANGLE,
            points = corners,
            center = Point2D(50f, 50f),
            radiusX = 50f,
            radiusY = 50f,
            rotationRad = 0f,
        )
        val out = QuickShapeReplay.samples(original, rect, false, rect)
        assertEquals(0, out.size % 6)
        for (c in corners) {
            var found = false
            for (i in 0 until out.size / 6) {
                if (abs(out[i * 6] - c.x) < 0.1f && abs(out[i * 6 + 1] - c.y) < 0.1f) {
                    found = true
                    break
                }
            }
            assertTrue("Corner $c should be preserved in output", found)
        }
    }
}
