/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class Point3D(val x: Float, val y: Float, val z: Float)
data class ProjectedPoint3D(val x: Float, val y: Float, val camZ: Float)

/**
 * 3D 空间透视投影计算器 (纯 Kotlin 逻辑，供参考线绘制与手势交互复用)
 */
object Perspective3DMath {

    /**
     * 将 3D 世界坐标投影至以画布中心为原点 (0, 0) 的 2D 坐标并携带相机深度 camZ
     */
    fun projectWithDepth(
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float = 0f,
        fovDeg: Float,
        distance: Float,
        canvasW: Float,
        canvasH: Float,
    ): ProjectedPoint3D? {
        if (canvasW <= 0f || canvasH <= 0f) return null

        val yawRad = (-yawDeg * (PI / 180.0)).toFloat()
        val pitchRad = (-pitchDeg * (PI / 180.0)).toFloat()
        val fovRad = (fovDeg.coerceIn(15f, 120f) * (PI / 180.0)).toFloat()

        // 1. 绕世界 Y 轴旋转 (Yaw)
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)
        val x1 = x * cosY - z * sinY
        val z1 = x * sinY + z * cosY

        // 2. 绕世界 X 轴旋转 (Pitch)
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        val y2 = y * cosP - z1 * sinP
        val z2 = y * sinP + z1 * cosP

        // 3. 视距偏移与近裁剪面
        val camZ = z2 + distance
        if (camZ <= 0.05f) return null

        // 4. 透视除法与画布居中投影
        val aspect = canvasW / maxOf(1f, canvasH)
        val f = 1.0f / tan(fovRad / 2f)

        var u = (x1 * f / (camZ * aspect)) * (canvasW / 2f)
        var v = (-y2 * f / camZ) * (canvasH / 2f)

        // 5. 绕视轴 (Z 轴) 旋转倾斜画面 (Roll)
        if (kotlin.math.abs(rollDeg) > 0.001f) {
            val rollRad = (rollDeg * (PI / 180.0)).toFloat()
            val cosR = cos(rollRad)
            val sinR = sin(rollRad)
            val ru = u * cosR - v * sinR
            val rv = u * sinR + v * cosR
            u = ru
            v = rv
        }

        if (!u.isFinite() || !v.isFinite()) return null
        return ProjectedPoint3D(u, v, camZ)
    }

    /**
     * 将 3D 世界坐标投影至以画布中心为原点 (0, 0) 的 2D 坐标
     */
    fun project(
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float = 0f,
        fovDeg: Float,
        distance: Float,
        canvasW: Float,
        canvasH: Float,
    ): Point2D? {
        val res = projectWithDepth(x, y, z, yawDeg, pitchDeg, rollDeg, fovDeg, distance, canvasW, canvasH) ?: return null
        return Point2D(res.x, res.y)
    }

    /**
     * 计算视平线在画布局部坐标系中的垂直位置 (Y 轴偏置)
     * 平视 (pitch=0) 时视平线正好穿过画布中心 Y=0
     */
    fun computeHorizonY(pitchDeg: Float, fovDeg: Float, canvasH: Float): Float? {
        if (canvasH <= 0f) return null
        val pitchRad = (-pitchDeg * (PI / 180.0)).toFloat()
        val fovRad = (fovDeg.coerceIn(15f, 120f) * (PI / 180.0)).toFloat()

        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)
        if (cosP <= 0.001f) return null // 垂直朝天或朝地时视平线在无穷远

        val f = 1.0f / tan(fovRad / 2f)
        val v = (-sinP * f / cosP) * (canvasH / 2f)
        return if (v.isFinite()) v else null
    }
}
