/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * "compose" 模式 (覆盖层画进前缓冲) 的纯几何, 无 Android 依赖, 零分配, 可单测。
 *
 * compose 模式下前缓冲不挖孔: 每包把要更新的屏幕矩形 (新墨脏区 + 光标/导引线新旧足迹) 重新烤成
 * "棋盘格 + 瓦片" 的不透明底, 再在这些矩形内按窗口同一顺序、同一函数重画覆盖层
 * (像素网格 → 动态导引线 → 光标 → 绘图辅助线)。本对象负责:
 *  - 光标/导引线的屏幕足迹拆成若干小矩形 (不并成大包围盒, 斜线的包围盒是整屏);
 *  - 屏幕矩形 ↔ 显示位图矩形 (只支持轴对齐 pose, 精确线性映射);
 *  - 文档在屏幕上"完整覆盖的整像素"内矩形 (静态覆盖层只画在这里: 这些像素前缓冲一定不透明);
 *  - 槽位图的货架打包 (多块矩形紧凑排进一张小位图, 不按屏幕包围盒分配)。
 */
object FrontBufferCompose {
    /** 包内矩形标志: 足迹块 (可越出文档, 越出部分烤成透明; 不要求有瓦片)。 */
    const val FLAG_FOOTPRINT = 1

    /** 足迹块上限 (光标 17 个 + 导引线新旧各 ≤ 24 块 + 灭点 2 个)。 */
    const val MAX_FOOTPRINT_RECTS = 72

    /** 一包的矩形总上限: 墨脏区 [RealInkPresent.MAX_DISCRETE_RECTS] + 足迹。 */
    const val MAX_PACKET_RECTS = RealInkPresent.MAX_DISCRETE_RECTS + MAX_FOOTPRINT_RECTS

    /** 导引线足迹单块沿线长度 (屏幕像素)。斜线每块包围盒 ~ (L/√2 + 2·pad)², 越短越省烘焙面积。 */
    const val GUIDE_CHUNK_PX = 96f

    /** 单条导引线最多拆几块 (超过则自动加长每块)。 */
    const val GUIDE_MAX_CHUNKS = 24

    fun isAxisAligned(p: CanvasPose): Boolean {
        val rot = p.rotation % 360f
        val rotMod = if (rot < 0f) rot + 360f else rot
        if (rotMod > 0.05f && rotMod < 359.95f) return false
        return p.signX > 0f && p.signY > 0f
    }

    /**
     * 轴对齐 pose 下文档四边形在屏幕上的范围, 向内取整 (只含被文档完整覆盖的像素), 夹到视口。
     * 空返回 false。
     */
    fun docInnerRect(p: CanvasPose, viewW: Int, viewH: Int, out: IntArray): Boolean {
        val x0 = p.centerX - p.halfW * p.scale
        val y0 = p.centerY - p.halfH * p.scale
        val x1 = p.centerX + (p.bmpW - p.halfW) * p.scale
        val y1 = p.centerY + (p.bmpH - p.halfH) * p.scale
        val l = ceil(min(x0, x1) - EPS).toInt().coerceAtLeast(0)
        val t = ceil(min(y0, y1) - EPS).toInt().coerceAtLeast(0)
        val r = floor(max(x0, x1) + EPS).toInt().coerceAtMost(viewW)
        val b = floor(max(y0, y1) + EPS).toInt().coerceAtMost(viewH)
        out[0] = l; out[1] = t; out[2] = r; out[3] = b
        return r > l && b > t
    }

    /**
     * 轴对齐 pose: 屏幕矩形 `[l,r)x[t,b)` 映射回显示位图坐标, 向外取整并夹进 `[0,bmpW]x[0,bmpH]`。
     * 结果为空 (矩形完全在文档外) 返回 false, [out] 写 0。
     */
    fun screenToBmpRect(p: CanvasPose, l: Int, t: Int, r: Int, b: Int, out: IntArray): Boolean {
        val s = p.scale
        if (!(s > 0f)) {
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0
            return false
        }
        val bl = floor((l - p.centerX) / s + p.halfW).toInt().coerceIn(0, p.bmpW)
        val bt = floor((t - p.centerY) / s + p.halfH).toInt().coerceIn(0, p.bmpH)
        val br = ceil((r - p.centerX) / s + p.halfW).toInt().coerceIn(0, p.bmpW)
        val bb = ceil((b - p.centerY) / s + p.halfH).toInt().coerceIn(0, p.bmpH)
        if (br <= bl || bb <= bt) {
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0
            return false
        }
        out[0] = bl; out[1] = bt; out[2] = br; out[3] = bb
        return true
    }

    /**
     * 追加一块屏幕矩形 (夹到视口, 空则忽略) 到 [rects] 第 [count] 块之后, 返回新块数。
     * 满了 ([cap]) 就并进最后一块 (只会变大, 永不丢像素)。
     */
    fun appendBox(rects: IntArray, count: Int, cap: Int, l: Int, t: Int, r: Int, b: Int, viewW: Int, viewH: Int): Int {
        val cl = l.coerceAtLeast(0)
        val ct = t.coerceAtLeast(0)
        val cr = r.coerceAtMost(viewW)
        val cb = b.coerceAtMost(viewH)
        if (cr <= cl || cb <= ct) return count
        if (count >= cap) {
            if (count <= 0) return count
            val o = (count - 1) * 4
            if (cl < rects[o]) rects[o] = cl
            if (ct < rects[o + 1]) rects[o + 1] = ct
            if (cr > rects[o + 2]) rects[o + 2] = cr
            if (cb > rects[o + 3]) rects[o + 3] = cb
            return count
        }
        val o = count * 4
        rects[o] = cl; rects[o + 1] = ct; rects[o + 2] = cr; rects[o + 3] = cb
        return count + 1
    }

    /** 光标 (主光标 + 对称镜像) 每个一块方框 (半边 [half], 向外取整)。 */
    fun appendCursorBoxes(
        x: Float, y: Float, half: Float,
        symX: FloatArray, symY: FloatArray, symCount: Int,
        viewW: Int, viewH: Int,
        rects: IntArray, count: Int, cap: Int,
    ): Int {
        var n = count
        if (x.isFinite() && y.isFinite()) {
            n = appendBox(rects, n, cap, floor(x - half).toInt(), floor(y - half).toInt(),
                ceil(x + half).toInt(), ceil(y + half).toInt(), viewW, viewH)
        }
        for (i in 0 until symCount.coerceAtMost(min(symX.size, symY.size))) {
            val sx = symX[i]
            val sy = symY[i]
            if (!sx.isFinite() || !sy.isFinite()) continue
            n = appendBox(rects, n, cap, floor(sx - half).toInt(), floor(sy - half).toInt(),
                ceil(sx + half).toInt(), ceil(sy + half).toInt(), viewW, viewH)
        }
        return n
    }

    /**
     * 导引线 (段 `seg[off..off+3]`, 半宽 [linePad]; [hasVp] 时加灭点方框 `seg[off+4..5]`, 半边 [vpHalf])
     * 的足迹, 沿线拆成长度 ≤ [chunkPx] 的小段, 每段一块包围盒 (外扩 linePad)。先裁进视口 (外扩 pad),
     * 端点可在 50 万像素外。[scratch] 至少 4 个 float。
     */
    fun appendGuideChunks(
        seg: FloatArray, off: Int, hasVp: Boolean,
        linePad: Float, vpHalf: Float,
        viewW: Int, viewH: Int,
        rects: IntArray, count: Int, cap: Int,
        scratch: FloatArray,
        chunkPx: Float = GUIDE_CHUNK_PX,
        maxChunks: Int = GUIDE_MAX_CHUNKS,
    ): Int {
        var n = count
        if (DynamicGuideFootprint.clipSegment(seg[off], seg[off + 1], seg[off + 2], seg[off + 3],
                -linePad, -linePad, viewW + linePad, viewH + linePad, scratch)
        ) {
            val ax = scratch[0]
            val ay = scratch[1]
            val bx = scratch[2]
            val by = scratch[3]
            val len = hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
            val pieces = ceil(len / chunkPx).toInt().coerceIn(1, maxChunks.coerceAtLeast(1))
            for (i in 0 until pieces) {
                val u0 = i.toFloat() / pieces
                val u1 = (i + 1).toFloat() / pieces
                val px0 = ax + (bx - ax) * u0
                val py0 = ay + (by - ay) * u0
                val px1 = if (i == pieces - 1) bx else ax + (bx - ax) * u1
                val py1 = if (i == pieces - 1) by else ay + (by - ay) * u1
                n = appendBox(rects, n, cap,
                    floor(min(px0, px1) - linePad).toInt(), floor(min(py0, py1) - linePad).toInt(),
                    ceil(max(px0, px1) + linePad).toInt(), ceil(max(py0, py1) + linePad).toInt(),
                    viewW, viewH)
            }
        }
        if (hasVp) {
            val vx = seg[off + 4]
            val vy = seg[off + 5]
            if (vx.isFinite() && vy.isFinite()) {
                n = appendBox(rects, n, cap, floor(vx - vpHalf).toInt(), floor(vy - vpHalf).toInt(),
                    ceil(vx + vpHalf).toInt(), ceil(vy + vpHalf).toInt(), viewW, viewH)
            }
        }
        return n
    }

    /** 两条导引线快照是否画出同样的像素 (种类相同且用到的坐标逐个相等)。 */
    fun sameGuide(kindA: Int, segA: FloatArray, kindB: Int, segB: FloatArray, lineKind: Int, vpKind: Int): Boolean {
        if (kindA != kindB) return false
        if (kindA != lineKind && kindA != vpKind) return true
        val used = if (kindA == vpKind) 6 else 4
        for (i in 0 until used) if (segA[i] != segB[i]) return false
        return true
    }

    /**
     * 新旧光标状态按 tip 并成一块足迹 (主 tip + 各对称 tip 各一块), 比分别
     * [appendCursorBoxes] 两次少一半烘焙块; 并集覆盖新旧全部像素, 恒等。
     * [half] 为外接半边 (已含描边/AA 余量)。任一侧未亮则只取亮的一侧。
     */
    fun appendCursorTransitionBoxes(
        x0: Float, y0: Float, on0: Boolean, symX0: FloatArray, symY0: FloatArray, n0: Int,
        x1: Float, y1: Float, on1: Boolean, symX1: FloatArray, symY1: FloatArray, n1: Int,
        half: Float,
        viewW: Int, viewH: Int,
        rects: IntArray, count: Int, cap: Int,
    ): Int {
        var n = count
        n = appendTipUnion(x0, y0, on0, x1, y1, on1, half, viewW, viewH, rects, n, cap)
        val m0 = n0.coerceAtMost(min(symX0.size, symY0.size))
        val m1 = n1.coerceAtMost(min(symX1.size, symY1.size))
        val m = max(m0, m1)
        for (i in 0 until m) {
            val aOn = i < m0 && symX0[i].isFinite() && symY0[i].isFinite() && on0
            val bOn = i < m1 && symX1[i].isFinite() && symY1[i].isFinite() && on1
            val ax = if (aOn) symX0[i] else Float.NaN
            val ay = if (aOn) symY0[i] else Float.NaN
            val bx = if (bOn) symX1[i] else Float.NaN
            val by = if (bOn) symY1[i] else Float.NaN
            n = appendTipUnion(ax, ay, aOn, bx, by, bOn, half, viewW, viewH, rects, n, cap)
        }
        return n
    }

    private fun appendTipUnion(
        x0: Float, y0: Float, on0: Boolean,
        x1: Float, y1: Float, on1: Boolean,
        half: Float, viewW: Int, viewH: Int,
        rects: IntArray, count: Int, cap: Int,
    ): Int {
        if (!on0 && !on1) return count
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        if (on0 && x0.isFinite() && y0.isFinite()) {
            l = min(l, x0 - half); t = min(t, y0 - half)
            r = max(r, x0 + half); b = max(b, y0 + half)
        }
        if (on1 && x1.isFinite() && y1.isFinite()) {
            l = min(l, x1 - half); t = min(t, y1 - half)
            r = max(r, x1 + half); b = max(b, y1 + half)
        }
        if (!(l < r && t < b)) return count
        return appendBox(rects, count, cap, floor(l).toInt(), floor(t).toInt(), ceil(r).toInt(), ceil(b).toInt(),
            viewW, viewH)
    }

    /**
     * 丢掉 [inkCount, total) 里完全落在某一墨脏区 (`rects[0, inkCount)`) 内的块。
     * 那些像素本包已经会重烤, 再烤一次是浪费。返回新的 total (前缀墨块不动)。
     */
    fun dropContainedAfter(rects: IntArray, inkCount: Int, total: Int): Int {
        if (inkCount <= 0 || total <= inkCount) return total
        var w = inkCount
        for (i in inkCount until total) {
            val o = i * 4
            val fl = rects[o]; val ft = rects[o + 1]; val fr = rects[o + 2]; val fb = rects[o + 3]
            var contained = false
            for (j in 0 until inkCount) {
                val jo = j * 4
                if (fl >= rects[jo] && ft >= rects[jo + 1] && fr <= rects[jo + 2] && fb <= rects[jo + 3]) {
                    contained = true
                    break
                }
            }
            if (contained) continue
            if (w != i) {
                val wo = w * 4
                rects[wo] = fl; rects[wo + 1] = ft; rects[wo + 2] = fr; rects[wo + 3] = fb
            }
            w++
        }
        return w
    }

    /**
     * 与任一墨脏区相交 (含边) 的足迹并进该墨块 (取并集), 从足迹段删掉。
     * 少一次独立 bakeRect; 并集只变大, 恒等。不相交的足迹保留。返回新 total。
     */
    fun absorbOverlappingAfter(rects: IntArray, inkCount: Int, total: Int): Int {
        if (inkCount <= 0 || total <= inkCount) return total
        var w = inkCount
        for (i in inkCount until total) {
            val o = i * 4
            val fl = rects[o]; val ft = rects[o + 1]; val fr = rects[o + 2]; val fb = rects[o + 3]
            var absorbed = false
            for (j in 0 until inkCount) {
                val jo = j * 4
                val il = rects[jo]; val it = rects[jo + 1]; val ir = rects[jo + 2]; val ib = rects[jo + 3]
                // 相交或触边
                if (fr > il && fl < ir && fb > it && ft < ib) {
                    if (fl < il) rects[jo] = fl
                    if (ft < it) rects[jo + 1] = ft
                    if (fr > ir) rects[jo + 2] = fr
                    if (fb > ib) rects[jo + 3] = fb
                    absorbed = true
                    break
                }
            }
            if (absorbed) continue
            if (w != i) {
                val wo = w * 4
                rects[wo] = fl; rects[wo + 1] = ft; rects[wo + 2] = fr; rects[wo + 3] = fb
            }
            w++
        }
        return w
    }

    /**
     * 货架打包: 把 [count] 块矩形 (`l,t,r,b`) 依次排进宽 [maxW] 的槽位图, 第 i 块的左上角写进
     * `outXY[2i], outXY[2i+1]`, 槽位图所需尺寸写进 `outWH`。单块比 [maxW] 宽或总高超过 [maxH]
     * 返回 false。
     */
    fun packShelves(rects: IntArray, count: Int, maxW: Int, maxH: Int, outXY: IntArray, outWH: IntArray): Boolean {
        var x = 0
        var y = 0
        var shelfH = 0
        var usedW = 0
        for (i in 0 until count) {
            val o = i * 4
            val w = rects[o + 2] - rects[o]
            val h = rects[o + 3] - rects[o + 1]
            if (w <= 0 || h <= 0) {
                outXY[i * 2] = 0
                outXY[i * 2 + 1] = 0
                continue
            }
            if (w > maxW) return false
            if (x + w > maxW) {
                y += shelfH
                x = 0
                shelfH = 0
            }
            outXY[i * 2] = x
            outXY[i * 2 + 1] = y
            x += w
            if (x > usedW) usedW = x
            if (h > shelfH) shelfH = h
        }
        val totalH = y + shelfH
        if (totalH > maxH) return false
        outWH[0] = usedW.coerceAtLeast(1)
        outWH[1] = totalH.coerceAtLeast(1)
        return true
    }

    private const val EPS = 1e-3f
}
