/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 纯 Kotlin 高性能 glTF 2.0 / GLB 与 VRM 二进制模型解析器
 */
object GlbParser {

    private const val GLTF_MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_TYPE_BIN = 0x004E4942  // "BIN\0"

    fun parse(stream: InputStream): SkinnedMesh3D {
        val bytes = stream.readBytes()
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): SkinnedMesh3D {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 1. 验证 12 字节 GLB 文件头
        val magic = buf.int
        require(magic == GLTF_MAGIC) { "Invalid GLB header magic: 0x${Integer.toHexString(magic)}" }
        val version = buf.int
        require(version == 2) { "Unsupported glTF version: $version" }
        val totalLength = buf.int

        // 2. 读取 Chunks (Chunk 0: JSON, Chunk 1: BIN)
        var jsonText = ""
        var binBuffer: ByteBuffer? = null

        while (buf.hasRemaining()) {
            val chunkLength = buf.int
            val chunkType = buf.int
            if (chunkType == CHUNK_TYPE_JSON) {
                val jsonBytes = ByteArray(chunkLength)
                buf.get(jsonBytes)
                jsonText = String(jsonBytes, Charsets.UTF_8)
            } else if (chunkType == CHUNK_TYPE_BIN) {
                val binSlice = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
                binSlice.limit(chunkLength)
                binBuffer = binSlice
                buf.position(buf.position() + chunkLength)
            } else {
                buf.position(buf.position() + chunkLength)
            }
        }

        require(jsonText.isNotEmpty()) { "GLB JSON chunk not found" }
        require(binBuffer != null) { "GLB BIN buffer chunk not found" }

        val json = JSONObject(jsonText)
        val bufferViews = json.optJSONArray("bufferViews") ?: JSONArray()
        val accessors = json.optJSONArray("accessors") ?: JSONArray()
        val meshes = json.optJSONArray("meshes") ?: JSONArray()
        val nodes = json.optJSONArray("nodes") ?: JSONArray()
        val skins = json.optJSONArray("skins")

        fun getAccessorFloats(accessorIdx: Int): FloatArray {
            if (accessorIdx < 0 || accessorIdx >= accessors.length()) return FloatArray(0)
            val acc = accessors.getJSONObject(accessorIdx)
            val bvIdx = acc.getInt("bufferView")
            val bv = bufferViews.getJSONObject(bvIdx)

            val bvOffset = bv.optInt("byteOffset", 0)
            val accOffset = acc.optInt("byteOffset", 0)
            val count = acc.getInt("count")
            val type = acc.getString("type")
            val numComponents = when (type) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                "MAT4" -> 16
                else -> 1
            }

            val componentType = acc.getInt("componentType")
            val totalFloats = count * numComponents
            val result = FloatArray(totalFloats)

            val slice = binBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            slice.position(bvOffset + accOffset)

            when (componentType) {
                5126 -> { // FLOAT
                    for (i in 0 until totalFloats) {
                        result[i] = slice.float
                    }
                }
                5121 -> { // UNSIGNED_BYTE (normalized or raw)
                    for (i in 0 until totalFloats) {
                        result[i] = (slice.get().toInt() and 0xFF) / 255.0f
                    }
                }
                5123 -> { // UNSIGNED_SHORT
                    for (i in 0 until totalFloats) {
                        result[i] = (slice.short.toInt() and 0xFFFF) / 65535.0f
                    }
                }
            }
            return result
        }

        fun getAccessorInts(accessorIdx: Int): IntArray {
            if (accessorIdx < 0 || accessorIdx >= accessors.length()) return IntArray(0)
            val acc = accessors.getJSONObject(accessorIdx)
            val bvIdx = acc.getInt("bufferView")
            val bv = bufferViews.getJSONObject(bvIdx)

            val bvOffset = bv.optInt("byteOffset", 0)
            val accOffset = acc.optInt("byteOffset", 0)
            val count = acc.getInt("count")
            val type = acc.getString("type")
            val numComponents = when (type) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                else -> 1
            }

            val componentType = acc.getInt("componentType")
            val totalInts = count * numComponents
            val result = IntArray(totalInts)

            val slice = binBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            slice.position(bvOffset + accOffset)

            when (componentType) {
                5121 -> { // UNSIGNED_BYTE
                    for (i in 0 until totalInts) {
                        result[i] = slice.get().toInt() and 0xFF
                    }
                }
                5123 -> { // UNSIGNED_SHORT
                    for (i in 0 until totalInts) {
                        result[i] = slice.short.toInt() and 0xFFFF
                    }
                }
                5125 -> { // UNSIGNED_INT
                    for (i in 0 until totalInts) {
                        result[i] = slice.int
                    }
                }
                5120 -> { // BYTE
                    for (i in 0 until totalInts) {
                        result[i] = slice.get().toInt()
                    }
                }
                5122 -> { // SHORT
                    for (i in 0 until totalInts) {
                        result[i] = slice.short.toInt()
                    }
                }
            }
            return result
        }

        // 3. 读取 Mesh 顶点与面数据 (第一组有效图元)
        var positions = FloatArray(0)
        var normals = FloatArray(0)
        var indices = IntArray(0)
        var joints0: IntArray? = null
        var weights0: FloatArray? = null

        if (meshes.length() > 0) {
            val meshObj = meshes.getJSONObject(0)
            val primitives = meshObj.getJSONArray("primitives")
            if (primitives.length() > 0) {
                val prim = primitives.getJSONObject(0)
                val attrs = prim.getJSONObject("attributes")

                if (attrs.has("POSITION")) {
                    positions = getAccessorFloats(attrs.getInt("POSITION"))
                }
                if (attrs.has("NORMAL")) {
                    normals = getAccessorFloats(attrs.getInt("NORMAL"))
                }
                if (prim.has("indices")) {
                    indices = getAccessorInts(prim.getInt("indices"))
                }
                if (attrs.has("JOINTS_0")) {
                    joints0 = getAccessorInts(attrs.getInt("JOINTS_0"))
                }
                if (attrs.has("WEIGHTS_0")) {
                    weights0 = getAccessorFloats(attrs.getInt("WEIGHTS_0"))
                }
            }
        }

        // 4. 计算法线与线框索引
        if (normals.isEmpty() && positions.isNotEmpty() && indices.isNotEmpty()) {
            normals = computeNormals(positions, indices)
        }
        val wireframeIndices = Mesh3D.extractWireframeIndices(indices)
        val edgeTriangles = Mesh3D.computeEdgeTriangles(indices, wireframeIndices)
        val baseMesh = Mesh3D(positions, normals, indices, wireframeIndices, edgeTriangles)

        // 5. 解析骨架层级 (Skins & Nodes)
        var skeleton: Skeleton3D? = null
        if (skins != null && skins.length() > 0) {
            val skinObj = skins.getJSONObject(0)
            val jointNodeIndices = skinObj.getJSONArray("joints")
            val invBindIdx = skinObj.optInt("inverseBindMatrices", -1)
            val invBinds = if (invBindIdx >= 0) getAccessorFloats(invBindIdx) else FloatArray(0)

            val jointCount = jointNodeIndices.length()
            val jointList = ArrayList<Joint3D>(jointCount)
            val nodeToJointIndex = HashMap<Int, Int>()

            for (j in 0 until jointCount) {
                nodeToJointIndex[jointNodeIndices.getInt(j)] = j
            }

            // 读取 VRM 扩展中的标准骨骼映射
            val humanoidMap = HashMap<String, Int>()
            val extensions = json.optJSONObject("extensions")
            if (extensions != null) {
                // VRM 0.x
                extensions.optJSONObject("VRM")?.optJSONObject("humanoid")?.optJSONArray("bones")?.let { vrmBones ->
                    for (k in 0 until vrmBones.length()) {
                        val bObj = vrmBones.getJSONObject(k)
                        val bName = bObj.optString("bone")
                        val nIdx = bObj.optInt("node", -1)
                        val jIdx = nodeToJointIndex[nIdx]
                        if (jIdx != null && bName.isNotEmpty()) {
                            humanoidMap[bName] = jIdx
                        }
                    }
                }
                // VRM 1.0 (VRMC_vrm)
                extensions.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")?.optJSONObject("humanBones")?.let { vrmcHumanBones ->
                    for (bName in vrmcHumanBones.keys()) {
                        val bObj = vrmcHumanBones.getJSONObject(bName)
                        val nIdx = bObj.optInt("node", -1)
                        val jIdx = nodeToJointIndex[nIdx]
                        if (jIdx != null) {
                            humanoidMap[bName] = jIdx
                        }
                    }
                }
            }

            for (j in 0 until jointCount) {
                val nodeIdx = jointNodeIndices.getInt(j)
                val nodeObj = nodes.getJSONObject(nodeIdx)
                val name = nodeObj.optString("name", "Joint_$j")

                // 解析局部变换矩阵
                val localMatrix = parseNodeLocalMatrix(nodeObj)

                // 逆绑定矩阵 (MAT4 = 16 floats)
                val invBind = if (j * 16 + 15 < invBinds.size) {
                    invBinds.copyOfRange(j * 16, j * 16 + 16)
                } else {
                    Math3D.identity()
                }

                // 子节点列表
                val childrenIndices = ArrayList<Int>()
                val chArray = nodeObj.optJSONArray("children")
                if (chArray != null) {
                    for (c in 0 until chArray.length()) {
                        val cNodeIdx = chArray.getInt(c)
                        val cJointIdx = nodeToJointIndex[cNodeIdx]
                        if (cJointIdx != null) {
                            childrenIndices.add(cJointIdx)
                        }
                    }
                }

                jointList.add(
                    Joint3D(
                        index = j,
                        name = name,
                        parentIndex = -1, // 下一步反向查找填充
                        localMatrix = localMatrix,
                        inverseBindMatrix = invBind,
                        children = childrenIndices,
                    )
                )

                // 填充启发式人形与动物骨骼映射 (若 VRM 未显式声明)
                if (!humanoidMap.containsValue(j)) {
                    val lower = name.lowercase()
                    val isL = lower.contains("left") || lower.startsWith("l_") || lower.startsWith("l.") ||
                        lower.endsWith(".l") || lower.endsWith("_l") || lower.contains(".l.") || lower.contains("_l_")
                    val isR = lower.contains("right") || lower.startsWith("r_") || lower.startsWith("r.") ||
                        lower.endsWith(".r") || lower.endsWith("_r") || lower.contains(".r.") || lower.contains("_r_")

                    when {
                        lower.contains("head") -> humanoidMap["head"] = j
                        lower.contains("neck") -> humanoidMap["neck"] = j
                        lower.contains("chest") || lower.contains("spine_02") || lower.contains("upperchest") -> humanoidMap["chest"] = j
                        lower.contains("spine") -> humanoidMap["spine"] = j
                        lower.contains("hips") || lower.contains("pelvis") -> humanoidMap["hips"] = j

                        // 猫咪尾巴与四足骨骼
                        lower.contains("tail1") || lower.contains("tail.001") -> humanoidMap["tail1"] = j
                        lower.contains("tail2") || lower.contains("tail.002") -> humanoidMap["tail2"] = j
                        lower.contains("tail3") || lower.contains("tail.003") -> humanoidMap["tail3"] = j
                        lower.contains("tail4") || lower.contains("tail.004") -> humanoidMap["tail4"] = j
                        lower == "l_front_leg" || lower == "leftfrontleg" || (lower.contains("front") && lower.contains("leg") && !lower.contains("paw") && !lower.contains("shoulder") && isL) -> humanoidMap["leftFrontLeg"] = j
                        lower == "r_front_leg" || lower == "rightfrontleg" || (lower.contains("front") && lower.contains("leg") && !lower.contains("paw") && !lower.contains("shoulder") && isR) -> humanoidMap["rightFrontLeg"] = j
                        lower == "l_hind_thigh" || lower == "lefthindthigh" || (lower.contains("hind") && (lower.contains("thigh") || lower.contains("leg")) && !lower.contains("foot") && !lower.contains("calf") && isL) -> humanoidMap["leftHindLeg"] = j
                        lower == "r_hind_thigh" || lower == "righthindthigh" || (lower.contains("hind") && (lower.contains("thigh") || lower.contains("leg")) && !lower.contains("foot") && !lower.contains("calf") && isR) -> humanoidMap["rightHindLeg"] = j

                        // 左侧肢体
                        isL && (lower.contains("shoulder") || lower.contains("clavicle")) -> humanoidMap["leftShoulder"] = j
                        isL && (lower.contains("arm") || lower.contains("upperarm")) && !lower.contains("fore") && !lower.contains("lower") -> humanoidMap["leftUpperArm"] = j
                        isL && (lower.contains("forearm") || lower.contains("lowerarm") || lower.contains("elbow")) -> humanoidMap["leftLowerArm"] = j
                        isL && (lower.contains("hand") || lower.contains("wrist")) -> humanoidMap["leftHand"] = j
                        isL && (lower.contains("thigh") || lower.contains("upleg") || (lower.contains("leg") && !lower.contains("lower") && !lower.contains("calf") && !lower.contains("shin"))) -> humanoidMap["leftUpperLeg"] = j
                        isL && (lower.contains("calf") || lower.contains("shin") || lower.contains("knee") || lower.contains("lowerleg")) -> humanoidMap["leftLowerLeg"] = j
                        isL && (lower.contains("foot") || lower.contains("ankle")) -> humanoidMap["leftFoot"] = j

                        // 右侧肢体
                        isR && (lower.contains("shoulder") || lower.contains("clavicle")) -> humanoidMap["rightShoulder"] = j
                        isR && (lower.contains("arm") || lower.contains("upperarm")) && !lower.contains("fore") && !lower.contains("lower") -> humanoidMap["rightUpperArm"] = j
                        isR && (lower.contains("forearm") || lower.contains("lowerarm") || lower.contains("elbow")) -> humanoidMap["rightLowerArm"] = j
                        isR && (lower.contains("hand") || lower.contains("wrist")) -> humanoidMap["rightHand"] = j
                        isR && (lower.contains("thigh") || lower.contains("upleg") || (lower.contains("leg") && !lower.contains("lower") && !lower.contains("calf") && !lower.contains("shin"))) -> humanoidMap["rightUpperLeg"] = j
                        isR && (lower.contains("calf") || lower.contains("shin") || lower.contains("knee") || lower.contains("lowerleg")) -> humanoidMap["rightLowerLeg"] = j
                        isR && (lower.contains("foot") || lower.contains("ankle")) -> humanoidMap["rightFoot"] = j
                    }
                }
            }

            // 根据 children 树反向填充 parentIndex
            val resolvedJoints = ArrayList<Joint3D>(jointCount)
            for (j in 0 until jointCount) {
                val orig = jointList[j]
                var pIdx = -1
                for (p in 0 until jointCount) {
                    if (jointList[p].children.contains(j)) {
                        pIdx = p
                        break
                    }
                }
                resolvedJoints.add(orig.copy(parentIndex = pIdx))
            }

            skeleton = Skeleton3D(resolvedJoints, humanoidMap)
        }

        return SkinnedMesh3D(
            baseMesh = baseMesh,
            skeleton = skeleton,
            jointIndices = joints0,
            jointWeights = weights0,
        )
    }

    private fun parseNodeLocalMatrix(node: JSONObject): FloatArray {
        if (node.has("matrix")) {
            val mArr = node.getJSONArray("matrix")
            val res = FloatArray(16)
            for (i in 0..15) res[i] = mArr.getDouble(i).toFloat()
            return res
        }

        var m = Math3D.identity()

        if (node.has("translation")) {
            val tArr = node.getJSONArray("translation")
            val tx = tArr.getDouble(0).toFloat()
            val ty = tArr.getDouble(1).toFloat()
            val tz = tArr.getDouble(2).toFloat()
            m = Math3D.translate(m, tx, ty, tz)
        }

        if (node.has("rotation")) {
            val rArr = node.getJSONArray("rotation")
            val qx = rArr.getDouble(0).toFloat()
            val qy = rArr.getDouble(1).toFloat()
            val qz = rArr.getDouble(2).toFloat()
            val qw = rArr.getDouble(3).toFloat()
            val rotMat = quaternionToMatrix(qx, qy, qz, qw)
            m = Math3D.multiplyMM(m, rotMat)
        }

        if (node.has("scale")) {
            val sArr = node.getJSONArray("scale")
            val sx = sArr.getDouble(0).toFloat()
            val sy = sArr.getDouble(1).toFloat()
            val sz = sArr.getDouble(2).toFloat()
            m = Math3D.scale(m, sx, sy, sz)
        }

        return m
    }

    private fun quaternionToMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val m = FloatArray(16)
        val xx = x * x; val xy = x * y; val xz = x * z; val xw = x * w
        val yy = y * y; val yz = y * z; val yw = y * w
        val zz = z * z; val zw = z * w

        m[0] = 1f - 2f * (yy + zz)
        m[1] = 2f * (xy + zw)
        m[2] = 2f * (xz - yw)
        m[3] = 0f

        m[4] = 2f * (xy - zw)
        m[5] = 1f - 2f * (xx + zz)
        m[6] = 2f * (yz + xw)
        m[7] = 0f

        m[8] = 2f * (xz + yw)
        m[9] = 2f * (yz - xw)
        m[10] = 1f - 2f * (xx + yy)
        m[11] = 0f

        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
        return m
    }

    private fun computeNormals(pos: FloatArray, indices: IntArray): FloatArray {
        val norms = FloatArray(pos.size)
        var i = 0
        while (i + 2 < indices.size) {
            val i0 = indices[i]; val i1 = indices[i + 1]; val i2 = indices[i + 2]
            val p0x = pos[i0 * 3]; val p0y = pos[i0 * 3 + 1]; val p0z = pos[i0 * 3 + 2]
            val p1x = pos[i1 * 3]; val p1y = pos[i1 * 3 + 1]; val p1z = pos[i1 * 3 + 2]
            val p2x = pos[i2 * 3]; val p2y = pos[i2 * 3 + 1]; val p2z = pos[i2 * 3 + 2]

            val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
            val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z

            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x

            norms[i0 * 3] += nx; norms[i0 * 3 + 1] += ny; norms[i0 * 3 + 2] += nz
            norms[i1 * 3] += nx; norms[i1 * 3 + 1] += ny; norms[i1 * 3 + 2] += nz
            norms[i2 * 3] += nx; norms[i2 * 3 + 1] += ny; norms[i2 * 3 + 2] += nz
            i += 3
        }

        for (v in 0 until pos.size / 3) {
            val nx = norms[v * 3]; val ny = norms[v * 3 + 1]; val nz = norms[v * 3 + 2]
            val len = kotlin.math.hypot(kotlin.math.hypot(nx, ny), nz).coerceAtLeast(1e-6f)
            norms[v * 3] = nx / len
            norms[v * 3 + 1] = ny / len
            norms[v * 3 + 2] = nz / len
        }
        return norms
    }
}
