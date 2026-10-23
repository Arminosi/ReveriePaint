/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 画布装饰层（辅助线、选区轮廓、像素网格）的可见性与几何口径。
 *
 * Compose 覆盖层（CanvasOverlay）、主画布（CanvasTouchView.drawCanvas）和前缓冲重放
 * （FrontBufferDecorRecorder）共用这里的判据，保证三处画的是同一份内容、同一套坐标。
 * 纯 JVM，可单测。
 */
internal object CanvasDecorGeometry {
    /**
     * 绘图辅助线是否显示。只看 [GuideMode]，**不看** `assistedDrawing`：
     * 3D 透视时 assistedDrawing 可能为 false（只显示不吸附），辅助线照样要画。
     * 与 drawDrawingGuides 内部的门（CanvasGuidesOverlay.kt）是同一个判据。
     */
    fun guidesVisible(guide: DrawingGuideConfig): Boolean = guide.mode != GuideMode.OFF

    /**
     * 选区蚂蚁线是否显示（CanvasOverlay 原判据）：非变换工具、存在选区，
     * 且不是"正在用替换模式框新选区"。
     */
    fun selectionOutlineVisible(
        hasSelection: Boolean,
        isTransform: Boolean,
        isSelecting: Boolean,
        selectionMode: Int,
    ): Boolean = !isTransform && hasSelection && (!isSelecting || selectionMode != 0)

    /**
     * Compose 覆盖层的位图尺寸口径（fork 现状：renderW/H，未设置时退回文档尺寸）。
     * 上游 ed32b1a8 已改成"瓦片网格 → 整图 → 文档"；合并时只改这里，覆盖层与前缓冲重放一起跟着变。
     */
    fun overlayBitmapSize(renderSize: Int, docSize: Int): Int = if (renderSize > 0) renderSize else docSize

    /** 像素网格的显示阈值：缩放到 4 倍起才画。 */
    const val PIXEL_GRID_MIN_SCALE = 4f

    /** 像素网格透明度：4x 起从 0 线性升到 8x 的 0.15。低于阈值返回 0。 */
    fun pixelGridAlpha(scale: Float): Float {
        if (!(scale >= PIXEL_GRID_MIN_SCALE)) return 0f
        return ((scale - PIXEL_GRID_MIN_SCALE) / 4f).coerceIn(0f, 1f) * 0.15f
    }

    /** 像素网格是否值得画（透明度低于 0.01 视同不画）。 */
    fun pixelGridDrawn(enabled: Boolean, scale: Float): Boolean = enabled && pixelGridAlpha(scale) > 0.01f

    /** 可见区 `[gx0..gx1] x [gy0..gy1]` 的网格线条数（竖线 + 横线）。 */
    fun pixelGridLineCount(gx0: Int, gx1: Int, gy0: Int, gy1: Int): Int =
        (gx1 - gx0 + 1).coerceAtLeast(0) + (gy1 - gy0 + 1).coerceAtLeast(0)

    /**
     * 把可见区的网格线写进 [out]（`x0, y0, x1, y1` 一组，位图居中坐标），返回写入的 float 个数。
     * 调用方保证 `out.size >= pixelGridLineCount(...) * 4`。零分配。
     */
    fun fillPixelGridLines(
        out: FloatArray,
        gx0: Int,
        gx1: Int,
        gy0: Int,
        gy1: Int,
        halfW: Float,
        halfH: Float,
    ): Int {
        var idx = 0
        val y0 = gy0.toFloat() - halfH
        val y1 = gy1.toFloat() - halfH
        for (gx in gx0..gx1) {
            val x = gx.toFloat() - halfW
            out[idx++] = x
            out[idx++] = y0
            out[idx++] = x
            out[idx++] = y1
        }
        val x0 = gx0.toFloat() - halfW
        val x1 = gx1.toFloat() - halfW
        for (gy in gy0..gy1) {
            val y = gy.toFloat() - halfH
            out[idx++] = x0
            out[idx++] = y
            out[idx++] = x1
            out[idx++] = y
        }
        return idx
    }
}
