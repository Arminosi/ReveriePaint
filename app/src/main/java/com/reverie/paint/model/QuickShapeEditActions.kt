/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/**
 * 编辑提示文案类型，用于根据不同形状与控制点模式向用户提供准确的操作引导。
 */
enum class QuickShapeEditHint {
    GENERIC,
    BOX,
    QUAD,
    CONTOUR,
    CURVE,
    ARC,
    LINE,
    TRIANGLE,
    CIRCLE,
    ELLIPSE,
}

/**
 * 速创形状在当前形状类型与全局开关下的可用操作能力矩阵。
 */
data class QuickShapeEditCapabilities(
    val showConversions: Boolean,
    val showCircleEllipse: Boolean,
    val showSnap: Boolean,
    val showRotate: Boolean,
    val showScale: Boolean,
    val showContourMode: Boolean,
    val showHoldReshape: Boolean,
    val hint: QuickShapeEditHint,
) {
    /** 兼容旧接口：只要旋转或缩放任一支持即为 true */
    val showRotateScale: Boolean
        get() = showRotate || showScale
}

/**
 * 根据形状类型及相关开关计算编辑能力与提示文案。
 *
 * 关键规则：
 * 1. 矩形排除在 showContourMode 与 showHoldReshape 之外（纯几何矩形，不支持边弯曲与局部四边形轮廓变形）；
 * 2. 轮廓（CONTOUR）与四边形（QUADRILATERAL）在开启八点控制且开启轮廓模式时支持切换轮廓与按住调整；
 * 3. 正圆（CIRCLE）因中心对称排除旋转能力（showRotate = false），仅保留缩放与转为椭圆；
 * 4. 提示文案与各形状实际可拖动控制点严格对应。
 */
fun quickShapeEditCapabilities(
    type: QuickShapeType,
    boxHandles: Boolean = false,
    conversions: Boolean = false,
    contour: Boolean = false,
): QuickShapeEditCapabilities {
    val boxShape = type == QuickShapeType.RECTANGLE ||
        type == QuickShapeType.QUADRILATERAL ||
        type == QuickShapeType.CONTOUR

    // 四边形转换对框形形状（矩形/四边形/轮廓）常显，不再依赖全局开关
    val showConversions = boxShape
    val showCircleEllipse = type == QuickShapeType.CIRCLE || type == QuickShapeType.ELLIPSE
    val showSnap = type == QuickShapeType.LINE ||
        type == QuickShapeType.RECTANGLE ||
        type == QuickShapeType.QUADRILATERAL ||
        type == QuickShapeType.CONTOUR

    // 正圆旋转无视觉变化，坚决排除；闭合多边形与椭圆保留旋转微调
    val showRotate = type == QuickShapeType.RECTANGLE ||
        type == QuickShapeType.ELLIPSE ||
        type == QuickShapeType.QUADRILATERAL ||
        type == QuickShapeType.CONTOUR

    val showScale = type == QuickShapeType.RECTANGLE ||
        type == QuickShapeType.CIRCLE ||
        type == QuickShapeType.ELLIPSE ||
        type == QuickShapeType.QUADRILATERAL ||
        type == QuickShapeType.CONTOUR

    // 矩形为纯几何矩形，彻底剔除直线/曲线轮廓切换与按住调整轮廓；仅 QUADRILATERAL 和 CONTOUR 正常显示
    val showContourMode = boxHandles && contour &&
        (type == QuickShapeType.QUADRILATERAL || type == QuickShapeType.CONTOUR)

    val showHoldReshape = boxHandles && contour &&
        (type == QuickShapeType.QUADRILATERAL || type == QuickShapeType.CONTOUR)

    val hint = when (type) {
        QuickShapeType.LINE -> QuickShapeEditHint.LINE
        QuickShapeType.TRIANGLE -> QuickShapeEditHint.TRIANGLE
        QuickShapeType.CIRCLE -> QuickShapeEditHint.CIRCLE
        QuickShapeType.ELLIPSE -> QuickShapeEditHint.ELLIPSE
        QuickShapeType.CONTOUR -> QuickShapeEditHint.CONTOUR
        QuickShapeType.CURVE -> QuickShapeEditHint.CURVE
        QuickShapeType.ARC -> QuickShapeEditHint.ARC
        QuickShapeType.QUADRILATERAL -> if (boxHandles) QuickShapeEditHint.BOX else QuickShapeEditHint.QUAD
        QuickShapeType.RECTANGLE -> if (boxHandles) QuickShapeEditHint.BOX else QuickShapeEditHint.GENERIC
        else -> QuickShapeEditHint.GENERIC
    }

    return QuickShapeEditCapabilities(
        showConversions = showConversions,
        showCircleEllipse = showCircleEllipse,
        showSnap = showSnap,
        showRotate = showRotate,
        showScale = showScale,
        showContourMode = showContourMode,
        showHoldReshape = showHoldReshape,
        hint = hint,
    )
}
