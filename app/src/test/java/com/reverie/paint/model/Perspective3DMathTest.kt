/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Perspective3DMathTest {

    @Test
    fun `project origin at level view projects to canvas center`() {
        val pt = Perspective3DMath.project(
            x = 0f,
            y = 0f,
            z = 0f,
            yawDeg = 0f,
            pitchDeg = 0f,
            fovDeg = 60f,
            distance = 10f,
            canvasW = 1000f,
            canvasH = 1000f,
        )
        assertNotNull(pt)
        assertEquals(0f, pt!!.x, 0.001f)
        assertEquals(0f, pt.y, 0.001f)
    }

    @Test
    fun `project points behind camera returns null`() {
        val pt = Perspective3DMath.project(
            x = 0f,
            y = 0f,
            z = -20f,
            yawDeg = 0f,
            pitchDeg = 0f,
            fovDeg = 60f,
            distance = 5f,
            canvasW = 1000f,
            canvasH = 1000f,
        )
        assertNull(pt)
    }

    @Test
    fun `level view horizon is exactly at center`() {
        val horizonY = Perspective3DMath.computeHorizonY(
            pitchDeg = 0f,
            fovDeg = 60f,
            canvasH = 1000f,
        )
        assertNotNull(horizonY)
        assertEquals(0f, horizonY!!, 0.001f)
    }

    @Test
    fun `pitch down shifts horizon upward`() {
        // 俯视 (pitch = -30°) 视平线应该上移 (Y < 0 在 Canvas 局部坐标系)
        val horizonY = Perspective3DMath.computeHorizonY(
            pitchDeg = -30f,
            fovDeg = 60f,
            canvasH = 1000f,
        )
        assertNotNull(horizonY)
        assertTrue(horizonY!! < 0f)
    }

    @Test
    fun `symmetry along X axis holds when yaw is zero`() {
        val left = Perspective3DMath.project(
            x = -2f,
            y = 1f,
            z = 0f,
            yawDeg = 0f,
            pitchDeg = -15f,
            fovDeg = 60f,
            distance = 8f,
            canvasW = 1200f,
            canvasH = 800f,
        )
        val right = Perspective3DMath.project(
            x = 2f,
            y = 1f,
            z = 0f,
            yawDeg = 0f,
            pitchDeg = -15f,
            fovDeg = 60f,
            distance = 8f,
            canvasW = 1200f,
            canvasH = 800f,
        )
        assertNotNull(left)
        assertNotNull(right)
        assertEquals(left!!.x, -right!!.x, 0.001f)
        assertEquals(left.y, right.y, 0.001f)
    }

    @Test
    fun `roll 90 degrees rotates 2D projected coordinates by 90 degrees`() {
        // 当 pitch=0, yaw=0 时，点 (0, 2, 0) 在 roll=0 时投影在 (0, -v)
        val pBase = Perspective3DMath.project(
            x = 0f,
            y = 2f,
            z = 0f,
            yawDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            fovDeg = 60f,
            distance = 10f,
            canvasW = 1000f,
            canvasH = 1000f,
        )
        assertNotNull(pBase)
        assertEquals(0f, pBase!!.x, 0.001f)
        assertTrue(pBase.y < 0f)

        // roll=90° 时，原 (0, y) 旋转后应变为 (-y, 0)
        val pRoll = Perspective3DMath.project(
            x = 0f,
            y = 2f,
            z = 0f,
            yawDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 90f,
            fovDeg = 60f,
            distance = 10f,
            canvasW = 1000f,
            canvasH = 1000f,
        )
        assertNotNull(pRoll)
        assertEquals(0f, pRoll!!.y, 0.01f)
        assertEquals(pBase.y, -pRoll.x, 0.01f)
    }
}
