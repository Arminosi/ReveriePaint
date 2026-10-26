/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 3D 单骨骼关节数据
 */
data class Joint3D(
    val index: Int,
    val name: String,
    val parentIndex: Int,
    val localMatrix: FloatArray,          // 4x4 局部变换矩阵 (列主序)
    val inverseBindMatrix: FloatArray,    // 4x4 逆绑定矩阵 (列主序)
    val children: List<Int> = emptyList(),
    val localPos: FloatArray = floatArrayOf(localMatrix[12], localMatrix[13], localMatrix[14]),
)

/**
 * 骨架层级树与人形/动物标准关节映射
 *
 * 性能约定 (AGENTS.md §4 热路径零分配):
 * - 父子拓扑序、语义键反向映射、绑定姿态世界矩阵、拖拽旋转轴角色全部在构造期算好,
 *   [computeSkinning] 每帧只做一次正向遍历, 不再递归查找、不再分配临时矩阵。
 */
class Skeleton3D(
    val joints: List<Joint3D>,
    val humanoidMapping: Map<String, Int> = emptyMap(),
) {
    val count: Int get() = joints.size

    /** 父先于子的关节遍历序: FK 一次正向循环即可, 无需递归/visited 数组 */
    private val order: IntArray

    /** 关节索引 -> 语义键 (standard humanoid key)。多键指向同一关节时按字典序取首个, 保证跨会话确定 */
    private val semanticKeys: Array<String?>

    /** 绑定姿态下的关节世界矩阵 (count * 16) */
    private val bindWorld: FloatArray

    /** 垂直拖拽 (pitch, 绕局部 X) 的手指位移 -> 角度符号 */
    private val pitchSigns: FloatArray

    /** 水平拖拽使用的局部轴: 1 = Y (沿骨骼扭转/转头), 2 = Z (横向摆骨) */
    private val swingAxes: IntArray

    /** 水平拖拽的手指位移 -> 角度符号 */
    private val swingSigns: FloatArray

    init {
        val n = joints.size
        order = buildTopologicalOrder(n)
        semanticKeys = resolveSemanticKeys(n)

        bindWorld = FloatArray(n * 16)
        // order 保证父先于子, 直接正向相乘即可
        for (k in 0 until n) {
            val i = order[k]
            val j = joints[i]
            val p = j.parentIndex
            if (p in 0 until n) {
                Math3D.multiplyMMInto(bindWorld, i * 16, bindWorld, p * 16, j.localMatrix, 0)
            } else {
                System.arraycopy(j.localMatrix, 0, bindWorld, i * 16, 16)
            }
        }

        pitchSigns = FloatArray(n) { 1f }
        swingAxes = IntArray(n) { 1 }
        swingSigns = FloatArray(n) { 1f }
        computeDragAxisRoles(n)
    }

    fun getJointIndex(boneKey: String): Int {
        humanoidMapping[boneKey]?.let { return it }
        val exact = joints.indexOfFirst { it.name.equals(boneKey, ignoreCase = true) }
        if (exact >= 0) return exact
        return joints.indexOfFirst { it.name.contains(boneKey, ignoreCase = true) }
    }

    /**
     * 垂直拖拽 (俯仰) 的符号: 保证手指向下移动时骨骼末端/朝向也在屏幕上向下
     */
    fun dragPitchSign(jointIndex: Int): Float =
        pitchSigns.getOrElse(jointIndex) { 1f }

    /**
     * 水平拖拽使用的局部旋转轴 (0 = X, 1 = Y, 2 = Z)
     *
     * 沿骨骼长度的轴 (对四肢来说就是 Y) 旋转只会让骨头自转, 屏幕上几乎看不到位移,
     * 所以对非竖直骨骼改用能产生横向摆动的轴, 让"横向拖拽"始终有可见反馈。
     */
    fun dragSwingAxis(jointIndex: Int): Int = swingAxes.getOrElse(jointIndex) { 1 }

    /**
     * 水平拖拽的符号: 保证手指向右移动时骨骼末端/朝向也在屏幕上向右
     */
    fun dragSwingSign(jointIndex: Int): Float = swingSigns.getOrElse(jointIndex) { 1f }

    /** 关节在绑定姿态下的世界坐标 */
    fun bindWorldPosition(jointIndex: Int, out: FloatArray, outOffset: Int = 0) {
        val off = jointIndex * 16
        out[outOffset] = bindWorld[off + 12]
        out[outOffset + 1] = bindWorld[off + 13]
        out[outOffset + 2] = bindWorld[off + 14]
    }

    /** 关节的第一个子关节 (骨骼末端), 没有子关节时返回 -1 */
    fun firstChildJoint(jointIndex: Int): Int {
        val j = joints.getOrNull(jointIndex) ?: return -1
        j.children.firstOrNull { it in joints.indices }?.let { return it }
        for (k in joints.indices) {
            if (joints[k].parentIndex == jointIndex) return k
        }
        return -1
    }

    /**
     * 根据关节旋转偏移量计算蒙皮形变矩阵 (Skinning Matrices) 与世界空间关节位置 (World Joint Positions)
     * @param jointEulerMap 关节索引或键名 -> [pitch, yaw, roll] 旋转角度
     * @param outSkinningMatrices 展开的一维蒙皮矩阵数组 (count * 16)
     * @param outWorldJointPositions 输出世界坐标关节位置 (count * 3)
     * @param worldScratch 可选的 count * 16 世界矩阵复用缓冲 (热路径传入以避免分配)
     */
    fun computeSkinning(
        jointEulerMap: Map<String, FloatArray>,
        outSkinningMatrices: FloatArray,
        outWorldJointPositions: FloatArray? = null,
        worldScratch: FloatArray? = null,
    ) {
        val n = joints.size
        if (n == 0) return
        require(outSkinningMatrices.size >= n * 16) { "skinning matrix buffer too small" }

        val world = if (worldScratch != null && worldScratch.size >= n * 16) worldScratch else FloatArray(n * 16)
        // 一次分配 6 个 4x4 临时矩阵, 避免每个关节 new 出十几个 FloatArray(16)
        val tmp = FloatArray(16 * 6)
        val rotYOff = 0
        val rotXOff = 16
        val rotZOff = 32
        val rotTmpOff = 48
        val rotAllOff = 64
        val localRotOff = 80

        for (k in 0 until n) {
            val i = order[k]
            val joint = joints[i]

            val euler = jointEulerMap[joint.name] ?: semanticKeys[i]?.let { jointEulerMap[it] }
            val parentOff = joint.parentIndex * 16
            val selfOff = i * 16
            val hasParent = joint.parentIndex in 0 until n

            val hasRotation = euler != null && (euler[0] != 0f || euler[1] != 0f || euler[2] != 0f)
            val local: FloatArray
            val localOff: Int
            if (hasRotation) {
                // 与旧实现完全一致的合成顺序: local * Ry * Rx * Rz
                Math3D.rotationMatrixInto(tmp, rotYOff, euler!![1], 0f, 1f, 0f)
                Math3D.rotationMatrixInto(tmp, rotXOff, euler[0], 1f, 0f, 0f)
                Math3D.multiplyMMInto(tmp, rotTmpOff, tmp, rotYOff, tmp, rotXOff)
                Math3D.rotationMatrixInto(tmp, rotZOff, euler[2], 0f, 0f, 1f)
                Math3D.multiplyMMInto(tmp, rotAllOff, tmp, rotTmpOff, tmp, rotZOff)
                Math3D.multiplyMMInto(tmp, localRotOff, joint.localMatrix, 0, tmp, rotAllOff)
                local = tmp
                localOff = localRotOff
            } else {
                local = joint.localMatrix
                localOff = 0
            }

            if (hasParent) {
                Math3D.multiplyMMInto(world, selfOff, world, parentOff, local, localOff)
            } else {
                System.arraycopy(local, localOff, world, selfOff, 16)
            }

            // 蒙皮变换: skinning = world * inverseBind
            Math3D.multiplyMMInto(
                outSkinningMatrices, selfOff,
                world, selfOff,
                joint.inverseBindMatrix, 0,
            )

            if (outWorldJointPositions != null && outWorldJointPositions.size >= n * 3) {
                outWorldJointPositions[i * 3] = world[selfOff + 12]
                outWorldJointPositions[i * 3 + 1] = world[selfOff + 13]
                outWorldJointPositions[i * 3 + 2] = world[selfOff + 14]
            }
        }
    }

    // ------------------------------------------------------------------
    // 构造期预计算
    // ------------------------------------------------------------------

    private fun buildTopologicalOrder(n: Int): IntArray {
        val result = IntArray(n)
        val state = IntArray(n) // 0 = 未访问, 1 = 已入队, 2 = 已输出
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        var written = 0

        for (i in 0 until n) {
            val p = joints[i].parentIndex
            if (p !in 0 until n) {
                state[i] = 1
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            result[written++] = i
            state[i] = 2
            for (c in 0 until n) {
                if (joints[c].parentIndex == i && state[c] == 0) {
                    state[c] = 1
                    queue[tail++] = c
                }
            }
        }
        // 防御: 环或断裂层级导致未输出的关节按索引补齐 (不会崩溃, 只是 FK 顺序退化)
        if (written < n) {
            for (i in 0 until n) {
                if (state[i] != 2) result[written++] = i
            }
        }
        return result
    }

    private fun resolveSemanticKeys(n: Int): Array<String?> {
        val keys = arrayOfNulls<String>(n)
        for (key in humanoidMapping.keys.sorted()) {
            val idx = humanoidMapping[key] ?: continue
            if (idx in 0 until n && keys[idx] == null) keys[idx] = key
        }
        return keys
    }

    /**
     * 推算出"垂直拖拽 / 水平拖拽"分别该用哪根局部轴、往哪个方向加角度。
     *
     * 判据全部来自绑定姿态几何 (与具体模型无关):
     * - 绕某轴旋转会让骨骼末端/朝向在屏幕上往哪个方向动, 就用哪个符号;
     * - 水平拖拽优先选"屏幕横向位移最大"的轴 (沿骨轴旋转 → 无位移, 会被自动淘汰);
     * - 头/颈这类骨骼横向拖拽应该转头 (绕局部 Y 扭转), 单独固定;
     */
    private fun computeDragAxisRoles(n: Int) {
        val axX = FloatArray(3)
        val axY = FloatArray(3)
        val axZ = FloatArray(3)
        // 探针: 骨骼末端 (子关节) / 骨骼正前方 (+Z) / 骨骼侧向 (+X)
        val probes = Array(3) { FloatArray(3) }

        for (i in 0 until n) {
            val selfOff = i * 16
            val ox = bindWorld[selfOff + 12]
            val oy = bindWorld[selfOff + 13]
            val oz = bindWorld[selfOff + 14]

            // 三根世界轴
            worldAxis(selfOff, 0, axX, 0)
            worldAxis(selfOff, 1, axY, 0)
            worldAxis(selfOff, 2, axZ, 0)

            val child = firstChildJoint(i)
            val tip = probes[0]
            if (child >= 0) {
                val cOff = child * 16
                tip[0] = bindWorld[cOff + 12] - ox
                tip[1] = bindWorld[cOff + 13] - oy
                tip[2] = bindWorld[cOff + 14] - oz
            } else {
                fillScaled(tip, axY, 0.12f)
            }
            fillScaled(probes[1], axZ, 0.12f)
            fillScaled(probes[2], axX, 0.06f)

            // --- 垂直拖拽 (pitch, 局部 X) ---
            var bestPitch = 0f
            for (p in 0 until 3) {
                val screenDy = screenDeltaY(axX, probes[p])
                if (abs(screenDy) > abs(bestPitch)) bestPitch = screenDy
            }
            pitchSigns[i] = if (abs(bestPitch) < 1e-5f) 1f else if (bestPitch > 0f) 1f else -1f

            // --- 水平拖拽 ---
            val semantic = semanticKeys[i]
            if (semantic == "head" || semantic == "neck") {
                // 转头: 绕局部 Y 扭转, 用"正前方"探针定符号
                swingAxes[i] = 1
                val dx = screenDeltaX(axY, probes[1])
                swingSigns[i] = if (abs(dx) < 1e-5f) 1f else if (dx > 0f) 1f else -1f
                continue
            }

            var bestAxis = 1
            var bestDx = 0f
            for (a in 1..2) {
                val ax = if (a == 1) axY else axZ
                var localBest = 0f
                for (p in 0 until 3) {
                    val dx = screenDeltaX(ax, probes[p])
                    if (abs(dx) > abs(localBest)) localBest = dx
                }
                if (abs(localBest) > abs(bestDx)) {
                    bestDx = localBest
                    bestAxis = a
                }
            }
            swingAxes[i] = bestAxis
            swingSigns[i] = if (abs(bestDx) < 1e-5f) 1f else if (bestDx > 0f) 1f else -1f
        }
    }

    private fun worldAxis(matrixOffset: Int, column: Int, out: FloatArray, outOffset: Int) {
        var x = bindWorld[matrixOffset + column * 4]
        var y = bindWorld[matrixOffset + column * 4 + 1]
        var z = bindWorld[matrixOffset + column * 4 + 2]
        val len = hypot(hypot(x, y), z).coerceAtLeast(1e-6f)
        x /= len; y /= len; z /= len
        out[outOffset] = x
        out[outOffset + 1] = y
        out[outOffset + 2] = z
    }

    private fun fillScaled(out: FloatArray, v: FloatArray, scale: Float) {
        out[0] = v[0] * scale
        out[1] = v[1] * scale
        out[2] = v[2] * scale
    }

    /** 单位角度旋转下探针的屏幕横向位移 (正视相机: 世界 +X 为屏幕右) */
    private fun screenDeltaX(axis: FloatArray, probe: FloatArray): Float =
        axis[1] * probe[2] - axis[2] * probe[1]

    /** 单位角度旋转下探针的屏幕纵向位移 (世界 +Y 为屏幕上, 屏幕 y 向下) */
    private fun screenDeltaY(axis: FloatArray, probe: FloatArray): Float =
        -(axis[2] * probe[0] - axis[0] * probe[2])
}

/**
 * 顶点输出缓冲: 交互拖拽时复用同一组数组, 避免每帧产生 300KB 级垃圾
 * (12k 顶点模型 = positions + normals 各 147KB)。
 *
 * 注意: 复用期间上一次返回的 [Mesh3D] 会指向同一组数组, 只允许在"画完即弃"的视口预览路径使用。
 */
class DeformBuffer(val vertexCount: Int, jointCount: Int) {
    val positions = FloatArray(vertexCount * 3)
    val normals = FloatArray(vertexCount * 3)
    val skinningMatrices = FloatArray(jointCount.coerceAtLeast(1) * 16)
    val worldMatrices = FloatArray(jointCount.coerceAtLeast(1) * 16)
    val worldJoints = FloatArray(jointCount.coerceAtLeast(1) * 3)
}

/**
 * 具备骨骼蒙皮形变能力的 3D 模型
 */
class SkinnedMesh3D(
    val baseMesh: Mesh3D,
    val skeleton: Skeleton3D?,
    val jointIndices: IntArray?,   // 每个顶点 4 个关节索引 [v0_j0, v0_j1, v0_j2, v0_j3, ...]
    val jointWeights: FloatArray?, // 每个顶点 4 个关节权重 [v0_w0, v0_w1, v0_w2, v0_w3, ...]
) {
    /** 绑定姿态 (静止姿态) 的最低点: 自动贴地时以它为"地面"基准线 */
    val restMinY: Float by lazy {
        var minY = Float.MAX_VALUE
        val p = baseMesh.positions
        var i = 1
        while (i < p.size) {
            if (p[i] < minY) minY = p[i]
            i += 3
        }
        if (minY == Float.MAX_VALUE) 0f else minY
    }

    /**
     * 应用骨骼蒙皮形变，生成形变后的静态 Mesh3D
     *
     * @param buffer 可复用的顶点输出缓冲 (尺寸不符时自动忽略并新建)
     * @param groundShiftY 姿态预设的整体升降量 (glTF: +Y 向上), 直接写入结果顶点, 不额外拷贝
     */
    fun createDeformedMesh(
        jointEulerMap: Map<String, FloatArray>,
        outWorldJointPositions: FloatArray? = null,
        buffer: DeformBuffer? = null,
        groundShiftY: Float = 0f,
        spineBend: Float = 0f,
    ): Mesh3D {
        if (skeleton == null || jointIndices == null || jointWeights == null || skeleton.count == 0) {
            return baseMesh
        }

        val vCount = baseMesh.vertexCount
        val usableBuffer = buffer?.takeIf { it.vertexCount == vCount }
        val defPositions = usableBuffer?.positions ?: FloatArray(vCount * 3)
        val defNormals = usableBuffer?.normals ?: FloatArray(vCount * 3)

        val skinningMatrices = usableBuffer?.skinningMatrices
            ?: FloatArray(skeleton.count * 16)
        skeleton.computeSkinning(
            jointEulerMap,
            skinningMatrices,
            outWorldJointPositions,
            usableBuffer?.worldMatrices,
        )

        val origPositions = baseMesh.positions
        val origNormals = baseMesh.normals
        val hasNormals = origNormals.size >= vCount * 3
        val jointCount = skeleton.count
        val lastJoint = jointCount - 1
        val dy = groundShiftY

        for (v in 0 until vCount) {
            val bIdx = v * 4
            val px = origPositions[v * 3]
            val py = origPositions[v * 3 + 1]
            val pz = origPositions[v * 3 + 2]

            var fx = 0f
            var fy = 0f
            var fz = 0f
            var fnx = 0f
            var fny = 0f
            var fnz = 0f
            var totalWeight = 0f

            for (k in 0 until 4) {
                val weight = jointWeights[bIdx + k]
                if (weight > 0.001f) {
                    val joint = jointIndices[bIdx + k].coerceIn(0, lastJoint)
                    val m = joint * 16

                    val m0 = skinningMatrices[m]
                    val m1 = skinningMatrices[m + 1]
                    val m2 = skinningMatrices[m + 2]
                    val m4 = skinningMatrices[m + 4]
                    val m5 = skinningMatrices[m + 5]
                    val m6 = skinningMatrices[m + 6]
                    val m8 = skinningMatrices[m + 8]
                    val m9 = skinningMatrices[m + 9]
                    val m10 = skinningMatrices[m + 10]
                    val m12 = skinningMatrices[m + 12]
                    val m13 = skinningMatrices[m + 13]
                    val m14 = skinningMatrices[m + 14]

                    fx += weight * (m0 * px + m4 * py + m8 * pz + m12)
                    fy += weight * (m1 * px + m5 * py + m9 * pz + m13)
                    fz += weight * (m2 * px + m6 * py + m10 * pz + m14)

                    totalWeight += weight
                }
            }

            if (totalWeight < 0.001f) {
                // 未分配权重的顶点保持原位
                defPositions[v * 3] = px
                defPositions[v * 3 + 1] = py + dy
                defPositions[v * 3 + 2] = pz
                if (hasNormals) {
                    defNormals[v * 3] = origNormals[v * 3]
                    defNormals[v * 3 + 1] = origNormals[v * 3 + 1]
                    defNormals[v * 3 + 2] = origNormals[v * 3 + 2]
                }
                continue
            }

            // 权重归一化: 部分导出器只写 3 个有效权重 / 权重和不足 1, 不归一化会让顶点被"吸"向原点
            val invW = 1f / totalWeight
            defPositions[v * 3] = fx * invW
            defPositions[v * 3 + 1] = fy * invW + dy
            defPositions[v * 3 + 2] = fz * invW

            if (hasNormals) {
                val px2 = origNormals[v * 3]
                val py2 = origNormals[v * 3 + 1]
                val pz2 = origNormals[v * 3 + 2]
                for (k in 0 until 4) {
                    val weight = jointWeights[bIdx + k]
                    if (weight > 0.001f) {
                        val joint = jointIndices[bIdx + k].coerceIn(0, lastJoint)
                        val m = joint * 16
                        fnx += weight * (skinningMatrices[m] * px2 + skinningMatrices[m + 4] * py2 + skinningMatrices[m + 8] * pz2)
                        fny += weight * (skinningMatrices[m + 1] * px2 + skinningMatrices[m + 5] * py2 + skinningMatrices[m + 9] * pz2)
                        fnz += weight * (skinningMatrices[m + 2] * px2 + skinningMatrices[m + 6] * py2 + skinningMatrices[m + 10] * pz2)
                    }
                }
                val nLen = hypot(hypot(fnx, fny), fnz).coerceAtLeast(1e-6f)
                defNormals[v * 3] = fnx / nLen
                defNormals[v * 3 + 1] = fny / nLen
                defNormals[v * 3 + 2] = fnz / nLen
            }
        }

        // 程序化脊柱弯曲: 沿 +Z (身体前向) 把躯干卷成一段圆弧, 用来做"首尾相接"的蜷缩姿态。
        // 骨架只有旋转、没有骨盆位移与脊柱扭转轴, 纯骨骼摆不出球形蜷缩, 这里补一个姿势空间变形。
        if (spineBend > 1e-4f) {
            bendSpine(defPositions, vCount, spineBend)
            if (outWorldJointPositions != null) {
                val jointCount = outWorldJointPositions.size / 3
                for (j in 0 until jointCount) {
                    val i = j * 3
                    val bx = outWorldJointPositions[i]
                    val by = outWorldJointPositions[i + 1]
                    val bz = outWorldJointPositions[i + 2]
                    outWorldJointPositions[i] = bx
                    val r = bendPoint(bz)
                    outWorldJointPositions[i + 1] = by * r[2] + r[1]
                    outWorldJointPositions[i + 2] = r[0]
                }
            }
        }

        if (outWorldJointPositions != null && dy != 0f) {
            var i = 1
            while (i < outWorldJointPositions.size) {
                outWorldJointPositions[i] += dy
                i += 3
            }
        }

        return Mesh3D(
            positions = defPositions,
            normals = defNormals,
            indices = baseMesh.indices,
            wireframeIndices = baseMesh.wireframeIndices,
            wireframeEdgeTriangles = baseMesh.wireframeEdgeTriangles,
        )
    }

    /** 弯曲基准: 以网格 +Z 最小值 (尾根) 为弧长起点, 1.0 弯曲约合 160° 卷曲 */
    private var bendZMin = 0f
    private var bendK = 0f

    /** 弯曲: x 不变, 截面按弧长旋转 —— z' = R·sinθ, y' = y·cosθ + R·(1-cosθ) */
    private fun bendPoint(z: Float): FloatArray {
        val s = z - bendZMin
        val theta = bendK * s
        val r = 1f / bendK
        return floatArrayOf(bendZMin + r * kotlin.math.sin(theta), r * (1f - kotlin.math.cos(theta)), kotlin.math.cos(theta))
    }

    private fun bendSpine(positions: FloatArray, vertexCount: Int, bend: Float) {
        var zMin = Float.MAX_VALUE
        var zMax = -Float.MAX_VALUE
        for (v in 0 until vertexCount) {
            val z = positions[v * 3 + 2]
            if (z < zMin) zMin = z
            if (z > zMax) zMax = z
        }
        val length = (zMax - zMin).coerceAtLeast(1e-3f)
        val totalAngle = bend.coerceIn(0f, 1f) * 2.793f // 160°
        bendZMin = zMin
        bendK = totalAngle / length
        for (v in 0 until vertexCount) {
            val i = v * 3
            val y = positions[i + 1]
            val p = bendPoint(positions[i + 2])
            positions[i + 1] = y * p[2] + p[1]
            positions[i + 2] = p[0]
        }
    }

    /** 形变后网格的最低点 (自动贴地/落地校验用) */
    fun minYOf(mesh: Mesh3D): Float {
        var minY = Float.MAX_VALUE
        val p = mesh.positions
        var i = 1
        while (i < p.size) {
            if (p[i] < minY) minY = p[i]
            i += 3
        }
        return if (minY == Float.MAX_VALUE) 0f else minY
    }
}
