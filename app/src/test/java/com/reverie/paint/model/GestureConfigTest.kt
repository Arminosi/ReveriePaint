/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

class GestureConfigTest {

    @Test
    fun `default mappings match expected baseline gestures`() {
        val config = GestureConfig()
        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_DOWN, config.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, config.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, config.getTrigger(GestureAction.REDO))
        assertEquals(GestureTrigger.FOUR_FINGER_TAP, config.getTrigger(GestureAction.FULLSCREEN))
        assertEquals(GestureTrigger.SINGLE_FINGER_LONG_PRESS, config.getTrigger(GestureAction.EYEDROPPER))
        assertEquals(GestureTrigger.QUICK_PINCH, config.getTrigger(GestureAction.FIT_CANVAS))
        assertEquals(GestureTrigger.NONE, config.getTrigger(GestureAction.CLEAR_LAYER))
    }

    @Test
    fun `reassigning trigger to another action automatically unbinds previous action`() {
        val config = GestureConfig()
        // Default: REDO uses THREE_FINGER_TAP.
        assertEquals(GestureAction.REDO, config.getActionForTrigger(GestureTrigger.THREE_FINGER_TAP))

        // Reassign THREE_FINGER_TAP to QUICK_MENU
        val (newConfig, conflict) = config.withMapping(GestureAction.QUICK_MENU, GestureTrigger.THREE_FINGER_TAP)

        assertEquals(GestureAction.REDO, conflict)
        assertEquals(GestureTrigger.THREE_FINGER_TAP, newConfig.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.NONE, newConfig.getTrigger(GestureAction.REDO))
        assertEquals(GestureAction.QUICK_MENU, newConfig.getActionForTrigger(GestureTrigger.THREE_FINGER_TAP))
    }

    @Test
    fun `assigning NONE does not conflict or unbind other actions`() {
        val config = GestureConfig()
        val (newConfig, conflict) = config.withMapping(GestureAction.UNDO, GestureTrigger.NONE)
        assertNull(conflict)
        assertEquals(GestureTrigger.NONE, newConfig.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, newConfig.getTrigger(GestureAction.REDO))
    }

    @Test
    fun `serialization and deserialization roundtrip preserves all mappings`() {
        var config = GestureConfig()
        config = config.withMapping(GestureAction.QUICK_MENU, GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE).first
        config = config.withMapping(GestureAction.CLEAR_LAYER, GestureTrigger.THREE_FINGER_SWIPE_LEFT).first

        val serialized = config.serialize()
        val deserialized = GestureConfig.deserialize(serialized)

        assertEquals(GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE, deserialized.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_LEFT, deserialized.getTrigger(GestureAction.CLEAR_LAYER))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, deserialized.getTrigger(GestureAction.UNDO))
    }

    @Test
    fun `empty or corrupt string deserialization falls back safely to defaults`() {
        val fallbackNull = GestureConfig.deserialize(null)
        val fallbackEmpty = GestureConfig.deserialize("")
        val fallbackCorrupt = GestureConfig.deserialize("invalid_data_without_colons;unknown:junk")

        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_DOWN, fallbackNull.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, fallbackEmpty.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, fallbackCorrupt.getTrigger(GestureAction.REDO))
    }

    @Test
    fun `radial quick menu slot angle calculations map correctly to 6 sectors`() {
        fun angleToSlot(deg: Float): Int = when {
            deg in -120f..-60f -> 0 // Top
            deg in -60f..0f -> 1    // Top-Right
            deg in 0f..60f -> 2     // Bottom-Right
            deg in 60f..120f -> 3   // Bottom
            deg in 120f..180f -> 4  // Bottom-Left
            else -> 5               // Top-Left (-180..-120)
        }

        // Exact cardinal & intercardinal tests
        assertEquals(0, angleToSlot(-90f))   // Up
        assertEquals(1, angleToSlot(-30f))   // Up-Right
        assertEquals(2, angleToSlot(30f))    // Down-Right
        assertEquals(3, angleToSlot(90f))    // Down
        assertEquals(4, angleToSlot(150f))   // Down-Left
        assertEquals(5, angleToSlot(-150f))  // Up-Left

        // Sector boundaries
        assertEquals(0, angleToSlot(-120f))
        assertEquals(0, angleToSlot(-60f))
        assertEquals(1, angleToSlot(0f))
        assertEquals(2, angleToSlot(60f))
        assertEquals(3, angleToSlot(120f))
        assertEquals(4, angleToSlot(180f))
        assertEquals(5, angleToSlot(-180f))
    }
}
