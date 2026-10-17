/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import kotlin.math.abs

class Model3DTest {

    @Test
    fun `Model3DState serializes and deserializes correctly`() {
        val state = Model3DState(
            layerId = 42L,
            modelType = "cube",
            rotX = 25.5f,
            rotY = -40f,
            rotZ = 12f,
            panX = 0.3f,
            panY = -0.5f,
            zoom = 1.8f,
            lightYaw = 60f,
            lightPitch = 45f,
            renderMode = RenderMode3D.WIREFRAME,
            baseColor = 0xFFABCDEFL,
            wireframeColor = 0xFF123456L,
        )

        val json = state.toJsonString()
        val restored = Model3DState.fromJson(json)

        assertNotNull(restored)
        assertEquals(state.layerId, restored!!.layerId)
        assertEquals(state.modelType, restored.modelType)
        assertEquals(state.rotX, restored.rotX, 0.01f)
        assertEquals(state.rotY, restored.rotY, 0.01f)
        assertEquals(state.rotZ, restored.rotZ, 0.01f)
        assertEquals(state.panX, restored.panX, 0.01f)
        assertEquals(state.panY, restored.panY, 0.01f)
        assertEquals(state.zoom, restored.zoom, 0.01f)
        assertEquals(state.lightYaw, restored.lightYaw, 0.01f)
        assertEquals(state.lightPitch, restored.lightPitch, 0.01f)
        assertEquals(RenderMode3D.WIREFRAME, restored.renderMode)
        assertEquals(state.baseColor, restored.baseColor)
        assertEquals(state.wireframeColor, restored.wireframeColor)
    }

    @Test
    fun `Math3D identity and multiplication preserves identity`() {
        val ident = Math3D.identity()
        val result = Math3D.multiplyMM(ident, ident)
        for (i in 0..15) {
            assertEquals(ident[i], result[i], 0.0001f)
        }
    }

    @Test
    fun `Math3D translation and scaling modifies diagonal and position`() {
        var m = Math3D.identity()
        m = Math3D.translate(m, 10f, 20f, 30f)
        m = Math3D.scale(m, 2f, 3f, 4f)

        assertEquals(2f, m[0], 0.0001f)
        assertEquals(3f, m[5], 0.0001f)
        assertEquals(4f, m[10], 0.0001f)
        assertEquals(10f, m[12], 0.0001f)
        assertEquals(20f, m[13], 0.0001f)
        assertEquals(30f, m[14], 0.0001f)
    }

    @Test
    fun `Math3D light direction is normalized and maintains forward bias`() {
        val light = Math3D.calculateLightDirection(45f, 30f)
        val len = Math.sqrt((light[0] * light[0] + light[1] * light[1] + light[2] * light[2]).toDouble())
        assertEquals(1.0, len, 0.001)

        // 即使在极端光照仰角/偏角下，Z 轴向前分量依然保持正数，确保正面不会陷入死黑
        val extreme1 = Math3D.calculateLightDirection(120f, 75f)
        assertTrue(extreme1[2] > 0f)
        val extreme2 = Math3D.calculateLightDirection(-120f, -75f)
        assertTrue(extreme2[2] > 0f)
    }

    @Test
    fun `Mesh3D cube generator produces 24 vertices and 12 triangles`() {
        val cube = Mesh3D.createCube(2.0f)
        assertEquals(24, cube.vertexCount)
        assertEquals(12, cube.triangleCount)
        assertTrue(cube.wireframeLineCount > 0)
    }

    @Test
    fun `Mesh3D head mannequin generates valid triangles and wireframes`() {
        val head = Mesh3D.createHeadMannequin()
        assertTrue(head.vertexCount > 20)
        assertTrue(head.triangleCount > 30)
        assertTrue(head.wireframeLineCount > 30)
    }

    @Test
    fun `Mesh3D parseObj parses simple obj string correctly`() {
        val objContent = """
            # Simple pyramid
            v 0.0 1.0 0.0
            v -1.0 -1.0 1.0
            v 1.0 -1.0 1.0
            v 0.0 -1.0 -1.0
            f 1 2 3
            f 1 3 4
            f 1 4 2
            f 2 4 3
        """.trimIndent()

        val mesh = Mesh3D.parseObj(BufferedReader(StringReader(objContent)))
        assertEquals(4, mesh.triangleCount)
        assertTrue(mesh.wireframeLineCount >= 6)
    }

    @Test
    fun `Mesh3D cylinder and sphere produce clean wireframe without diagonal clutter`() {
        val cyl = Mesh3D.createCylinder()
        assertTrue(cyl.triangleCount > 0)
        assertTrue(cyl.wireframeLineCount > 0)
        assertEquals(cyl.wireframeLineCount * 2, cyl.wireframeEdgeTriangles.size)

        val sph = Mesh3D.createSphere()
        assertTrue(sph.triangleCount > 0)
        assertTrue(sph.wireframeLineCount > 0)
        assertEquals(sph.wireframeLineCount * 2, sph.wireframeEdgeTriangles.size)
    }

    @Test
    fun `computeEdgeTriangles associates wireframe edges with adjacent triangles`() {
        // Simple quad made of 2 triangles: (0,1,2) and (0,2,3)
        // Edge 0-2 is shared by both triangles
        val triIndices = intArrayOf(0, 1, 2,  0, 2, 3)
        val wireIndices = intArrayOf(0, 2,  1, 2)
        val edgeTris = Mesh3D.computeEdgeTriangles(triIndices, wireIndices)

        assertEquals(4, edgeTris.size)
        // Edge 0-2 belongs to tri 0 and tri 1
        assertEquals(0, edgeTris[0])
        assertEquals(1, edgeTris[1])
        // Edge 1-2 belongs to tri 0 only
        assertEquals(0, edgeTris[2])
        assertEquals(-1, edgeTris[3])
    }
}
