/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class ThreeFingerSwipeTest {
    private fun tracker(density: Float = 1f) = ThreeFingerSwipe().apply {
        begin(1000L, density)
        for (i in 0..2) setStart(i, i * 40f * density, 100f * density)
    }

    @Test fun `three fingers moving down together trigger once`() {
        val gesture = tracker()
        for (i in 0..2) gesture.update(i, i * 40f + 4f, 156f)
        gesture.evaluate(1200L)
        assertTrue(gesture.swiped)
        assertFalse(gesture.isTap(1200L))
        gesture.evaluate(1800L)
        assertTrue(gesture.swiped)
    }

    @Test fun `tap jitter remains a redo tap`() {
        val gesture = tracker()
        for (i in 0..2) gesture.update(i, i * 40f + 2f, 103f)
        gesture.evaluate(1100L)
        assertTrue(gesture.isTap(1100L))
        assertFalse(gesture.swiped)
    }

    @Test fun `one moving finger cannot open menu or redo`() {
        val gesture = tracker()
        gesture.update(0, 0f, 160f)
        gesture.evaluate(1200L)
        assertFalse(gesture.swiped)
        assertFalse(gesture.isTap(1200L))
    }

    @Test fun `three fingers moving in all four cardinal directions are recognized`() {
        // Down
        val downGesture = tracker()
        for (i in 0..2) downGesture.update(i, i * 40f, 156f)
        downGesture.evaluate(1200L)
        assertTrue(downGesture.swiped)
        assertEquals(SwipeDirection.DOWN, downGesture.swipeDirection)

        // Up
        val upGesture = tracker()
        for (i in 0..2) upGesture.update(i, i * 40f, 44f)
        upGesture.evaluate(1200L)
        assertTrue(upGesture.swiped)
        assertEquals(SwipeDirection.UP, upGesture.swipeDirection)

        // Left
        val leftGesture = tracker()
        for (i in 0..2) leftGesture.update(i, i * 40f - 56f, 100f)
        leftGesture.evaluate(1200L)
        assertTrue(leftGesture.swiped)
        assertEquals(SwipeDirection.LEFT, leftGesture.swipeDirection)

        // Right
        val rightGesture = tracker()
        for (i in 0..2) rightGesture.update(i, i * 40f + 56f, 100f)
        rightGesture.evaluate(1200L)
        assertTrue(rightGesture.swiped)
        assertEquals(SwipeDirection.RIGHT, rightGesture.swipeDirection)
    }

    @Test fun `pinching and unequal finger travel do not open menu`() {
        val gesture = tracker()
        for (i in 0..2) gesture.update(i, i * 40f, 150f + i * 30f)
        gesture.evaluate(1200L)
        assertFalse(gesture.swiped)
        assertFalse(gesture.isTap(1200L))
    }

    @Test fun `gesture thresholds scale with screen density`() {
        val gesture = tracker(3f)
        for (i in 0..2) gesture.update(i, i * 120f, 360f)
        gesture.evaluate(1100L)
        assertFalse(gesture.swiped)
        for (i in 0..2) gesture.update(i, i * 120f, 456f)
        gesture.evaluate(1200L)
        assertTrue(gesture.swiped)
    }

    @Test fun `late slide cannot become menu after a long hold`() {
        val gesture = tracker()
        for (i in 0..2) gesture.update(i, i * 40f, 160f)
        gesture.evaluate(1700L)
        assertTrue(gesture.rejected)
        assertFalse(gesture.swiped)
    }

    @Test fun `fourth pointer or cancellation invalidates a recognized swipe`() {
        val gesture = tracker()
        for (i in 0..2) gesture.update(i, i * 40f, 160f)
        gesture.evaluate(1200L)
        gesture.reject()
        assertFalse(gesture.swiped)
        assertFalse(gesture.isTap(1200L))
    }

    @Test fun `new gesture does not inherit previous movement`() {
        val gesture = tracker()
        gesture.reject()
        gesture.begin(2000L, 1f)
        for (i in 0..2) gesture.setStart(i, i * 40f, 100f)
        assertTrue(gesture.isTap(2100L))
        assertFalse(gesture.swiped)
    }

    @Test fun `valid staggered travel waits for every finger without rejection`() {
        val gesture = tracker()
        for (i in 2 downTo 0) {
            gesture.update(i, i * 40f, 160f)
            gesture.evaluate(1100L + (2 - i) * 50L)
            assertFalse(gesture.rejected)
            assertEquals(i == 0, gesture.swiped)
        }
    }
}
