/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HoleMaskGridTest {
    private fun alpha(w: Int, h: Int, stride: Int = w, set: (Int, Int) -> Boolean): ByteArray {
        val a = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) if (set(x, y)) a[y * stride + x] = 0x20
        return a
    }

    @Test
    fun emptyInputGivesEmptyMask() {
        val a = alpha(40, 30) { _, _ -> false }
        val g = HoleMaskGrid.build(a, 40, 40, 30)
        assertTrue(g.isEmpty)
        assertEquals(0f, g.coverage, 0f)
        assertFalse(g.anyInRect(0, 0, 40, 30))
        assertFalse(g.anyExact(0, 0, 40, 30))
        assertFalse(g.isHole(3, 3))
        assertFalse(HoleMaskGrid.EMPTY.anyExact(0, 0, 100, 100))
    }

    @Test
    fun singlePixelDilatesBySquareMargin() {
        val a = alpha(50, 50) { x, y -> x == 20 && y == 30 }
        val g = HoleMaskGrid.build(a, 50, 50, 50, margin = 2)
        assertEquals(25L, g.holePixels)
        for (y in 0 until 50) for (x in 0 until 50) {
            val expect = x in 18..22 && y in 28..32
            assertEquals("($x,$y)", expect, g.isHole(x, y))
            assertEquals(if (expect) 0xFF.toByte() else 0.toByte(), a[y * 50 + x])
        }
    }

    @Test
    fun strideIsRespectedAndPaddingUntouchedInLogic() {
        val a = alpha(10, 10, stride = 16) { x, y -> x == 9 && y == 0 }
        // padding 字节里放垃圾, 不应被当成孔
        for (y in 0 until 10) for (x in 10 until 16) a[y * 16 + x] = 0x7F
        val g = HoleMaskGrid.build(a, 16, 10, 10, margin = 1)
        assertTrue(g.isHole(8, 1))
        assertFalse(g.isHole(7, 0))
        assertEquals(4L, g.holePixels) // (8..9) x (0..1)
    }

    @Test
    fun exactMatchesBruteForceAndConservativeNeverMisses() {
        val rnd = Random(7)
        repeat(6) { iter ->
            val w = 97 + iter * 13
            val h = 71 + iter * 7
            // 一些细线 + 散点
            val a = alpha(w, h) { x, y -> (x == w / 3) || (y == h / 2 && x > w / 2) || rnd.nextInt(900) == 0 }
            val g = HoleMaskGrid.build(a, w, w, h, margin = iter % 3, cell = if (iter % 2 == 0) 8 else 5)
            repeat(400) {
                val l = rnd.nextInt(-5, w + 5)
                val t = rnd.nextInt(-5, h + 5)
                val r = l + rnd.nextInt(0, 40)
                val b = t + rnd.nextInt(0, 40)
                var brute = false
                for (y in maxOf(0, t) until minOf(h, b)) for (x in maxOf(0, l) until minOf(w, r)) if (g.isHole(x, y)) brute = true
                assertEquals("rect $l,$t,$r,$b", brute, g.anyExact(l, t, r, b))
                if (brute) assertTrue(g.anyInRect(l, t, r, b))
            }
        }
    }

    @Test
    fun fullCellsShortCircuitAndCoverage() {
        val a = alpha(64, 64) { x, _ -> x < 32 }
        val g = HoleMaskGrid.build(a, 64, 64, 64, margin = 0)
        assertEquals(0.5f, g.coverage, 1e-6f)
        assertTrue(g.anyExact(8, 8, 24, 24))
        assertFalse(g.anyExact(32, 0, 64, 64))
        assertTrue(g.anyExact(31, 10, 33, 11))
    }
}
