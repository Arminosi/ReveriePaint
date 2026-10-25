/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.io.BufferedReader
import kotlin.math.*

/**
 * 3D 几何网格数据 (顶点位置、法线、三角面索引与线框线段索引)
 */
class Mesh3D(
    val positions: FloatArray,        // [x0, y0, z0, x1, y1, z1, ...]
    val normals: FloatArray,          // [nx0, ny0, nz0, nx1, ny1, nz1, ...]
    val indices: IntArray,            // 三角形顶点索引
    val wireframeIndices: IntArray,   // 线框线段顶点对索引 [e0_a, e0_b, e1_a, e1_b, ...]
    val wireframeEdgeTriangles: IntArray = computeEdgeTriangles(indices, wireframeIndices),
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val wireframeLineCount: Int get() = wireframeIndices.size / 2

    val flatPositions: FloatArray by lazy {
        val triCount = indices.size / 3
        val result = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            val b = t * 9
            result[b] = positions[i0 * 3]; result[b + 1] = positions[i0 * 3 + 1]; result[b + 2] = positions[i0 * 3 + 2]
            result[b + 3] = positions[i1 * 3]; result[b + 4] = positions[i1 * 3 + 1]; result[b + 5] = positions[i1 * 3 + 2]
            result[b + 6] = positions[i2 * 3]; result[b + 7] = positions[i2 * 3 + 1]; result[b + 8] = positions[i2 * 3 + 2]
        }
        result
    }

    val flatNormals: FloatArray by lazy {
        val triCount = indices.size / 3
        val result = FloatArray(triCount * 9)
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            val b = t * 9
            val p0x = positions[i0 * 3]; val p0y = positions[i0 * 3 + 1]; val p0z = positions[i0 * 3 + 2]
            val p1x = positions[i1 * 3]; val p1y = positions[i1 * 3 + 1]; val p1z = positions[i1 * 3 + 2]
            val p2x = positions[i2 * 3]; val p2y = positions[i2 * 3 + 1]; val p2z = positions[i2 * 3 + 2]
            val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
            val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
            var fnx = e1y * e2z - e1z * e2y
            var fny = e1z * e2x - e1x * e2z
            var fnz = e1x * e2y - e1y * e2x
            val len = kotlin.math.hypot(kotlin.math.hypot(fnx, fny), fnz).coerceAtLeast(1e-6f)
            fnx /= len; fny /= len; fnz /= len
            result[b] = fnx; result[b + 1] = fny; result[b + 2] = fnz
            result[b + 3] = fnx; result[b + 4] = fny; result[b + 5] = fnz
            result[b + 6] = fnx; result[b + 7] = fny; result[b + 8] = fnz
        }
        result
    }

    companion object {

        /**
         * 预计算每条线框线段对应的邻接三角面索引 (最多关联 2 个三角面，用于正面边缘剔除)
         */
        fun computeEdgeTriangles(triangleIndices: IntArray, wireIndices: IntArray): IntArray {
            val lineCount = wireIndices.size / 2
            val result = IntArray(lineCount * 2) { -1 }
            val triCount = triangleIndices.size / 3

            for (k in 0 until lineCount) {
                val e0 = wireIndices[k * 2]
                val e1 = wireIndices[k * 2 + 1]
                var found = 0

                for (t in 0 until triCount) {
                    val a = triangleIndices[t * 3]
                    val b = triangleIndices[t * 3 + 1]
                    val c = triangleIndices[t * 3 + 2]

                    val hasE0 = (a == e0 || b == e0 || c == e0)
                    val hasE1 = (a == e1 || b == e1 || c == e1)
                    if (hasE0 && hasE1) {
                        if (found == 0) {
                            result[k * 2] = t
                            found = 1
                        } else {
                            result[k * 2 + 1] = t
                            break
                        }
                    }
                }
            }
            return result
        }

        /**
         * 提取三角形网格中不重复的边缘线段，构造线框索引
         */
        fun extractWireframeIndices(triangleIndices: IntArray): IntArray {
            val edgeSet = HashSet<Long>()
            val lineIndices = ArrayList<Int>()

            fun addEdge(i1: Int, i2: Int) {
                val minIdx = min(i1, i2)
                val maxIdx = max(i1, i2)
                val edgeKey = (minIdx.toLong() shl 32) or (maxIdx.toLong() and 0xFFFFFFFFL)
                if (edgeSet.add(edgeKey)) {
                    lineIndices.add(minIdx)
                    lineIndices.add(maxIdx)
                }
            }

            var i = 0
            while (i + 2 < triangleIndices.size) {
                val a = triangleIndices[i]
                val b = triangleIndices[i + 1]
                val c = triangleIndices[i + 2]
                addEdge(a, b)
                addEdge(b, c)
                addEdge(c, a)
                i += 3
            }

            return lineIndices.toIntArray()
        }

        /**
         * 创建标准透视方块 (带面法线与棱边)
         */
        fun createCube(size: Float = 1.0f): Mesh3D {
            val h = size * 0.5f

            // 6 个面，每个面 4 个顶点，独立法线以呈现硬朗折角
            val p = floatArrayOf(
                // 前面 (z = +h)
                -h, -h,  h,   h, -h,  h,   h,  h,  h,  -h,  h,  h,
                // 后面 (z = -h)
                 h, -h, -h,  -h, -h, -h,  -h,  h, -h,   h,  h, -h,
                // 上面 (y = +h)
                -h,  h,  h,   h,  h,  h,   h,  h, -h,  -h,  h, -h,
                // 下面 (y = -h)
                -h, -h, -h,   h, -h, -h,   h, -h,  h,  -h, -h,  h,
                // 右面 (x = +h)
                 h, -h,  h,   h, -h, -h,   h,  h, -h,   h,  h,  h,
                // 左面 (x = -h)
                -h, -h, -h,  -h, -h,  h,  -h,  h,  h,  -h,  h, -h,
            )

            val n = floatArrayOf(
                // 前
                0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,  0f, 0f, 1f,
                // 后
                0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f,
                // 上
                0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,  0f, 1f, 0f,
                // 下
                0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f,
                // 右
                1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,  1f, 0f, 0f,
                // 左
                -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f,
            )

            val indices = IntArray(36)
            var idx = 0
            for (face in 0..5) {
                val base = face * 4
                indices[idx++] = base
                indices[idx++] = base + 1
                indices[idx++] = base + 2
                indices[idx++] = base
                indices[idx++] = base + 2
                indices[idx++] = base + 3
            }

            // 立方体的 12 条棱边
            val wire = intArrayOf(
                0, 1, 1, 2, 2, 3, 3, 0, // 前
                4, 5, 5, 6, 6, 7, 7, 4, // 后
                0, 5, 1, 4, 2, 7, 3, 6  // 连线
            )

            return Mesh3D(p, n, indices, wire)
        }

        /**
         * 创建经纬球体网格
         */
        fun createSphere(radius: Float = 1.0f, stacks: Int = 16, slices: Int = 24): Mesh3D {
            val positions = ArrayList<Float>()
            val normals = ArrayList<Float>()
            val indices = ArrayList<Int>()

            for (i in 0..stacks) {
                val phi = (PI * i / stacks).toFloat()
                val y = cos(phi) * radius
                val r = sin(phi) * radius

                for (j in 0..slices) {
                    val theta = (2.0 * PI * j / slices).toFloat()
                    val x = r * sin(theta)
                    val z = r * cos(theta)

                    positions.add(x)
                    positions.add(y)
                    positions.add(z)

                    val len = hypot(hypot(x, y), z).coerceAtLeast(1e-6f)
                    normals.add(x / len)
                    normals.add(y / len)
                    normals.add(z / len)
                }
            }

            for (i in 0 until stacks) {
                for (j in 0 until slices) {
                    val first = i * (slices + 1) + j
                    val second = first + slices + 1

                    indices.add(first)
                    indices.add(second)
                    indices.add(first + 1)

                    indices.add(second)
                    indices.add(second + 1)
                    indices.add(first + 1)
                }
            }

            // 结构线框：纯净的主经纬圆环 (赤道、±45° 纬线、4 条主要子午线)，不含任何杂乱对角线
            val wireList = ArrayList<Int>()
            val stride = slices + 1

            // 纬线圈：赤道与上下两条纬线圈
            val keyStacks = intArrayOf(stacks / 4, stacks / 2, (stacks * 3) / 4)
            for (st in keyStacks) {
                for (j in 0 until slices) {
                    val v1 = st * stride + j
                    val v2 = st * stride + (j + 1)
                    wireList.add(v1)
                    wireList.add(v2)
                }
            }

            // 4 条主要子午线：0°, 90°, 180°, 270°
            val keySlices = intArrayOf(0, slices / 4, slices / 2, (slices * 3) / 4)
            for (sl in keySlices) {
                for (i in 0 until stacks) {
                    val v1 = i * stride + sl
                    val v2 = (i + 1) * stride + sl
                    wireList.add(v1)
                    wireList.add(v2)
                }
            }

            val pArray = positions.toFloatArray()
            val nArray = normals.toFloatArray()
            val iArray = indices.toIntArray()
            val wArray = wireList.toIntArray()
            return Mesh3D(pArray, nArray, iArray, wArray)
        }

        /**
         * 创建标准圆柱体
         */
        fun createCylinder(radius: Float = 0.8f, height: Float = 1.8f, slices: Int = 24): Mesh3D {
            val positions = ArrayList<Float>()
            val normals = ArrayList<Float>()
            val indices = ArrayList<Int>()
            val halfH = height * 0.5f

            // 侧面顶点 (上圈与下圈)
            for (j in 0..slices) {
                val theta = (2.0 * PI * j / slices).toFloat()
                val nx = sin(theta)
                val nz = cos(theta)
                val x = radius * nx
                val z = radius * nz

                // 上顶点
                positions.add(x); positions.add(halfH); positions.add(z)
                normals.add(nx); normals.add(0f); normals.add(nz)

                // 下顶点
                positions.add(x); positions.add(-halfH); positions.add(z)
                normals.add(nx); normals.add(0f); normals.add(nz)
            }

            for (j in 0 until slices) {
                val top1 = j * 2
                val bot1 = top1 + 1
                val top2 = (j + 1) * 2
                val bot2 = top2 + 1

                indices.add(top1); indices.add(bot1); indices.add(top2)
                indices.add(bot1); indices.add(bot2); indices.add(top2)
            }

            // 顶盖与底盖中心点
            val topCenterIdx = positions.size / 3
            positions.add(0f); positions.add(halfH); positions.add(0f)
            normals.add(0f); normals.add(1f); normals.add(0f)

            val botCenterIdx = positions.size / 3
            positions.add(0f); positions.add(-halfH); positions.add(0f)
            normals.add(0f); normals.add(-1f); normals.add(0f)

            // 顶底面三角形
            for (j in 0 until slices) {
                val top1 = j * 2
                val top2 = (j + 1) * 2
                indices.add(topCenterIdx); indices.add(top2); indices.add(top1)

                val bot1 = j * 2 + 1
                val bot2 = (j + 1) * 2 + 1
                indices.add(botCenterIdx); indices.add(bot1); indices.add(bot2)
            }

            // 结构线框：顶圈、底圈与 4 根主母线，完全去除对角交叉杂线
            val wireList = ArrayList<Int>()
            for (j in 0 until slices) {
                wireList.add(j * 2)
                wireList.add((j + 1) * 2)
                wireList.add(j * 2 + 1)
                wireList.add((j + 1) * 2 + 1)
            }
            val keySlices = intArrayOf(0, slices / 4, slices / 2, (slices * 3) / 4)
            for (sl in keySlices) {
                wireList.add(sl * 2)
                wireList.add(sl * 2 + 1)
            }

            val pArray = positions.toFloatArray()
            val nArray = normals.toFloatArray()
            val iArray = indices.toIntArray()
            val wArray = wireList.toIntArray()
            return Mesh3D(pArray, nArray, iArray, wArray)
        }

        /**
         * 基础头部比例素模 (具有清晰的面部结构体块、下颌、鼻梁、颧骨、额头平面)
         */
        fun createHeadMannequin(): Mesh3D {
            // 精心设计的折面头部体块网格 (30+ 个特征关键控制点，覆盖三庭五眼基础比例)
            val v = floatArrayOf(
                // 额头与头顶
                 0.0f,  1.10f,  0.0f,   // 0 头顶
                -0.45f, 0.85f, -0.2f,   // 1 后脑左上
                 0.45f, 0.85f, -0.2f,   // 2 后脑右上
                -0.35f, 0.75f,  0.35f,  // 3 额头左上
                 0.35f, 0.75f,  0.35f,  // 4 额头右上
                 0.0f,  0.78f,  0.45f,  // 5 额头正中
                // 眉弓与太阳穴
                -0.45f, 0.45f,  0.25f,  // 6 左太阳穴
                 0.45f, 0.45f,  0.25f,  // 7 右太阳穴
                -0.20f, 0.45f,  0.48f,  // 8 左眉弓
                 0.20f, 0.45f,  0.48f,  // 9 右眉弓
                 0.0f,  0.45f,  0.50f,  // 10 眉心 (山根上部)
                // 眼睛与颧骨
                -0.42f, 0.20f,  0.35f,  // 11 左颧骨
                 0.42f, 0.20f,  0.35f,  // 12 右颧骨
                -0.15f, 0.25f,  0.38f,  // 13 左眼窝
                 0.15f, 0.25f,  0.38f,  // 14 右眼窝
                // 鼻子
                 0.0f,  0.22f,  0.58f,  // 15 鼻根/鼻梁
                 0.0f,  0.05f,  0.68f,  // 16 鼻尖
                -0.10f, 0.02f,  0.55f,  // 17 左鼻翼
                 0.10f, 0.02f,  0.55f,  // 18 右鼻翼
                // 嘴唇与人中
                 0.0f, -0.10f,  0.54f,  // 19 上唇结节
                 0.0f, -0.22f,  0.52f,  // 20 下唇中央
                -0.20f,-0.15f,  0.42f,  // 21 左嘴角
                 0.20f,-0.15f,  0.42f,  // 22 右嘴角
                // 下巴与下颌角
                 0.0f, -0.45f,  0.52f,  // 23 下巴正中前突
                -0.12f,-0.48f,  0.46f,  // 24 下巴左侧
                 0.12f,-0.48f,  0.46f,  // 25 下巴右侧
                -0.38f,-0.20f, -0.05f,  // 26 左下颌角
                 0.38f,-0.20f, -0.05f,  // 27 右下颌角
                 0.0f, -0.58f,  0.25f,  // 28 颈底正中
                // 后脑与枕骨
                 0.0f,  0.30f, -0.55f,  // 29 枕骨隆突
                 0.0f, -0.25f, -0.40f,  // 30 后颈窝
            )

            val rawTriangles = intArrayOf(
                // 额头折面
                0, 3, 5,   0, 5, 4,   0, 1, 3,   0, 4, 2,
                3, 6, 8,   4, 9, 7,   5, 8, 10,  5, 10, 9,  3, 8, 5,   4, 5, 9,
                // 眼窝与鼻梁
                8, 6, 11,  8, 11, 13, 9, 14, 12, 9, 12, 7,
                10, 8, 13, 10, 14, 9, 10, 13, 15, 10, 15, 14,
                // 鼻子
                15, 13, 17, 15, 18, 14, 15, 17, 16, 15, 16, 18,
                // 颧骨到面颊
                11, 21, 13, 12, 14, 22, 13, 21, 17, 14, 18, 22,
                16, 17, 19, 16, 19, 18, 17, 21, 19, 18, 19, 22,
                // 嘴巴与下巴
                19, 21, 20, 19, 20, 22,
                20, 21, 24, 20, 25, 22, 20, 24, 23, 20, 23, 25,
                // 脸颊与下颌轮廓
                11, 26, 21, 12, 22, 27,
                21, 26, 24, 22, 25, 27,
                23, 24, 28, 23, 28, 25,
                24, 26, 28, 25, 28, 27,
                // 后脑与枕骨闭合
                1, 29, 3,   2, 4, 29,   6, 26, 11,  7, 12, 27,
                1, 29, 26,  2, 27, 29,  26, 30, 28, 27, 28, 30,
                29, 30, 26, 29, 27, 30, 0, 2, 29,   0, 29, 1
            )

            // 自动计算顶点法线 (每个三角形贡献面法线并归一化)
            val numVertices = v.size / 3
            val normals = FloatArray(v.size)
            var t = 0
            while (t + 2 < rawTriangles.size) {
                val i0 = rawTriangles[t]
                val i1 = rawTriangles[t + 1]
                val i2 = rawTriangles[t + 2]

                val x0 = v[i0 * 3]; val y0 = v[i0 * 3 + 1]; val z0 = v[i0 * 3 + 2]
                val x1 = v[i1 * 3]; val y1 = v[i1 * 3 + 1]; val z1 = v[i1 * 3 + 2]
                val x2 = v[i2 * 3]; val y2 = v[i2 * 3 + 1]; val z2 = v[i2 * 3 + 2]

                val e1x = x1 - x0; val e1y = y1 - y0; val e1z = z1 - z0
                val e2x = x2 - x0; val e2y = y2 - y0; val e2z = z2 - z0

                val fnx = e1y * e2z - e1z * e2y
                val fny = e1z * e2x - e1x * e2z
                val fnz = e1x * e2y - e1y * e2x

                normals[i0 * 3] += fnx; normals[i0 * 3 + 1] += fny; normals[i0 * 3 + 2] += fnz
                normals[i1 * 3] += fnx; normals[i1 * 3 + 1] += fny; normals[i1 * 3 + 2] += fnz
                normals[i2 * 3] += fnx; normals[i2 * 3 + 1] += fny; normals[i2 * 3 + 2] += fnz
                t += 3
            }

            for (i in 0 until numVertices) {
                val nx = normals[i * 3]; val ny = normals[i * 3 + 1]; val nz = normals[i * 3 + 2]
                val len = hypot(hypot(nx, ny), nz).coerceAtLeast(1e-6f)
                normals[i * 3] = nx / len
                normals[i * 3 + 1] = ny / len
                normals[i * 3 + 2] = nz / len
            }

            // 头部解剖与体块关键结构基准线 (严谨定义三庭五眼与面部折面，消除网格对角线杂乱)
            val wire = intArrayOf(
                // 头部中轴线 (三庭五眼主线)
                0, 5,   5, 10,   10, 15,   15, 16,   16, 19,   19, 20,   20, 23,   23, 28,
                // 额头发际线与头顶轮廓
                1, 3,   3, 5,    5, 4,     4, 2,     0, 1,     0, 2,
                // 眉弓横线 (左右贯通)
                6, 8,   8, 10,   10, 9,    9, 7,
                // 眼眶与颧骨结构
                8, 13,  13, 11,  9, 14,    14, 12,   10, 13,   10, 14,
                11, 6,  12, 7,
                // 鼻梁与鼻部体块
                13, 17, 14, 18,  15, 17,   15, 18,   17, 16,   18, 16,
                // 嘴唇轮廓与嘴角
                21, 19, 19, 22,  21, 20,   20, 22,   17, 21,   18, 22,
                // 下颌与下巴 (下颌骨边缘)
                26, 24, 24, 23,  23, 25,   25, 27,
                26, 21, 27, 22,  21, 24,   22, 25,
                // 侧脸与耳前垂直分界
                6, 26,  7, 27,   11, 26,   12, 27,
                // 颈部与后脑轮廓
                24, 28, 25, 28,  26, 30,   27, 30,   28, 30,
                1, 29,  2, 29,   29, 30,   0, 29
            )
            return Mesh3D(v, normals, rawTriangles, wire)
        }

        /**
         * 解析标准 Wavefront OBJ 文件流
         */
        fun parseObj(reader: BufferedReader): Mesh3D {
            val rawPositions = ArrayList<Float>()
            val rawNormals = ArrayList<Float>()

            val finalPositions = ArrayList<Float>()
            val finalNormals = ArrayList<Float>()
            val indices = ArrayList<Int>()

            val vertexMap = HashMap<String, Int>()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.isEmpty() || l.startsWith("#")) continue

                val tokens = l.split("\\s+".toRegex())
                if (tokens.isEmpty()) continue

                when (tokens[0]) {
                    "v" -> {
                        if (tokens.size >= 4) {
                            rawPositions.add(tokens[1].toFloat())
                            rawPositions.add(tokens[2].toFloat())
                            rawPositions.add(tokens[3].toFloat())
                        }
                    }
                    "vn" -> {
                        if (tokens.size >= 4) {
                            rawNormals.add(tokens[1].toFloat())
                            rawNormals.add(tokens[2].toFloat())
                            rawNormals.add(tokens[3].toFloat())
                        }
                    }
                    "f" -> {
                        if (tokens.size >= 4) {
                            val faceVerts = ArrayList<Int>()
                            for (k in 1 until tokens.size) {
                                val token = tokens[k]
                                val existingIndex = vertexMap[token]
                                if (existingIndex != null) {
                                    faceVerts.add(existingIndex)
                                } else {
                                    val parts = token.split("/")
                                    val vIdx = parts[0].toInt().let { if (it < 0) (rawPositions.size / 3) + it else it - 1 }
                                    val vnIdx = if (parts.size >= 3 && parts[2].isNotEmpty()) {
                                        parts[2].toInt().let { if (it < 0) (rawNormals.size / 3) + it else it - 1 }
                                    } else -1

                                    val newIdx = finalPositions.size / 3
                                    if (vIdx >= 0 && vIdx * 3 + 2 < rawPositions.size) {
                                        finalPositions.add(rawPositions[vIdx * 3])
                                        finalPositions.add(rawPositions[vIdx * 3 + 1])
                                        finalPositions.add(rawPositions[vIdx * 3 + 2])
                                    } else {
                                        finalPositions.add(0f); finalPositions.add(0f); finalPositions.add(0f)
                                    }

                                    if (vnIdx >= 0 && vnIdx * 3 + 2 < rawNormals.size) {
                                        finalNormals.add(rawNormals[vnIdx * 3])
                                        finalNormals.add(rawNormals[vnIdx * 3 + 1])
                                        finalNormals.add(rawNormals[vnIdx * 3 + 2])
                                    } else {
                                        finalNormals.add(0f); finalNormals.add(1f); finalNormals.add(0f)
                                    }

                                    vertexMap[token] = newIdx
                                    faceVerts.add(newIdx)
                                }
                            }

                            // 三角化扇面 (Fan triangulation)
                            for (k in 1 until faceVerts.size - 1) {
                                indices.add(faceVerts[0])
                                indices.add(faceVerts[k])
                                indices.add(faceVerts[k + 1])
                            }
                        }
                    }
                }
            }

            val pArr = finalPositions.toFloatArray()
            val nArr = finalNormals.toFloatArray()
            val iArr = indices.toIntArray()
            val wArr = extractWireframeIndices(iArr)

            return Mesh3D(pArr, nArr, iArr, wArr)
        }
    }
}
