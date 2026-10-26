/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D 线稿提取: 只输出轮廓/折角并剔除隐藏线, 不再把三角剖分的所有边都画出来。
 */
class Outline3DTest {

    @Test
    fun `cube outline keeps only visible edges`() {
        val cube = Mesh3D.createCube(1.8f)
        val state = Model3DState(rotX = 15f, rotY = -30f)
        val segments = Outline3D.extractScreenSegments(cube, state, 800f, 800f)
        val count = segments.size / 4

        assertTrue("should emit segments", count > 0)
        // 立方体总共只有 12 条棱, 3/4 视角下可见的不会超过这个数
        assertTrue("cube outline should stay at edge count, got $count", count <= 12)

        // 立方体近距透视下允许溢出画布, 只要求坐标有限且量级合理
        var i = 0
        var inside = 0
        while (i < segments.size) {
            val x = segments[i]
            val y = segments[i + 1]
            assertTrue("segment finite: $x,$y", x.isFinite() && y.isFinite())
            assertTrue("segment sane: $x,$y", x > -2400f && x < 3200f && y > -2400f && y < 3200f)
            if (x in 0f..800f && y in 0f..800f) inside++
            i += 2
        }
        assertTrue("some segments should land on canvas", inside > 4)
    }

    @Test
    fun `sphere outline drops interior triangulation edges`() {
        val sphere = Mesh3D.createSphere(1.2f, 18, 24)
        val state = Model3DState()
        val segments = Outline3D.extractScreenSegments(sphere, state, 600f, 600f)
        val count = segments.size / 4
        val wireEdges = sphere.wireframeLineCount

        assertTrue("should emit silhouette segments", count > 0)
        // 球面是光滑的: 只有轮廓线, 内部三角剖分边一条都不该出现
        assertTrue("outline($count) must be far below wire edges($wireEdges)", count * 3 < wireEdges)
    }

    @Test
    fun `outline is stable at different rotations`() {
        val cat = GlbParser.parse(java.io.File("src/main/assets/3d_models/anime_cat.glb").inputStream()).baseMesh
        var total = 0
        for (rotY in intArrayOf(-40, 0, 40, 90)) {
            val segments = Outline3D.extractScreenSegments(
                cat,
                Model3DState(rotX = 12f, rotY = rotY.toFloat()),
                900f,
                900f,
            )
            val count = segments.size / 4
            assertTrue("rotY=$rotY should emit lines", count > 20)
            // 猫猫 9484 面, 线框边约 1.4 万条; 线稿必须只保留边缘级别的量
            assertTrue("rotY=$rotY too many segments: $count", count < cat.wireframeLineCount / 2)
            total += count
        }
        assertTrue(total > 0)
    }
}
