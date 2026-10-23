/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 画布装饰层的 Compose 绘制, CanvasOverlay 与前缓冲重放 ([FrontBufferDecorRecorder]) 共用。
 * 调用方已把 DrawScope 变换到"位图居中"坐标。
 */
internal object CanvasDecorDraw {
    /**
     * 选区边界黑白交替蚂蚁线。[scale] = zoom * fitScale (已 coerceAtLeast(0.001f)),
     * [animFraction] 为流动相位 (前缓冲重放固定为 0)。
     */
    fun DrawScope.drawSelectionAnts(outlinePath: Path, scale: Float, animFraction: Float) {
        val strokeW = 1.0.dp.toPx() / scale
        val blackStrokeW = 1.4.dp.toPx() / scale
        val dashInterval = 3.5.dp.toPx() / scale
        val antPhase = animFraction * (dashInterval * 2)

        drawPath(
            path = outlinePath,
            color = Color.Black.copy(alpha = 0.85f),
            style = Stroke(
                width = blackStrokeW,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashInterval, dashInterval),
                    phase = antPhase,
                ),
            ),
        )
        drawPath(
            path = outlinePath,
            color = Color.White,
            style = Stroke(
                width = strokeW,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(dashInterval, dashInterval),
                    phase = antPhase + dashInterval,
                ),
            ),
        )
    }
}
