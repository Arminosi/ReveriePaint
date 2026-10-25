/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TileDeltaLedgerTest {
    @Test
    fun deltaIsUnionOfWritesSinceVersion() {
        val l = TileDeltaLedger()
        l.reset(4)
        val out = IntArray(4)
        assertEquals(TileDeltaLedger.DELTA_NONE, l.deltaSince(1, 0, out))
        l.recordWrite(1, 10, 10, 20, 20)
        val v1 = l.version(1)
        l.recordWrite(1, 100, 5, 110, 15)
        l.recordWrite(2, 0, 0, 512, 512) // 别的瓦片不影响
        assertEquals(TileDeltaLedger.DELTA_RECT, l.deltaSince(1, 0, out))
        assertArrayEquals(intArrayOf(10, 5, 110, 20), out)
        assertEquals(TileDeltaLedger.DELTA_RECT, l.deltaSince(1, v1, out))
        assertArrayEquals(intArrayOf(100, 5, 110, 15), out)
        assertEquals(TileDeltaLedger.DELTA_NONE, l.deltaSince(1, l.version(1), out))
    }

    @Test
    fun historyOverflowAndBadArgsFallBackToFull() {
        val l = TileDeltaLedger()
        l.reset(2)
        val out = IntArray(4)
        repeat(TileDeltaLedger.HISTORY) { l.recordWrite(0, it, it, it + 1, it + 1) }
        assertEquals(TileDeltaLedger.DELTA_RECT, l.deltaSince(0, 0, out))
        l.recordWrite(0, 0, 0, 1, 1)
        assertEquals(TileDeltaLedger.DELTA_FULL, l.deltaSince(0, 0, out))
        assertEquals(TileDeltaLedger.DELTA_RECT, l.deltaSince(0, 1, out))
        assertEquals(TileDeltaLedger.DELTA_FULL, l.deltaSince(0, -1, out))
        assertEquals(TileDeltaLedger.DELTA_FULL, l.deltaSince(0, l.version(0) + 1, out))
        assertEquals(TileDeltaLedger.DELTA_FULL, l.deltaSince(5, 0, out))
    }

    @Test
    fun resetForgetsVersionsAndTags() {
        val l = TileDeltaLedger()
        l.reset(2)
        val key = Any()
        l.recordWrite(0, 0, 0, 4, 4)
        l.tagBitmap(key, 0, l.version(0))
        l.reset(2)
        assertEquals(-1L, l.takeTag(key, 0))
        assertEquals(0L, l.version(0))
    }

    @Test
    fun tagsAreIdentityKeyedAndSingleUse() {
        val l = TileDeltaLedger(tagCapacity = 4)
        l.reset(3)
        val a = Any()
        val b = Any()
        l.tagBitmap(a, 1, 7)
        l.tagBitmap(b, 2, 3)
        assertTrue(l.hasTagFor(a, 1))
        assertFalse(l.hasTagFor(a, 2))
        assertEquals(-1L, l.takeTag(b, 1)) // 标的不是这块: 不能用, 且标签作废
        assertEquals(-1L, l.takeTag(b, 2))
        assertEquals(7L, l.takeTag(a, 1))
        assertEquals(-1L, l.takeTag(a, 1))
        // 表满覆盖最老: 只会丢标签, 不会张冠李戴
        val keys = Array(6) { Any() }
        keys.forEachIndexed { i, k -> l.tagBitmap(k, 0, i.toLong()) }
        assertEquals(-1L, l.takeTag(keys[0], 0))
        assertEquals(5L, l.takeTag(keys[5], 0))
    }

    /** 模拟 COW: 后备位图 + 增量拷贝 必须逐像素等于 当前位图。 */
    @Test
    fun simulatedCowWithDeltaCopyMatchesFullCopy() {
        val n = 16
        val tiles = 3
        val rnd = Random(42)
        val l = TileDeltaLedger(tagCapacity = 8)
        l.reset(tiles)
        val cur = Array(tiles) { IntArray(n * n) }
        val pool = ArrayDeque<IntArray>()
        val out = IntArray(4)
        repeat(4000) { step ->
            val idx = rnd.nextInt(tiles)
            val x0 = rnd.nextInt(n)
            val y0 = rnd.nextInt(n)
            val x1 = (x0 + 1 + rnd.nextInt(n - x0)).coerceAtMost(n)
            val y1 = (y0 + 1 + rnd.nextInt(n - y0)).coerceAtMost(n)
            val cow = rnd.nextBoolean()
            val target: IntArray
            if (cow) {
                val pick = pool.indexOfFirst { l.hasTagFor(it, idx) }.let { if (it >= 0) it else if (pool.isNotEmpty()) 0 else -1 }
                val spare = if (pick >= 0) pool.removeAt(pick) else IntArray(n * n) { -7 }
                val sv = l.takeTag(spare, idx)
                when (if (sv >= 0) l.deltaSince(idx, sv, out) else TileDeltaLedger.DELTA_FULL) {
                    TileDeltaLedger.DELTA_NONE -> {}
                    TileDeltaLedger.DELTA_RECT -> for (y in out[1] until out[3]) for (x in out[0] until out[2]) spare[y * n + x] = cur[idx][y * n + x]
                    else -> cur[idx].copyInto(spare)
                }
                assertArrayEquals("step $step", cur[idx], spare)
                val old = cur[idx]
                l.tagBitmap(old, idx, l.version(idx))
                pool.addLast(old)
                if (pool.size > 6) l.removeTag(pool.removeFirst())
                cur[idx] = spare
                target = spare
            } else {
                target = cur[idx]
            }
            for (y in y0 until y1) for (x in x0 until x1) target[y * n + x] = step
            l.recordWrite(idx, x0, y0, x1, y1)
        }
    }
}
