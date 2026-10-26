/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * glTF / GLB 解析健壮性测试
 *
 * 1. 多 mesh / 多 primitive 合并 (导入的 VRM 角色身体+衣服+眼睛常是多个图元, 旧实现只看第一个 → 缺件);
 * 2. bufferView.byteStride 交错顶点缓冲 (Unity / 部分导出器的默认布局);
 * 3. 合并后顶点索引、关节索引、蒙皮权重必须整体平移而不错位。
 */
class GlbPrimitiveMergeTest {

    /** 内联构造一个两图元 + 交错顶点缓冲 + 双关节蒙皮的最小 GLB */
    private fun buildTwoPrimitiveSkinnedGlb(): ByteArray {
        val bin = ByteBuffer.allocate(356).order(ByteOrder.LITTLE_ENDIAN)
        // [0..95] 交错 POSITION: 每个顶点 3 float + 4 字节填充 (byteStride = 16)
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
            2f, 0f, 0f,
            3f, 0f, 0f,
            2f, 1f, 0f,
        )
        for (i in positions.indices) {
            bin.putFloat(positions[i])
            if (i % 3 == 2) bin.putInt(0)
        }
        // [96..107] 索引 (ushort): 两个图元各自一套 0,1,2
        bin.position(96)
        for (t in 0 until 2) {
            for (i in 0 until 3) bin.putShort(i.toShort())
        }
        // [108..131] JOINTS_0 (ubyte VEC4): 图元0 全部关节0, 图元1 全部关节1
        bin.position(108)
        for (t in 0 until 2) {
            for (i in 0 until 3) for (c in 0 until 4) bin.put(if (t == 0) 0 else 1)
        }
        // [132..227] WEIGHTS_0 (float VEC4): 各自权重 1,0,0,0
        bin.position(132)
        for (t in 0 until 2) {
            for (i in 0 until 3) {
                bin.putFloat(1f); bin.putFloat(0f); bin.putFloat(0f); bin.putFloat(0f)
            }
        }
        // [228..355] 逆绑定矩阵: 关节0 单位阵, 关节1 为 T(0,-1,0)
        bin.position(228)
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        for (v in identity) bin.putFloat(v)
        val invBindHead = identity.copyOf().also { it[13] = -1f }
        for (v in invBindHead) bin.putFloat(v)

        val json = """
            {
              "asset": {"version": "2.0"},
              "buffers": [{"byteLength": 356}],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 96, "byteStride": 16, "target": 34962},
                {"buffer": 0, "byteOffset": 96, "byteLength": 12, "target": 34963},
                {"buffer": 0, "byteOffset": 108, "byteLength": 24, "target": 34962},
                {"buffer": 0, "byteOffset": 132, "byteLength": 96, "target": 34962},
                {"buffer": 0, "byteOffset": 228, "byteLength": 128}
              ],
              "accessors": [
                {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 0, "byteOffset": 48, "componentType": 5126, "count": 3, "type": "VEC3"},
                {"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR"},
                {"bufferView": 1, "byteOffset": 6, "componentType": 5123, "count": 3, "type": "SCALAR"},
                {"bufferView": 2, "byteOffset": 0, "componentType": 5121, "count": 3, "type": "VEC4"},
                {"bufferView": 2, "byteOffset": 12, "componentType": 5121, "count": 3, "type": "VEC4"},
                {"bufferView": 3, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC4"},
                {"bufferView": 3, "byteOffset": 48, "componentType": 5126, "count": 3, "type": "VEC4"},
                {"bufferView": 4, "byteOffset": 0, "componentType": 5126, "count": 2, "type": "MAT4"}
              ],
              "meshes": [
                {"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 4, "WEIGHTS_0": 6}, "indices": 2, "mode": 4}]},
                {"primitives": [{"attributes": {"POSITION": 1, "JOINTS_0": 5, "WEIGHTS_0": 7}, "indices": 3}]}
              ],
              "nodes": [
                {"name": "Root", "children": [1, 2, 3]},
                {"name": "Head", "translation": [0, 1, 0]},
                {"name": "Body", "mesh": 0},
                {"name": "Armor", "mesh": 1}
              ],
              "skins": [{"joints": [0, 1], "inverseBindMatrices": 8}],
              "scenes": [{"nodes": [0, 2, 3]}],
              "scene": 0
            }
        """.trimIndent()

        return packGlb(json, bin.array())
    }

    private fun packGlb(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binPad = (4 - bin.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + bin.size + binPad
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67) // "glTF"
        buf.putInt(2)
        buf.putInt(total)
        buf.putInt(jsonBytes.size + jsonPad)
        buf.putInt(0x4E4F534A) // "JSON"
        buf.put(jsonBytes)
        repeat(jsonPad) { buf.put(' '.code.toByte()) }
        buf.putInt(bin.size + binPad)
        buf.putInt(0x004E4942) // "BIN"
        buf.put(bin)
        repeat(binPad) { buf.put(0) }
        return buf.array()
    }

    @Test
    fun `all primitives are merged and interleaved vertex buffers are decoded`() {
        val skinned = GlbParser.parse(buildTwoPrimitiveSkinnedGlb())
        val mesh = skinned.baseMesh

        assertEquals("两个三角形图元应合并成 6 个顶点", 6, mesh.vertexCount)
        assertEquals("应有两个三角面", 2, mesh.triangleCount)

        // 交错缓冲: 第二个图元的顶点从 byteOffset 48 开始读
        assertEquals(2f, mesh.positions[9], 1e-5f)
        assertEquals(3f, mesh.positions[12], 1e-5f)
        assertEquals(2f, mesh.positions[15], 1e-5f)
        assertEquals(1f, mesh.positions[16], 1e-5f)

        // 索引整体平移 (+3)
        assertTrue(mesh.indices.contentEquals(intArrayOf(0, 1, 2, 3, 4, 5)))

        // 关节索引/权重跟着平移: 后三个顶点绑到关节 1
        val joints = skinned.jointIndices!!
        assertEquals(0, joints[0])
        assertEquals(1, joints[3 * 4])
        assertEquals(1, joints[5 * 4])
        assertEquals(1f, skinned.jointWeights!![3 * 4], 1e-5f)

        // 骨架解析正确
        val skeleton = skinned.skeleton
        assertNotNull(skeleton)
        assertEquals(2, skeleton!!.count)
        val headIdx = skeleton.joints.indexOfFirst { it.name == "Head" }
        val rootIdx = skeleton.joints.indexOfFirst { it.name == "Root" }
        assertTrue(headIdx >= 0 && rootIdx >= 0)
        assertEquals(rootIdx, skeleton.joints[headIdx].parentIndex)

        // 缺失法线时应按合并后的索引重算, 不能残留全零
        assertEquals(mesh.positions.size, mesh.normals.size)
        var normalLength = 0f
        for (i in 2 until 11 step 3) normalLength += kotlin.math.abs(mesh.normals[i])
        assertTrue("normals should be computed", normalLength > 0f)
    }

    @Test
    fun `merged mesh deforms the skinned primitive without touching the static one`() {
        val skinned = GlbParser.parse(buildTwoPrimitiveSkinnedGlb())
        val rest = skinned.createDeformedMesh(emptyMap())
        var maxDev = 0f
        for (i in rest.positions.indices) {
            maxDev = maxOf(maxDev, kotlin.math.abs(rest.positions[i] - skinned.baseMesh.positions[i]))
        }
        assertTrue("rest pose must not move vertices, maxDev=$maxDev", maxDev < 1e-4f)

        val posed = skinned.createDeformedMesh(mapOf("Head" to floatArrayOf(30f, 0f, 0f)))
        // 前三个顶点绑在 Root, 不受 Head 旋转影响
        for (i in 0 until 9) {
            assertEquals(skinned.baseMesh.positions[i], posed.positions[i], 1e-4f)
        }
        // 后三个顶点绑在 Head, 旋转后必须移动, 且保持有限
        var moved = 0f
        for (i in 9 until 18) {
            moved += kotlin.math.abs(posed.positions[i] - skinned.baseMesh.positions[i])
            assertTrue(posed.positions[i].isFinite())
        }
        assertTrue("head-bound vertices should move, moved=$moved", moved > 0.1f)
        // 旋转 30° 不应改变顶点到关节的半径 (刚体变换)
        val head = floatArrayOf(2f, 1f, 0f)
        val r0 = hypot(hypot(head[0], head[1] - 1f), head[2])
        val p = floatArrayOf(posed.positions[15], posed.positions[16], posed.positions[17])
        val r1 = hypot(hypot(p[0], p[1] - 1f), p[2])
        assertEquals(r0, r1, 1e-3f)
    }
}
