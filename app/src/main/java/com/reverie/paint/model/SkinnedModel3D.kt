/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin

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
 */
class Skeleton3D(
    val joints: List<Joint3D>,
    val humanoidMapping: Map<String, Int> = emptyMap(),
) {
    val count: Int get() = joints.size

    fun getJointIndex(boneKey: String): Int {
        humanoidMapping[boneKey]?.let { return it }
        val exact = joints.indexOfFirst { it.name.equals(boneKey, ignoreCase = true) }
        if (exact >= 0) return exact
        return joints.indexOfFirst { it.name.contains(boneKey, ignoreCase = true) }
    }

    /**
     * 根据关节旋转偏移量计算蒙皮形变矩阵 (Skinning Matrices) 与世界空间关节位置 (World Joint Positions)
     * @param jointEulerMap 关节索引或键名 -> [pitch, yaw, roll] 旋转角度
     * @param outSkinningMatrices 展开的一维蒙皮矩阵数组 (count * 16)
     * @param outWorldJointPositions 输出世界坐标关节位置 (count * 3)
     */
    fun computeSkinning(
        jointEulerMap: Map<String, FloatArray>,
        outSkinningMatrices: FloatArray,
        outWorldJointPositions: FloatArray? = null,
    ) {
        val worldMatrices = Array(joints.size) { FloatArray(16) }
        val computed = BooleanArray(joints.size)

        fun computeJointWorld(i: Int) {
            if (computed[i]) return
            val joint = joints[i]
            val parentWorld = if (joint.parentIndex in joints.indices) {
                computeJointWorld(joint.parentIndex)
                worldMatrices[joint.parentIndex]
            } else {
                Math3D.identity()
            }

            // 获取该关节的旋转角度 (支持骨骼原名与标准人形映射键名)
            val euler = jointEulerMap[joint.name]
                ?: humanoidMapping.entries.firstOrNull { it.value == i }?.key?.let { jointEulerMap[it] }

            val localWithRot = if (euler != null && (euler[0] != 0f || euler[1] != 0f || euler[2] != 0f)) {
                var m = joint.localMatrix.copyOf()
                if (euler[1] != 0f) m = Math3D.rotate(m, euler[1], 0f, 1f, 0f)
                if (euler[0] != 0f) m = Math3D.rotate(m, euler[0], 1f, 0f, 0f)
                if (euler[2] != 0f) m = Math3D.rotate(m, euler[2], 0f, 0f, 1f)
                m
            } else {
                joint.localMatrix
            }

            val world = Math3D.multiplyMM(parentWorld, localWithRot)
            worldMatrices[i] = world
            computed[i] = true

            // 蒙皮变换: skinning = world * inverseBind
            val skinning = Math3D.multiplyMM(world, joint.inverseBindMatrix)
            System.arraycopy(skinning, 0, outSkinningMatrices, i * 16, 16)

            if (outWorldJointPositions != null) {
                outWorldJointPositions[i * 3] = world[12]
                outWorldJointPositions[i * 3 + 1] = world[13]
                outWorldJointPositions[i * 3 + 2] = world[14]
            }
        }

        for (i in joints.indices) {
            computeJointWorld(i)
        }
    }
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
    /**
     * 应用骨骼蒙皮形变，生成形变后的静态 Mesh3D
     */
    fun createDeformedMesh(
        jointEulerMap: Map<String, FloatArray>,
        outWorldJointPositions: FloatArray? = null,
    ): Mesh3D {
        if (skeleton == null || jointIndices == null || jointWeights == null || skeleton.count == 0) {
            return baseMesh
        }

        val skinningMatrices = FloatArray(skeleton.count * 16)
        skeleton.computeSkinning(jointEulerMap, skinningMatrices, outWorldJointPositions)

        val origPositions = baseMesh.positions
        val origNormals = baseMesh.normals
        val vCount = baseMesh.vertexCount

        val defPositions = FloatArray(origPositions.size)
        val defNormals = FloatArray(origNormals.size)

        for (v in 0 until vCount) {
            val bIdx = v * 4
            val px = origPositions[v * 3]
            val py = origPositions[v * 3 + 1]
            val pz = origPositions[v * 3 + 2]

            val nx = origNormals[v * 3]
            val ny = origNormals[v * 3 + 1]
            val nz = origNormals[v * 3 + 2]

            var fx = 0f; var fy = 0f; var fz = 0f
            var fnx = 0f; var fny = 0f; var fnz = 0f

            var totalWeight = 0f
            for (k in 0 until 4) {
                val weight = jointWeights[bIdx + k]
                if (weight > 0.001f) {
                    val joint = jointIndices[bIdx + k].coerceIn(0, skeleton.count - 1)
                    val mOffset = joint * 16

                    // Transform position by skinning matrix
                    val m0 = skinningMatrices[mOffset];     val m4 = skinningMatrices[mOffset + 4]
                    val m8 = skinningMatrices[mOffset + 8]; val m12 = skinningMatrices[mOffset + 12]
                    val m1 = skinningMatrices[mOffset + 1]; val m5 = skinningMatrices[mOffset + 5]
                    val m9 = skinningMatrices[mOffset + 9]; val m13 = skinningMatrices[mOffset + 13]
                    val m2 = skinningMatrices[mOffset + 2]; val m6 = skinningMatrices[mOffset + 6]
                    val m10 = skinningMatrices[mOffset + 10]; val m14 = skinningMatrices[mOffset + 14]

                    fx += weight * (m0 * px + m4 * py + m8 * pz + m12)
                    fy += weight * (m1 * px + m5 * py + m9 * pz + m13)
                    fz += weight * (m2 * px + m6 * py + m10 * pz + m14)

                    // Transform normal by rotation part of skinning matrix
                    fnx += weight * (m0 * nx + m4 * ny + m8 * nz)
                    fny += weight * (m1 * nx + m5 * ny + m9 * nz)
                    fnz += weight * (m2 * nx + m6 * ny + m10 * nz)

                    totalWeight += weight
                }
            }

            if (totalWeight < 0.001f) {
                // 未分配权重的顶点保持原位
                defPositions[v * 3] = px
                defPositions[v * 3 + 1] = py
                defPositions[v * 3 + 2] = pz
                defNormals[v * 3] = nx
                defNormals[v * 3 + 1] = ny
                defNormals[v * 3 + 2] = nz
            } else {
                defPositions[v * 3] = fx
                defPositions[v * 3 + 1] = fy
                defPositions[v * 3 + 2] = fz

                val nLen = kotlin.math.hypot(kotlin.math.hypot(fnx, fny), fnz).coerceAtLeast(1e-6f)
                defNormals[v * 3] = fnx / nLen
                defNormals[v * 3 + 1] = fny / nLen
                defNormals[v * 3 + 2] = fnz / nLen
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
}
