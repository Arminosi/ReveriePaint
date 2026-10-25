/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasDecorGeometryTest {

    @Test
    fun `perspective 3d with assistedDrawing off is still visible and recorded`() {
        val guide = DrawingGuideConfig(
            mode = GuideMode.PERSPECTIVE,
            perspective3d = true,
            assistedDrawing = false,
        )
        assertTrue(CanvasDecorGeometry.guidesVisible(guide))
    }

    @Test
    fun `every non OFF mode is visible regardless of assistedDrawing`() {
        for (mode in GuideMode.values()) {
            for (assisted in listOf(true, false)) {
                val g = DrawingGuideConfig(mode = mode, assistedDrawing = assisted)
                assertEquals("$mode/$assisted", mode != GuideMode.OFF, CanvasDecorGeometry.guidesVisible(g))
            }
        }
    }

    @Test
    fun `selection outline predicate matches overlay`() {
        assertTrue(CanvasDecorGeometry.selectionOutlineVisible(true, false, false, 0))
        assertFalse(CanvasDecorGeometry.selectionOutlineVisible(false, false, false, 0))
        assertFalse(CanvasDecorGeometry.selectionOutlineVisible(true, true, false, 0))
        // 替换模式正在框选: 隐藏旧蚂蚁线
        assertFalse(CanvasDecorGeometry.selectionOutlineVisible(true, false, true, 0))
        // 加/减模式正在框选: 保留旧蚂蚁线
        assertTrue(CanvasDecorGeometry.selectionOutlineVisible(true, false, true, 1))
    }

    @Test
    fun `overlay bitmap size falls back to doc size`() {
        assertEquals(2048, CanvasDecorGeometry.overlayBitmapSize(2048, 4096))
        assertEquals(4096, CanvasDecorGeometry.overlayBitmapSize(0, 4096))
    }

    @Test
    fun `pixel grid alpha ramps from 4x to 8x`() {
        assertEquals(0f, CanvasDecorGeometry.pixelGridAlpha(3.99f), 0f)
        assertEquals(0f, CanvasDecorGeometry.pixelGridAlpha(4f), 0f)
        assertEquals(0.075f, CanvasDecorGeometry.pixelGridAlpha(6f), 1e-6f)
        assertEquals(0.15f, CanvasDecorGeometry.pixelGridAlpha(8f), 1e-6f)
        assertEquals(0.15f, CanvasDecorGeometry.pixelGridAlpha(64f), 1e-6f)
        assertEquals(0f, CanvasDecorGeometry.pixelGridAlpha(Float.NaN), 0f)
        assertFalse(CanvasDecorGeometry.pixelGridDrawn(true, 4.1f))
        assertTrue(CanvasDecorGeometry.pixelGridDrawn(true, 5f))
        assertFalse(CanvasDecorGeometry.pixelGridDrawn(false, 8f))
    }

    @Test
    fun `pixel grid lines are bitmap centred, verticals first`() {
        assertEquals(5, CanvasDecorGeometry.pixelGridLineCount(0, 2, 0, 1))
        assertEquals(0, CanvasDecorGeometry.pixelGridLineCount(3, 2, 5, 4))
        val out = FloatArray(5 * 4)
        val n = CanvasDecorGeometry.fillPixelGridLines(out, 0, 2, 0, 1, 1f, 0.5f)
        assertEquals(20, n)
        assertArrayEquals(
            floatArrayOf(
                -1f, -0.5f, -1f, 0.5f,
                0f, -0.5f, 0f, 0.5f,
                1f, -0.5f, 1f, 0.5f,
                -1f, -0.5f, 1f, -0.5f,
                -1f, 0.5f, 1f, 0.5f,
            ),
            out,
            0f,
        )
    }
}
