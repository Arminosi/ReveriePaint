/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.R

/**
 * 渐变形状类型
 */
enum class GradientType(val id: Int, val labelRes: Int) {
    LINEAR(0, R.string.gradient_type_linear),
    RADIAL(1, R.string.gradient_type_radial),
    CONICAL(2, R.string.gradient_type_angle),
    SYMMETRIC(3, R.string.gradient_type_symmetric),
    DIAMOND(4, R.string.gradient_type_diamond);

    companion object {
        fun fromId(id: Int): GradientType = entries.find { it.id == id } ?: LINEAR
    }
}

/**
 * 渐变边缘扩展/重复方式
 */
enum class GradientRepeat(val id: Int, val labelRes: Int) {
    NONE(0, R.string.gradient_repeat_none),
    LOOP(1, R.string.gradient_repeat_loop),
    MIRROR(2, R.string.gradient_repeat_mirror);

    companion object {
        fun fromId(id: Int): GradientRepeat = entries.find { it.id == id } ?: NONE
    }
}

/**
 * 单个色标定义 (position: 0f..1f, colorArgb: 32位 ARGB)
 */
data class GradientColorStop(
    val position: Float,
    val colorArgb: Int,
)

/**
 * 预设渐变数据
 */
data class GradientPreset(
    val id: String,
    val nameRes: Int? = null,
    val nameStr: String? = null,
    val stops: List<GradientColorStop>,
    val isBuiltIn: Boolean = false,
)

/**
 * 画布交互手柄 ID 标识
 */
object GradientHandleId {
    const val NONE = -1
    const val START = 0
    const val END = 1
    const val MID_BODY = 2
    const val STOP_BASE = 100 // 100 + stopIndex
}
