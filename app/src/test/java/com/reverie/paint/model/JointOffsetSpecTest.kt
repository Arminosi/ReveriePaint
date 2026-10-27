/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JointOffsetSpecTest {

    @Test
    fun `offset key uses the translation suffix the skinning path already understands`() {
        assertEquals("scapula_L_ry", JointOffsetSpec.key("scapula_L", 1))
        assertEquals("scapula_L_rx", JointOffsetSpec.key("scapula_L", 0))
        assertEquals("scapula_L_rz", JointOffsetSpec.key("scapula_L", 2))
    }

    @Test
    fun `withOffset writes and clears a single bone`() {
        var angles = emptyMap<String, Float>()
        angles = JointOffsetSpec.withOffset(angles, "knee_L", 4f)
        assertEquals(4f, JointOffsetSpec.offsetOf(angles, "knee_L"), 1e-4f)
        angles = JointOffsetSpec.withOffset(angles, "knee_L", 0f)
        assertFalse(angles.containsKey("knee_L_ry"))
        assertEquals(0f, JointOffsetSpec.offsetOf(angles, "knee_L"), 1e-4f)
    }

    @Test
    fun `clearOffsets drops every rest offset but keeps other keys untouched`() {
        val offsets = mapOf(
            "knee_L_ry" to 3f,
            "spine_01_rx" to -2f,
        )
        assertTrue(JointOffsetSpec.clearOffsets(offsets).isEmpty())
    }

    @Test
    fun `toWorldOffsets converts slider units to model units per axis`() {
        val world = JointOffsetSpec.toWorldOffsets(mapOf("knee_L_rx" to 5f, "knee_L_ry" to -10f))
        val arr = world.getValue("knee_L")
        assertEquals(0.05f, arr[0], 1e-5f)
        assertEquals(-0.10f, arr[1], 1e-5f)
        assertEquals(0f, arr[2], 1e-5f)
    }

    @Test
    fun `hasOffsets detects any translation key`() {
        assertFalse(JointOffsetSpec.hasOffsets(mapOf("knee_L_ry" to 0f).filterValues { it != 0f }))
        assertTrue(JointOffsetSpec.hasOffsets(mapOf("knee_L_ry" to 0.5f)))
    }

    @Test
    fun `every cat bone belongs to exactly one group`() {
        val all = JointOffsetSpec.CAT_BONES.flatMap { it.bones }
        assertEquals("骨骼不得重复", all.size, all.toSet().size)
        assertTrue("前肢包含新加的肘段", all.contains("forearm_L"))
        assertTrue("腰背段存在", all.contains("lumbar"))
        assertEquals("尾巴 8 段", 8, all.count { it.startsWith("tail_") })
    }

    @Test
    fun `describe renders readable rest-offset lines only`() {
        val text = JointOffsetSpec.describe(mapOf("knee_L_ry" to 4f, "knee_L_p" to 9f))
        assertEquals("knee_L ry=4.0", text.trim())
    }
}
