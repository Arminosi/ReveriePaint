/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.model.CanvasPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiClipDeltaTest {

    private fun pose(
        cx: Float = 1280f,
        cy: Float = 800f,
        scale: Float = 1f,
        rot: Float = 0f,
        bmpW: Int = 2000,
        bmpH: Int = 1500,
    ) = CanvasPose(
        centerX = cx,
        centerY = cy,
        scale = scale,
        rotation = rot,
        signX = 1f,
        signY = 1f,
        halfW = bmpW / 2f,
        halfH = bmpH / 2f,
        bmpW = bmpW,
        bmpH = bmpH,
    )

    // =========================================================================
    // 1. clipDelta tests
    // =========================================================================

    @Test
    fun `clipDelta handles empty slots and empty maps`() {
        // Both empty -> no delta
        val delta1 = clipDelta(emptyMap(), emptyMap())
        assertTrue(delta1.isEmpty())

        // Added slot from empty
        val slot1Rect = ClipRect(100f, 100f, 200f, 200f)
        val delta2 = clipDelta(emptyMap(), mapOf(1 to slot1Rect))
        assertEquals(1, delta2.size)
        assertEquals(slot1Rect, delta2[0])

        // Removed slot to empty
        val delta3 = clipDelta(mapOf(1 to slot1Rect), emptyMap())
        assertEquals(1, delta3.size)
        assertEquals(slot1Rect, delta3[0])

        // Empty rect in map should not produce deltas
        val emptyRect = ClipRect(100f, 100f, 100f, 100f)
        val delta4 = clipDelta(emptyMap(), mapOf(1 to emptyRect))
        assertTrue(delta4.isEmpty())
    }

    @Test
    fun `clipDelta handles shared-slot union`() {
        // Slot 1 expands from single owner to union of two owners
        val singleOwner = ClipRect(100f, 100f, 200f, 200f)
        val unionOwners = ClipRect(100f, 100f, 300f, 200f) // width expanded to the right by 100px
        val delta = clipDelta(mapOf(1 to singleOwner), mapOf(1 to unionOwners))
        assertFalse(delta.isEmpty())
        // The delta should cover the newly added right region [200, 100, 300, 200]
        val rightStrip = ClipRect(200f, 100f, 300f, 200f)
        assertTrue(delta.any { it.intersects(rightStrip) })
        // The common region [100, 100, 200, 200] is not in delta
        for (d in delta) {
            assertFalse(d.right <= 200f && d.left >= 100f && d.bottom <= 200f && d.top >= 100f)
        }
    }

    @Test
    fun `clipDelta handles only shadow-padding change`() {
        // 本体 bounds 不变，仅软阴影外扩增加（例如左右外扩各加 10px）
        val baseHole = ClipRect(100f, 100f, 300f, 300f)
        val paddedHole = ClipRect(90f, 100f, 310f, 300f) // left -10, right +10
        val delta = clipDelta(mapOf(5 to baseHole), mapOf(5 to paddedHole))
        assertEquals(2, delta.size)
        assertTrue(delta.contains(ClipRect(90f, 100f, 100f, 300f)))
        assertTrue(delta.contains(ClipRect(300f, 100f, 310f, 300f)))
    }

    @Test
    fun `clipDelta handles slot count change`() {
        // Slot 2 remains unchanged, slot 1 removed, slot 3 added
        val r1 = ClipRect(10f, 10f, 50f, 50f)
        val r2 = ClipRect(100f, 100f, 200f, 200f)
        val r3 = ClipRect(300f, 300f, 400f, 400f)

        val oldBySlot = mapOf(1 to r1, 2 to r2)
        val newBySlot = mapOf(2 to r2, 3 to r3)

        val delta = clipDelta(oldBySlot, newBySlot)
        assertEquals(2, delta.size)
        assertTrue(delta.contains(r1)) // removed
        assertTrue(delta.contains(r3)) // added
        // slot 2 is completely unchanged and not present in delta
        assertFalse(delta.contains(r2))
    }

    // =========================================================================
    // 2. poseNeedsRelease tests
    // =========================================================================

    @Test
    fun `poseNeedsRelease returns false when old is null`() {
        assertFalse(poseNeedsRelease(null, pose()))
    }

    @Test
    fun `pose 0_001 centerX displacement does not release`() {
        val base = pose(cx = 1000f)
        val jitter = pose(cx = 1000.001f)
        assertFalse(poseNeedsRelease(base, jitter))
    }

    @Test
    fun `1 px pan displacement releases`() {
        val base = pose(cx = 1000f)
        val moved = pose(cx = 1001f)
        assertTrue(poseNeedsRelease(base, moved))
    }

    @Test
    fun `bmpW change releases`() {
        val base = pose(bmpW = 2000)
        val resized = pose(bmpW = 2001)
        assertTrue(poseNeedsRelease(base, resized))
    }

    @Test
    fun `bmpH change releases`() {
        val base = pose(bmpH = 1500)
        val resized = pose(bmpH = 1501)
        assertTrue(poseNeedsRelease(base, resized))
    }

    @Test
    fun `rotation causing corners to move over 0_5px releases`() {
        val base = pose(rot = 0f)
        val rotated = pose(rot = 0.5f) // At 1000px radius, 0.5 deg is ~8.7px displacement
        assertTrue(poseNeedsRelease(base, rotated))
    }

    // =========================================================================
    // 3. previewSubmitAllowed truth table
    // =========================================================================

    @Test
    fun `previewSubmitAllowed truth table`() {
        // !held && !blockPreviewClear -> true
        assertTrue(previewSubmitAllowed(held = false, blockPreviewClear = false))

        // blockPreviewClear = true -> false
        assertFalse(previewSubmitAllowed(held = false, blockPreviewClear = true))

        // held = true -> false
        assertFalse(previewSubmitAllowed(held = true, blockPreviewClear = false))

        // held = true && blockPreviewClear = true -> false
        assertFalse(previewSubmitAllowed(held = true, blockPreviewClear = true))
    }

    // =========================================================================
    // 4. clipChangeAction tests
    // =========================================================================

    @Test
    fun `clipChangeAction never returns release`() {
        val emptyDelta = emptyList<ClipRect>()
        val ink = ClipRect(100f, 100f, 200f, 200f)
        val nonIntersectDelta = listOf(ClipRect(500f, 500f, 600f, 600f))
        val intersectDelta = listOf(ClipRect(150f, 150f, 250f, 250f))

        // Empty delta -> KEEP_UPDATE
        assertEquals(ClipChangeAction.KEEP_UPDATE, clipChangeAction(emptyDelta, ink))

        // inkBounds == null -> KEEP_UPDATE
        assertEquals(ClipChangeAction.KEEP_UPDATE, clipChangeAction(intersectDelta, null))

        // Delta doesn't intersect ink or head -> KEEP_UPDATE
        assertEquals(ClipChangeAction.KEEP_UPDATE, clipChangeAction(nonIntersectDelta, ink))

        // Delta intersects inkBounds -> REBAKE
        assertEquals(ClipChangeAction.REBAKE, clipChangeAction(intersectDelta, ink))

        // Delta intersects headRect -> REBAKE
        val head = ClipRect(550f, 550f, 560f, 560f)
        assertEquals(ClipChangeAction.REBAKE, clipChangeAction(nonIntersectDelta, ink, head))
    }
}
