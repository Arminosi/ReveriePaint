/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 形状识别几何原语
 *
 * 纯 Kotlin, 无 Android 依赖, 可直接单测
 *
 * 包含折线简化 / 凸包 / 最小面积旋转外接框 / 点到折线距离 / 弧长采样等
 * 形状识别所需的基础几何运算
 */
object ShapeSnapGeometry {

    /**
     * Ramer-Douglas-Peucker 折线简化
     *
     * 迭代栈实现 (而非递归): 笔画点数上限 2048, 深递归在极端笔迹上有栈溢出风险
     */
    fun rdp(points: List<Point2D>, epsilon: Float): List<Point2D> {
        val n = points.size
        if (n <= 2 || epsilon <= 0f) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j <= i + 1) continue
            val a = points[i]
            val b = points[j]
            var maxDist = -1f
            var maxIdx = -1
            for (k in i + 1 until j) {
                val d = perpendicularDistance(points[k], a, b)
                if (d > maxDist) {
                    maxDist = d
                    maxIdx = k
                }
            }
            if (maxIdx >= 0 && maxDist > epsilon) {
                keep[maxIdx] = true
                stack.addLast(intArrayOf(i, maxIdx))
                stack.addLast(intArrayOf(maxIdx, j))
            }
        }
        return points.filterIndexed { idx, _ -> keep[idx] }
    }

    /**
     * 多尺度简化: ε 从 [eps0] 起按 [step] 递增, 直到 ε ≥ [epsMax] 或顶点数 ≤ [maxVertices]
     *
     * 容差从小步长递增, 直到顶点数降到上限或容差达到上限 —— 既保住拐角, 又不让
     * 手抖产生的碎角撑出过多顶点
     */
    fun multiScaleRdp(
        points: List<Point2D>,
        eps0: Float = 1.0f,
        step: Float = 2.0f,
        epsMax: Float = 128.0f,
        maxVertices: Int = 64,
    ): List<Point2D> {
        var eps = eps0
        var simplified = rdp(points, eps)
        while (eps < epsMax && simplified.size > maxVertices) {
            eps += step
            simplified = rdp(points, eps)
        }
        return simplified
    }

    /** 点到线段的垂距 (超出线段时退化为到最近端点距离) */
    fun perpendicularDistance(p: Point2D, a: Point2D, b: Point2D): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val len2 = abx * abx + aby * aby
        if (len2 < 1e-6f) return p.distanceTo(a)
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2
        t = t.coerceIn(0f, 1f)
        val projX = a.x + t * abx
        val projY = a.y + t * aby
        return hypot(p.x - projX, p.y - projY)
    }

    /**
     * Graham 扫描求凸包 (CCW 输出)
     */
    fun grahamScan(points: List<Point2D>): List<Point2D> {
        if (points.size <= 2) return points
        val pts = points.sortedWith(compareBy({ it.y }, { it.x }))
        val pivot = pts[0]
        // 按极角排序, 共线时按距离。
        //
        // **不要**用"叉积符号 + 距离"拼比较器: 与 pivot 共线时, 距离这个次要键会与叉积
        // 给出的角度序互相矛盾, 比较器因此不满足传递性, TimSort 直接抛
        // "Comparison method violates its general contract"。
        // (实测: 遍历 360 个角度的直线笔迹时必崩 —— 这是崩溃, 不是手感问题。)
        //
        // 改为**预计算极角**做主键: atan2 给出的角度本身就是全序, 距离只作为同角的
        // 平局判定, 比较器因此可证明满足传递性。每次识别只调用一次, n ≤ 2048,
        // 多算一次 atan2 的开销可以忽略。
        val rest = pts.drop(1).sortedWith(
            Comparator { a, b ->
                val aa = atan2((a.y - pivot.y).toDouble(), (a.x - pivot.x).toDouble())
                val ab = atan2((b.y - pivot.y).toDouble(), (b.x - pivot.x).toDouble())
                val byAngle = aa.compareTo(ab)
                if (byAngle != 0) byAngle
                else pivot.distanceTo(a).compareTo(pivot.distanceTo(b))
            }
        )
        val hull = ArrayList<Point2D>(pts.size)
        hull.add(pivot)
        for (p in rest) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        return hull
    }

    private fun cross(o: Point2D, a: Point2D, b: Point2D): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * 最小面积旋转外接框 (旋转卡壳遍历凸包边)
     *
     * 归一化使 [RotatedFrame.halfWidth] ≥ [RotatedFrame.halfHeight], 角度指向长轴
     */
    fun tightestFrame(points: List<Point2D>): RotatedFrame {
        val hull = grahamScan(points)
        if (hull.size < 2) return RotatedFrame.invalid()
        if (hull.size == 2) {
            val a = hull[0]
            val b = hull[1]
            val len = a.distanceTo(b)
            if (len <= 1e-6f) return RotatedFrame.invalid()
            val ang = atan2(b.y - a.y, b.x - a.x)
            val c = Point2D((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            return RotatedFrame(c, len / 2f, 0f, ang)
        }

        var bestArea = Float.MAX_VALUE
        var best: RotatedFrame? = null
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val elen = hypot(ex, ey)
            if (elen <= 1e-6f) continue
            val ux = ex / elen
            val uy = ey / elen
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = -p.x * uy + p.y * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val du = maxU - minU
            val dv = maxV - minV
            val area = du * dv
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2f
                val cv = (minV + maxV) / 2f
                val cx = cu * ux - cv * uy
                val cy = cu * uy + cv * ux
                var hw = du / 2f
                var hh = dv / 2f
                var ang = atan2(uy, ux)
                if (hh > hw) {
                    val t = hw
                    hw = hh
                    hh = t
                    ang += (Math.PI / 2.0).toFloat()
                }
                best = RotatedFrame(Point2D(cx, cy), hw, hh, normalizeAngle(ang))
            }
        }
        return best ?: RotatedFrame.invalid()
    }

    /** 折线总长; [closed] 为真时计入末点到首点的闭合段 */
    fun polylineLength(poly: List<Point2D>, closed: Boolean): Float {
        if (poly.size < 2) return 0f
        var total = 0f
        for (i in 1 until poly.size) total += poly[i - 1].distanceTo(poly[i])
        if (closed) total += poly[poly.size - 1].distanceTo(poly[0])
        return total
    }

    /**
     * 按弧长均匀采样 [count] 个点 (含首尾)
     *
     * 采样语义与打分环节一致: 取 33 个点, t = i/32 × 路径长
     */
    fun samplePolyline(poly: List<Point2D>, count: Int, closed: Boolean): List<Point2D> {
        if (poly.isEmpty()) return emptyList()
        if (poly.size == 1 || count <= 1) return listOf(poly[0])
        val total = polylineLength(poly, closed)
        if (total <= 1e-6f) return List(count) { poly[0] }

        val out = ArrayList<Point2D>(count)
        val segCount = if (closed) poly.size else poly.size - 1
        var segIdx = 0
        var segStart = 0f
        var segLen = poly[0].distanceTo(poly[1 % poly.size])
        for (i in 0 until count) {
            val target = total * i / (count - 1).toFloat()
            while (segIdx < segCount - 1 && segStart + segLen < target) {
                segStart += segLen
                segIdx++
                segLen = poly[segIdx].distanceTo(poly[(segIdx + 1) % poly.size])
            }
            val a = poly[segIdx]
            val b = poly[(segIdx + 1) % poly.size]
            val t = if (segLen <= 1e-6f) 0f else ((target - segStart) / segLen).coerceIn(0f, 1f)
            out.add(Point2D(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        return out
    }

    /** 点到折线的最近距离; [closed] 为真时计入闭合段; 对应 `PathMeasure::closestPoint` */
    fun pointToPolylineDistance(p: Point2D, poly: List<Point2D>, closed: Boolean): Float {
        if (poly.isEmpty()) return Float.MAX_VALUE
        if (poly.size == 1) return p.distanceTo(poly[0])
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val d = perpendicularDistance(p, poly[i], poly[i + 1])
            if (d < best) best = d
        }
        if (closed) {
            val d = perpendicularDistance(p, poly[poly.size - 1], poly[0])
            if (d < best) best = d
        }
        return best
    }

    /** 质心 (采样点均值); 对应 `BoundsFitter::getCentroid` */
    fun centroid(points: List<Point2D>): Point2D {
        if (points.isEmpty()) return Point2D(0f, 0f)
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        return Point2D(sx / points.size, sy / points.size)
    }

    /** 归一化角度到 (-π, π] */
    fun normalizeAngle(rad: Float): Float {
        var a = rad
        val twoPi = (2.0 * Math.PI).toFloat()
        while (a <= -Math.PI) a += twoPi
        while (a > Math.PI) a -= twoPi
        return a
    }

    /** 三角形的有向面积 (叉积的一半) */
    fun triangleArea(a: Point2D, b: Point2D, c: Point2D): Float =
        abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2f

    /** 点是否有限 (拒绝 NaN / Inf) */
    fun isFinitePoint(p: Point2D): Boolean = p.x.isFinite() && p.y.isFinite()

    /** 判断三点是否共线 (相对面积阈值) */
    fun isCollinear(a: Point2D, b: Point2D, c: Point2D, eps: Float = 1e-5f): Boolean {
        val ab = a.distanceTo(b)
        val bc = b.distanceTo(c)
        val ca = c.distanceTo(a)
        val scale = max(ab, max(bc, ca))
        if (scale <= 1e-6f) return true
        return triangleArea(a, b, c) / (scale * scale) < eps
    }
}

/**
 * 最小面积旋转外接框
 *
 * [halfWidth] ≥ [halfHeight], [angleRad] 指向长轴。
 * 提供到单位方框的仿射映射: 归一化比较的基准
 */
data class RotatedFrame(
    val center: Point2D,
    val halfWidth: Float,
    val halfHeight: Float,
    val angleRad: Float,
) {
    val isValid: Boolean
        get() = center.x.isFinite() && center.y.isFinite() &&
            halfWidth.isFinite() && halfHeight.isFinite() &&
            angleRad.isFinite() && halfWidth > 1e-4f && halfHeight > 1e-4f

    /** 四角 (CCW, 从局部 (-hw,-hh) 起) */
    fun corners(): List<Point2D> {
        val cosR = cos(angleRad)
        val sinR = sin(angleRad)
        return listOf(
            Point2D(-halfWidth, -halfHeight),
            Point2D(halfWidth, -halfHeight),
            Point2D(halfWidth, halfHeight),
            Point2D(-halfWidth, halfHeight),
        ).map { c -> Point2D(center.x + c.x * cosR - c.y * sinR, center.y + c.x * sinR + c.y * cosR) }
    }

    /** 外接框 → 单位方框 [0,1]² */
    fun toUnitBox(p: Point2D): Point2D {
        val dx = p.x - center.x
        val dy = p.y - center.y
        val cosR = cos(angleRad)
        val sinR = sin(angleRad)
        val lx = dx * cosR + dy * sinR
        val ly = -dx * sinR + dy * cosR
        return Point2D(
            (lx + halfWidth) / (2f * halfWidth),
            (ly + halfHeight) / (2f * halfHeight),
        )
    }

    /** 单位方框 → 外接框 (逆映射) */
    fun fromUnitBox(p: Point2D): Point2D {
        val lx = p.x * 2f * halfWidth - halfWidth
        val ly = p.y * 2f * halfHeight - halfHeight
        val cosR = cos(angleRad)
        val sinR = sin(angleRad)
        return Point2D(center.x + lx * cosR - ly * sinR, center.y + lx * sinR + ly * cosR)
    }

    companion object {
        fun invalid(): RotatedFrame = RotatedFrame(Point2D(0f, 0f), 0f, 0f, 0f)
    }
}

/** 对一组点做 min/max 包围盒, 返回 (minX, minY, maxX, maxY) */
internal fun boundsOf(points: List<Point2D>): FloatArray {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }
    return floatArrayOf(minX, minY, maxX, maxY)
}

/** 供候选生成使用的常量 */
internal object ShapeSnapConstants {
    const val UNIT = 1f
    const val HALF = 0.5f
    const val MIN_EXTENT = 1e-4f
    /** 规则多边形候选的边数范围 */
    val POLYGON_SIDES = 3..8
    /** 闭合笔迹的收尾重复点判定 (首尾距离 / 折线长) */
    const val RING_DUPLICATE_RATIO = 0.02f

    /**
     * "按环拟合"的缺口上限 (首尾距离 / **真实周长估计** = 已画长 + 缺口)
     *
     * 手绘闭合形状很少精确接上; 缺口在此比例内时, 多边形拟合按环处理 (缺口用一条弦补上),
     * 否则 RDP 会把首尾两个端点都当成顶点, 三角形就会变成四边形。
     * 这是拟合方式的选择, 不是候选门 —— 缺口只是给打分加一点分量。
     *
     * 取 0.3: 手绘闭合形状的缺口通常 <15%, 但宽松些能让"画到一半停住"也能成形;
     * 真正的防误吸由打分 (双向距离 < 0.06) 负责, 不靠这道门
     */
    const val RING_GAP_RATIO = 0.3f
    /** 椭圆候选的采样段数 */
    const val ELLIPSE_SEGMENTS = 64
    /** 圆弧候选的采样段数 */
    const val ARC_SEGMENTS = 40
}
