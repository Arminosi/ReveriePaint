/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class QuickShapeEditActionsTest {

    @Test
    fun `rectangle excludes all contour modes and hold reshape`() {
        // Rectangle must NOT show contour mode (straight/curved toggle) NOR hold reshape
        val capsWithBoth = quickShapeEditCapabilities(
            type = QuickShapeType.RECTANGLE,
            boxHandles = true,
            conversions = true,
            contour = true,
        )
        assertFalse("Rectangle must never show contour mode toggle", capsWithBoth.showContourMode)
        assertFalse("Rectangle must never show hold reshape", capsWithBoth.showHoldReshape)
        assertTrue(capsWithBoth.showConversions)
        assertTrue(capsWithBoth.showSnap)
        assertTrue(capsWithBoth.showRotate)
        assertTrue(capsWithBoth.showScale)
        assertTrue(capsWithBoth.showRotateScale)
        assertEquals(QuickShapeEditHint.BOX, capsWithBoth.hint)

        // When boxHandles is disabled
        val capsNoHandles = quickShapeEditCapabilities(
            type = QuickShapeType.RECTANGLE,
            boxHandles = false,
            conversions = true,
            contour = true,
        )
        assertFalse(capsNoHandles.showContourMode)
        assertFalse(capsNoHandles.showHoldReshape)
        assertTrue(capsNoHandles.showConversions)
        assertEquals(QuickShapeEditHint.GENERIC, capsNoHandles.hint)

        // Conversions are always displayed for box shapes (no toggle)
        val capsNoConv = quickShapeEditCapabilities(
            type = QuickShapeType.RECTANGLE,
            boxHandles = true,
            conversions = false,
            contour = true,
        )
        assertTrue(capsNoConv.showConversions)
    }

    @Test
    fun `quadrilateral and contour show contour mode only when boxHandles and contour are enabled`() {
        for (type in listOf(QuickShapeType.QUADRILATERAL, QuickShapeType.CONTOUR)) {
            val enabled = quickShapeEditCapabilities(type, boxHandles = true, conversions = true, contour = true)
            assertTrue("$type should show contour mode when both enabled", enabled.showContourMode)
            assertTrue("$type should show hold reshape when both enabled", enabled.showHoldReshape)
            assertTrue(enabled.showConversions)
            assertTrue(enabled.showSnap)
            assertTrue(enabled.showRotate)
            assertTrue(enabled.showScale)
            assertTrue(enabled.showRotateScale)

            val noContour = quickShapeEditCapabilities(type, boxHandles = true, conversions = true, contour = false)
            assertFalse("$type should not show contour mode when contour switch is off", noContour.showContourMode)
            assertFalse(noContour.showHoldReshape)

            val noHandles = quickShapeEditCapabilities(type, boxHandles = false, conversions = true, contour = true)
            assertFalse("$type should not show contour mode when boxHandles is off", noHandles.showContourMode)
            assertFalse(noHandles.showHoldReshape)
        }
    }

    @Test
    fun `circle excludes rotation but supports scale and ellipse conversion`() {
        val circle = quickShapeEditCapabilities(QuickShapeType.CIRCLE)
        assertEquals(QuickShapeEditHint.CIRCLE, circle.hint)
        assertTrue("Circle must support ellipse conversion", circle.showCircleEllipse)
        assertFalse("Circle must not show rotate", circle.showRotate)
        assertTrue("Circle supports scale", circle.showScale)
        assertFalse(circle.showSnap)

        val ellipse = quickShapeEditCapabilities(QuickShapeType.ELLIPSE)
        assertEquals(QuickShapeEditHint.ELLIPSE, ellipse.hint)
        assertTrue(ellipse.showCircleEllipse)
        assertTrue("Ellipse supports rotate", ellipse.showRotate)
        assertTrue(ellipse.showScale)
        assertFalse(ellipse.showSnap)
    }

    @Test
    fun `hint mapping matches expected control points across all shape types`() {
        // Line
        val line = quickShapeEditCapabilities(QuickShapeType.LINE)
        assertEquals(QuickShapeEditHint.LINE, line.hint)
        assertTrue(line.showSnap)
        assertFalse(line.showRotate)
        assertFalse(line.showScale)
        assertFalse(line.showConversions)
        assertFalse(line.showContourMode)

        // Triangle
        val tri = quickShapeEditCapabilities(QuickShapeType.TRIANGLE)
        assertEquals(QuickShapeEditHint.TRIANGLE, tri.hint)
        assertFalse(tri.showSnap)
        assertFalse(tri.showRotate)
        assertFalse(tri.showScale)

        // Curve & Arc
        val curve = quickShapeEditCapabilities(QuickShapeType.CURVE)
        assertEquals(QuickShapeEditHint.CURVE, curve.hint)
        assertFalse(curve.showSnap)
        assertFalse(curve.showRotate)
        assertFalse(curve.showScale)

        val arc = quickShapeEditCapabilities(QuickShapeType.ARC)
        assertEquals(QuickShapeEditHint.ARC, arc.hint)
        assertFalse(arc.showSnap)
        assertFalse(arc.showRotate)
        assertFalse(arc.showScale)

        // Contour hint is always CONTOUR
        val contourWithHandles = quickShapeEditCapabilities(QuickShapeType.CONTOUR, boxHandles = true)
        val contourWithoutHandles = quickShapeEditCapabilities(QuickShapeType.CONTOUR, boxHandles = false)
        assertEquals(QuickShapeEditHint.CONTOUR, contourWithHandles.hint)
        assertEquals(QuickShapeEditHint.CONTOUR, contourWithoutHandles.hint)

        // Quadrilateral hint switches between QUAD and BOX
        val quadWithHandles = quickShapeEditCapabilities(QuickShapeType.QUADRILATERAL, boxHandles = true)
        val quadWithoutHandles = quickShapeEditCapabilities(QuickShapeType.QUADRILATERAL, boxHandles = false)
        assertEquals(QuickShapeEditHint.BOX, quadWithHandles.hint)
        assertEquals(QuickShapeEditHint.QUAD, quadWithoutHandles.hint)

        // Rectangle hint switches between GENERIC and BOX
        val rectWithHandles = quickShapeEditCapabilities(QuickShapeType.RECTANGLE, boxHandles = true)
        val rectWithoutHandles = quickShapeEditCapabilities(QuickShapeType.RECTANGLE, boxHandles = false)
        assertEquals(QuickShapeEditHint.BOX, rectWithHandles.hint)
        assertEquals(QuickShapeEditHint.GENERIC, rectWithoutHandles.hint)

        // None / generic fallback
        val none = quickShapeEditCapabilities(QuickShapeType.NONE)
        assertEquals(QuickShapeEditHint.GENERIC, none.hint)
    }
}
