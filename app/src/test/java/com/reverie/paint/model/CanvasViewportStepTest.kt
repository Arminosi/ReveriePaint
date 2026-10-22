/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasViewportStepTest {
    @Test
    fun `view center stays on the same document point after each step including flipped views`() {
        for (flipX in listOf(-1f, 1f)) for (flipY in listOf(-1f, 1f)) {
            for (angle in listOf(-170f, 0f, 43f, 350f)) for (step in CanvasViewportStep.entries) {
                val zoom = 2f
                val x = 37f * flipX
                val y = -23f * flipY
                val rad = Math.toRadians(angle.toDouble())
                val panX = -zoom * (x * cos(rad) - y * sin(rad)).toFloat()
                val panY = -zoom * (x * sin(rad) + y * cos(rad)).toFloat()
                val next = requireNotNull(step.apply(zoom, angle, panX, panY, false))
                val nextRad = Math.toRadians(next.rotation.toDouble())
                val screenX = next.panX + next.zoom * (x * cos(nextRad) - y * sin(nextRad)).toFloat()
                val screenY = next.panY + next.zoom * (x * sin(nextRad) + y * cos(nextRad)).toFloat()
                assertEquals(0f, screenX, 0.0001f)
                assertEquals(0f, screenY, 0.0001f)
            }
        }
    }

    @Test
    fun `zoom limits use the clamped scale and do not keep moving the viewport`() {
        val upper = requireNotNull(CanvasViewportStep.ZOOM_IN.apply(120f, 37f, 60f, -30f, false))
        assertEquals(128f, upper.zoom, 0f)
        assertEquals(64f, upper.panX, 0.0001f)
        assertEquals(-32f, upper.panY, 0.0001f)
        assertEquals(upper, CanvasViewportStep.ZOOM_IN.apply(upper.zoom, 37f, upper.panX, upper.panY, false))
        val lower = requireNotNull(CanvasViewportStep.ZOOM_OUT.apply(0.021f, 37f, 21f, -42f, false))
        assertEquals(0.02f, lower.zoom, 0f)
        assertEquals(20f, lower.panX, 0.0001f)
        assertEquals(-40f, lower.panY, 0.0001f)
        assertEquals(lower, CanvasViewportStep.ZOOM_OUT.apply(lower.zoom, 37f, lower.panX, lower.panY, false))
    }

    @Test
    fun `rotation steps are fifteen degrees relative to the current angle`() {
        assertEquals(22f, CanvasViewportStep.ROTATE_CW.apply(1f, 7f, 0f, 0f, false)!!.rotation, 0f)
        assertEquals(-8f, CanvasViewportStep.ROTATE_CCW.apply(1f, 7f, 0f, 0f, false)!!.rotation, 0f)
        assertEquals(5f, CanvasViewportStep.ROTATE_CW.apply(1f, 350f, 0f, 0f, false)!!.rotation, 0f)
        assertEquals(-5f, CanvasViewportStep.ROTATE_CCW.apply(1f, -350f, 0f, 0f, false)!!.rotation, 0f)
    }

    @Test
    fun `opposite steps restore zoom rotation and pan`() {
        for ((first, second) in listOf(
            CanvasViewportStep.ZOOM_IN to CanvasViewportStep.ZOOM_OUT,
            CanvasViewportStep.ROTATE_CW to CanvasViewportStep.ROTATE_CCW,
        )) {
            val a = requireNotNull(first.apply(2f, 35f, 123f, -45f, false))
            val b = requireNotNull(second.apply(a.zoom, a.rotation, a.panX, a.panY, false))
            assertEquals(2f, b.zoom, 0.0001f)
            assertEquals(35f, b.rotation, 0.0001f)
            assertEquals(123f, b.panX, 0.0001f)
            assertEquals(-45f, b.panY, 0.0001f)
        }
    }

    @Test
    fun `twenty four rotation steps make one turn without moving the center`() {
        var state = CanvasViewportTransform(1f, 0f, 100f, -50f)
        repeat(24) {
            state = requireNotNull(CanvasViewportStep.ROTATE_CW.apply(
                state.zoom, state.rotation, state.panX, state.panY, false,
            ))
        }
        assertEquals(0f, state.rotation, 0f)
        assertEquals(100f, state.panX, 0.001f)
        assertEquals(-50f, state.panY, 0.001f)
    }

    @Test
    fun `locked and invalid viewports are not changed`() {
        for (step in CanvasViewportStep.entries) {
            assertNull(step.apply(1f, 0f, 10f, 20f, true))
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertNull(step.apply(bad, 0f, 0f, 0f, false))
                assertNull(step.apply(1f, bad, 0f, 0f, false))
                assertNull(step.apply(1f, 0f, bad, 0f, false))
                assertNull(step.apply(1f, 0f, 0f, bad, false))
            }
            assertNull(step.apply(0f, 0f, 0f, 0f, false))
            assertNull(step.apply(-1f, 0f, 0f, 0f, false))
        }
    }

    @Test
    fun `legacy keyboard commands are not reinterpreted as quick actions`() {
        for (command in listOf("zoom_in", "zoom_out", "rotate_cw", "reset_view")) {
            assertNull(CanvasViewportStep.fromCommand(command))
        }
        for (step in CanvasViewportStep.entries) assertEquals(step, CanvasViewportStep.fromCommand(step.command))
    }

    @Test
    fun `custom swipe menu preserves new actions and their order across encoding`() {
        val saved = "quick:rotate_ccw,copy,quick:zoom_in,quick:rotate_cw,quick:zoom_out"
        assertEquals(saved, CanvasEditAction.encode(CanvasEditAction.decode(saved)))
        // Adding optional operations must not rearrange existing installations.
        assertEquals("quick:undo,quick:reset_view", CanvasEditAction.encode(
            CanvasEditAction.decode("quick:undo,quick:reset_view"),
        ))
    }
}
