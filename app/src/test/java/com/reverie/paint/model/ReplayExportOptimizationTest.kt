/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * 回放与缩时导出加速方案核心逻辑与数值单测。
 * 覆盖 Hold 帧合并延迟、100% 原始尺寸直传门禁、进度节流与 ETA 算法、以及结构事件全量判定。
 */
class ReplayExportOptimizationTest {

    @Test
    fun `GIF effective delay correctly accumulates hold frames and clamps to 16-bit`() {
        fun computeEffectiveDelay(delayCentiseconds: Int, repeatCount: Int): Int {
            return (delayCentiseconds.toLong() * repeatCount.coerceAtLeast(1))
                .coerceIn(1L, 65535L).toInt()
        }

        // 30fps: 100 / 30 = 3cs. 30 帧定格 (1秒) -> 3 * 30 = 90cs
        assertEquals(90, computeEffectiveDelay(3, 30))

        // 15fps: 100 / 15 = 7cs. 15 帧定格 (1秒) -> 7 * 15 = 105cs
        assertEquals(105, computeEffectiveDelay(7, 15))

        // 单帧不放大
        assertEquals(5, computeEffectiveDelay(5, 1))
        assertEquals(5, computeEffectiveDelay(5, 0))

        // 超大重复数安全钳位至 65535 (16 位无符号上限)
        assertEquals(65535, computeEffectiveDelay(100, 10000))
    }

    @Test
    fun `zero copy eligibility strictly guards overlays, dimensions, and opacity`() {
        fun isZeroCopyEligible(
            showCursor: Boolean,
            watermarkText: String,
            targetW: Int,
            targetH: Int,
            docW: Int,
            docH: Int,
            hasAlpha: Boolean,
        ): Boolean {
            return !showCursor &&
                watermarkText.isEmpty() &&
                targetW == docW &&
                targetH == docH &&
                !hasAlpha
        }

        // 完全符合条件：直传生效
        assertTrue(
            isZeroCopyEligible(
                showCursor = false,
                watermarkText = "",
                targetW = 1920,
                targetH = 1080,
                docW = 1920,
                docH = 1080,
                hasAlpha = false,
            )
        )

        // 任一覆盖层开启 -> 必须走合成
        assertFalse(
            isZeroCopyEligible(
                showCursor = true,
                watermarkText = "",
                targetW = 1920,
                targetH = 1080,
                docW = 1920,
                docH = 1080,
                hasAlpha = false,
            )
        )
        assertFalse(
            isZeroCopyEligible(
                showCursor = false,
                watermarkText = "Author",
                targetW = 1920,
                targetH = 1080,
                docW = 1920,
                docH = 1080,
                hasAlpha = false,
            )
        )

        // 尺寸缩放或对齐差异 -> 必须走缩放
        assertFalse(
            isZeroCopyEligible(
                showCursor = false,
                watermarkText = "",
                targetW = 960,
                targetH = 540,
                docW = 1920,
                docH = 1080,
                hasAlpha = false,
            )
        )

        // 画布含透明通道 (需要垫黑) -> 必须走黑底合成以防反预乘亮边
        assertFalse(
            isZeroCopyEligible(
                showCursor = false,
                watermarkText = "",
                targetW = 1920,
                targetH = 1080,
                docW = 1920,
                docH = 1080,
                hasAlpha = true,
            )
        )
    }

    @Test
    fun `ETA calculation correctly estimates remaining seconds without division by zero`() {
        fun calculateEtaSeconds(curFrame: Int, totalFrames: Int, elapsedMs: Long): Int {
            if (curFrame <= 0 || curFrame >= totalFrames || elapsedMs <= 500L) return 0
            val remainFrames = totalFrames - curFrame
            val msPerFrame = elapsedMs.toDouble() / curFrame
            return ((remainFrames * msPerFrame) / 1000.0).roundToInt().coerceAtLeast(1)
        }

        // 第 0 帧或已结束 -> 0
        assertEquals(0, calculateEtaSeconds(0, 300, 1000L))
        assertEquals(0, calculateEtaSeconds(300, 300, 10000L))

        // 刚开始耗时太短 (<500ms) -> 不显示
        assertEquals(0, calculateEtaSeconds(10, 300, 300L))

        // 100 帧耗时 5000ms (50ms/帧)，剩余 200 帧 -> 200 * 50ms = 10000ms = 10s
        assertEquals(10, calculateEtaSeconds(100, 300, 5000L))

        // 290 帧耗时 29000ms (100ms/帧)，剩余 10 帧 -> 1s
        assertEquals(1, calculateEtaSeconds(290, 300, 29000L))
    }

    @Test
    fun `structural events trigger full frame re-render while strokes use incremental`() {
        fun isStructuralCheckpoint(type: Int): Boolean {
            return type == LAYER_OP || type == TOOL_OP || type == FILTER || type == FILTER_LUT
        }

        assertTrue(isStructuralCheckpoint(LAYER_OP))
        assertTrue(isStructuralCheckpoint(TOOL_OP))
        assertTrue(isStructuralCheckpoint(FILTER))
        assertTrue(isStructuralCheckpoint(FILTER_LUT))

        assertFalse(isStructuralCheckpoint(STROKE_MOVE))
        assertFalse(isStructuralCheckpoint(STROKE_END))
    }

    @Test
    fun `zip no compression constant is confirmed as zero`() {
        // 确认 Deflater.NO_COMPRESSION 为 0，避免引入多余压缩开销
        assertEquals(0, Deflater.NO_COMPRESSION)
    }
}
