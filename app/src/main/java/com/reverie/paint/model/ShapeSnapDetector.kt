/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 宽松识别内核 (源自 `backup/quickshape-original` 的 ShapeSnapDetector)
 *
 * 流水线:
 * 1. 点数 < 3 → 不识别
 * 2. 多尺度 RDP 简化 (ε=1.0 起, 步长 2.0, 上限 128, 顶点 ≤64)
 * 3. 凸包 + 最小面积旋转外接框 + 质心
 * 4. 直线早退: 外接框长宽比 < 0.05 → 直线
 * 5. 归一化到单位框 (不做闭合判定 —— 缺口由打分容忍, 不设前置门)
 * 6. 生成候选 (顺序 = 优先级: Line → Rect → Ellipse → Polygon → Quad → Arc)
 * 7. 打分: 各方向弧长采 33 点, 双向最近距离, (sum/32)/K, 取较大者 (K = 0.06)
 * 8. **取首个 score < 1.0 者**; 全不中 → null (保持手绘笔迹)
 * 9. 逆变换回文档坐标 + 朝向吸附
 *
 * 三条承重约束 (改动前务必确认):
 * - 打分在**归一化空间**, 除数是常量 K, 不是路径长
 * - 择优是**按序取首个 <1.0**, 不是取最小
 * - **不启用自由路径兜底**, 不像就不识别 (否则任何笔迹都会被吸成"笔迹自己")
 *
 * 门控由调用方以参数传入 (不写可变 gate 字段), 识别可以并发调用。
 */
internal object ShapeSnapDetector {

    // ---- 阈值 (保持旧内核数值, 不要为让测试变绿而调整) ----

    /** 直线早退: 外接框短边/长边 低于此值判为直线 */
    private const val LINE_ASPECT_RATIO_THRESHOLD = 0.05f
    private const val RDP_EPSILON_INITIAL = 1.0f
    private const val RDP_EPSILON_STEP = 2.0f
    private const val RDP_EPSILON_MAX = 128.0f
    private const val RDP_MAX_VERTICES = 64
    /** 打分采样点数 (33 = i 取 0..32, 即 32 个间隔) */
    private const val SAMPLE_COUNT = 33
    /** 归一化除数 K; 命中条件 = 平均双向最近距离 < K */
    private const val NORM_DIVISOR_K = 0.06f
    /** 择优阈值 */
    private const val HIT_THRESHOLD = 1.0f
    /** 圆弧候选的半径有效范围 (归一化单位; 旧内核仅声明, 未参与判定) */
    @Suppress("unused")
    private const val ARC_MIN_RADIUS = 0.05f
    @Suppress("unused")
    private const val ARC_MAX_RADIUS = 50.0f
    /** 朝向吸附容差 (度), 45° 倍数统一 6° */
    private const val SNAP_TOLERANCE_DEG = 6.0f

    /**
     * 识别入口: 返回拟合出的几何图元; 无匹配返回 null (调用方须保持笔迹原样)
     *
     * @param recognizeArcs 圆弧候选门控
     * @param recognizeQuadrilaterals 四边形候选门控
     */
    fun detect(
        points: List<Point2D>,
        recognizeArcs: Boolean = true,
        recognizeQuadrilaterals: Boolean = true,
    ): QuickShapeResult? =
        detectDetailed(points, recognizeArcs, recognizeQuadrilaterals)?.result

    /** 含分数与候选类型的识别结果 (测试与调试用) */
    fun detectDetailed(
        points: List<Point2D>,
        recognizeArcs: Boolean = true,
        recognizeQuadrilaterals: Boolean = true,
    ): ShapeSnapMatch? {
        // 1. 输入门
        if (points.size < 3) return null
        if (points.any { !ShapeSnapGeometry.isFinitePoint(it) }) return null

        // 2. 多尺度简化
        val simplified = ShapeSnapGeometry.multiScaleRdp(
            points,
            eps0 = RDP_EPSILON_INITIAL,
            step = RDP_EPSILON_STEP,
            epsMax = RDP_EPSILON_MAX,
            maxVertices = RDP_MAX_VERTICES,
        )

        // 3. 外接框
        val frame = ShapeSnapGeometry.tightestFrame(points)
        if (!frame.isValid) return null

        // 4. 直线早退: 极细长 (长宽比 ≥20:1) 直接判直线, 不进候选与打分
        val shortSide = min(frame.halfWidth, frame.halfHeight)
        val longSide = max(frame.halfWidth, frame.halfHeight)
        val ratio = if (longSide > 1e-6f) shortSide / longSide else 1f
        if (ratio < LINE_ASPECT_RATIO_THRESHOLD) {
            return lineResult(points, frame)
        }

        // 5. 归一化笔迹到单位框 (候选在文档空间构造后各自归一化, 保证与笔迹经历同一形变)
        val normStroke = points.map { frame.toUnitBox(it) }
        if (normStroke.any { !ShapeSnapGeometry.isFinitePoint(it) }) return null

        // 6. 候选生成 (文档坐标进, 归一化坐标出); 六类无条件全构造, 不判闭合
        // 门控: 直线/矩形/椭圆/三角形恒开; 圆弧与四边形由调用方决定。
        // 边数不是 3 的多边形候选在打分前丢掉, 否则会因"首个达标就停"抢走矩形/椭圆。
        val candidates = ShapeSnapCandidates.generate(points, simplified, frame)
            .filter { c ->
                when (c.type) {
                    ShapeSnapCandidateType.LINE,
                    ShapeSnapCandidateType.RECTANGLE,
                    ShapeSnapCandidateType.ELLIPSE,
                    -> true

                    ShapeSnapCandidateType.POLYGON -> c.sides == 3
                    ShapeSnapCandidateType.QUAD -> recognizeQuadrilaterals
                    ShapeSnapCandidateType.ARC -> recognizeArcs
                }
            }
            .filter { it.polyline.size >= 2 }
        if (candidates.isEmpty()) return null

        // 7. 打分: 与"归一化笔迹"比较 (双向最近距离, 除以常量 K)
        // 参考 = 按绘制顺序的开放折线 (不闭合, 缺口由打分容忍)
        for (c in candidates) {
            val score = compare(c.polyline, c.closed, normStroke, referenceClosed = false)
            if (!score.isFinite()) continue
            // 8. 按序取首个达标者 —— 不是取最小值 (候选顺序即优先级)
            if (score < HIT_THRESHOLD) {
                val match = buildMatch(c, score, frame) ?: continue
                // 9. 朝向吸附 (直线/矩形/四边形); 在打分之后执行, 只做一次
                return ShapeSnapMatch(
                    result = snappedToAxis(match.result, SNAP_TOLERANCE_DEG),
                    score = match.score,
                    candidateType = match.candidateType,
                )
            }
        }
        return null
    }

    /**
     * 打分: 双向最近距离
     *
     * `max( (Σ候选→笔迹 / 32) / K , (Σ笔迹→候选 / 32) / K )`
     */
    private fun compare(
        candidate: List<Point2D>,
        candidateClosed: Boolean,
        reference: List<Point2D>,
        referenceClosed: Boolean,
    ): Float {
        val n = SAMPLE_COUNT
        if (n < 2) return Float.MAX_VALUE

        val candSamples = ShapeSnapGeometry.samplePolyline(candidate, n, candidateClosed)
        val refSamples = ShapeSnapGeometry.samplePolyline(reference, n, referenceClosed)
        if (candSamples.isEmpty() || refSamples.isEmpty()) return Float.MAX_VALUE

        var sum1 = 0f
        for (p in candSamples) {
            val d = ShapeSnapGeometry.pointToPolylineDistance(p, reference, referenceClosed)
            if (!d.isFinite()) return Float.MAX_VALUE
            sum1 += d
        }
        var sum2 = 0f
        for (p in refSamples) {
            val d = ShapeSnapGeometry.pointToPolylineDistance(p, candidate, candidateClosed)
            if (!d.isFinite()) return Float.MAX_VALUE
            sum2 += d
        }
        // 除以 32 (采样点间隔数) 而非 33; 除数用 K
        val denom = (n - 1).toFloat() * NORM_DIVISOR_K
        if (denom <= 0f) return Float.MAX_VALUE
        val a = sum1 / denom
        val b = sum2 / denom
        return max(a, b)
    }

    /** 直线早退的结果: 沿外接框长轴, 取笔迹在长轴上的极值 */
    private fun lineResult(points: List<Point2D>, frame: RotatedFrame): ShapeSnapMatch {
        val ux = cos(frame.angleRad)
        val uy = sin(frame.angleRad)
        val cx = frame.center.x
        val cy = frame.center.y
        var minT = Float.MAX_VALUE
        var maxT = -Float.MAX_VALUE
        for (p in points) {
            val t = (p.x - cx) * ux + (p.y - cy) * uy
            if (t < minT) minT = t
            if (t > maxT) maxT = t
        }
        val p0 = Point2D(cx + ux * minT, cy + uy * minT)
        val p1 = Point2D(cx + ux * maxT, cy + uy * maxT)
        val result = QuickShapeResult(
            type = QuickShapeType.LINE,
            points = listOf(p0, p1),
            center = Point2D((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f),
        )
        return ShapeSnapMatch(
            result = snappedToAxis(result, SNAP_TOLERANCE_DEG),
            score = 0f,
            candidateType = ShapeSnapCandidateType.LINE,
        )
    }

    /**
     * 把归一化空间的胜出候选映射回文档坐标并定型
     *
     * 对外类型只使用现有 [QuickShapeType]; 返回 null 表示该候选无法构造有效图元,
     * 调用方应继续尝试后续候选。
     */
    private fun buildMatch(c: ShapeCandidate, score: Float, frame: RotatedFrame): ShapeSnapMatch? {
        val docPoints = c.polyline.map { frame.fromUnitBox(it) }
        val result = when (c.type) {
            ShapeSnapCandidateType.RECTANGLE -> QuickShapeResult(
                type = QuickShapeType.RECTANGLE,
                points = emptyList(),
                center = frame.center,
                radiusX = frame.halfWidth,
                radiusY = frame.halfHeight,
                rotationRad = frame.angleRad,
            )

            ShapeSnapCandidateType.ELLIPSE -> {
                // 椭圆族: 长短轴比接近 1 时定名为正圆
                val longSide = max(frame.halfWidth, frame.halfHeight)
                val shortSide = min(frame.halfWidth, frame.halfHeight)
                val aspect = if (longSide > 1e-6f) shortSide / longSide else 1f
                val isCircle = aspect > 0.9f
                QuickShapeResult(
                    type = if (isCircle) QuickShapeType.CIRCLE else QuickShapeType.ELLIPSE,
                    points = emptyList(),
                    center = frame.center,
                    radiusX = frame.halfWidth,
                    radiusY = frame.halfHeight,
                    rotationRad = frame.angleRad,
                )
            }

            ShapeSnapCandidateType.POLYGON -> {
                // 非三边多边形已在打分前过滤, 这里只可能是三角形
                if (c.sides != 3 || docPoints.size != 3) return null
                QuickShapeResult(
                    type = QuickShapeType.TRIANGLE,
                    points = docPoints,
                    center = ShapeSnapGeometry.centroid(docPoints),
                )
            }

            ShapeSnapCandidateType.QUAD -> {
                // 8 点表示: 角、边中点、角、边中点…… (与 QuickShapeRecognition 的 handles 拼法一致)
                if (docPoints.size != 4) return null
                val handles = docPoints.indices.flatMap {
                    listOf(docPoints[it], (docPoints[it] + docPoints[(it + 1) % 4]) / 2f)
                }
                QuickShapeResult(
                    type = QuickShapeType.QUADRILATERAL,
                    points = handles,
                    center = ShapeSnapGeometry.centroid(docPoints),
                    radiusX = frame.halfWidth,
                    radiusY = frame.halfHeight,
                    rotationRad = frame.angleRad,
                )
            }

            ShapeSnapCandidateType.ARC -> {
                // 编辑拖拽要求 points 正好 3 个: 起、弧上中点、终
                if (docPoints.size < 3) return null
                QuickShapeArc.through(
                    docPoints.first(),
                    docPoints[docPoints.size / 2],
                    docPoints.last(),
                ) ?: return null
            }

            ShapeSnapCandidateType.LINE -> QuickShapeResult(
                type = QuickShapeType.LINE,
                points = docPoints,
                center = ShapeSnapGeometry.centroid(docPoints),
            )
        }
        return ShapeSnapMatch(result = result, score = score, candidateType = c.type)
    }

    // ---------------------------------------------------------------- 朝向吸附

    /**
     * 识别期的朝向吸附 (从旧 `QuickShapeResult.snappedToAxis` 移植, 只作用于本次识别结果)
     *
     * 直线/矩形/四边形吸附到 45° 倍数, 统一 6° 容差; 其它类型原样返回。
     */
    private fun snappedToAxis(shape: QuickShapeResult, toleranceDeg: Float): QuickShapeResult {
        if (!toleranceDeg.isFinite() || toleranceDeg < 0f) return shape
        return when (shape.type) {
            QuickShapeType.LINE -> snapLine(shape, toleranceDeg)
            QuickShapeType.RECTANGLE -> snapRectangle(shape, toleranceDeg)
            QuickShapeType.QUADRILATERAL -> snapQuad(shape, toleranceDeg)
            else -> shape
        }
    }

    /** 直线吸附: 命中目标角则绕中点旋转到精确角度并保持长度 */
    private fun snapLine(shape: QuickShapeResult, toleranceDeg: Float): QuickShapeResult {
        if (shape.points.size != 2) return shape
        val p0 = shape.points[0]
        val p1 = shape.points[1]
        if (!ShapeSnapGeometry.isFinitePoint(p0) || !ShapeSnapGeometry.isFinitePoint(p1)) return shape
        val len = p0.distanceTo(p1)
        if (!len.isFinite() || len <= 0f) return shape
        val angleDeg = normalizeDeg(atan2(p1.y - p0.y, p1.x - p0.x) * RAD_TO_DEG)
        val target = nearestSnapTarget(angleDeg, toleranceDeg) ?: return shape
        val mid = Point2D((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
        val deltaRad = normalizeDeg(target - angleDeg) * DEG_TO_RAD
        val cosR = cos(deltaRad)
        val sinR = sin(deltaRad)
        return shape.copy(points = listOf(rotated(p0, mid, cosR, sinR), rotated(p1, mid, cosR, sinR)))
    }

    /** 矩形吸附: rotationRad 接近 90° 整数倍时吸附 */
    private fun snapRectangle(shape: QuickShapeResult, toleranceDeg: Float): QuickShapeResult {
        if (!shape.rotationRad.isFinite()) return shape
        val deg = normalizeDeg(shape.rotationRad * RAD_TO_DEG)
        val nearest = kotlin.math.round(deg / 90f) * 90f
        val diff = abs(normalizeDeg(deg - nearest))
        if (diff > toleranceDeg) return shape
        return shape.copy(rotationRad = normalizeDeg(nearest) * DEG_TO_RAD)
    }

    /**
     * 四边形吸附: 仅当四个内角都在 90°±12° 内 (等效矩形) 才按首边方向整体旋转吸附
     *
     * 输入是 8 点表示 (角/边中点交替), 只旋转 4 个角并重建边中点。
     */
    private fun snapQuad(shape: QuickShapeResult, toleranceDeg: Float): QuickShapeResult {
        if (shape.points.size != 8) return shape
        val vs = listOf(shape.points[0], shape.points[2], shape.points[4], shape.points[6])
        if (vs.any { !ShapeSnapGeometry.isFinitePoint(it) }) return shape
        for (i in 0 until 4) {
            val prev = vs[(i + 3) % 4]
            val cur = vs[i]
            val next = vs[(i + 1) % 4]
            val d1x = prev.x - cur.x
            val d1y = prev.y - cur.y
            val d2x = next.x - cur.x
            val d2y = next.y - cur.y
            val cross = d1x * d2y - d1y * d2x
            val dot = d1x * d2x + d1y * d2y
            val angleDeg = abs(atan2(cross, dot)) * RAD_TO_DEG
            if (abs(angleDeg - 90f) > QUAD_RECTANGLE_TOLERANCE_DEG) return shape
        }
        val edge = vs[1] - vs[0]
        if (!ShapeSnapGeometry.isFinitePoint(edge)) return shape
        val edgeDeg = normalizeDeg(atan2(edge.y, edge.x) * RAD_TO_DEG)
        val target = nearestSnapTarget(edgeDeg, toleranceDeg) ?: return shape
        val c = ShapeSnapGeometry.centroid(vs)
        if (!ShapeSnapGeometry.isFinitePoint(c)) return shape
        val deltaRad = normalizeDeg(target - edgeDeg) * DEG_TO_RAD
        val cosR = cos(deltaRad)
        val sinR = sin(deltaRad)
        val rotatedVs = vs.map { rotated(it, c, cosR, sinR) }
        val handles = rotatedVs.indices.flatMap {
            listOf(rotatedVs[it], (rotatedVs[it] + rotatedVs[(it + 1) % 4]) / 2f)
        }
        return shape.copy(points = handles, center = c)
    }

    /** 在 8 个 45° 目标里找最近者, 命中统一容差才返回目标角 */
    private fun nearestSnapTarget(angleDeg: Float, toleranceDeg: Float): Float? {
        var bestTarget = Float.NaN
        var bestDist = Float.MAX_VALUE
        for (target in SNAP_TARGETS) {
            val dist = abs(normalizeDeg(angleDeg - target))
            if (dist < bestDist) {
                bestDist = dist
                bestTarget = target
            }
        }
        if (!bestTarget.isFinite()) return null
        return if (bestDist <= toleranceDeg) bestTarget else null
    }

    /** 归一化角度到 (-180, 180] (度) */
    private fun normalizeDeg(deg: Float): Float {
        if (!deg.isFinite()) return deg
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** p 绕 c 逆时针旋转 (cosR, sinR) 后的世界坐标 */
    private fun rotated(p: Point2D, c: Point2D, cosR: Float, sinR: Float): Point2D {
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Point2D(c.x + dx * cosR - dy * sinR, c.y + dx * sinR + dy * cosR)
    }

    /** 四边形视作矩形的内角容差 (度) */
    private const val QUAD_RECTANGLE_TOLERANCE_DEG = 12f

    private const val DEG_TO_RAD = 0.017453292f
    private const val RAD_TO_DEG = 57.29578f

    /** 吸附目标角 (度), 45° 倍数 */
    private val SNAP_TARGETS = floatArrayOf(0f, 45f, 90f, 135f, 180f, -135f, -90f, -45f)
}

/**
 * 识别结果 (含分数与命中的候选类型)
 *
 * @param result 可直接落笔的几何图元
 * @param score 不相似度 (越小越像; < 1.0 才会被采纳)
 * @param candidateType 命中的候选类型 (与 [result] 的类型可能不同, 如椭圆族定名为正圆)
 */
internal data class ShapeSnapMatch(
    val result: QuickShapeResult,
    val score: Float,
    val candidateType: ShapeSnapCandidateType,
)
