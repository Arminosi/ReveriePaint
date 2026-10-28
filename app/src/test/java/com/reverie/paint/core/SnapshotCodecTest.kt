/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 快照二进制布局的 JVM 单测: 按 reverie_core.h 里写死的字段偏移手工拼一份字节缓冲,
 * 验证 Kotlin 解析与小端/偏移假设一致 (C 侧的 offsetof 由 g++ 编译期核对过,
 * 见 docs/plans/CORE-SPLIT.md P0 一节)。
 */
package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SnapshotCodecTest {

    private fun newBuffer(
        count: Int,
        rev: Int = 7,
        currentLayer: Int = 1,
        soloActive: Boolean = false,
    ): ByteBuffer {
        val stride = SnapshotCodec.LAYER_BYTES
        val buf = ByteBuffer.allocate(SnapshotCodec.HEADER_BYTES + count * stride)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SnapshotCodec.MAGIC)
        buf.putInt(1) // abi
        buf.putInt(rev)
        buf.putInt(count)
        buf.putInt(currentLayer)
        buf.putInt(if (soloActive) 1 else 0)
        buf.putInt(stride)
        buf.putInt(0) // reserved
        assertEquals(SnapshotCodec.HEADER_BYTES, buf.position())
        return buf
    }

    private fun ByteBuffer.writeLayer(
        slot: Int,
        id: Long,
        name: String,
        blend: String,
        index: Int,
        nodeType: Int,
        depth: Int,
        colorLabel: Int,
        flags: Int,
        opacity: Double,
        strokeSize: Int = 6,
        strokeColor: Int = 0xFF000000.toInt(),
        strokePosition: Int = 0,
        strokeOpacity: Int = 100,
        fillColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val base = SnapshotCodec.HEADER_BYTES + slot * SnapshotCodec.LAYER_BYTES
        putLong(base + 0, id)
        putDouble(base + 8, opacity)
        putInt(base + 16, index)
        putInt(base + 20, nodeType)
        putInt(base + 24, depth)
        putInt(base + 28, colorLabel)
        putInt(base + 32, strokeSize)
        putInt(base + 36, strokePosition)
        putInt(base + 40, strokeOpacity)
        putInt(base + 44, strokeColor)
        putInt(base + 48, fillColor)
        putInt(base + 52, flags)
        writeString(base + 56, 64, name)
        writeString(base + 120, 32, blend)
    }

    private fun ByteBuffer.writeString(offset: Int, cap: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, cap - 1)
        for (i in 0 until n) put(offset + i, bytes[i])
        put(offset + n, 0)
    }

    @Test
    fun `parse reads header and layer fields from documented offsets`() {
        val buffer = newBuffer(count = 2, rev = 42, currentLayer = 1)
        buffer.writeLayer(
            slot = 0, id = 1001L, name = "颜料图层 1", blend = "normal", index = 0,
            nodeType = 0, depth = 0, colorLabel = 3,
            flags = SnapshotCodec.FLAG_VISIBLE or SnapshotCodec.FLAG_STROKE or
                SnapshotCodec.FLAG_CLIPPED,
            opacity = 0.8, strokeSize = 12, strokeColor = 0xFF112233.toInt(),
            strokePosition = 1, strokeOpacity = 77, fillColor = 0xFF445566.toInt(),
        )
        buffer.writeLayer(
            slot = 1, id = 1002L, name = "组", blend = "multiply", index = 1,
            nodeType = 1, depth = 1, colorLabel = 0,
            flags = SnapshotCodec.FLAG_VISIBLE or SnapshotCodec.FLAG_GROUP or
                SnapshotCodec.FLAG_PASS_THROUGH,
            opacity = 0.55,
        )

        val page = SnapshotCodec.parse(buffer)
        assertNotNull(page)
        page!!
        assertEquals(42, page.rev)
        assertEquals(1, page.currentLayer)
        assertFalse(page.soloActive)
        assertEquals(2, page.layers.size)

        val a = page.layers[0]
        assertEquals(1001L, a.id)
        assertEquals("颜料图层 1", a.name)
        assertEquals("normal", a.blendMode)
        assertEquals(0.8, a.opacity, 0.0)
        assertTrue(a.visible)
        assertTrue(a.isStrokeLayer)
        assertTrue(a.clipped)
        assertFalse(a.isGroup)
        assertEquals(3, a.colorLabel)
        assertEquals(12, a.strokeSize)
        assertEquals(0xFF112233.toInt(), a.strokeColor)
        assertEquals(1, a.strokePosition)
        assertEquals(77, a.strokeOpacity)
        assertEquals(0xFF445566.toInt(), a.fillColor)

        val b = page.layers[1]
        assertEquals(1002L, b.id)
        assertTrue(b.isGroup)
        assertTrue(b.passThrough)
        assertEquals(1, b.depth)
    }

    @Test
    fun `solo active hides non keep layers and ignores native visible bit`() {
        val buffer = newBuffer(count = 2, soloActive = true)
        buffer.writeLayer(slot = 0, id = 1L, name = "keep", blend = "normal", index = 0,
            nodeType = 0, depth = 0, colorLabel = 0, flags = SnapshotCodec.FLAG_VISIBLE,
            opacity = 1.0)
        buffer.writeLayer(slot = 1, id = 2L, name = "shadowed", blend = "normal", index = 1,
            nodeType = 0, depth = 0, colorLabel = 0,
            flags = SnapshotCodec.FLAG_VISIBLE or SnapshotCodec.FLAG_SOLO_SHADOWED,
            opacity = 1.0)
        val page = SnapshotCodec.parse(buffer)!!
        assertTrue(page.soloActive)
        assertTrue(page.layers[0].visible)
        assertFalse(page.layers[1].visible)
    }

    @Test
    fun `bad magic and truncated payload are rejected`() {
        val bad = newBuffer(count = 1)
        bad.writeLayer(slot = 0, id = 1L, name = "a", blend = "normal", index = 0,
            nodeType = 0, depth = 0, colorLabel = 0, flags = 0, opacity = 1.0)
        bad.putInt(0, 0x12345678)
        assertNull(SnapshotCodec.parse(bad))

        val truncated = newBuffer(count = 1)
        truncated.writeLayer(slot = 0, id = 1L, name = "a", blend = "normal", index = 0,
            nodeType = 0, depth = 0, colorLabel = 0, flags = 0, opacity = 1.0)
        truncated.putInt(12, 2) // 头部声称 2 层, 实际只有 1 层
        assertNull(SnapshotCodec.parse(truncated))
    }
}
