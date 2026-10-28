/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettledTilePolicyTest {

    @Test
    fun `isTileSettled returns false when tile was never written`() {
        assertFalse(
            SettledTilePolicy.isTileSettled(
                tx = 0, ty = 0,
                tipCol = 5, tipRow = 5,
                lastWriteFrame = 0,
                currentFrame = 10,
                refreshedAtFrame = 0,
            )
        )
    }

    @Test
    fun `isTileSettled returns false when Chebyshev distance is 2 or less`() {
        val tipCol = 5
        val tipRow = 5
        val lastWrite = 1
        val current = 10
        val refreshed = 0

        // Distance 0 (same tile)
        assertFalse(SettledTilePolicy.isTileSettled(5, 5, tipCol, tipRow, lastWrite, current, refreshed))
        // Distance 1
        assertFalse(SettledTilePolicy.isTileSettled(6, 5, tipCol, tipRow, lastWrite, current, refreshed))
        assertFalse(SettledTilePolicy.isTileSettled(4, 4, tipCol, tipRow, lastWrite, current, refreshed))
        // Distance 2
        assertFalse(SettledTilePolicy.isTileSettled(7, 5, tipCol, tipRow, lastWrite, current, refreshed))
        assertFalse(SettledTilePolicy.isTileSettled(5, 7, tipCol, tipRow, lastWrite, current, refreshed))
        assertFalse(SettledTilePolicy.isTileSettled(7, 7, tipCol, tipRow, lastWrite, current, refreshed))
        assertFalse(SettledTilePolicy.isTileSettled(3, 3, tipCol, tipRow, lastWrite, current, refreshed))

        // Distance 3 (settled!)
        assertTrue(SettledTilePolicy.isTileSettled(8, 5, tipCol, tipRow, lastWrite, current, refreshed))
        assertTrue(SettledTilePolicy.isTileSettled(5, 8, tipCol, tipRow, lastWrite, current, refreshed))
        assertTrue(SettledTilePolicy.isTileSettled(2, 2, tipCol, tipRow, lastWrite, current, refreshed))
    }

    @Test
    fun `isTileSettled returns false when fewer than 3 frames have elapsed`() {
        val tipCol = 0
        val tipRow = 0
        val tx = 5
        val ty = 5 // distance = 5 > 2
        val lastWrite = 10

        // 0 frames elapsed
        assertFalse(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, currentFrame = 10, refreshedAtFrame = 0))
        // 1 frame elapsed
        assertFalse(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, currentFrame = 11, refreshedAtFrame = 0))
        // 2 frames elapsed
        assertFalse(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, currentFrame = 12, refreshedAtFrame = 0))

        // 3 frames elapsed (threshold met)
        assertTrue(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, currentFrame = 13, refreshedAtFrame = 0))
    }

    @Test
    fun `isTileSettled returns false when tile was already refreshed after last write`() {
        val tipCol = 0
        val tipRow = 0
        val tx = 5
        val ty = 5
        val lastWrite = 10
        val current = 15

        // Refreshed at exactly lastWrite frame
        assertFalse(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, current, refreshedAtFrame = 10))
        // Refreshed after lastWrite frame
        assertFalse(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, current, refreshedAtFrame = 12))

        // Refreshed prior to last write (e.g. earlier in stroke)
        assertTrue(SettledTilePolicy.isTileSettled(tx, ty, tipCol, tipRow, lastWrite, current, refreshedAtFrame = 8))
    }

    @Test
    fun `settledTiles filters correctly across grid`() {
        // 10x10 grid (cols = 10)
        val cols = 10
        val total = 100
        val lastWrite = IntArray(total)
        val refreshedAt = IntArray(total)

        // Pen tip is at (tx=2, ty=2) -> index 22
        val tipCol = 2
        val tipRow = 2
        val frameNo = 10

        // Tile (2, 2) written at frame 1: distance = 0 -> not settled
        lastWrite[22] = 1

        // Tile (4, 2) written at frame 1: distance = 2 -> not settled
        lastWrite[24] = 1

        // Tile (5, 2) written at frame 1: distance = 3, frameNo - 1 = 9 >= 3 -> settled! (index = 25)
        lastWrite[25] = 1

        // Tile (8, 8) written at frame 9: distance = 6 > 2, but frameNo - 9 = 1 < 3 -> not settled (index = 88)
        lastWrite[88] = 9

        // Tile (0, 8) written at frame 2, but already refreshed at frame 5 -> not settled (index = 80)
        lastWrite[80] = 2
        refreshedAt[80] = 5

        // Tile (9, 0) written at frame 3, never refreshed: distance = 7 > 2, frameNo - 3 = 7 >= 3 -> settled! (index = 9)
        lastWrite[9] = 3

        val settled = SettledTilePolicy.settledTiles(lastWrite, refreshedAt, frameNo, tipCol, tipRow, cols)
        assertEquals(listOf(9, 25), settled)
    }

    @Test
    fun `settledTiles with throttle suppresses refresh when interval not elapsed`() {
        val cols = 10
        val total = 100
        val lastWrite = IntArray(total)
        val refreshedAt = IntArray(total)

        // Tile (8, 8) settled
        lastWrite[88] = 1
        val frameNo = 10
        val tipCol = 0
        val tipRow = 0

        // Throttle: minIntervalMs = 100
        // Elapsed: 50ms (< 100ms) -> returns empty
        val throttled = SettledTilePolicy.settledTiles(
            lastWrite, refreshedAt, frameNo, tipCol, tipRow, cols,
            nowMs = 150L, lastRefreshMs = 100L, minIntervalMs = 100L
        )
        assertTrue(throttled.isEmpty())

        // Elapsed: 100ms (>= 100ms) -> returns settled
        val ready = SettledTilePolicy.settledTiles(
            lastWrite, refreshedAt, frameNo, tipCol, tipRow, cols,
            nowMs = 200L, lastRefreshMs = 100L, minIntervalMs = 100L
        )
        assertEquals(listOf(88), ready)

        // When minIntervalMs is 0 (disabled), returns empty
        val disabled = SettledTilePolicy.settledTiles(
            lastWrite, refreshedAt, frameNo, tipCol, tipRow, cols,
            nowMs = 300L, lastRefreshMs = 100L, minIntervalMs = 0L
        )
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun `findSettledTiles zero-allocation version matches list version`() {
        val cols = 6
        val total = 36
        val lastWrite = IntArray(total)
        val refreshedAt = IntArray(total)

        lastWrite[35] = 2 // (5, 5), distance from (0, 0) is 5
        val out = IntArray(total)
        val count = SettledTilePolicy.findSettledTiles(lastWrite, refreshedAt, 10, 0, 0, cols, out)

        assertEquals(1, count)
        assertEquals(35, out[0])
    }

    @Test
    fun `findLayerAndAncestorIndices finds target layer and its ancestor groups only`() {
        // Hierarchy:
        // Index 0: RootGroup (depth 0, isGroup = true)
        // Index 1: SubGroup (depth 1, isGroup = true)
        // Index 2: PaintLayerInSubGroup (depth 2, isGroup = false)
        // Index 3: SiblingLayerInSubGroup (depth 2, isGroup = false)
        // Index 4: UnrelatedTopLevelLayer (depth 0, isGroup = false)
        val depths = intArrayOf(0, 1, 2, 2, 0)
        val isGroups = booleanArrayOf(true, true, false, false, false)

        // Target: PaintLayerInSubGroup (index 2) -> should return {2, 1, 0}
        val ancestorsOf2 = SettledTilePolicy.findLayerAndAncestorIndices(
            targetIndex = 2,
            layerCount = depths.size,
            depth = { depths[it] },
            isGroup = { isGroups[it] },
        )
        assertEquals(setOf(2, 1, 0), ancestorsOf2)

        // Target: SubGroup itself (index 1) -> should return {1, 0}
        val ancestorsOf1 = SettledTilePolicy.findLayerAndAncestorIndices(
            targetIndex = 1,
            layerCount = depths.size,
            depth = { depths[it] },
            isGroup = { isGroups[it] },
        )
        assertEquals(setOf(1, 0), ancestorsOf1)

        // Target: UnrelatedTopLevelLayer (index 4) -> should return {4}
        val ancestorsOf4 = SettledTilePolicy.findLayerAndAncestorIndices(
            targetIndex = 4,
            layerCount = depths.size,
            depth = { depths[it] },
            isGroup = { isGroups[it] },
        )
        assertEquals(setOf(4), ancestorsOf4)

        // Target out of range -> should return empty
        val invalid = SettledTilePolicy.findLayerAndAncestorIndices(
            targetIndex = -1,
            layerCount = depths.size,
            depth = { depths[it] },
            isGroup = { isGroups[it] },
        )
        assertTrue(invalid.isEmpty())
    }
}
