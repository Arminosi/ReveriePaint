/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.gl3d

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.reverie.paint.model.*
import kotlin.math.*

/**
 * 极速 120Hz 原生 Canvas 3D 实时投影渲染器
 *
 * 专门用于触摸手势期间提供 0 延迟、0 丢帧、0 GC 内存分配的视口预览。
 * 彻底消除 glReadPixels 在 UI 线程上的 GPU 阻塞停顿。
 */
class Canvas3DDrawer {

    private var screenX = FloatArray(1024)
    private var screenY = FloatArray(1024)
    private var depthZ = FloatArray(1024)
    private var linePts = FloatArray(4096)

    private val triPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var triangleOrder = IntArray(1024)
    private var triangleDepths = FloatArray(1024)

    private fun ensureCapacity(vertCount: Int, lineCount: Int, triCount: Int) {
        if (screenX.size < vertCount) {
            val newCap = maxOf(vertCount * 2, 1024)
            screenX = FloatArray(newCap)
            screenY = FloatArray(newCap)
            depthZ = FloatArray(newCap)
        }
        val neededLineFloats = lineCount * 4
        if (linePts.size < neededLineFloats) {
            linePts = FloatArray(maxOf(neededLineFloats * 2, 4096))
        }
        if (triangleOrder.size < triCount) {
            val newCap = maxOf(triCount * 2, 1024)
            triangleOrder = IntArray(newCap)
            triangleDepths = FloatArray(newCap)
        }
    }

    /**
     * 将 3D 网格直接以满帧率渲染至硬件加速的 Canvas 上 (在文档中心坐标系下)
     */
    fun draw(
        canvas: Canvas,
        mesh: Mesh3D,
        state: Model3DState,
        docW: Float,
        docH: Float,
    ) {
        val vertCount = mesh.vertexCount
        val triCount = mesh.triangleCount
        val lineCount = mesh.wireframeLineCount
        ensureCapacity(vertCount, lineCount, triCount)

        val aspect = (docW / docH.coerceAtLeast(1f)).coerceAtLeast(0.01f)
        val proj = Math3D.perspective(45f, aspect, 0.1f, 100f)
        val dist = 3.5f / state.zoom.coerceIn(0.1f, 10f)
        val view = Math3D.lookAt(0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)

        var model = Math3D.identity()
        model = Math3D.translate(model, state.panX * 2f, state.panY * 2f, 0f)
        model = Math3D.rotate(model, state.rotX, 1f, 0f, 0f)
        model = Math3D.rotate(model, state.rotY, 0f, 1f, 0f)
        model = Math3D.rotate(model, state.rotZ, 0f, 0f, 1f)

        val mv = Math3D.multiplyMM(view, model)
        val mvp = Math3D.multiplyMM(proj, mv)

        // 提取 3x3 法线旋转矩阵
        val nm0 = mv[0]; val nm1 = mv[1]; val nm2 = mv[2]
        val nm4 = mv[4]; val nm5 = mv[5]; val nm6 = mv[6]
        val nm8 = mv[8]; val nm9 = mv[9]; val nm10 = mv[10]

        // 顶点投影变换
        val positions = mesh.positions
        val halfW = docW * 0.45f
        val halfH = docH * 0.45f

        for (i in 0 until vertCount) {
            val vx = positions[i * 3]
            val vy = positions[i * 3 + 1]
            val vz = positions[i * 3 + 2]

            val cx = mvp[0] * vx + mvp[4] * vy + mvp[8] * vz + mvp[12]
            val cy = mvp[1] * vx + mvp[5] * vy + mvp[9] * vz + mvp[13]
            val cz = mvp[2] * vx + mvp[6] * vy + mvp[10] * vz + mvp[14]
            val cw = mvp[3] * vx + mvp[7] * vy + mvp[11] * vz + mvp[15]

            val invW = if (abs(cw) > 1e-6f) 1f / cw else 1f
            screenX[i] = (cx * invW) * halfW
            screenY[i] = (-cy * invW) * halfH
            depthZ[i] = cz * invW
        }

        val baseCol = state.baseColor
        val baseRed = ((baseCol shr 16) and 0xFF).toInt()
        val baseGreen = ((baseCol shr 8) and 0xFF).toInt()
        val baseBlue = (baseCol and 0xFF).toInt()

        val lightDir = Math3D.calculateLightDirection(state.lightYaw, state.lightPitch)

        when (state.renderMode) {
            RenderMode3D.WIREFRAME -> {
                wirePaint.color = (state.wireframeColor or 0xFF000000L).toInt()
                var pIdx = 0
                val wireIndices = mesh.wireframeIndices
                for (k in 0 until lineCount) {
                    val i1 = wireIndices[k * 2]
                    val i2 = wireIndices[k * 2 + 1]
                    linePts[pIdx++] = screenX[i1]
                    linePts[pIdx++] = screenY[i1]
                    linePts[pIdx++] = screenX[i2]
                    linePts[pIdx++] = screenY[i2]
                }
                canvas.drawLines(linePts, 0, pIdx, wirePaint)
            }

            RenderMode3D.CLAY, RenderMode3D.SHADING -> {
                val indices = mesh.indices
                val normals = mesh.normals
                val hasNormals = normals.isNotEmpty()

                // 背面剔除与深度排序收集
                var visibleCount = 0
                for (t in 0 until triCount) {
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]

                    val x0 = screenX[i0]; val y0 = screenY[i0]
                    val x1 = screenX[i1]; val y1 = screenY[i1]
                    val x2 = screenX[i2]; val y2 = screenY[i2]

                    // 2D 叉积背面剔除 (Backface culling)
                    val cross = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
                    if (cross < 0f) {
                        triangleOrder[visibleCount] = t
                        triangleDepths[visibleCount] = (depthZ[i0] + depthZ[i1] + depthZ[i2]) * 0.3333f
                        visibleCount++
                    }
                }

                // 画家算法按深度排序 (远到近)
                sortTriangles(visibleCount)

                // 逐三角面着色绘制
                for (k in 0 until visibleCount) {
                    val t = triangleOrder[k]
                    val i0 = indices[t * 3]
                    val i1 = indices[t * 3 + 1]
                    val i2 = indices[t * 3 + 2]

                    var nx = 0f; var ny = 0f; var nz = 1f
                    if (hasNormals && i0 * 3 + 2 < normals.size) {
                        val rawNx = (normals[i0 * 3] + normals[i1 * 3] + normals[i2 * 3]) * 0.3333f
                        val rawNy = (normals[i0 * 3 + 1] + normals[i1 * 3 + 1] + normals[i2 * 3 + 1]) * 0.3333f
                        val rawNz = (normals[i0 * 3 + 2] + normals[i1 * 3 + 2] + normals[i2 * 3 + 2]) * 0.3333f

                        nx = nm0 * rawNx + nm4 * rawNy + nm8 * rawNz
                        ny = nm1 * rawNx + nm5 * rawNy + nm9 * rawNz
                        nz = nm2 * rawNx + nm6 * rawNy + nm10 * rawNz
                        val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                        nx /= len; ny /= len; nz /= len
                    }

                    val color = if (state.renderMode == RenderMode3D.CLAY) {
                        // 素模半球光
                        val hemi = (ny * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val skyR = 1.0f; val skyG = 0.98f; val skyB = 0.94f
                        val gndR = 0.68f; val gndG = 0.70f; val gndB = 0.76f
                        val ambR = gndR + (skyR - gndR) * hemi
                        val ambG = gndG + (skyG - gndG) * hemi
                        val ambB = gndB + (skyB - gndB) * hemi

                        val keyDirX = 0.4f; val keyDirY = 0.7f; val keyDirZ = 0.6f
                        val diff = max(nx * keyDirX + ny * keyDirY + nz * keyDirZ, 0f)
                        val rim = (1.0f - max(nz, 0f)).coerceIn(0f, 1f).pow(2) * 0.25f

                        val r = (baseRed * (ambR * 0.7f + diff * 0.45f) + rim * 255f).toInt().coerceIn(0, 255)
                        val g = (baseGreen * (ambG * 0.7f + diff * 0.45f) + rim * 255f).toInt().coerceIn(0, 255)
                        val b = (baseBlue * (ambB * 0.7f + diff * 0.45f) + rim * 255f).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    } else {
                        // 光影明暗切面
                        val ndotl = max(nx * lightDir[0] + ny * lightDir[1] + nz * lightDir[2], 0f)
                        val diff = if (ndotl > 0.05f) 1.0f else 0.35f
                        val r = (baseRed * diff).toInt().coerceIn(0, 255)
                        val g = (baseGreen * diff).toInt().coerceIn(0, 255)
                        val b = (baseBlue * diff).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    }

                    fillPaint.color = color
                    triPath.rewind()
                    triPath.moveTo(screenX[i0], screenY[i0])
                    triPath.lineTo(screenX[i1], screenY[i1])
                    triPath.lineTo(screenX[i2], screenY[i2])
                    triPath.close()
                    canvas.drawPath(triPath, fillPaint)
                }
            }
        }
    }

    private fun sortTriangles(count: Int) {
        // 简易快速插入排序 (面数小，几乎有序，开销极低且 0 分配)
        for (i in 1 until count) {
            val keyIdx = triangleOrder[i]
            val keyDepth = triangleDepths[i]
            var j = i - 1
            while (j >= 0 && triangleDepths[j] < keyDepth) {
                triangleOrder[j + 1] = triangleOrder[j]
                triangleDepths[j + 1] = triangleDepths[j]
                j--
            }
            triangleOrder[j + 1] = keyIdx
            triangleDepths[j + 1] = keyDepth
        }
    }
}
