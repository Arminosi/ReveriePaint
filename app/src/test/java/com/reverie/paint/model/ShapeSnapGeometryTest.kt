/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 形状识别几何原语的单元测试 */
class ShapeSnapGeometryTest {

    @Test
    fun `rdp keeps corners of an L shaped polyline`() {
        // L 形: 沿 X 走 30 点, 再沿 Y 走 30 点
        val pts = (0..30).map { Point2D(it * 10f, 0f) } + (1..30).map { Point2D(300f, it * 10f) }
        val simplified = ShapeSnapGeometry.rdp(pts, 0.2f)
        assertTrue("L 形应简化为 3~4 个顶点, 实际 ${simplified.size}", simplified.size in 3..4)
        // 拐角必须保留
        assertTrue("拐点 (300,0) 必须保留", simplified.any { it.x == 300f && it.y == 0f })
    }

    @Test
    fun `rdp of two points returns both`() {
        val pts = listOf(Point2D(0f, 0f), Point2D(10f, 10f))
        assertEquals(pts, ShapeSnapGeometry.rdp(pts, 1f))
    }

    @Test
    fun `graham scan of a square with an interior point returns 4 vertices`() {
        val pts = listOf(
            Point2D(0f, 0f), Point2D(100f, 0f), Point2D(100f, 100f), Point2D(0f, 100f),
            Point2D(50f, 50f), // 内部点, 不应进凸包
        )
        val hull = ShapeSnapGeometry.grahamScan(pts)
        assertEquals(4, hull.size)
        assertTrue("内部点不应出现在凸包", hull.none { it.x == 50f && it.y == 50f })
    }

    @Test
    fun `tightest frame of an axis aligned 4x2 rectangle is 4 by 2`() {
        val pts = listOf(
            Point2D(0f, 0f), Point2D(4f, 0f), Point2D(4f, 2f), Point2D(0f, 2f),
        )
        val frame = ShapeSnapGeometry.tightestFrame(pts)
        assertTrue("外接框应有效", frame.isValid)
        assertEquals("半宽应为 2", 2f, frame.halfWidth, 0.01f)
        assertEquals("半高应为 1", 1f, frame.halfHeight, 0.01f)
        assertEquals("中心 X", 2f, frame.center.x, 0.01f)
        assertEquals("中心 Y", 1f, frame.center.y, 0.01f)
    }

    @Test
    fun `tightest frame of a rotated rectangle recovers its angle`() {
        val ang = 30f * PI.toFloat() / 180f
        val cosA = cos(ang)
        val sinA = sin(ang)
        // 长 200 宽 60 的矩形, 绕原点旋转 30°
        val local = listOf(
            Point2D(-100f, -30f), Point2D(100f, -30f), Point2D(100f, 30f), Point2D(-100f, 30f),
        )
        val pts = local.map { Point2D(it.x * cosA - it.y * sinA, it.x * sinA + it.y * cosA) }
        val frame = ShapeSnapGeometry.tightestFrame(pts)
        assertTrue(frame.isValid)
        assertEquals("长半轴应为 100", 100f, frame.halfWidth, 0.5f)
        assertEquals("短半轴应为 30", 30f, frame.halfHeight, 0.5f)
        // 角度指向长轴; 30° 与 210° 等价
        val deg = frame.angleRad * 180f / PI.toFloat()
        val normalized = ((deg % 180f) + 180f) % 180f
        assertTrue("角度应约为 30°, 实际 $normalized", normalized in 25f..35f)
    }

    @Test
    fun `unit box mapping round trips`() {
        val frame = RotatedFrame(Point2D(100f, 50f), 40f, 20f, 0.4f)
        val p = Point2D(123f, 61f)
        val back = frame.fromUnitBox(frame.toUnitBox(p))
        assertEquals(p.x, back.x, 0.01f)
        assertEquals(p.y, back.y, 0.01f)
    }

    @Test
    fun `unit box maps frame corners to the unit square`() {
        val frame = RotatedFrame(Point2D(10f, 20f), 30f, 15f, 0.7f)
        val corners = frame.corners()
        val mapped = corners.map { frame.toUnitBox(it) }
        // 四角应落在 [0,1] 的角上
        for (m in mapped) {
            assertTrue("映射后应在单位框内: ($m.x, $m.y)", m.x in -0.01f..1.01f && m.y in -0.01f..1.01f)
        }
        val xs = mapped.map { it.x }
        val ys = mapped.map { it.y }
        assertEquals(0f, xs.min(), 0.01f)
        assertEquals(1f, xs.max(), 0.01f)
        assertEquals(0f, ys.min(), 0.01f)
        assertEquals(1f, ys.max(), 0.01f)
    }

    @Test
    fun `point to segment distance clamps beyond the endpoints`() {
        val a = Point2D(0f, 0f)
        val b = Point2D(10f, 0f)
        // 在线段之外, 应退化为到最近端点距离
        assertEquals(5f, ShapeSnapGeometry.perpendicularDistance(Point2D(15f, 0f), a, b), 0.01f)
        assertEquals(5f, ShapeSnapGeometry.perpendicularDistance(Point2D(-5f, 0f), a, b), 0.01f)
        // 线段之上, 垂距为 0
        assertEquals(0f, ShapeSnapGeometry.perpendicularDistance(Point2D(5f, 0f), a, b), 0.01f)
        // 垂距
        assertEquals(3f, ShapeSnapGeometry.perpendicularDistance(Point2D(5f, 3f), a, b), 0.01f)
    }

    @Test
    fun `point to polyline distance handles the closing segment when closed`() {
        val square = listOf(
            Point2D(0f, 0f), Point2D(10f, 0f), Point2D(10f, 10f), Point2D(0f, 10f),
        )
        // 点靠近左边 (0,5): 只有把末点→首点那段算进去, 距离才接近 0
        val openDist = ShapeSnapGeometry.pointToPolylineDistance(Point2D(-0.5f, 5f), square, closed = false)
        val closedDist = ShapeSnapGeometry.pointToPolylineDistance(Point2D(-0.5f, 5f), square, closed = true)
        assertEquals("闭合时距离应为 0.5", 0.5f, closedDist, 0.01f)
        assertTrue("开放时应明显更远, 实际 $openDist", openDist > closedDist)
    }

    @Test
    fun `sample polyline returns count points including both ends`() {
        val line = listOf(Point2D(0f, 0f), Point2D(100f, 0f))
        val samples = ShapeSnapGeometry.samplePolyline(line, 33, closed = false)
        assertEquals(33, samples.size)
        assertEquals(0f, samples.first().x, 0.01f)
        assertEquals(100f, samples.last().x, 0.01f)
        // 均匀: 相邻间距应为 100/32
        assertEquals(100f / 32f, samples[1].x - samples[0].x, 0.01f)
    }

    @Test
    fun `polyline length includes closing segment when closed`() {
        val square = listOf(
            Point2D(0f, 0f), Point2D(10f, 0f), Point2D(10f, 10f), Point2D(0f, 10f),
        )
        assertEquals(30f, ShapeSnapGeometry.polylineLength(square, closed = false), 0.01f)
        assertEquals(40f, ShapeSnapGeometry.polylineLength(square, closed = true), 0.01f)
    }

    @Test
    fun `collinear detection rejects a straight triple`() {
        assertTrue(ShapeSnapGeometry.isCollinear(Point2D(0f, 0f), Point2D(5f, 0f), Point2D(10f, 0f)))
        assertTrue(!ShapeSnapGeometry.isCollinear(Point2D(0f, 0f), Point2D(5f, 8f), Point2D(10f, 0f)))
    }

    @Test
    fun `centroid of a square is its centre`() {
        val pts = listOf(
            Point2D(0f, 0f), Point2D(10f, 0f), Point2D(10f, 10f), Point2D(0f, 10f),
        )
        val c = ShapeSnapGeometry.centroid(pts)
        assertEquals(5f, c.x, 0.01f)
        assertEquals(5f, c.y, 0.01f)
    }

    @Test
    fun `multi scale rdp reduces a dense circle to few vertices`() {
        val pts = (0 until 400).map { i ->
            val a = i / 400f * 2f * PI.toFloat()
            Point2D(200f + 100f * cos(a), 200f + 100f * sin(a))
        }
        val simplified = ShapeSnapGeometry.multiScaleRdp(pts)
        assertTrue("密集圆应被大幅简化, 实际 ${simplified.size}", simplified.size <= 64)
        assertTrue("但不该简化到丢失形状, 实际 ${simplified.size}", simplified.size >= 4)
    }
}
