/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.cos
import kotlin.math.sin

/** 离散快捷操作，与键盘原有的 90° 旋转命令分开。仅改变视图，不修改文档。 */
enum class CanvasViewportStep(val command: String, private val factor: Float, private val degrees: Float) {
    ZOOM_IN("view_zoom_in", 1.2f, 0f),
    ZOOM_OUT("view_zoom_out", 1f / 1.2f, 0f),
    ROTATE_CCW("view_rotate_ccw", 1f, -15f),
    ROTATE_CW("view_rotate_cw", 1f, 15f),
    ;

    /**
     * 平移向量也必须跟着缩放/旋转，才能保住视图中心对应的画布内容。
     * 在屏幕坐标计算补偿，因此画布已有旋转或镜像时仍成立。
     * 这条路径只在点击时调用，不进入触摸采样热路径。
     */
    fun apply(
        zoom: Float, rotation: Float, panX: Float, panY: Float, locked: Boolean,
    ): CanvasViewportTransform? {
        if (locked || !zoom.isFinite() || zoom <= 0f || !rotation.isFinite() ||
            !panX.isFinite() || !panY.isFinite()) return null
        // 与 CanvasTouchView 的手势范围一致，边界处必须用实际倍率补偿平移。
        val nextZoom = if (factor == 1f) zoom else (zoom * factor).coerceIn(0.02f, 128f)
        val ratio = nextZoom / zoom
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val nextPanX = ratio * (panX * c - panY * s)
        val nextPanY = ratio * (panX * s + panY * c)
        if (!nextPanX.isFinite() || !nextPanY.isFinite()) return null
        val nextRotation = if (degrees == 0f) rotation else (rotation % 360f + degrees) % 360f
        return CanvasViewportTransform(nextZoom, nextRotation, nextPanX, nextPanY)
    }

    companion object {
        fun fromCommand(command: String): CanvasViewportStep? = entries.find { it.command == command }
    }
}

data class CanvasViewportTransform(val zoom: Float, val rotation: Float, val panX: Float, val panY: Float)
