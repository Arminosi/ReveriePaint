/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 动态吸附导引线 (一条线段 + 可选灭点圆) 的屏幕足迹几何, 纯逻辑可单测, 零分配。
 *
 * 真墨按住时导引线由前缓冲画 (孔洞外) 与窗口画 (孔洞内 + UI 避让区内) 两半拼成;
 * 导引线移动时窗口只需要重画"足迹 ∩ 孔洞"与"足迹 ∩ 避让区"。导引线端点可能在 50 万像素外,
 * 一律先裁到视口再计算。
 */
object DynamicGuideFootprint {
    /**
     * Liang–Barsky: 把线段裁进 [l,r]x[t,b], 结果写进 [out] (`x1,y1,x2,y2`)。完全在外返回 false。
     */
    fun clipSegment(
        x1: Float, y1: Float, x2: Float, y2: Float,
        l: Float, t: Float, r: Float, b: Float,
        out: FloatArray,
    ): Boolean {
        if (!(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite())) return false
        if (r < l || b < t) return false
        // 端点可能在 50 万像素外: 用 double 算, 免得 float 舍入把裁剪点挪开零点几个像素。
        val ox = x1.toDouble()
        val oy = y1.toDouble()
        val dx = x2.toDouble() - ox
        val dy = y2.toDouble() - oy
        var u0 = 0.0
        var u1 = 1.0
        for (k in 0 until 4) {
            // 四条边: p*u <= q
            val p = when (k) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
            val q = when (k) { 0 -> ox - l; 1 -> r - ox; 2 -> oy - t; else -> b - oy }
            if (p == 0.0) {
                if (q < 0.0) return false
            } else {
                val u = q / p
                if (p < 0.0) {
                    if (u > u1) return false
                    if (u > u0) u0 = u
                } else {
                    if (u < u0) return false
                    if (u < u1) u1 = u
                }
            }
        }
        out[0] = (ox + u0 * dx).toFloat()
        out[1] = (oy + u0 * dy).toFloat()
        out[2] = (ox + u1 * dx).toFloat()
        out[3] = (oy + u1 * dy).toFloat()
        return true
    }

    /**
     * 线段 (半宽 [pad]) 在孔洞网格里可能压到的区域的包围盒, 并进 [acc] (`l,t,r,b`, int)。
     * 沿线段每 [HoleMaskGrid.cell] 像素取一个方框 (半边 pad + cell/2, 覆盖胶囊形足迹),
     * 用 O(1) 保守的 [HoleMaskGrid.anyInRect] 判定。返回是否命中。[scratch] 至少 4 个 float。
     */
    fun accumulateSegmentHoleHits(
        grid: HoleMaskGrid,
        x1: Float, y1: Float, x2: Float, y2: Float,
        pad: Float,
        acc: IntArray,
        scratch: FloatArray,
    ): Boolean {
        if (grid.isEmpty) return false
        if (!clipSegment(x1, y1, x2, y2, -pad, -pad, grid.width + pad, grid.height + pad, scratch)) return false
        val ax = scratch[0]
        val ay = scratch[1]
        val bx = scratch[2]
        val by = scratch[3]
        val len = kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
        val step = grid.cell.toFloat()
        val n = maxOf(1, kotlin.math.ceil(len / step).toInt())
        val half = pad + step * 0.5f + 1f
        var hit = false
        for (i in 0..n) {
            val u = i.toFloat() / n
            val px = ax + (bx - ax) * u
            val py = ay + (by - ay) * u
            val l = kotlin.math.floor(px - half).toInt()
            val t = kotlin.math.floor(py - half).toInt()
            val r = kotlin.math.ceil(px + half).toInt()
            val b = kotlin.math.ceil(py + half).toInt()
            if (grid.anyInRect(l, t, r, b)) {
                union(acc, l, t, r, b)
                hit = true
            }
        }
        return hit
    }

    /** 以 ([cx],[cy]) 为心、半边 [half] 的方框若可能压到孔洞, 并进 [acc]。 */
    fun accumulateBoxHoleHit(grid: HoleMaskGrid, cx: Float, cy: Float, half: Float, acc: IntArray): Boolean {
        if (grid.isEmpty || !cx.isFinite() || !cy.isFinite()) return false
        val l = kotlin.math.floor(cx - half).toInt()
        val t = kotlin.math.floor(cy - half).toInt()
        val r = kotlin.math.ceil(cx + half).toInt()
        val b = kotlin.math.ceil(cy + half).toInt()
        if (!grid.anyInRect(l, t, r, b)) return false
        union(acc, l, t, r, b)
        return true
    }

    /**
     * 线段 (半宽 [pad]) 与矩形 [l,r]x[t,b] 的交的包围盒 (外扩 pad 后再夹回矩形), 并进 [acc]。
     * 返回是否相交。[scratch] 至少 4 个 float。
     */
    fun accumulateSegmentRectHit(
        x1: Float, y1: Float, x2: Float, y2: Float,
        pad: Float,
        l: Float, t: Float, r: Float, b: Float,
        acc: IntArray,
        scratch: FloatArray,
    ): Boolean {
        if (!clipSegment(x1, y1, x2, y2, l - pad, t - pad, r + pad, b + pad, scratch)) return false
        val sl = maxOf(l, minOf(scratch[0], scratch[2]) - pad)
        val st = maxOf(t, minOf(scratch[1], scratch[3]) - pad)
        val sr = minOf(r, maxOf(scratch[0], scratch[2]) + pad)
        val sb = minOf(b, maxOf(scratch[1], scratch[3]) + pad)
        if (sr <= sl || sb <= st) return false
        union(acc, kotlin.math.floor(sl).toInt(), kotlin.math.floor(st).toInt(),
            kotlin.math.ceil(sr).toInt(), kotlin.math.ceil(sb).toInt())
        return true
    }

    fun resetAcc(acc: IntArray) {
        acc[0] = Int.MAX_VALUE
        acc[1] = Int.MAX_VALUE
        acc[2] = Int.MIN_VALUE
        acc[3] = Int.MIN_VALUE
    }

    fun isEmptyAcc(acc: IntArray): Boolean = acc[0] >= acc[2] || acc[1] >= acc[3]

    private fun union(acc: IntArray, l: Int, t: Int, r: Int, b: Int) {
        if (l < acc[0]) acc[0] = l
        if (t < acc[1]) acc[1] = t
        if (r > acc[2]) acc[2] = r
        if (b > acc[3]) acc[3] = b
    }
}
