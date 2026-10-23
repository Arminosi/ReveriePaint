/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DynamicGuideFootprintTest {
    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val l2 = dx * dx + dy * dy
        var u = if (l2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / l2
        u = u.coerceIn(0.0, 1.0)
        val qx = ax + u * dx
        val qy = ay + u * dy
        return Math.hypot(px - qx, py - qy)
    }

    private fun inBox(acc: IntArray, x: Int, y: Int) = x >= acc[0] && x < acc[2] && y >= acc[1] && y < acc[3]

    @Test
    fun clipSegmentHandlesHugeEndpointsAndMisses() {
        val out = FloatArray(4)
        assertTrue(DynamicGuideFootprint.clipSegment(-500_000f, 100f, 500_000f, 100f, 0f, 0f, 200f, 300f, out))
        assertEquals(0f, out[0], 1e-3f); assertEquals(100f, out[1], 1e-3f)
        assertEquals(200f, out[2], 1e-3f); assertEquals(100f, out[3], 1e-3f)
        assertFalse(DynamicGuideFootprint.clipSegment(-10f, -10f, -5f, 400f, 0f, 0f, 200f, 300f, out))
        assertFalse(DynamicGuideFootprint.clipSegment(Float.NaN, 0f, 1f, 1f, 0f, 0f, 200f, 300f, out))
        // 对角线
        assertTrue(DynamicGuideFootprint.clipSegment(-100f, -100f, 400f, 400f, 0f, 0f, 200f, 300f, out))
        assertEquals(0f, out[0], 1e-3f); assertEquals(0f, out[1], 1e-3f)
        assertEquals(200f, out[2], 1e-3f); assertEquals(200f, out[3], 1e-3f)
    }

    @Test
    fun holeHitsCoverEveryHolePixelNearTheSegment() {
        val rnd = Random(11)
        repeat(40) {
            val w = 160 + rnd.nextInt(80)
            val h = 120 + rnd.nextInt(80)
            val a = ByteArray(w * h)
            // 几块随机小孔
            repeat(1 + rnd.nextInt(5)) {
                val cx = rnd.nextInt(w)
                val cy = rnd.nextInt(h)
                val r = 1 + rnd.nextInt(6)
                for (y in maxOf(0, cy - r) until minOf(h, cy + r)) for (x in maxOf(0, cx - r) until minOf(w, cx + r)) {
                    a[y * w + x] = 0x40
                }
            }
            val g = HoleMaskGrid.build(a, w, w, h)
            val far = rnd.nextBoolean()
            val ax = if (far) -500_000f else rnd.nextFloat() * w
            val ay = rnd.nextFloat() * h
            val bx = if (far) 500_000f else rnd.nextFloat() * w
            val by = rnd.nextFloat() * h
            val pad = 4f
            val acc = IntArray(4)
            DynamicGuideFootprint.resetAcc(acc)
            val hit = DynamicGuideFootprint.accumulateSegmentHoleHits(g, ax, ay, bx, by, pad, acc, FloatArray(4))
            var anyNear = false
            for (y in 0 until h) for (x in 0 until w) {
                if (!g.isHole(x, y)) continue
                if (distToSeg(x + 0.5f, y + 0.5f, ax, ay, bx, by) <= pad + 1.0) {
                    anyNear = true
                    assertTrue("($x,$y) missed", inBox(acc, x, y))
                }
            }
            if (anyNear) assertTrue(hit)
        }
    }

    @Test
    fun emptyGridNeverHits() {
        val acc = IntArray(4)
        DynamicGuideFootprint.resetAcc(acc)
        assertFalse(DynamicGuideFootprint.accumulateSegmentHoleHits(HoleMaskGrid.EMPTY, 0f, 0f, 100f, 100f, 3f, acc, FloatArray(4)))
        assertTrue(DynamicGuideFootprint.isEmptyAcc(acc))
        assertFalse(DynamicGuideFootprint.accumulateBoxHoleHit(HoleMaskGrid.EMPTY, 5f, 5f, 3f, acc))
    }

    @Test
    fun rectHitCoversFootprintInsideRectAndStaysInsideIt() {
        val rnd = Random(5)
        repeat(200) {
            val l = rnd.nextInt(50).toFloat()
            val t = rnd.nextInt(50).toFloat()
            val r = l + 10 + rnd.nextInt(60)
            val b = t + 10 + rnd.nextInt(60)
            val ax = rnd.nextFloat() * 200 - 50
            val ay = rnd.nextFloat() * 200 - 50
            val bx = rnd.nextFloat() * 200 - 50
            val by = rnd.nextFloat() * 200 - 50
            val pad = 3f
            val acc = IntArray(4)
            DynamicGuideFootprint.resetAcc(acc)
            val hit = DynamicGuideFootprint.accumulateSegmentRectHit(ax, ay, bx, by, pad, l, t, r, b, acc, FloatArray(4))
            if (hit) {
                assertTrue(acc[0] >= kotlin.math.floor(l).toInt() && acc[2] <= kotlin.math.ceil(r).toInt())
                assertTrue(acc[1] >= kotlin.math.floor(t).toInt() && acc[3] <= kotlin.math.ceil(b).toInt())
            }
            for (y in t.toInt() until b.toInt()) for (x in l.toInt() until r.toInt()) {
                if (distToSeg(x + 0.5f, y + 0.5f, ax, ay, bx, by) <= pad - 0.75) {
                    assertTrue("($x,$y) missed", hit && inBox(acc, x, y))
                }
            }
        }
    }
}
