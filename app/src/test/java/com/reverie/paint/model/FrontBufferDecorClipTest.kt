/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FrontBufferDecorClipTest {
    private val out = FloatArray(FrontBufferDecorClip.MAX_VERTS * 2)
    private val tmp = FloatArray(FrontBufferDecorClip.MAX_VERTS * 2)

    private fun area(p: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return abs(a) / 2f
    }

    private fun clip(src: FloatArray, l: Float, t: Float, r: Float, b: Float) =
        FrontBufferDecorClip.clipConvexToRect(src, src.size / 2, l, t, r, b, out, tmp)

    @Test
    fun `quad fully inside is unchanged`() {
        val q = floatArrayOf(1f, 1f, 3f, 1f, 3f, 3f, 1f, 3f)
        val n = clip(q, 0f, 0f, 10f, 10f)
        assertEquals(4, n)
        assertEquals(4f, area(out, n), 1e-4f)
    }

    @Test
    fun `rect fully containing the clip yields the clip`() {
        val q = floatArrayOf(-5f, -5f, 15f, -5f, 15f, 15f, -5f, 15f)
        val n = clip(q, 2f, 3f, 6f, 7f)
        assertEquals(4, n)
        assertEquals(16f, area(out, n), 1e-4f)
    }

    @Test
    fun `disjoint gives empty`() {
        val q = floatArrayOf(20f, 20f, 30f, 20f, 30f, 30f, 20f, 30f)
        assertEquals(0, clip(q, 0f, 0f, 10f, 10f))
        assertEquals(0, clip(floatArrayOf(0f, 0f, 1f, 1f), 0f, 0f, 10f, 10f))
        assertEquals(0, clip(floatArrayOf(1f, 1f, 3f, 1f, 3f, 3f, 1f, 3f), 5f, 5f, 5f, 9f))
    }

    @Test
    fun `45 degree diamond centred in square clips to octagon`() {
        // 菱形顶点距中心 7, 正方形半边 5: 每个角被切掉一个直角三角形 -> 八边形
        val c = 5f
        val q = floatArrayOf(c, c - 7f, c + 7f, c, c, c + 7f, c - 7f, c)
        val n = clip(q, 0f, 0f, 10f, 10f)
        assertEquals(8, n)
        // 正方形 100 减 4 个直角边为 3 的等腰直角三角形 (各 4.5)
        assertEquals(100f - 4 * 4.5f, area(out, n), 1e-3f)
    }

    @Test
    fun `rotated quad area never exceeds either input`() {
        for (deg in 0 until 360 step 15) {
            val rad = Math.toRadians(deg.toDouble())
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val q = FloatArray(8)
            val corners = floatArrayOf(-4f, -3f, 4f, -3f, 4f, 3f, -4f, 3f)
            for (i in 0 until 4) {
                val x = corners[i * 2]
                val y = corners[i * 2 + 1]
                q[i * 2] = 10f + x * cs - y * sn
                q[i * 2 + 1] = 10f + x * sn + y * cs
            }
            val n = clip(q, 8f, 8f, 20f, 20f)
            val a = area(out, n)
            assert(n in 3..FrontBufferDecorClip.MAX_VERTS) { "deg=$deg n=$n" }
            assert(a <= 48f + 1e-3f && a <= 144f + 1e-3f) { "deg=$deg a=$a" }
            assert(a > 0f) { "deg=$deg" }
            for (i in 0 until n) {
                assert(out[i * 2] in 8f - 1e-3f..20f + 1e-3f && out[i * 2 + 1] in 8f - 1e-3f..20f + 1e-3f)
            }
        }
    }
}
