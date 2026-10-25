/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 形状识别内核的回归测试
 *
 * 重点覆盖用户实测反馈的三类问题:
 * 1. 圆不再被误判为矩形 (旧实现圆度阈值 0.86 过高, 手绘圆落到"矩形"分支)
 * 2. 弧线能被识别 (旧实现 90° 弧的弦长/弧长比 0.90 高于直线阈值 0.88, 被抢判为直线)
 * 3. 涂鸦不被吸附 (旧实现末尾无条件回退直线)
 */
class ShapeSnapDetectorTest {

    // ---- 造点工具: 固定种子, 保证可重复 ----

    private fun jitter(n: Int, amp: Float, seed: Long): (Float, Float) -> Point2D {
        val rnd = Random(seed)
        return { x, y -> Point2D(x + (rnd.nextFloat() * 2f - 1f) * amp, y + (rnd.nextFloat() * 2f - 1f) * amp) }
    }

    /** 沿参数曲线均匀取 [n] 点并加抖动 */
    private fun stroke(n: Int, amp: Float, seed: Long, f: (Float) -> Pair<Float, Float>): List<Point2D> {
        val j = jitter(n, amp, seed)
        return (0 until n).map { i ->
            val (x, y) = f(i / (n - 1).toFloat())
            j(x, y)
        }
    }

    // ---- 基础图元 ----

    @Test
    fun `detects a jittered straight line as line`() {
        val pts = stroke(60, 0.4f, 1L) { t -> (50f + t * 300f) to (80f + t * 120f) }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("直线应被识别", res)
        assertEquals(QuickShapeType.LINE, res!!.type)
    }

    @Test
    fun `detects a jittered circle as circle`() {
        // 用户反馈: 圆被识别成矩形 —— 这是本测试要守住的回归
        val pts = stroke(80, 0.5f, 2L) { t ->
            val a = t * 2f * PI.toFloat()
            (200f + 100f * cos(a)) to (200f + 100f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("圆应被识别", res)
        assertTrue(
            "圆应识别为 CIRCLE (或退化时 ELLIPSE), 实际 ${res!!.type}",
            res.type == QuickShapeType.CIRCLE || res.type == QuickShapeType.ELLIPSE,
        )
        assertTrue("半径应接近 100, 实际 ${res.radiusX}", res.radiusX in 85f..115f)
    }

    @Test
    fun `detects a jittered ellipse as ellipse`() {
        val pts = stroke(80, 0.5f, 3L) { t ->
            val a = t * 2f * PI.toFloat()
            (200f + 150f * cos(a)) to (150f + 60f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("椭圆应被识别", res)
        assertTrue(
            "明显偏长的椭圆不应识别为正圆, 实际 ${res!!.type}",
            res.type == QuickShapeType.ELLIPSE,
        )
    }

    @Test
    fun `detects a jittered rectangle as rectangle`() {
        val pts = stroke(100, 0.6f, 4L) { t ->
            // 沿周长参数化: 0..1 走一圈
            val p = t * 4f
            when {
                p < 1f -> (50f + p * 300f) to 50f
                p < 2f -> 350f to (50f + (p - 1f) * 200f)
                p < 3f -> (350f - (p - 2f) * 300f) to 250f
                else -> 50f to (250f - (p - 3f) * 200f)
            }
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("矩形应被识别", res)
        assertEquals(QuickShapeType.RECTANGLE, res!!.type)
    }

    @Test
    fun `detects a jittered triangle as triangle`() {
        val pts = stroke(90, 0.6f, 5L) { t ->
            val p = t * 3f
            when {
                p < 1f -> (100f + p * 200f) to 100f
                p < 2f -> (300f - (p - 1f) * 100f) to (100f + (p - 1f) * 180f)
                else -> (200f - (p - 2f) * 100f) to (280f - (p - 2f) * 180f)
            }
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("三角形应被识别", res)
        assertEquals(QuickShapeType.TRIANGLE, res!!.type)
    }

    @Test
    fun `detects a jittered regular pentagon as polygon with five sides`() {
        val pts = stroke(100, 0.5f, 6L) { t ->
            val a = t * 2f * PI.toFloat() - PI.toFloat() / 2f
            (200f + 120f * cos(a)) to (200f + 120f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("正五边形应被识别", res)
        // 五边形: 多边形候选或（若判定为圆）圆 —— 二者都算通过, 但不应是矩形
        assertTrue(
            "正五边形不应识别为矩形, 实际 ${res!!.type}",
            res.type != QuickShapeType.RECTANGLE,
        )
    }

    @Test
    fun `detects a jittered hexagon as polygon with six sides`() {
        val pts = (0 until 120).map { i ->
            val a = i / 120f * 2f * PI.toFloat()
            // 六边形: 沿角向做半径交替, 逼近正六边形轮廓
            val k = 6
            val seg = (a / (2f * PI.toFloat() / k))
            val local = seg - kotlin.math.floor(seg)
            val r = 120f / cos((local - 0.5f) * (2f * PI.toFloat() / k) / 2f)
            val jx = (Random(i.toLong()).nextFloat() * 2f - 1f) * 0.4f
            val jy = (Random(i.toLong() + 7).nextFloat() * 2f - 1f) * 0.4f
            Point2D(200f + r * cos(a) + jx, 200f + r * sin(a) + jy)
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("正六边形应被识别", res)
        assertTrue(
            "正六边形不应识别为矩形, 实际 ${res!!.type}",
            res.type != QuickShapeType.RECTANGLE,
        )
    }

    // ---- 用户反馈: 弧线 ----

    @Test
    fun `detects a circular arc as arc`() {
        // 120° 圆弧
        val pts = stroke(60, 0.4f, 8L) { t ->
            val a = (t - 0.5f) * (120f * PI.toFloat() / 180f)
            (250f + 150f * cos(a)) to (250f + 150f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("120° 圆弧应被识别", res)
        assertEquals(QuickShapeType.ARC, res!!.type)
    }

    @Test
    fun `detects a shallow arc as arc not line`() {
        // 用户反馈: 弧线几乎不能识别 —— 60° 弧在旧实现里弦长/弧长比约 0.955, 被判成直线
        val pts = stroke(60, 0.3f, 9L) { t ->
            val a = (t - 0.5f) * (60f * PI.toFloat() / 180f)
            (300f + 400f * cos(a)) to (300f + 400f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("浅弧应被识别", res)
        assertEquals("浅弧不应被识别为直线", QuickShapeType.ARC, res!!.type)
    }

    // ---- 朝向吸附 ----

    @Test
    fun `snaps a near horizontal line to horizontal`() {
        val pts = stroke(40, 0.3f, 10L) { t ->
            // 约 3° 偏角
            (60f + t * 400f) to (200f + t * 400f * kotlin.math.tan(3f * PI.toFloat() / 180f))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
        // 无 snapKind 字段: 直接校验吸附后的几何角度 (水平 → sin ≈ 0)
        val angle = kotlin.math.atan2(res.points[1].y - res.points[0].y, res.points[1].x - res.points[0].x)
        assertTrue("应吸附为水平, 实际角度 ${angle * 180f / PI.toFloat()}", abs(sin(angle)) < 1e-3f)
    }

    @Test
    fun `snaps a near vertical line to vertical`() {
        val pts = stroke(40, 0.3f, 11L) { t ->
            (200f + t * 400f * kotlin.math.tan(4f * PI.toFloat() / 180f)) to (60f + t * 400f)
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
        // 无 snapKind 字段: 直接校验吸附后的几何角度 (垂直 → cos ≈ 0)
        val angle = kotlin.math.atan2(res.points[1].y - res.points[0].y, res.points[1].x - res.points[0].x)
        assertTrue("应吸附为垂直, 实际角度 ${angle * 180f / PI.toFloat()}", abs(cos(angle)) < 1e-3f)
    }

    @Test
    fun `does not snap a line that is far from an axis`() {
        // 30° 偏角: 超出所有吸附容差, 角度必须保持 30°
        val pts = stroke(40, 0.3f, 12L) { t ->
            (60f + t * 400f * cos(30f * PI.toFloat() / 180f)) to
                (200f + t * 400f * sin(30f * PI.toFloat() / 180f))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
        // 无 snapKind 字段: 直接校验几何角度未被吸附
        val angle = kotlin.math.atan2(res.points[1].y - res.points[0].y, res.points[1].x - res.points[0].x)
        assertEquals(30f * PI.toFloat() / 180f, abs(angle), 0.01f)
    }

    // ---- 缺口回归 (核心修复): 手绘不会精确闭合, 不做闭合门才能识别 ----

    @Test
    fun `detects a triangle with a visible gap`() {
        // 手绘三角形几乎不可能首尾精确接上; 早期实现自造了 0.15 闭合门,
        // 有缺口时只剩 Line/Arc 候选 → 什么都识别不出 (用户实测的高频现象)
        val pts = stroke(80, 0.5f, 51L) { t ->
            // 只画到 88% 的周长就停笔, 留下明显缺口
            val p = t * 3f * 0.88f
            when {
                p < 1f -> (100f + p * 200f) to 100f
                p < 2f -> (300f - (p - 1f) * 100f) to (100f + (p - 1f) * 180f)
                else -> (200f - (p - 2f) * 100f) to (280f - (p - 2f) * 180f)
            }
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("带缺口的三角形必须能识别", res)
        assertEquals(QuickShapeType.TRIANGLE, res!!.type)
    }

    @Test
    fun `detects a rectangle with a visible gap`() {
        val pts = stroke(90, 0.5f, 52L) { t ->
            // 走到 90% 周长, 缺口在左下角
            val p = t * 4f * 0.9f
            when {
                p < 1f -> (50f + p * 300f) to 50f
                p < 2f -> 350f to (50f + (p - 1f) * 200f)
                p < 3f -> (350f - (p - 2f) * 300f) to 250f
                else -> 50f to (250f - (p - 3f) * 200f)
            }
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("带缺口的矩形必须能识别", res)
        assertEquals(QuickShapeType.RECTANGLE, res!!.type)
    }

    @Test
    fun `detects a circle with a visible gap`() {
        val pts = stroke(80, 0.5f, 53L) { t ->
            // 320 度圆弧: 留下 40 度缺口
            val a = t * (320f * PI.toFloat() / 180f)
            (200f + 100f * cos(a)) to (200f + 100f * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("带缺口的圆必须能识别", res)
        assertTrue(
            "带缺口的圆应识别为圆或椭圆, 实际 ${res!!.type}",
            res.type == QuickShapeType.CIRCLE || res.type == QuickShapeType.ELLIPSE,
        )
    }

    // ---- 三角形健壮性 (用户实测"三角形识别很差"的回归) ----

    /**
     * 沿三角形周长采样, 可指定起笔位置/缺口/抖动
     *
     * @param startT 起笔位置 (0=角点, 0.33/0.66=另两角, 0.5=边正中)
     * @param gapRatio 缺口占周长的比例
     */
    private fun triangleStroke(
        startT: Float = 0f,
        gapRatio: Float = 0f,
        jitter: Float = 0.6f,
        seed: Long = 7L,
        samples: Int = 90,
        size: Float = 200f,
    ): List<Point2D> {
        val k = size / 200f
        val corners = listOf(
            Point2D(100f, 100f),
            Point2D(100f + 200f * k, 100f),
            Point2D(100f + 100f * k, 100f + 180f * k),
        )
        val lens = listOf(
            corners[0].distanceTo(corners[1]),
            corners[1].distanceTo(corners[2]),
            corners[2].distanceTo(corners[0]),
        )
        val perim = lens.sum()
        val rnd = Random(seed)
        val start = startT * perim
        val span = perim * (1f - gapRatio)
        return (0 until samples).map { i ->
            var rem = (start + span * i / (samples - 1).toFloat()) % perim
            var idx = 0
            while (idx < 3 && rem > lens[idx]) {
                rem -= lens[idx]
                idx++
            }
            if (idx >= 3) idx = 2
            val p0 = corners[idx]
            val p1 = corners[(idx + 1) % 3]
            val t = if (lens[idx] <= 0f) 0f else (rem / lens[idx]).coerceIn(0f, 1f)
            Point2D(
                p0.x + (p1.x - p0.x) * t + (rnd.nextFloat() * 2f - 1f) * jitter,
                p0.y + (p1.y - p0.y) * t + (rnd.nextFloat() * 2f - 1f) * jitter,
            )
        }
    }

    @Test
    fun `detects triangles regardless of where the stroke starts`() {
        // 回归: 早期实现只有从角点起笔才识别, 从边中间起笔会被数成四边形
        // (环起点落在边上时简化结果多一个顶点, 且该顶点永远去不掉)
        for (startT in listOf(0f, 0.1f, 0.2f, 0.33f, 0.5f, 0.66f, 0.8f, 0.9f)) {
            val res = ShapeSnapDetector.detect(triangleStroke(startT = startT, seed = 7L))
            assertNotNull("起笔位置 $startT 处应能识别", res)
            assertEquals(
                "起笔位置 $startT 处应识别为三角形, 实际 ${res!!.type}",
                QuickShapeType.TRIANGLE,
                res.type,
            )
        }
    }

    @Test
    fun `detects triangles across realistic sizes and jitter`() {
        // 回归: 早期"直边门"用 ε/短半轴 归一化, 小形状被手抖误杀
        // (100px 通过率仅 16%, 700px 有 46%)
        for (size in listOf(100f, 300f, 700f)) {
            for (jitter in listOf(0.3f, 0.8f, 1.5f)) {
                val res = ShapeSnapDetector.detect(
                    triangleStroke(jitter = jitter, seed = 11L, size = size)
                )
                assertNotNull("尺寸 $size / 抖动 $jitter 应能识别", res)
                assertEquals(
                    "尺寸 $size / 抖动 $jitter 应识别为三角形, 实际 ${res!!.type}",
                    QuickShapeType.TRIANGLE,
                    res.type,
                )
            }
        }
    }

    @Test
    fun `detects triangles with a realistic gap`() {
        // 手绘三角形不会精确闭合; 缺口 <=15% 属于常态, 必须能识别
        for (gap in listOf(0f, 0.05f, 0.1f, 0.15f)) {
            for (startT in listOf(0f, 0.5f)) {
                val res = ShapeSnapDetector.detect(
                    triangleStroke(startT = startT, gapRatio = gap, seed = 13L)
                )
                assertNotNull("缺口 $gap / 起笔 $startT 应能识别", res)
                assertEquals(
                    "缺口 $gap / 起笔 $startT 应识别为三角形, 实际 ${res!!.type}",
                    QuickShapeType.TRIANGLE,
                    res.type,
                )
            }
        }
    }

    @Test
    fun `detects triangles across sample counts`() {
        // 采样密度不该影响识别 (慢速运笔点数多, 快速运笔点数少)
        for (n in listOf(40, 90, 200)) {
            val res = ShapeSnapDetector.detect(triangleStroke(seed = 17L, samples = n))
            assertNotNull("采样数 $n 应能识别", res)
            assertEquals("采样数 $n 应识别为三角形", QuickShapeType.TRIANGLE, res!!.type)
        }
    }

    // ---- 不吸附 (关键回归: 旧实现末尾无条件回退直线) ----

    @Test
    fun `scribble is not snapped`() {
        val rnd = Random(21L)
        val pts = (0 until 120).map {
            Point2D(100f + rnd.nextFloat() * 300f, 100f + rnd.nextFloat() * 300f)
        }
        val res = ShapeSnapDetector.detect(pts)
        assertTrue("随机涂鸦不应被吸附, 实际 ${res?.type}", res == null)
    }

    @Test
    fun `wobbly closed loop is accepted as a round shape`() {
        // 半径小幅波动的闭合曲线 (90~160, 即 ±28%): 椭圆候选是外接框内切椭圆，没有圆度门。
        val rnd = Random(22L)
        val radii = (0 until 40).map { 90f + rnd.nextFloat() * 70f }
        val pts = (0 until 40).map { i ->
            val a = i / 40f * 2f * PI.toFloat()
            Point2D(200f + radii[i] * cos(a), 200f + radii[i] * sin(a))
        }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull("波动闭合曲线应被吸成圆/椭圆", res)
        assertTrue(
            "应为圆或椭圆, 实际 ${res!!.type}",
            res.type == QuickShapeType.CIRCLE || res.type == QuickShapeType.ELLIPSE,
        )
    }

    // ---- 退化输入 ----

    @Test
    fun `too few points returns null`() {
        assertTrue(ShapeSnapDetector.detect(emptyList()) == null)
        assertTrue(ShapeSnapDetector.detect(listOf(Point2D(1f, 1f))) == null)
        assertTrue(ShapeSnapDetector.detect(listOf(Point2D(1f, 1f), Point2D(2f, 2f))) == null)
    }

    @Test
    fun `coincident points return null`() {
        val pts = List(30) { Point2D(50f, 50f) }
        assertTrue("重合点不应被吸附", ShapeSnapDetector.detect(pts) == null)
    }

    @Test
    fun `non finite points return null`() {
        val pts = listOf(Point2D(0f, 0f), Point2D(Float.NaN, 10f), Point2D(10f, 10f), Point2D(20f, 0f))
        assertTrue(ShapeSnapDetector.detect(pts) == null)
    }

    @Test
    fun `detected shapes report a score below the hit threshold`() {
        val pts = stroke(60, 0.4f, 31L) { t -> (50f + t * 300f) to (80f + t * 120f) }
        val match = ShapeSnapDetector.detectDetailed(pts)
        assertNotNull(match)
        assertTrue("命中的分数必须 < 1.0, 实际 ${match!!.score}", match.score < 1f)
    }

    @Test
    fun `line early exit handles a very thin stroke`() {
        // 极细长 (长宽比 > 20:1) 走早退分支
        val pts = stroke(60, 0.2f, 41L) { t -> (10f + t * 800f) to (300f + t * 6f) }
        val res = ShapeSnapDetector.detect(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
    }
}
