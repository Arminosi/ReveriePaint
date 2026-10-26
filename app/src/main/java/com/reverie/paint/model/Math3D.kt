/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.*

/**
 * 纯 Kotlin 3D 几何与 4x4 矩阵运算工具 (列主序 Column-Major, 与 OpenGL ES 标准一致)
 */
object Math3D {

    /**
     * 创建 4x4 单位矩阵
     */
    fun identity(): FloatArray {
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /**
     * 4x4 矩阵相乘: out = lhs * rhs
     */
    fun multiplyMM(lhs: FloatArray, rhs: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (i in 0..3) {
            val r0 = lhs[i]
            val r1 = lhs[i + 4]
            val r2 = lhs[i + 8]
            val r3 = lhs[i + 12]

            out[i] = r0 * rhs[0] + r1 * rhs[1] + r2 * rhs[2] + r3 * rhs[3]
            out[i + 4] = r0 * rhs[4] + r1 * rhs[5] + r2 * rhs[6] + r3 * rhs[7]
            out[i + 8] = r0 * rhs[8] + r1 * rhs[9] + r2 * rhs[10] + r3 * rhs[11]
            out[i + 12] = r0 * rhs[12] + r1 * rhs[13] + r2 * rhs[14] + r3 * rhs[15]
        }
        return out
    }

    /**
     * 4x4 矩阵相乘并写入调用方提供的缓冲: out[outOff..] = a[aOff..] * b[bOff..]
     *
     * 与 [multiplyMM] 结果完全一致, 但零分配 —— 骨骼 FK 每帧要调用 (2 * 关节数) 次,
     * 热路径禁止产生临时矩阵 (见 AGENTS.md §4)。
     */
    fun multiplyMMInto(
        out: FloatArray,
        outOff: Int,
        a: FloatArray,
        aOff: Int,
        b: FloatArray,
        bOff: Int,
    ) {
        for (i in 0..3) {
            val r0 = a[aOff + i]
            val r1 = a[aOff + i + 4]
            val r2 = a[aOff + i + 8]
            val r3 = a[aOff + i + 12]

            out[outOff + i] = r0 * b[bOff] + r1 * b[bOff + 1] + r2 * b[bOff + 2] + r3 * b[bOff + 3]
            out[outOff + i + 4] = r0 * b[bOff + 4] + r1 * b[bOff + 5] + r2 * b[bOff + 6] + r3 * b[bOff + 7]
            out[outOff + i + 8] = r0 * b[bOff + 8] + r1 * b[bOff + 9] + r2 * b[bOff + 10] + r3 * b[bOff + 11]
            out[outOff + i + 12] = r0 * b[bOff + 12] + r1 * b[bOff + 13] + r2 * b[bOff + 14] + r3 * b[bOff + 15]
        }
    }

    /**
     * 绕任意轴旋转矩阵写入 out (列主序, 零分配)
     */
    fun rotationMatrixInto(
        out: FloatArray,
        outOff: Int,
        angleDegrees: Float,
        x: Float,
        y: Float,
        z: Float,
    ) {
        val rad = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val len = hypot(hypot(x, y), z).coerceAtLeast(1e-6f)
        val invLen = 1f / len
        val nx = x * invLen
        val ny = y * invLen
        val nz = z * invLen
        val nc = 1.0f - c

        out[outOff] = nx * nx * nc + c
        out[outOff + 1] = ny * nx * nc + nz * s
        out[outOff + 2] = nz * nx * nc - ny * s
        out[outOff + 3] = 0f

        out[outOff + 4] = nx * ny * nc - nz * s
        out[outOff + 5] = ny * ny * nc + c
        out[outOff + 6] = nz * ny * nc + nx * s
        out[outOff + 7] = 0f

        out[outOff + 8] = nx * nz * nc + ny * s
        out[outOff + 9] = ny * nz * nc - nx * s
        out[outOff + 10] = nz * nz * nc + c
        out[outOff + 11] = 0f

        out[outOff + 12] = 0f
        out[outOff + 13] = 0f
        out[outOff + 14] = 0f
        out[outOff + 15] = 1f
    }

    /**
     * 透视投影矩阵
     * @param fovyDegrees 垂直视场角
     * @param aspect 宽高比 (width / height)
     * @param zNear 近裁剪面
     * @param zFar 远裁剪面
     */
    fun perspective(fovyDegrees: Float, aspect: Float, zNear: Float, zFar: Float): FloatArray {
        val m = FloatArray(16)
        val f = 1.0f / tan(Math.toRadians(fovyDegrees.toDouble()) / 2.0).toFloat()
        val rangeReciprocal = 1.0f / (zNear - zFar)

        m[0] = f / aspect
        m[1] = 0f
        m[2] = 0f
        m[3] = 0f

        m[4] = 0f
        m[5] = f
        m[6] = 0f
        m[7] = 0f

        m[8] = 0f
        m[9] = 0f
        m[10] = (zFar + zNear) * rangeReciprocal
        m[11] = -1.0f

        m[12] = 0f
        m[13] = 0f
        m[14] = 2.0f * zFar * zNear * rangeReciprocal
        m[15] = 0f

        return m
    }

    /**
     * 正交投影矩阵
     */
    fun ortho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): FloatArray {
        val m = FloatArray(16)
        val rWidth = 1.0f / (right - left)
        val rHeight = 1.0f / (top - bottom)
        val rDepth = 1.0f / (far - near)

        m[0] = 2.0f * rWidth
        m[5] = 2.0f * rHeight
        m[10] = -2.0f * rDepth
        m[12] = -(right + left) * rWidth
        m[13] = -(top + bottom) * rHeight
        m[14] = -(far + near) * rDepth
        m[15] = 1.0f

        return m
    }

    /**
     * 相机 LookAt 视图矩阵
     */
    fun lookAt(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float
    ): FloatArray {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        val rlf = 1.0f / hypot(hypot(fx, fy), fz).coerceAtLeast(1e-6f)
        fx *= rlf
        fy *= rlf
        fz *= rlf

        // compute s = f x up
        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val rls = 1.0f / hypot(hypot(sx, sy), sz).coerceAtLeast(1e-6f)
        sx *= rls
        sy *= rls
        sz *= rls

        // compute u = s x f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        val rm = FloatArray(16)
        rm[0] = sx
        rm[1] = ux
        rm[2] = -fx
        rm[3] = 0f

        rm[4] = sy
        rm[5] = uy
        rm[6] = -fy
        rm[7] = 0f

        rm[8] = sz
        rm[9] = uz
        rm[10] = -fz
        rm[11] = 0f

        rm[12] = 0f
        rm[13] = 0f
        rm[14] = 0f
        rm[15] = 1f

        return translate(rm, -eyeX, -eyeY, -eyeZ)
    }

    /**
     * 平移变换
     */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = m.copyOf()
        for (i in 0..3) {
            out[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        }
        return out
    }

    /**
     * 缩放变换
     */
    fun scale(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(16)
        for (i in 0..3) {
            out[i] = m[i] * x
            out[4 + i] = m[4 + i] * y
            out[8 + i] = m[8 + i] * z
            out[12 + i] = m[12 + i]
        }
        return out
    }

    /**
     * 绕任意轴旋转 (角度为角度制)
     */
    fun rotate(m: FloatArray, angleDegrees: Float, x: Float, y: Float, z: Float): FloatArray {
        val rad = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val len = hypot(hypot(x, y), z)
        if (len < 1e-6f) return m.copyOf()
        val invLen = 1f / len
        val nx = x * invLen
        val ny = y * invLen
        val nz = z * invLen
        val nc = 1.0f - c

        val rot = FloatArray(16)
        rot[0] = nx * nx * nc + c
        rot[1] = ny * nx * nc + nz * s
        rot[2] = nz * nx * nc - ny * s
        rot[3] = 0f

        rot[4] = nx * ny * nc - nz * s
        rot[5] = ny * ny * nc + c
        rot[6] = nz * ny * nc + nx * s
        rot[7] = 0f

        rot[8] = nx * nz * nc + ny * s
        rot[9] = ny * nz * nc - nx * s
        rot[10] = nz * nz * nc + c
        rot[11] = 0f

        rot[12] = 0f
        rot[13] = 0f
        rot[14] = 0f
        rot[15] = 1f

        return multiplyMM(m, rot)
    }

    /**
     * 根据水平航向角 (Yaw) 与俯仰角 (Pitch) 计算标准化定向光单位向量 [x, y, z]
     */
    fun calculateLightDirection(yawDegrees: Float, pitchDegrees: Float): FloatArray {
        val yawRad = Math.toRadians(yawDegrees.toDouble())
        val pitchRad = Math.toRadians(pitchDegrees.toDouble())
        val cosPitch = cos(pitchRad).toFloat()
        val x = (sin(yawRad) * cosPitch).toFloat()
        val y = sin(pitchRad).toFloat()
        val z = (cos(yawRad) * cosPitch).toFloat() * 0.7f + 0.35f

        val len = hypot(hypot(x, y), z).coerceAtLeast(1e-6f)
        return floatArrayOf(x / len, y / len, z / len)
    }
}
