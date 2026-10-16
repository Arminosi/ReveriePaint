/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PaintViewModelShortcutsTest {

    @Test
    fun `default shortcut mappings align with desktop standards`() {
        val undoDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "undo" }
        assertNotNull("Undo definition should exist", undoDef)
        assertEquals("LeftCtrl + Z", undoDef?.defaultKey)

        val redoDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "redo" }
        assertNotNull("Redo definition should exist", redoDef)
        assertEquals("LeftCtrl + LeftShift + Z", redoDef?.defaultKey)

        val brushDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "tool_brush" }
        assertNotNull("Brush tool definition should exist", brushDef)
        assertEquals("B", brushDef?.defaultKey)

        val eraserDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "tool_eraser" }
        assertNotNull("Eraser tool definition should exist", eraserDef)
        assertEquals("E", eraserDef?.defaultKey)
    }

    @Test
    fun `setting shortcut key to an existing key unbinds the conflicting action`() {
        val map = mutableMapOf<String, String>()
        val targetKey = "E"
        val idToSet = "undo"

        var overriddenDef: ShortcutDefinition? = null
        for (def in ALL_SHORTCUT_DEFINITIONS) {
            if (def.id != idToSet) {
                val current = map[def.id] ?: def.defaultKey
                if (current.equals(targetKey, ignoreCase = true)) {
                    map[def.id] = "无"
                    overriddenDef = def
                }
            }
        }
        map[idToSet] = targetKey

        assertEquals("tool_eraser", overriddenDef?.id)
        assertEquals("无", map["tool_eraser"])
        assertEquals("E", map["undo"])
    }

    @Test
    fun `binding with blank or none resets to unbound`() {
        val map = mutableMapOf("undo" to "LeftCtrl + Z")
        for (blankInput in listOf("", "   ", "无", "none", "None")) {
            val target = blankInput.trim()
            if (target.isBlank() || target == "无" || target.equals("none", ignoreCase = true)) {
                map["undo"] = "无"
            }
            assertEquals("无", map["undo"])
        }
    }

    @Test
    fun `disable touch shortcut and quick action are properly registered`() {
        val touchDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "disable_touch" }
        assertNotNull("Disable touch shortcut definition should exist", touchDef)
        assertEquals("无", touchDef?.defaultKey)
        assertEquals(ShortcutCategory.PAINTING, touchDef?.category)

        val quickAction = com.reverie.paint.model.QuickAction.fromId("disable_touch")
        assertNotNull("QuickAction.DISABLE_TOUCH should exist", quickAction)
        assertEquals(com.reverie.paint.model.QuickAction.DISABLE_TOUCH, quickAction)
        // Ensure DISABLE_TOUCH is not in default actions list (as per user decision)
        org.junit.Assert.assertFalse(
            "DISABLE_TOUCH should not be in DEFAULT_ACTIONS",
            com.reverie.paint.model.QuickAction.DEFAULT_ACTIONS.contains(com.reverie.paint.model.QuickAction.DISABLE_TOUCH),
        )
    }

    @Test
    fun `normalizeShortcut normalizes modifiers and orders correctly`() {
        assertEquals("CTRL+Z", normalizeShortcut("Ctrl + Z"))
        assertEquals("CTRL+Z", normalizeShortcut("LeftCtrl + Z"))
        assertEquals("CTRL+Z", normalizeShortcut("RightCtrl + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("Ctrl + Shift + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("Shift + Ctrl + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("LeftCtrl + LeftShift + Z"))
        assertEquals("CTRL+ALT+SHIFT+Z", normalizeShortcut("Shift + Alt + Ctrl + Z"))
        assertEquals("SPACE(长按)", normalizeShortcut("Space(长按)"))
        assertEquals("F1", normalizeShortcut("F1"))
        assertEquals("BUTTON 1", normalizeShortcut("Button 1"))
        assertEquals("ZOOMIN", normalizeShortcut("ZoomIn"))
    }

    @Test
    fun `isShortcutMatch compares combinations irrespective of modifier naming and order`() {
        org.junit.Assert.assertTrue(isShortcutMatch("LeftCtrl + Z", "Ctrl + Z"))
        org.junit.Assert.assertTrue(isShortcutMatch("Ctrl + Shift + Z", "LeftCtrl + LeftShift + Z"))
        org.junit.Assert.assertTrue(isShortcutMatch("Shift + Ctrl + Z", "Ctrl + Shift + Z"))
        org.junit.Assert.assertTrue(isShortcutMatch("Button 1", "button 1"))
        org.junit.Assert.assertTrue(isShortcutMatch("F1", "f1"))
        org.junit.Assert.assertTrue(isShortcutMatch("ZoomIn", "zoomin"))
        org.junit.Assert.assertFalse(isShortcutMatch("Ctrl + Z", "Ctrl + Y"))
        org.junit.Assert.assertFalse(isShortcutMatch("Ctrl + Z", "无"))
    }
}

