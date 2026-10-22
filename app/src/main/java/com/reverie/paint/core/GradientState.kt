/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.reverie.paint.model.GradientColorStop
import com.reverie.paint.model.GradientHandleId
import com.reverie.paint.model.GradientRepeat
import com.reverie.paint.model.GradientType

/**
 * 渐变工具的画布交互式状态容器
 */
class GradientState {
    /** 是否处于画布交互手柄微调阶段 */
    var active by mutableStateOf(false)

    /** 起点与终点 (文档坐标系) */
    var p1 by mutableStateOf(Offset.Zero)
    var p2 by mutableStateOf(Offset.Zero)

    /** 当前激活/拖动中的手柄 ID */
    var activeHandle by mutableIntStateOf(GradientHandleId.NONE)

    /** 渐变形状类型 (线性/径向/角度/对称/菱形) */
    var type by mutableStateOf(GradientType.LINEAR)

    /** 渐变重复方式 (单次/重复/往返) */
    var repeat by mutableStateOf(GradientRepeat.NONE)

    /** 是否反向渐变 */
    var reverse by mutableStateOf(false)

    /** 独立不透明度 (0f..1f) */
    var opacity by mutableFloatStateOf(1f)

    /** 图层混合模式 (normal, multiply, screen, overlay 等) */
    var blendMode by mutableStateOf("normal")

    /** 防色彩断层仿色抖动 (Dither) */
    var dither by mutableStateOf(true)

    /** 是否跟随前景色/背景色动态变化 */
    var isDynamicFgBg by mutableStateOf(true)

    /** 是否跟随前景色/透明度动态变化 */
    var isDynamicFgTrans by mutableStateOf(false)

    /** 静态/自定义多色标列表 (当非动态模式时使用) */
    val stops = mutableStateListOf<GradientColorStop>()

    /** 拖动起始记录点 */
    var dragStartDocPos = Offset.Zero
    var dragOriginalP1 = Offset.Zero
    var dragOriginalP2 = Offset.Zero

    fun setDynamicFgBg() {
        isDynamicFgBg = true
        isDynamicFgTrans = false
    }

    fun setDynamicFgTrans() {
        isDynamicFgBg = false
        isDynamicFgTrans = true
    }

    fun setCustomStops(newStops: List<GradientColorStop>) {
        isDynamicFgBg = false
        isDynamicFgTrans = false
        stops.clear()
        stops.addAll(newStops.sortedBy { it.position })
    }

    /**
     * 获取参与实际渲染的色标列表
     */
    fun getEffectiveStops(fgColor: Color, bgColor: Color): List<GradientColorStop> {
        return when {
            isDynamicFgBg -> {
                val fgArgb = fgColor.toArgb()
                val bgArgb = bgColor.toArgb()
                listOf(
                    GradientColorStop(0.0f, fgArgb),
                    GradientColorStop(1.0f, bgArgb),
                )
            }
            isDynamicFgTrans -> {
                val fgArgb = fgColor.toArgb()
                val transArgb = fgColor.copy(alpha = 0f).toArgb()
                listOf(
                    GradientColorStop(0.0f, fgArgb),
                    GradientColorStop(1.0f, transArgb),
                )
            }
            stops.isNotEmpty() -> {
                stops.toList()
            }
            else -> {
                val fgArgb = fgColor.toArgb()
                val transArgb = fgColor.copy(alpha = 0f).toArgb()
                listOf(
                    GradientColorStop(0.0f, fgArgb),
                    GradientColorStop(1.0f, transArgb),
                )
            }
        }
    }

    fun reset(newP1: Offset, newP2: Offset) {
        p1 = newP1
        p2 = newP2
        active = true
        activeHandle = GradientHandleId.NONE
    }

    fun clear() {
        active = false
        activeHandle = GradientHandleId.NONE
        p1 = Offset.Zero
        p2 = Offset.Zero
    }
}
