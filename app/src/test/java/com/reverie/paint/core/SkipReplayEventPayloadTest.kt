/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingEvents.CONTEXT
import com.reverie.paint.model.RecordingEvents.CONTEXT_EXT
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import com.reverie.paint.model.RecordingEvents.T_CROP
import com.reverie.paint.model.RecordingEvents.T_ENCLOSE_FILL
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V3
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_PROFILE
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_SIZE
import com.reverie.paint.model.RecordingEvents.T_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_WARP
import com.reverie.paint.model.RecordingReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 skipReplayEventPayload 与各事件写入格式的字节长度一致性。
 * 这些测试不触碰 JNI/C++，只验证 RecordingReader 游标推进。
 */
class SkipReplayEventPayloadTest {
    private fun skipAndAssert(
        type: Int,
        version: Int = 2,
        build: RecordingBuffer.() -> Unit,
    ) {
        val buf = RecordingBuffer(16)
        buf.build()
        val r = RecordingReader(buf.data.copyOf(buf.size))
        skipReplayEventPayload(type, r, version)
        assertEquals(buf.size, r.pos)
        assertEquals(0, r.remaining())
    }

    @Test
    fun `stroke move payload`() {
        skipAndAssert(STROKE_MOVE) {
            f32(1f)
            f32(2f)
            f32(0.5f)
        }
    }

    @Test
    fun `context payload`() {
        skipAndAssert(CONTEXT) {
            u8(0)
            u16(3)
            f32(12f)
            f32(1f)
            f32(1f)
            str("normal")
            str("#FF112233")
            u16(2)
        }
    }

    @Test
    fun `context ext payload version 2 and 1`() {
        val base: RecordingBuffer.() -> Unit = {
            repeat(9) { f32(it.toFloat()) }
            str("#FF000000")
            u8(1)
            f32(0.5f)
        }
        skipAndAssert(CONTEXT_EXT, 2) {
            base()
            u8(0)
        }
        skipAndAssert(CONTEXT_EXT, 1) {
            base()
        }
    }

    @Test
    fun `filter and lut payloads`() {
        skipAndAssert(FILTER) {
            u16(1)
            u8(2)
            f64(1.0)
            f64(2.0)
            f64(3.0)
            f64(4.0)
            str("Curves")
        }
        skipAndAssert(FILTER_LUT) {
            u16(1)
            u8(0)
            u32(3)
            writeBytes(byteArrayOf(1, 2, 3))
            str("LUT")
        }
    }

    @Test
    fun `layer op payload`() {
        skipAndAssert(LAYER_OP) {
            u8(1)
            u16(2)
            str("arg")
        }
    }

    @Test
    fun `tool shape polygon and enclose fill payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_SHAPE)
            u8(1)
            f32(1f)
            f32(2f)
            f32(3f)
            f32(4f)
            u8(1)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_POLYGON)
            u8(1)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
        }
        skipAndAssert(TOOL_OP) {
            u8(T_ENCLOSE_FILL)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
            u16(10)
            u8(1)
            u16(0)
            u16(2)
            u16(4)
            u8(0)
            u32(0xFF112233.toInt())
            u8(1)
        }
    }

    @Test
    fun `tool warp crop and select all payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_WARP)
            u16(2)
            repeat(2) {
                f32(1f)
                f32(2f)
            }
            repeat(2) {
                f32(3f)
                f32(4f)
            }
            f64(0.0)
            f64(0.0)
            f64(100.0)
            f64(100.0)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_CROP)
            u16(1)
            u16(2)
            u16(3)
            u16(4)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_SELECT_ALL)
            u16(2)
            u8(0)
        }
    }

    @Test
    fun `tool gradient v3 payload`() {
        skipAndAssert(TOOL_OP) {
            u8(T_GRADIENT_V3)
            f32(1f)
            f32(2f)
            f32(3f)
            f32(4f)
            u8(0)
            u8(0)
            u8(0)
            f32(1f)
            u8(0)
            str("normal")
            u16(2)
            repeat(2) {
                f32(0.5f)
                u32(0xFF112233.toInt())
            }
        }
    }

    @Test
    fun `tool liquify payloads`() {
        skipAndAssert(TOOL_OP) {
            u8(T_LIQUIFY_PROFILE)
            u8(1)
            f32(0.5f)
        }
        skipAndAssert(TOOL_OP) {
            u8(T_LIQUIFY_SIZE)
            f32(20f)
        }
    }
}
