/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲装饰重放的裁剪几何: 装饰只能画在本包真墨 SRC 贴过的像素上 (那里的窗口层装饰被盖住了),
 * 其余位置前缓冲是透明的, 窗口层装饰照常透出; 若两边都画, 半透明辅助线会叠成双倍浓度。
 *
 * 旋转/镜像时贴图区域是「屏幕矩形 ∩ 位图矩形在屏幕上的四边形」, 这里用 Sutherland–Hodgman
 * 把凸多边形裁进轴对齐矩形。纯 JVM、零分配。
 */
internal object FrontBufferDecorClip {
    /** 裁剪结果最多的顶点数 (凸四边形被矩形四条边各切一次最多 8 个)。 */
    const val MAX_VERTS = 8

    /**
     * 把凸多边形 [src] (`x0,y0,x1,y1,...`, [n] 个顶点) 裁进矩形 `[l,t,r,b]`, 结果写进 [out],
     * 返回顶点数 (< 3 表示为空)。[tmp] 为中间缓冲。[out]/[tmp] 至少 `2 * MAX_VERTS` 个 float。
     */
    fun clipConvexToRect(
        src: FloatArray,
        n: Int,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        out: FloatArray,
        tmp: FloatArray,
    ): Int {
        if (n < 3 || r <= l || b <= t) return 0
        var count = clipEdge(src, n, out, EDGE_LEFT, l)
        count = clipEdge(out, count, tmp, EDGE_TOP, t)
        count = clipEdge(tmp, count, out, EDGE_RIGHT, r)
        count = clipEdge(out, count, tmp, EDGE_BOTTOM, b)
        for (i in 0 until count * 2) out[i] = tmp[i]
        return if (count >= 3) count else 0
    }

    private const val EDGE_LEFT = 0
    private const val EDGE_TOP = 1
    private const val EDGE_RIGHT = 2
    private const val EDGE_BOTTOM = 3

    private fun inside(x: Float, y: Float, edge: Int, v: Float): Boolean = when (edge) {
        EDGE_LEFT -> x >= v
        EDGE_TOP -> y >= v
        EDGE_RIGHT -> x <= v
        else -> y <= v
    }

    private fun clipEdge(src: FloatArray, n: Int, dst: FloatArray, edge: Int, v: Float): Int {
        if (n == 0) return 0
        var count = 0
        var px = src[(n - 1) * 2]
        var py = src[(n - 1) * 2 + 1]
        var pIn = inside(px, py, edge, v)
        for (i in 0 until n) {
            val cx = src[i * 2]
            val cy = src[i * 2 + 1]
            val cIn = inside(cx, cy, edge, v)
            if (cIn != pIn && count < MAX_VERTS) {
                // 交点
                val ix: Float
                val iy: Float
                if (edge == EDGE_LEFT || edge == EDGE_RIGHT) {
                    val k = (v - px) / (cx - px)
                    ix = v
                    iy = py + (cy - py) * k
                } else {
                    val k = (v - py) / (cy - py)
                    ix = px + (cx - px) * k
                    iy = v
                }
                dst[count * 2] = ix
                dst[count * 2 + 1] = iy
                count++
            }
            if (cIn && count < MAX_VERTS) {
                dst[count * 2] = cx
                dst[count * 2 + 1] = cy
                count++
            }
            px = cx
            py = cy
            pIn = cIn
        }
        return count
    }
}
