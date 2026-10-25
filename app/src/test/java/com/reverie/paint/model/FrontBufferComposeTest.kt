/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrontBufferComposeTest {
    private fun pose(cx: Float, cy: Float, s: Float, w: Int = 1000, h: Int = 800, rot: Float = 0f, sx: Float = 1f) =
        CanvasPose(cx, cy, s, rot, sx, 1f, w / 2f, h / 2f, w, h)

    private fun covers(rects: IntArray, n: Int, x: Int, y: Int): Boolean {
        for (i in 0 until n) {
            val o = i * 4
            if (x >= rects[o] && x < rects[o + 2] && y >= rects[o + 1] && y < rects[o + 3]) return true
        }
        return false
    }

    @Test
    fun axisAligned() {
        assertTrue(FrontBufferCompose.isAxisAligned(pose(0f, 0f, 1f)))
        assertTrue(FrontBufferCompose.isAxisAligned(pose(0f, 0f, 1f, rot = 360f)))
        assertFalse(FrontBufferCompose.isAxisAligned(pose(0f, 0f, 1f, rot = 90f)))
        assertFalse(FrontBufferCompose.isAxisAligned(pose(0f, 0f, 1f, sx = -1f)))
    }

    @Test
    fun docInnerRoundsInwardAndClamps() {
        val out = IntArray(4)
        // 文档 1000x800 缩放 0.5 中心 (300.25, 200.75) -> 屏幕 [50.25, 550.25) x [0.75, 400.75)
        assertTrue(FrontBufferCompose.docInnerRect(pose(300.25f, 200.75f, 0.5f), 2000, 2000, out))
        assertEquals(51, out[0]); assertEquals(1, out[1]); assertEquals(550, out[2]); assertEquals(400, out[3])
        // 夹进视口
        assertTrue(FrontBufferCompose.docInnerRect(pose(0f, 0f, 4f), 300, 200, out))
        assertEquals(0, out[0]); assertEquals(0, out[1]); assertEquals(300, out[2]); assertEquals(200, out[3])
        // 完全在视口外
        assertFalse(FrontBufferCompose.docInnerRect(pose(-5000f, 0f, 1f), 300, 200, out))
    }

    /** 屏幕矩形映射回位图后再正向映射, 必须覆盖原屏幕矩形与文档的交集 (烤的范围不缺像素)。 */
    @Test
    fun screenToBmpCoversScreenRect() {
        val rnd = Random(7)
        val out = IntArray(4)
        repeat(2000) {
            val s = listOf(0.13f, 0.5f, 1f, 1.7f, 4f, 9.3f)[rnd.nextInt(6)]
            val p = pose(rnd.nextFloat() * 1200f - 100f, rnd.nextFloat() * 900f - 50f, s)
            val l = rnd.nextInt(-50, 1200)
            val t = rnd.nextInt(-50, 900)
            val r = l + rnd.nextInt(1, 300)
            val b = t + rnd.nextInt(1, 300)
            val ok = FrontBufferCompose.screenToBmpRect(p, l, t, r, b, out)
            // 屏幕上的文档范围
            val dl = p.centerX - p.halfW * s
            val dt = p.centerY - p.halfH * s
            val dr = dl + p.bmpW * s
            val db = dt + p.bmpH * s
            val il = maxOf(l.toFloat(), dl)
            val it2 = maxOf(t.toFloat(), dt)
            val ir = minOf(r.toFloat(), dr)
            val ib = minOf(b.toFloat(), db)
            if (ir - il > 1e-2f && ib - it2 > 1e-2f) {
                assertTrue(ok)
                val sl = (out[0] - p.halfW) * s + p.centerX
                val st = (out[1] - p.halfH) * s + p.centerY
                val sr = (out[2] - p.halfW) * s + p.centerX
                val sb = (out[3] - p.halfH) * s + p.centerY
                assertTrue(sl <= il + 1e-2f && st <= it2 + 1e-2f && sr >= ir - 1e-2f && sb >= ib - 1e-2f)
                assertTrue(out[0] >= 0 && out[1] >= 0 && out[2] <= p.bmpW && out[3] <= p.bmpH)
            }
        }
    }

    @Test
    fun cursorBoxesAndCapMergesNeverDrops() {
        val rects = IntArray(4 * 4)
        val sx = floatArrayOf(10f, Float.NaN, 300f, 50f, 60f)
        val sy = floatArrayOf(10f, 5f, 300f, 50f, 60f)
        val n = FrontBufferCompose.appendCursorBoxes(100.5f, 100.5f, 9f, sx, sy, 5, 320, 320, rects, 0, 4)
        assertEquals(4, n)
        for ((x, y) in listOf(100 to 100, 92 to 92, 109 to 109, 10 to 10, 1 to 1, 300 to 300, 50 to 50, 60 to 60, 68 to 68)) {
            assertTrue("($x,$y)", covers(rects, n, x, y))
        }
        // 夹进视口
        for (i in 0 until n) {
            val o = i * 4
            assertTrue(rects[o] >= 0 && rects[o + 1] >= 0 && rects[o + 2] <= 320 && rects[o + 3] <= 320)
        }
    }

    /** 导引线足迹: 视口内离线段 ≤ pad 的每个像素中心都被某块覆盖; 块数有上限。 */
    @Test
    fun guideChunksCoverCapsule() {
        val rnd = Random(11)
        val rects = IntArray(4 * 40)
        val scratch = FloatArray(4)
        val w = 400
        val h = 300
        repeat(300) {
            val seg = FloatArray(6)
            seg[0] = rnd.nextFloat() * 4000f - 2000f
            seg[1] = rnd.nextFloat() * 3000f - 1500f
            seg[2] = rnd.nextFloat() * 4000f - 2000f
            seg[3] = rnd.nextFloat() * 3000f - 1500f
            seg[4] = rnd.nextFloat() * 400f
            seg[5] = rnd.nextFloat() * 300f
            val pad = 5.5f
            val vp = 12f
            val n = FrontBufferCompose.appendGuideChunks(seg, 0, true, pad, vp, w, h, rects, 0, 40, scratch)
            assertTrue(n <= FrontBufferCompose.GUIDE_MAX_CHUNKS + 1)
            for (y in 0 until h step 3) for (x in 0 until w step 3) {
                val px = x + 0.5f
                val py = y + 0.5f
                val d = distToSeg(px, py, seg[0], seg[1], seg[2], seg[3])
                if (d <= pad - 0.01) assertTrue("line ($x,$y)", covers(rects, n, x, y))
                if (Math.abs(px - seg[4]) < vp - 0.01f && Math.abs(py - seg[5]) < vp - 0.01f) {
                    assertTrue("vp ($x,$y)", covers(rects, n, x, y))
                }
            }
        }
    }

    private fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val l2 = dx * dx + dy * dy
        var u = if (l2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / l2
        u = u.coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + u * dx), py - (ay + u * dy))
    }

    @Test
    fun sameGuideComparesOnlyUsedCoords() {
        val a = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val b = floatArrayOf(1f, 2f, 3f, 4f, 9f, 9f)
        assertTrue(FrontBufferCompose.sameGuide(1, a, 1, b, 1, 2))
        assertFalse(FrontBufferCompose.sameGuide(2, a, 2, b, 1, 2))
        assertFalse(FrontBufferCompose.sameGuide(1, a, 2, a, 1, 2))
        assertTrue(FrontBufferCompose.sameGuide(0, a, 0, b, 1, 2))
    }

    @Test
    fun packShelvesNoOverlapWithinBounds() {
        val rnd = Random(3)
        repeat(200) {
            val n = rnd.nextInt(1, 40)
            val rects = IntArray(n * 4)
            for (i in 0 until n) {
                val l = rnd.nextInt(0, 2000)
                val t = rnd.nextInt(0, 2000)
                rects[i * 4] = l; rects[i * 4 + 1] = t
                rects[i * 4 + 2] = l + rnd.nextInt(1, 300); rects[i * 4 + 3] = t + rnd.nextInt(1, 300)
            }
            val xy = IntArray(n * 2)
            val wh = IntArray(2)
            assertTrue(FrontBufferCompose.packShelves(rects, n, 1024, 100_000, xy, wh))
            for (i in 0 until n) {
                val wi = rects[i * 4 + 2] - rects[i * 4]
                val hi = rects[i * 4 + 3] - rects[i * 4 + 1]
                assertTrue(xy[i * 2] >= 0 && xy[i * 2] + wi <= wh[0] && xy[i * 2 + 1] + hi <= wh[1])
                for (j in 0 until i) {
                    val wj = rects[j * 4 + 2] - rects[j * 4]
                    val hj = rects[j * 4 + 3] - rects[j * 4 + 1]
                    val sep = xy[i * 2] + wi <= xy[j * 2] || xy[j * 2] + wj <= xy[i * 2] ||
                        xy[i * 2 + 1] + hi <= xy[j * 2 + 1] || xy[j * 2 + 1] + hj <= xy[i * 2 + 1]
                    assertTrue(sep)
                }
            }
        }
        // 单块过宽 / 总高超限
        assertFalse(FrontBufferCompose.packShelves(intArrayOf(0, 0, 2000, 10), 1, 1024, 4096, IntArray(2), IntArray(2)))
        assertFalse(FrontBufferCompose.packShelves(intArrayOf(0, 0, 10, 5000), 1, 1024, 4096, IntArray(2), IntArray(2)))
    }

    /** 新旧光标按 tip 并成一块: 1 对称时 2 块 (不是分别 append 的 4 块), 且盖住新旧位置。 */
    @Test
    fun cursorTransitionUnionsPerTip() {
        val rects = IntArray(4 * 8)
        val sym0x = floatArrayOf(200f)
        val sym0y = floatArrayOf(200f)
        val sym1x = floatArrayOf(210f)
        val sym1y = floatArrayOf(210f)
        val n = FrontBufferCompose.appendCursorTransitionBoxes(
            100f, 100f, true, sym0x, sym0y, 1,
            108f, 104f, true, sym1x, sym1y, 1,
            10f, 400, 400, rects, 0, 8,
        )
        assertEquals(2, n)
        // 主 tip 新旧
        for ((x, y) in listOf(90 to 90, 117 to 113, 100 to 100, 108 to 104)) {
            assertTrue("prim ($x,$y)", covers(rects, n, x, y))
        }
        // 对称 tip 新旧
        for ((x, y) in listOf(190 to 190, 219 to 219, 200 to 200, 210 to 210)) {
            assertTrue("sym ($x,$y)", covers(rects, n, x, y))
        }
    }

    /** 足迹块完全落在某一墨脏区内 → 丢掉 (那块已经会重烤)。 */
    @Test
    fun dropFootprintsContainedInInk() {
        val rects = intArrayOf(
            0, 0, 100, 100, // ink
            200, 200, 250, 250, // ink
            10, 10, 20, 20, // foot inside first
            180, 180, 220, 220, // foot not contained (straddles)
            210, 210, 240, 240, // foot inside second
        )
        val n = FrontBufferCompose.dropContainedAfter(rects, inkCount = 2, total = 5)
        assertEquals(3, n) // 2 ink + straddling foot
        assertEquals(0, rects[0]); assertEquals(100, rects[2])
        assertEquals(200, rects[4]); assertEquals(250, rects[6])
        assertEquals(180, rects[8]); assertEquals(220, rects[10])
    }

    /** 与墨脏区相交的足迹并进墨块, 少一次独立烘焙。 */
    @Test
    fun absorbOverlappingFootprintsIntoInk() {
        val rects = intArrayOf(
            50, 50, 110, 110, // ink
            100, 100, 120, 120, // foot overlaps → absorb
            300, 300, 320, 320, // foot far → keep
        )
        val n = FrontBufferCompose.absorbOverlappingAfter(rects, inkCount = 1, total = 3)
        assertEquals(2, n)
        assertEquals(50, rects[0]); assertEquals(50, rects[1])
        assertEquals(120, rects[2]); assertEquals(120, rects[3])
        assertEquals(300, rects[4]); assertEquals(320, rects[6])
    }
}
