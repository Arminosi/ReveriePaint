/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * H-min 前缓冲孔洞掩码的纯逻辑部分 (无 Android 依赖, 可单测)。
 *
 * 孔洞 = 所有"画在画布之上"的覆盖层 (辅助线/对称轴/像素网格/蚂蚁线…) 的**覆盖超集**:
 * alpha>0 二值化后再做边长 (2·margin+1) 的方形膨胀, 吸收抗锯齿边缘与 CPU/GPU 光栅差。
 * 前缓冲在孔洞内一律清成 0 (DST_OUT), 由窗口帧负责这些像素, 于是前缓冲像素
 * F ∈ {0, 与关低延迟时相同的不透明画布像素}, 视觉恒等。
 *
 * 表示:
 *  - [bits]: 逐像素位图 (每行 [wordsPerRow] 个 Long), 精确判定用;
 *  - 两张按 [cell] 像素分块的积分图: "块内有孔" / "块内全是孔"。
 *
 * 判定代价:
 *  - [anyInRect]: O(1), 保守 (块粒度, 可能误报不漏报);
 *  - [anyExact]: O(1) 先排除 / 先命中 (整块落在矩形内且全孔), 其余只扫与矩形相交的**边界块**,
 *    代价随孔洞周长而不是矩形面积增长。每包调用, 零分配。
 */
class HoleMaskGrid private constructor(
    val width: Int,
    val height: Int,
    val cell: Int,
    private val bits: LongArray,
    private val wordsPerRow: Int,
    private val cellsW: Int,
    private val cellsH: Int,
    private val anySat: IntArray,
    private val fullSat: IntArray,
    /** 孔洞像素数 (膨胀后)。 */
    val holePixels: Long,
) {
    val isEmpty: Boolean get() = holePixels == 0L

    /** 孔洞占屏比例 [0,1]。 */
    val coverage: Float
        get() = if (width <= 0 || height <= 0) 0f else (holePixels.toDouble() / (width.toLong() * height)).toFloat()

    fun isHole(x: Int, y: Int): Boolean {
        if (holePixels == 0L || x < 0 || y < 0 || x >= width || y >= height) return false
        return (bits[y * wordsPerRow + (x ushr 6)] ushr (x and 63)) and 1L != 0L
    }

    private fun satSum(sat: IntArray, c0: Int, r0: Int, c1: Int, r1: Int): Int {
        // [c0,c1) x [r0,r1) 块范围
        val s = cellsW + 1
        return sat[r1 * s + c1] - sat[r0 * s + c1] - sat[r1 * s + c0] + sat[r0 * s + c0]
    }

    /** 半开矩形 [l,r)x[t,b) 是否可能含孔 (块粒度, 保守)。O(1)。 */
    fun anyInRect(l: Int, t: Int, r: Int, b: Int): Boolean {
        if (holePixels == 0L) return false
        val x0 = maxOf(l, 0)
        val y0 = maxOf(t, 0)
        val x1 = minOf(r, width)
        val y1 = minOf(b, height)
        if (x0 >= x1 || y0 >= y1) return false
        val c0 = x0 / cell
        val r0 = y0 / cell
        val c1 = (x1 - 1) / cell + 1
        val r1 = (y1 - 1) / cell + 1
        return satSum(anySat, c0, r0, c1, r1) > 0
    }

    /** 半开矩形 [l,r)x[t,b) 是否**确实**含孔像素。零分配。 */
    fun anyExact(l: Int, t: Int, r: Int, b: Int): Boolean {
        if (holePixels == 0L) return false
        val x0 = maxOf(l, 0)
        val y0 = maxOf(t, 0)
        val x1 = minOf(r, width)
        val y1 = minOf(b, height)
        if (x0 >= x1 || y0 >= y1) return false
        val c0 = x0 / cell
        val r0 = y0 / cell
        val c1 = (x1 - 1) / cell + 1
        val r1 = (y1 - 1) / cell + 1
        if (satSum(anySat, c0, r0, c1, r1) == 0) return false
        // 完全落在矩形内的块里只要有一块全是孔就命中。
        val ic0 = (x0 + cell - 1) / cell
        val ir0 = (y0 + cell - 1) / cell
        val ic1 = x1 / cell
        val ir1 = y1 / cell
        if (ic0 < ic1 && ir0 < ir1 && satSum(fullSat, ic0, ir0, ic1, ir1) > 0) return true
        // 其余逐块: 无孔块跳过, 全孔块与矩形相交即命中, 只有边界块逐像素扫。
        for (cr in r0 until r1) {
            for (cc in c0 until c1) {
                if (satSum(anySat, cc, cr, cc + 1, cr + 1) == 0) continue
                if (satSum(fullSat, cc, cr, cc + 1, cr + 1) > 0) return true
                val px0 = maxOf(x0, cc * cell)
                val px1 = minOf(x1, cc * cell + cell)
                val py0 = maxOf(y0, cr * cell)
                val py1 = minOf(y1, cr * cell + cell)
                for (y in py0 until py1) {
                    if (rowAny(y, px0, px1)) return true
                }
            }
        }
        return false
    }

    private fun rowAny(y: Int, x0: Int, x1: Int): Boolean {
        val base = y * wordsPerRow
        var x = x0
        while (x < x1) {
            val w = x ushr 6
            val bit = x and 63
            val n = minOf(64 - bit, x1 - x)
            val mask = if (n == 64) -1L else ((1L shl n) - 1L) shl bit
            if (bits[base + w] and mask != 0L) return true
            x += n
        }
        return false
    }

    companion object {
        const val DEFAULT_CELL = 8
        const val DEFAULT_MARGIN = 2

        val EMPTY: HoleMaskGrid = HoleMaskGrid(0, 0, DEFAULT_CELL, LongArray(0), 0, 0, 0, IntArray(1), IntArray(1), 0L)

        /**
         * 从 A8 覆盖图建孔洞掩码。[alpha] 按 [stride] 字节一行, 只读前 [width] 列;
         * 建完后 **原地改写** 成二值膨胀结果 (0 / 0xFF), 供 GPU 侧 DST_OUT 直接使用 ——
         * 绘制用的位图与判定用的位图同源, 不会出现"判定说没孔但画了孔"或反之。
         */
        fun build(alpha: ByteArray, stride: Int, width: Int, height: Int, margin: Int = DEFAULT_MARGIN, cell: Int = DEFAULT_CELL): HoleMaskGrid {
            require(width >= 0 && height >= 0 && stride >= width && cell > 0)
            if (width == 0 || height == 0) return EMPTY
            val m = margin.coerceAtLeast(0)
            // 1) 二值化 + 水平膨胀 → h (逐行)
            val h = ByteArray(width * height)
            var anySrc = false
            for (y in 0 until height) {
                val src = y * stride
                val dst = y * width
                var last = Int.MIN_VALUE / 2 // 最近一个 alpha>0 的 x
                // 正向: 记录左侧 m 内是否有孔
                for (x in 0 until width) {
                    if (alpha[src + x].toInt() != 0) last = x
                    if (x - last <= m) h[dst + x] = 1
                }
                if (last != Int.MIN_VALUE / 2) anySrc = true
                // 反向: 右侧 m 内
                var next = Int.MAX_VALUE / 2
                for (x in width - 1 downTo 0) {
                    if (alpha[src + x].toInt() != 0) next = x
                    if (next - x <= m) h[dst + x] = 1
                }
            }
            if (!anySrc) {
                for (y in 0 until height) java.util.Arrays.fill(alpha, y * stride, y * stride + width, 0)
                return empty(width, height, cell)
            }
            // 2) 垂直膨胀 → bits, 并写回 alpha
            val wpr = (width + 63) ushr 6
            val bits = LongArray(wpr * height)
            var count = 0L
            for (y in 0 until height) {
                val ya = maxOf(0, y - m)
                val yb = minOf(height - 1, y + m)
                val dstA = y * stride
                val base = y * wpr
                for (x in 0 until width) {
                    var on = false
                    var yy = ya
                    while (yy <= yb) {
                        if (h[yy * width + x].toInt() != 0) { on = true; break }
                        yy++
                    }
                    if (on) {
                        bits[base + (x ushr 6)] = bits[base + (x ushr 6)] or (1L shl (x and 63))
                        alpha[dstA + x] = 0xFF.toByte()
                        count++
                    } else {
                        alpha[dstA + x] = 0
                    }
                }
            }
            return fromBits(bits, wpr, width, height, cell, count)
        }

        private fun empty(width: Int, height: Int, cell: Int): HoleMaskGrid {
            val cw = (width + cell - 1) / cell
            val ch = (height + cell - 1) / cell
            return HoleMaskGrid(width, height, cell, LongArray(0), 0, cw, ch, IntArray((cw + 1) * (ch + 1)), IntArray((cw + 1) * (ch + 1)), 0L)
        }

        private fun fromBits(bits: LongArray, wpr: Int, width: Int, height: Int, cell: Int, count: Long): HoleMaskGrid {
            val cw = (width + cell - 1) / cell
            val ch = (height + cell - 1) / cell
            val cellCount = IntArray(cw * ch)
            for (y in 0 until height) {
                val base = y * wpr
                val cr = y / cell
                for (w in 0 until wpr) {
                    var word = bits[base + w]
                    while (word != 0L) {
                        val b = java.lang.Long.numberOfTrailingZeros(word)
                        val x = (w shl 6) + b
                        cellCount[cr * cw + x / cell]++
                        word = word and (word - 1)
                    }
                }
            }
            val s = cw + 1
            val any = IntArray(s * (ch + 1))
            val full = IntArray(s * (ch + 1))
            for (r in 0 until ch) {
                val ph = minOf(cell, height - r * cell)
                var rowAny = 0
                var rowFull = 0
                for (c in 0 until cw) {
                    val pw = minOf(cell, width - c * cell)
                    val n = cellCount[r * cw + c]
                    if (n > 0) rowAny++
                    if (n == pw * ph) rowFull++
                    any[(r + 1) * s + c + 1] = any[r * s + c + 1] + rowAny
                    full[(r + 1) * s + c + 1] = full[r * s + c + 1] + rowFull
                }
            }
            return HoleMaskGrid(width, height, cell, bits, wpr, cw, ch, any, full, count)
        }
    }
}
