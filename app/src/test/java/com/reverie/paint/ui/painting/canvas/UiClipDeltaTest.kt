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

    // =========================================================================
    // 6. !held packet is dropped (decision function)
    // =========================================================================

    @Test
    fun `decidePresentAction drops packet when not held`() {
        // Normal held stroke -> SUBMIT
        assertEquals(
            PresentAction.SUBMIT,
            decidePresentAction(seq = 1L, strokeSeq = 1L, held = true, allBaked = true),
        )

        // When !held, baked packet must be dropped (slot freed, no ghost ink)
        assertEquals(
            PresentAction.DROP,
            decidePresentAction(seq = 1L, strokeSeq = 1L, held = false, allBaked = true),
        )

        // Stale sequence -> DROP
        assertEquals(
            PresentAction.DROP,
            decidePresentAction(seq = 1L, strokeSeq = 2L, held = true, allBaked = true),
        )

        // Not all baked -> RETRY
        assertEquals(
            PresentAction.RETRY,
            decidePresentAction(seq = 1L, strokeSeq = 1L, held = true, allBaked = false),
        )
    }

    // =========================================================================
    // 7. stale-seq onStrokeCommitted does not release
    // =========================================================================

    @Test
    fun `stale-seq onStrokeCommitted does not commit or release`() {
        // Matching seq -> commit allowed
        assertTrue(shouldCommitStroke(currentSeq = 42L, committedSeq = 42L))

        // Stale seq (previous stroke commit arriving after new stroke armed) -> rejected!
        assertFalse(shouldCommitStroke(currentSeq = 43L, committedSeq = 42L))

        // Unspecified / default seq -> allowed for backwards compatibility
        assertTrue(shouldCommitStroke(currentSeq = 42L, committedSeq = -1L))
        assertTrue(shouldCommitStroke(currentSeq = 42L, committedSeq = 0L))
    }

    // =========================================================================
    // 8. rebake-hole rect computation (packet ∩ clips)
    // =========================================================================

    @Test
    fun `computeRebakeHoleRects finds exact intersections`() {
        val packetRects = listOf(
            ClipRect(10f, 10f, 100f, 100f),
            ClipRect(200f, 200f, 300f, 300f),
        )
        val uiClips = listOf(
            ClipRect(50f, 50f, 150f, 150f), // Intersects packet 1 [50, 50, 100, 100]
            ClipRect(400f, 400f, 500f, 500f), // Disjoint
        )

        val holes = computeRebakeHoleRects(packetRects, uiClips)
        assertEquals(1, holes.size)
        assertEquals(ClipRect(50f, 50f, 100f, 100f), holes[0])

        // Empty cases
        assertTrue(computeRebakeHoleRects(emptyList(), uiClips).isEmpty())
        assertTrue(computeRebakeHoleRects(packetRects, emptyList()).isEmpty())
    }

    @Test
    fun `computeRebakeHoles zero-allocation array implementation matches`() {
        val screenRects = intArrayOf(
            10, 10, 100, 100,
            200, 200, 300, 300,
        )
        val uiClipRects = floatArrayOf(
            50f, 50f, 150f, 150f,
            400f, 400f, 500f, 500f,
        )
        val outHoles = FloatArray(16)

        val count = computeRebakeHoles(screenRects, 2, uiClipRects, 2, outHoles)
        assertEquals(1, count)
        assertEquals(50f, outHoles[0], 0.001f)
        assertEquals(50f, outHoles[1], 0.001f)
        assertEquals(100f, outHoles[2], 0.001f)
        assertEquals(100f, outHoles[3], 0.001f)

        // Non-intersecting test
        val noInter = computeRebakeHoles(screenRects, 1, floatArrayOf(200f, 200f, 300f, 300f), 1, outHoles)
        assertEquals(0, noInter)
    }

    // =========================================================================
    // 9. packed-bounds encode/decode incl negative and clamped values
    // =========================================================================

    @Test
    fun `packInkBounds and unpackInkBounds round trip standard coordinates`() {
        val packed = packInkBounds(100, 150, 800, 600)
        val unpacked = unpackInkBounds(packed)
        assertEquals(ClipRect(100f, 150f, 800f, 600f), unpacked)
    }

    @Test
    fun `packInkBounds and unpackInkBounds handle negative coordinates`() {
        val packed = packInkBounds(-250, -500, 120, 300)
        val unpacked = unpackInkBounds(packed)
        assertEquals(ClipRect(-250f, -500f, 120f, 300f), unpacked)
    }

    @Test
    fun `packInkBounds clamps out-of-range coordinates`() {
        val packed = packInkBounds(-50000, -40000, 50000, 40000)
        val unpacked = unpackInkBounds(packed)
        assertEquals(ClipRect(-32768f, -32768f, 32767f, 32767f), unpacked)
    }

    @Test
    fun `packInkBounds returns 0 and unpacks to null for empty or inverted rects`() {
        assertEquals(0L, packInkBounds(100, 100, 50, 50)) // left >= right
        assertEquals(0L, packInkBounds(100, 100, 100, 100)) // zero area
        assertEquals(null, unpackInkBounds(0L))
    }

    // =========================================================================
    // 10. idle shrink decision tests
    // =========================================================================

    @Test
    fun `shouldShrinkSlot respects grow-only during stroke and shrinks when idle`() {
        // While held (during stroke): grow-only! Never shrink even if large.
        assertFalse(shouldShrinkSlot(held = true, isBusy = false, width = 2560, height = 1600))

        // While busy (in baking/GL blit): never shrink.
        assertFalse(shouldShrinkSlot(held = false, isBusy = true, width = 2560, height = 1600))

        // When idle and within 2x SLOT_MIN_PX (1024): keep preallocated bitmap!
        assertFalse(shouldShrinkSlot(held = false, isBusy = false, width = 1024, height = 1024))
        assertFalse(shouldShrinkSlot(held = false, isBusy = false, width = 512, height = 512))

        // When idle and oversized (> 1024): shrink back!
        assertTrue(shouldShrinkSlot(held = false, isBusy = false, width = 1280, height = 800))
        assertTrue(shouldShrinkSlot(held = false, isBusy = false, width = 2560, height = 1600))
    }

    @Test
    fun `sensiblePreallocateSize clamps between minPx and maxThreshold with 256 alignment`() {
        // Surface 2560x1600 -> capped at 1024x1024
        val (w1, h1) = sensiblePreallocateSize(2560, 1600)
        assertEquals(1024, w1)
        assertEquals(1024, h1)

        // Surface smaller than 512 -> clamped up to 512
        val (w2, h2) = sensiblePreallocateSize(300, 300)
        assertEquals(512, w2)
        assertEquals(512, h2)

        // Intermediate size 800x600 -> aligned to 256 boundary
        val (w3, h3) = sensiblePreallocateSize(800, 600)
        assertEquals(1024, w3)
        assertEquals(768, h3)
    }
}
