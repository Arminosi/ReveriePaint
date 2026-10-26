/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffToolBrushGateTest {
    @Test
    fun `isRealInkTool truth table`() {
        // BRUSH / BRUSH -> true
        assertTrue(RealInkHoldPolicy.isRealInkTool(Tool.BRUSH, Tool.BRUSH))
        // BRUSH / ERASER (侧键临时切橡皮) -> false
        assertFalse(RealInkHoldPolicy.isRealInkTool(Tool.BRUSH, Tool.ERASER))
        // ERASER / ERASER -> false
        assertFalse(RealInkHoldPolicy.isRealInkTool(Tool.ERASER, Tool.ERASER))
        // ERASER / BRUSH -> false
        assertFalse(RealInkHoldPolicy.isRealInkTool(Tool.ERASER, Tool.BRUSH))
        // SMUDGE / SMUDGE -> false
        assertFalse(RealInkHoldPolicy.isRealInkTool(Tool.SMUDGE, Tool.SMUDGE))
    }
}
