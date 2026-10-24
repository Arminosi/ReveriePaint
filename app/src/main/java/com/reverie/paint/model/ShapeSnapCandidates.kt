/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 形状识别的理想候选生成
 *
 * **三条关键设计** (改动前务必确认):
 *
 * 1. 候选一律是**标准几何体**, 而不是"笔迹自身的简化结果"。若用笔迹自身的 RDP 结果当候选,
 *    它与参考笔迹逐点相同 → 分数恒为 0 → 永远命中, 前面几个真实几何候选永远轮不到。
 *    因此多边形取"正则化后的顶点"而非原始简化顶点, 且不提供自由路径 (Path) 兜底。
 *
 * 2. **理想几何体在文档坐标系构造, 再经同一个外接框变换归一化**。
 *    归一化是各向异性的 (外接框不是正方形), 若直接在归一化空间里拟合几何,
 *    圆弧会被压成椭圆弧 —— 用圆去拟合椭圆弧必然失败 (这正是"弧线识别不出来"的根因)。
 *
 * 3. **多边形取"最小可行边数"**。"把笔迹简化到恰好 k 个顶点"对任何 k ≥ 真实拐角数
 *    都成立 (多余顶点落在直边上), 若逐个 k 都当候选, 四边形会抢走三角形。
 *
 * 4. **没有闭合门**。六个候选**无条件全部构造**, 参考路径是**原始开放折线**。
 *    早期版本曾自造一道 `closedDistanceRatio = 0.15` 的门, 把候选拆成"闭合族/开放族" ——
 *    后果是手绘没完全围起来时只剩 Line/Arc 两个候选, 什么都识别不出。
 *    正确做法是**不判闭合**, 让打分决定 (缺口只会给双向距离加一点分量)。
 */
internal object ShapeSnapCandidates {

    /**
     * 生成候选 (顺序 = 优先级: Rect → Ellipse → Polygon → Quad → Arc)
     *
     * @param docStroke 文档坐标的笔迹点
     * @param docSimplified 文档坐标的多尺度简化结果
     * @param frame 笔迹的最小面积外接框 (归一化基准)
     * @return 候选列表, 其 [ShapeCandidate.polyline] 已归一化到单位框
     */
    fun generate(
        docStroke: List<Point2D>,
        docSimplified: List<Point2D>,
        frame: RotatedFrame,
    ): List<ShapeCandidate> {
        val out = ArrayList<ShapeCandidate>(8)
        val norm = { p: Point2D -> frame.toUnitBox(p) }

        // 六类候选无条件全部构造, 由打分与优先级决定谁胜出

        // --- 直线 ---
        if (docStroke.size >= 2) {
            lineCandidate(docStroke, norm)?.let { out.add(it) }
        }

        // --- 矩形: 外接框本身 → 归一化后即单位方框 ---
        out.add(
            ShapeCandidate(
                type = ShapeSnapCandidateType.RECTANGLE,
                polyline = frame.corners().map(norm),
                closed = true,
            )
        )

        // --- 椭圆/正圆: 外接框内切椭圆 → 归一化后即单位圆 ---
        out.add(
            ShapeCandidate(
                type = ShapeSnapCandidateType.ELLIPSE,
                polyline = ellipseInFrame(frame).map(norm),
                closed = true,
                isEllipseFamily = true,
            )
        )

        // --- 多边形: 简化顶点正则化 (相邻边延长求交点), 取最小可行边数 ---
        bestPolygonFit(docStroke, frame)?.let { fit ->
            polygonCandidate(fit.vertices, fit.sides, norm)?.let { out.add(it) }
        }

        // --- 四边形: 只在简化结果恰好 4 个凸顶点且内角合理时给候选 ---
        quadCandidate(docSimplified, norm)?.let { out.add(it) }

        // --- 圆弧: 点数足够即可 ---
        if (docStroke.size >= 3) {
            arcCandidate(docStroke, norm)?.let { out.add(it) }
        }

        return out
    }

    // ---------------------------------------------------------------- 直线

    /** 直线候选: 总最小二乘拟合方向上的两端极值点 */
    private fun lineCandidate(
        points: List<Point2D>,
        norm: (Point2D) -> Point2D,
    ): ShapeCandidate? {
        val n = points.size
        if (n < 2) return null
        var sx = 0f
        var sy = 0f
        for (p in points) {
            sx += p.x
            sy += p.y
        }
        val mx = sx / n
        val my = sy / n
        var sxx = 0f
        var sxy = 0f
        var syy = 0f
        for (p in points) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        // 主方向: 协方差矩阵最大特征值对应的特征向量
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        val ux = cos(theta)
        val uy = sin(theta)
        var minT = Float.MAX_VALUE
        var maxT = -Float.MAX_VALUE
        for (p in points) {
            val t = (p.x - mx) * ux + (p.y - my) * uy
            if (t < minT) minT = t
            if (t > maxT) maxT = t
        }
        if (maxT - minT <= ShapeSnapConstants.MIN_EXTENT) return null
        val a = Point2D(mx + ux * minT, my + uy * minT)
        val b = Point2D(mx + ux * maxT, my + uy * maxT)
        return ShapeCandidate(
            type = ShapeSnapCandidateType.LINE,
            polyline = listOf(norm(a), norm(b)),
            closed = false,
        )
    }

    // ---------------------------------------------------------------- 椭圆

    /** 外接框内切椭圆 (文档坐标, 半轴 = 外接框半宽/半高) */
    private fun ellipseInFrame(frame: RotatedFrame): List<Point2D> {
        val segments = ShapeSnapConstants.ELLIPSE_SEGMENTS
        val cosR = cos(frame.angleRad)
        val sinR = sin(frame.angleRad)
        val out = ArrayList<Point2D>(segments)
        for (i in 0 until segments) {
            val ang = 2.0 * PI * i / segments
            val lx = frame.halfWidth * cos(ang).toFloat()
            val ly = frame.halfHeight * sin(ang).toFloat()
            out.add(Point2D(frame.center.x + lx * cosR - ly * sinR, frame.center.y + lx * sinR + ly * cosR))
        }
        return out
    }

    // ---------------------------------------------------------------- 多边形

    /** 折线拟合结果: 顶点 + 达到该顶点数所需的简化容差 (容差即最大偏离) + 边数 */
    private data class PolygonFit(val vertices: List<Point2D>, val epsilon: Float, val sides: Int)

    /**
     * 求"最小可行边数"的多边形拟合
     *
     * 从 k = 3 起递增, 返回第一个成立 (顶点数恰好为 k 且直边门通过) 的解。
     * 这样三角形不会被四边形抢走 —— 后者只是把直边上的多余顶点当成了拐角
     */
    private fun bestPolygonFit(docStroke: List<Point2D>, frame: RotatedFrame): PolygonFit? {
        for (k in ShapeSnapConstants.POLYGON_SIDES) {
            simplifyToVertexCount(docStroke, k, frame)?.let { return it.copy(sides = k) }
        }
        return null
    }

    /**
     * 用**递增容差**把笔迹简化到恰好 [target] 个顶点; 找不到返回 null
     *
     * 容差从很小起步、按固定倍率递增, 取"顶点数首次降到 target"的那一组。
     * 手绘三角形不会被手抖的碎角撑成 6~7 个顶点 —— 碎角的偏离远小于真实拐角, 会先被消掉。
     * 容差上限设为对角线的 5%: 再大就会开始吃掉真实的拐角。
     *
     * 闭合笔迹按**显式闭环**处理 (末尾补回首点), 简化后去掉重复点与"落在边中间"的首点
     */
    private fun simplifyToVertexCount(
        docStroke: List<Point2D>,
        target: Int,
        frame: RotatedFrame,
    ): PolygonFit? {
        if (target < 3) return null
        // 按环简化 (而不是按开放折线), 否则 RDP 会把首尾两个端点都当成顶点 ——
        // 手绘三角形留个小缺口就会变成 4 个顶点, 永远匹配不上 k=3。
        //
        // 分母必须用**真实周长的估计值 (已画长 + 缺口)**, 不能用"已画长":
        // 缺口比例 g 相对真实周长算时, g/已画长 = g/(1-g), 会被系统性放大
        // (25% 的缺口算出 0.333)。早期用已画长作分母, 导致 20% 以上的缺口一律判为
        // "开放笔迹" → 开放链 RDP 保留两端点 → k=3 结构上不可达 → 三角形识别不到;
        // 且抖动/采样数会撑大分母, 造成"越抖越容易识别"的反常现象
        val drawn = ShapeSnapGeometry.polylineLength(docStroke, false)
        val gap = if (docStroke.size >= 2) docStroke.first().distanceTo(docStroke.last()) else 0f
        val perimeter = drawn + gap
        val asRing = docStroke.size >= 3 && perimeter > 0f &&
            gap <= ShapeSnapConstants.RING_GAP_RATIO * perimeter
        val pts = if (asRing && gap <= ShapeSnapConstants.RING_DUPLICATE_RATIO * perimeter) {
            // 首尾已重合: 去掉重复点
            docStroke.dropLast(1)
        } else {
            docStroke
        }
        if (pts.size < target) return null

        val b = boundsOf(pts)
        val diag = hypot(b[2] - b[0], b[3] - b[1])
        if (diag <= ShapeSnapConstants.MIN_EXTENT) return null

        val input = if (asRing) pts + pts.first() else pts
        val scale = minOf(frame.halfWidth, frame.halfHeight)
        if (scale <= ShapeSnapConstants.MIN_EXTENT) return null

        var eps = diag * 0.001f
        val epsCap = diag * 0.05f
        while (eps <= epsCap) {
            val simplified = ShapeSnapGeometry.rdp(input, eps)
            val verts = if (asRing) stripRingDuplicate(simplified) else simplified
            // 环起点若落在某条边的中间 (尤其"从边正中起笔"时首点两侧对称),
            // 简化结果会多出一个顶点。同一容差下把"剥掉环起点"也作为一种候选,
            // 谁正好等于 target 就用谁 —— 否则三角形会被数成四边形
            val stripped = if (verts.size == target + 1) stripRingStartIfOnEdge(verts) else verts
            val chosen = when {
                verts.size == target -> verts
                stripped.size == target -> stripped
                else -> null
            }
            if (chosen != null) {
                // 直边判据: RDP 的 ε 就是"所有点到该折线的最大偏离"。
                // 手抖是**绝对像素量**, 所以上限也必须是绝对值 —— 用 ε/短半轴 会让小形状
                // 被手抖误杀 (实测 100px 三角形通过率仅 16%, 700px 有 46%)。
                // 取"短半轴的 25%"作为绝对上限: 真实折线远低于此, 波浪形曲线远高于此
                if (eps > scale * EDGE_STRAIGHT_MAX_RATIO) return null
                return PolygonFit(chosen, eps, target)
            }
            if (verts.size < target) return null
            eps *= 1.15f
        }
        return null
    }

    /**
     * 若环起点落在相邻两点的连线上 (是边上的点而不是拐角), 剥掉它
     *
     * 与 [stripRingDuplicate] 里那段判据同一逻辑, 但这里作用于**任意顶点数**的结果:
     * "从边正中起笔"时首点两侧对称, 容差怎么爬都降不到目标顶点数, 必须先剥点再判
     */
    private fun stripRingStartIfOnEdge(verts: List<Point2D>): List<Point2D> {
        if (verts.size < 4) return verts
        val prev = verts[verts.size - 1]
        val first = verts[0]
        val second = verts[1]
        val chord = prev.distanceTo(second)
        if (chord <= ShapeSnapConstants.MIN_EXTENT) return verts
        val dev = ShapeSnapGeometry.perpendicularDistance(first, prev, second)
        return if (dev / chord <= CORNER_DEVIATION_RATIO) verts.drop(1) else verts
    }


    /**
     * 闭环简化结果的后处理: 去掉首尾重复点; 若首点落在一条**直边**上 (是边中点而不是拐角),
     * 一并去掉 —— 否则闭合笔迹的边数会被多算一个
     *
     * 关键: "是不是拐角"必须用**相对容差**判定。手绘笔迹的首点几乎不可能精确共线,
     * 早期用绝对阈值 (1e-4) 时, 只要用户从边的中间起笔, 那个点就永远去不掉 ——
     * 三角形被数成 4 个顶点, 于是只能匹配到四边形/五边形。
     * 实测: 从角点起笔识别为三角形, 从边中间起笔一律退化成 4~5 边形。
     *
     * 判据用"首点到相邻两点连线的偏离" 相对 **该边的长度**, 阈值取 [CORNER_DEVIATION_RATIO];
     * 真实拐角的偏离是边长的一个可观比例 (通常是几十 %), 边中点则接近 0
     */
    private fun stripRingDuplicate(ring: List<Point2D>): List<Point2D> {
        if (ring.size < 3) return ring
        var verts = ring
        // 1) 去掉首尾重复点 (环的收尾)。
        // 注意: 这一步**之后不能再动首点** —— 环的首点本身就是一个真实拐角,
        // 早期版本接着对它做"是否落在边上"的判定, 会把三角形从 3 个顶点削到 2 个,
        // 于是 k=3 永远失败、只能退到四边形 (实测三角形识别率骤降的主因之一)
        val span = ShapeSnapGeometry.polylineLength(verts, false)
        if (span > 0f && verts.first().distanceTo(verts.last()) <= ShapeSnapConstants.RING_DUPLICATE_RATIO * span) {
            verts = verts.dropLast(1)
        }
        // 2) 环起点可能落在某条边的中间 (用户从边中间起笔): 它会在环里多占一个顶点。
        // 判据是"首点到相邻两点连线的偏离 / 弦长" —— 手绘抖动远小于此, 真实拐角远大于此。
        // 关键: 剥掉后**必须仍 >= 3 个顶点**, 否则会把三角形削成 2 个点
        // (早期版本缺这个保护, 导致 k=3 永远失败、三角形退化成四边形)
        if (verts.size > 3) {
            val prev = verts.last()
            val first = verts[0]
            val second = verts[1]
            val chord = prev.distanceTo(second)
            if (chord > ShapeSnapConstants.MIN_EXTENT) {
                val dev = ShapeSnapGeometry.perpendicularDistance(first, prev, second)
                if (dev / chord <= CORNER_DEVIATION_RATIO) {
                    verts = verts.drop(1)
                }
            }
        }
        return verts
    }

    /**
     * 多边形候选: 把简化出的 k 个顶点正则化
     *
     * 做法: 相邻两条边延长求交点,
     * 用交点替换原顶点 —— 即"把拐角修成精确的直线角", 但不改变整体形状。
     * 手绘三角形的顶点带抖动, 正则化后与理想三角形重合, 分数才会掉到阈值以下
     */
    private fun polygonCandidate(
        verts: List<Point2D>,
        k: Int,
        norm: (Point2D) -> Point2D,
    ): ShapeCandidate? {
        if (k < 3 || verts.size != k) return null
        val refined = ArrayList<Point2D>(k)
        for (i in 0 until k) {
            val prev = verts[(i + k - 1) % k]
            val cur = verts[i]
            val next = verts[(i + 1) % k]
            val inter = lineIntersection(prev, cur, cur, next)
            refined.add(inter ?: cur)
        }
        if (refined.any { !ShapeSnapGeometry.isFinitePoint(it) }) return null
        return ShapeCandidate(
            type = ShapeSnapCandidateType.POLYGON,
            polyline = refined.map(norm),
            closed = true,
            sides = k,
        )
    }

    /** 两条直线 (p1→p2 与 p3→p4) 的交点; 近平行时返回 null */
    private fun lineIntersection(p1: Point2D, p2: Point2D, p3: Point2D, p4: Point2D): Point2D? {
        val d1x = p2.x - p1.x
        val d1y = p2.y - p1.y
        val d2x = p4.x - p3.x
        val d2y = p4.y - p3.y
        val denom = d1x * d2y - d1y * d2x
        if (abs(denom) <= 1e-6f) return null
        val t = ((p3.x - p1.x) * d2y - (p3.y - p1.y) * d2x) / denom
        return Point2D(p1.x + d1x * t, p1.y + d1y * t)
    }

    // ---------------------------------------------------------------- 四边形

    /**
     * 四边形候选: 简化结果必须**恰好 4 个顶点**、构成凸四边形、且内角在 15°~165°
     *
     * 这三道门是"涂鸦不会被吸成四边形"的关键 —— 缺了它们, 任何闭合笔迹的
     * 简化结果都能凑出一个四边形候选并高分命中
     */
    private fun quadCandidate(
        docSimplified: List<Point2D>,
        norm: (Point2D) -> Point2D,
    ): ShapeCandidate? {
        // 简化结果末端若与首点重合 (相对阈值), 去掉后才是真正的顶点集
        val pts = if (docSimplified.size >= 2 &&
            docSimplified.first().distanceTo(docSimplified.last()) <=
            ShapeSnapConstants.RING_DUPLICATE_RATIO *
            maxOf(1f, ShapeSnapGeometry.polylineLength(docSimplified, false))
        ) {
            docSimplified.dropLast(1)
        } else {
            docSimplified
        }
        if (pts.size != 4) return null

        // 凸性: 四个叉积同号
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
            if (abs(cr) <= ShapeSnapConstants.MIN_EXTENT) return null
            val s = if (cr > 0f) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return null
        }

        // 内角合理: 15°~165°
        val minCos = cos(15.0 * PI / 180.0).toFloat()
        val maxCos = cos(165.0 * PI / 180.0).toFloat()
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val cur = pts[i]
            val next = pts[(i + 1) % 4]
            val v1x = prev.x - cur.x
            val v1y = prev.y - cur.y
            val v2x = next.x - cur.x
            val v2y = next.y - cur.y
            val l1 = hypot(v1x, v1y)
            val l2 = hypot(v2x, v2y)
            if (l1 <= ShapeSnapConstants.MIN_EXTENT || l2 <= ShapeSnapConstants.MIN_EXTENT) return null
            val cosA = (v1x * v2x + v1y * v2y) / (l1 * l2)
            if (cosA > minCos || cosA < maxCos) return null
        }

        return ShapeCandidate(
            type = ShapeSnapCandidateType.QUAD,
            polyline = pts.map(norm),
            closed = true,
        )
    }

    // ---------------------------------------------------------------- 圆弧

    /**
     * 圆弧候选: 过首/中/末三点的外接圆
     *
     * **在文档坐标拟合** (归一化各向异性, 在归一化空间拟合会把圆弧当椭圆弧), 采样后再逐点归一化
     */
    private fun arcCandidate(
        docPoints: List<Point2D>,
        norm: (Point2D) -> Point2D,
    ): ShapeCandidate? {
        val p0 = docPoints.first()
        val p1 = docPoints[docPoints.size / 2]
        val p2 = docPoints.last()
        if (ShapeSnapGeometry.isCollinear(p0, p1, p2)) return null

        val ax = p0.x
        val ay = p0.y
        val bx = p1.x
        val by = p1.y
        val cx = p2.x
        val cy = p2.y
        val d = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) <= 1e-9f) return null
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
        val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        if (!ux.isFinite() || !uy.isFinite()) return null
        val r = hypot(p0.x - ux, p0.y - uy)
        if (!r.isFinite() || r <= 1e-3f) return null

        val angA = atan2(p0.y - uy, p0.x - ux)
        val angB = atan2(p1.y - uy, p1.x - ux)
        val angC = atan2(p2.y - uy, p2.x - ux)
        val twoPi = (2.0 * PI).toFloat()
        // 选经过中点的扫掠方向
        var ccwSpan = ShapeSnapGeometry.normalizeAngle(angC - angA)
        if (ccwSpan < 0f) ccwSpan += twoPi
        var ccwMid = ShapeSnapGeometry.normalizeAngle(angB - angA)
        if (ccwMid < 0f) ccwMid += twoPi
        val sweep = if (ccwMid <= ccwSpan) ccwSpan else ccwSpan - twoPi

        val segments = ShapeSnapConstants.ARC_SEGMENTS
        val out = ArrayList<Point2D>(segments + 1)
        for (i in 0..segments) {
            val t = i / segments.toFloat()
            val ang = angA + sweep * t
            out.add(norm(Point2D(ux + r * cos(ang), uy + r * sin(ang))))
        }
        return ShapeCandidate(type = ShapeSnapCandidateType.ARC, polyline = out, closed = false)
    }

    /**
     * "首点落在直边上"的判定比例: 首点到相邻两点连线的偏离 / 该弦长
     *
     * 真实拐角的偏离通常是弦长的几十 %, 边中点则接近 0。取 8% 作为分界:
     * 手绘抖动远小于此, 真实拐角远大于此。用绝对阈值会让"从边中间起笔"的三角形
     * 永远多出一个顶点 (实测识别率骤降的主因)
     */
    private const val CORNER_DEVIATION_RATIO = 0.08f

    /**
     * 直边门: 把笔迹压到 k 个顶点所需的简化容差, 相对外接框短半轴的上限
     *
     * 越小越严。0.04 表示"每条边上的点偏离该边不超过短半轴的 4%" —— 波浪形闭合曲线
     * 要压到 8 顶点需要大得多的容差, 会被这道门挡掉; 手绘三角形/五边形则轻松通过
     */
    /**
     * 直边门: 把笔迹压到 k 个顶点所需的简化容差上限, 相对外接框短半轴
     *
     * 手抖是绝对像素量, 但形状越大允许的绝对偏离也越大 (大形状的手抖占比更小),
     * 因此用"短半轴的百分比"作为上限。0.25 是宽松值: 只挡真正弯曲的笔迹 (波浪形闭合
     * 曲线要压到 3~8 顶点需要远超此的容差), 不挡手抖
     */
    private const val EDGE_STRAIGHT_MAX_RATIO = 0.25f
}

/**
 * 旧 ShapeSnap 内核内部的候选种类
 *
 * 对外只映射到现有 [QuickShapeType] 子集: POLYGON 仅在 sides == 3 时映射为 TRIANGLE,
 * QUAD 映射为 QUADRILATERAL。因此这里不引入枚举外的公开类型。
 */
internal enum class ShapeSnapCandidateType {
    LINE,
    RECTANGLE,
    ELLIPSE,
    POLYGON,
    QUAD,
    ARC,
}

/**
 * 一个理想候选
 *
 * @param type 候选形状类型
 * @param polyline 归一化空间 (单位框) 中的理想轮廓
 * @param closed 是否闭合
 * @param sides 规则多边形边数 (POLYGON 有效)
 * @param isEllipseFamily 椭圆族 (供最终判定 正圆/椭圆)
 */
internal data class ShapeCandidate(
    val type: ShapeSnapCandidateType,
    val polyline: List<Point2D>,
    val closed: Boolean,
    val sides: Int = 0,
    val isEllipseFamily: Boolean = false,
)
