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

    @Test
    fun `eraser and smudge can hold real ink`() {
        // 画笔、橡皮与涂抹恢复真墨按住支持 (e474ec11 行为)
        assertTrue(RealInkHoldPolicy.isHoldToolSupported(Tool.BRUSH))
        assertTrue(RealInkHoldPolicy.isHoldToolSupported(Tool.ERASER))
        assertTrue(RealInkHoldPolicy.isHoldToolSupported(Tool.SMUDGE))
        // 非笔画类工具不按住
        assertFalse(RealInkHoldPolicy.isHoldToolSupported(Tool.LASSO))
        assertFalse(RealInkHoldPolicy.isHoldToolSupported(Tool.FILL))
    }
}
