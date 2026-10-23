/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint

/**
 * 绘画中辅助吸附动态导引线 (透视灭点射线 / 等轴测 / 2D 网格高亮) 的绘制。
 *
 * 几何由 CanvasTouchView.computeDynamicGuide 算出, 写成 [SEG_SIZE] 个 float 的屏幕坐标段:
 * `x1, y1, x2, y2, vpX, vpY`。主画布 (onDraw) 与前缓冲 (真墨按住时, GL 线程) 各持一个实例,
 * 画笔不跨线程共享。
 */
internal class DynamicGuidePainter(density: Float) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = android.graphics.Color.argb(70, 0x5A, 0x6E, 0x8A) // Morandi misty blue glow
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = android.graphics.Color.argb(220, 0x78, 0x88, 0x9F) // Morandi accentHi crisp line
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val vpGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = android.graphics.Color.argb(140, 0x5A, 0x6E, 0x8A)
    }
    private val vpCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = android.graphics.Color.argb(255, 0x78, 0x88, 0x9F)
    }
    private val vpCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }
    private val glowR = 14f * density
    private val coreR = 7f * density
    private val centerR = 3.5f * density

    /** 按 [kind] 画 [seg] (从 [offset] 起)。[kind] 取 [KIND_NONE] / [KIND_LINE] / [KIND_LINE_VP]。 */
    fun draw(canvas: Canvas, kind: Int, seg: FloatArray, offset: Int = 0) {
        if (kind != KIND_LINE && kind != KIND_LINE_VP) return
        val x1 = seg[offset]
        val y1 = seg[offset + 1]
        val x2 = seg[offset + 2]
        val y2 = seg[offset + 3]
        canvas.drawLine(x1, y1, x2, y2, glowPaint)
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        if (kind == KIND_LINE_VP) {
            val vx = seg[offset + 4]
            val vy = seg[offset + 5]
            canvas.drawCircle(vx, vy, glowR, vpGlowPaint)
            canvas.drawCircle(vx, vy, coreR, vpCorePaint)
            canvas.drawCircle(vx, vy, centerR, vpCenterPaint)
        }
    }

    companion object {
        const val SEG_SIZE = 6
        const val KIND_NONE = 0
        const val KIND_LINE = 1
        const val KIND_LINE_VP = 2
    }
}
