/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 3D 网格的"线稿"提取 (纯 Kotlin, 不依赖 Android)
 *
 * 只输出**轮廓线 (silhouette) + 折角线 (crease)**, 并按正/背面剔除隐藏线:
 * - 轮廓线: 同一根边两侧三角形一正一背 (视线擦过表面处)
 * - 折角线: 两侧都朝向相机, 且两面夹角大于阈值 (体积转折, 如鼻梁、耳根)
 * - 边界边: 只有一个相邻面 (开放的薄片)
 *
 * 旧实现是把三角剖分的所有边都画出来 (7k 面的模型 ~10k 条边), 还会铺一层不透明白底,
 * 结果是"三角面网格 + 实心白模", 而不是线稿。
 */
object Outline3D {

    /**
     * @return 屏幕线段数组 [x1,y1,x2,y2, ...], 坐标原点在画布左上角 (0..docW, 0..docH)
     */
    fun extractScreenSegments(
        mesh: Mesh3D,
        state: Model3DState,
        docW: Float,
        docH: Float,
        creaseDegrees: Float = 32f,
    ): FloatArray {
        val triCount = mesh.triangleCount
        val vertCount = mesh.vertexCount
        if (triCount == 0 || vertCount == 0) return FloatArray(0)

        val mvp = buildMvp(state, docW, docH)
        val positions = mesh.positions
        val sx = FloatArray(vertCount)
        val sy = FloatArray(vertCount)
        val halfW = docW * 0.5f
        val halfH = docH * 0.5f

        for (i in 0 until vertCount) {
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
            val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
            val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
            val inv = if (abs(cw) > 1e-6f) 1f / cw else 0f
            // 直接偏移到画布坐标 (与渲染/视口一致的 0.5 半长口径)
            sx[i] = (cx * inv) * halfW + halfW
            sy[i] = (-cy * inv) * halfH + halfH
        }

        val indices = mesh.indices
        val front = BooleanArray(triCount)
        // 面法线 (仅用于折角判断)
        val nx = FloatArray(triCount)
        val ny = FloatArray(triCount)
        val nz = FloatArray(triCount)

        for (t in 0 until triCount) {
            val i0 = indices[t * 3]
            val i1 = indices[t * 3 + 1]
            val i2 = indices[t * 3 + 2]
            val area = (sx[i1] - sx[i0]) * (sy[i2] - sy[i0]) - (sy[i1] - sy[i0]) * (sx[i2] - sx[i0])
            front[t] = area < 0f

            val ax = positions[i1 * 3] - positions[i0 * 3]
            val ay = positions[i1 * 3 + 1] - positions[i0 * 3 + 1]
            val az = positions[i1 * 3 + 2] - positions[i0 * 3 + 2]
            val bx = positions[i2 * 3] - positions[i0 * 3]
            val by = positions[i2 * 3 + 1] - positions[i0 * 3 + 1]
            val bz = positions[i2 * 3 + 2] - positions[i0 * 3 + 2]
            var fx = ay * bz - az * by
            var fy = az * bx - ax * bz
            var fz = ax * by - ay * bx
            val fl = hypot(hypot(fx, fy), fz).coerceAtLeast(1e-9f)
            nx[t] = fx / fl; ny[t] = fy / fl; nz[t] = fz / fl
        }

        // 顶点按位置焊接: glTF/内置网格普遍是"每面独立顶点"(平面着色), 不焊接就没有相邻面,
        // 轮廓/折角判定会全部退化成边界边 (这也是旧线框看着像"三角网格"的原因之一)
        val canon = weldByPosition(positions, vertCount)

        // 边 -> 相邻三角形 (最多两个)
        val edgeTriA = HashMap<Long, Int>(triCount * 2)
        val edgeTriB = HashMap<Long, Int>(triCount)
        for (t in 0 until triCount) {
            val v0 = canon[indices[t * 3]]
            val v1 = canon[indices[t * 3 + 1]]
            val v2 = canon[indices[t * 3 + 2]]
            if (v0 == v1 || v1 == v2 || v2 == v0) continue
            link(edgeTriA, edgeTriB, edgeKey(v0, v1), t)
            link(edgeTriA, edgeTriB, edgeKey(v1, v2), t)
            link(edgeTriA, edgeTriB, edgeKey(v2, v0), t)
        }

        val cosCrease = kotlin.math.cos(Math.toRadians(creaseDegrees.toDouble())).toFloat()
        val out = ArrayList<Float>(edgeTriA.size * 2)
        for ((key, t0) in edgeTriA) {
            val t1 = edgeTriB[key] ?: -1
            // 隐藏线剔除: 至少一侧朝向相机才画
            if (t1 < 0) {
                if (!front[t0]) continue
            } else {
                val f0 = front[t0]
                val f1 = front[t1]
                if (f0 == f1) {
                    if (!f0) continue                       // 两侧都背面 -> 隐藏
                    val dot = nx[t0] * nx[t1] + ny[t0] * ny[t1] + nz[t0] * nz[t1]
                    if (dot > cosCrease) continue            // 近似共面 -> 不是折角, 跳过
                }
            }
            val a = (key ushr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            val x0 = sx[a]; val y0 = sy[a]
            val x1 = sx[b]; val y1 = sy[b]
            if (abs(x1 - x0) < 0.35f && abs(y1 - y0) < 0.35f) continue
            out.add(x0); out.add(y0); out.add(x1); out.add(y1)
        }
        val result = FloatArray(out.size)
        for (i in out.indices) result[i] = out[i]
        return result
    }

    /** 位置量化焊接: 1/8192 ≈ 1.2e-4, 对 unit 尺度模型足够区分且能合并重复顶点 */
    private fun weldByPosition(positions: FloatArray, vertCount: Int): IntArray {
        val map = HashMap<Long, Int>(vertCount * 2)
        val canon = IntArray(vertCount)
        var next = 0
        for (i in 0 until vertCount) {
            val qx = (Math.round(positions[i * 3] * 8192f).toLong() and 0x1FFFFF)
            val qy = (Math.round(positions[i * 3 + 1] * 8192f).toLong() and 0x1FFFFF)
            val qz = (Math.round(positions[i * 3 + 2] * 8192f).toLong() and 0x1FFFFF)
            val key = (qx shl 42) or (qy shl 21) or qz
            val hit = map[key]
            if (hit != null) {
                canon[i] = hit
            } else {
                map[key] = next
                canon[i] = next
                next++
            }
        }
        return canon
    }

    private fun link(a: HashMap<Long, Int>, b: HashMap<Long, Int>, key: Long, tri: Int) {
        if (a.putIfAbsent(key, tri) != null) b[key] = tri
    }

    private fun edgeKey(i: Int, j: Int): Long {
        val lo = if (i < j) i else j
        val hi = if (i < j) j else i
        return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
    }

    /** 与视口/离屏渲染一致的 MVP (45° 透视, 视距 3.5/zoom, T*Ry*Rx*Rz) */
    private fun buildMvp(state: Model3DState, docW: Float, docH: Float): FloatArray {
        val aspect = (docW / docH.coerceAtLeast(1f)).coerceAtLeast(0.01f)
        val proj = Math3D.perspective(45f, aspect, 0.1f, 100f)
        val dist = 3.5f / state.zoom.coerceIn(0.1f, 10f)
        val view = Math3D.lookAt(0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        var model = Math3D.identity()
        model = Math3D.translate(model, state.panX * 2f, state.panY * 2f, 0f)
        model = Math3D.rotate(model, state.rotY, 0f, 1f, 0f)
        model = Math3D.rotate(model, state.rotX, 1f, 0f, 0f)
        model = Math3D.rotate(model, state.rotZ, 0f, 0f, 1f)
        return Math3D.multiplyMM(proj, Math3D.multiplyMM(view, model))
    }
}
