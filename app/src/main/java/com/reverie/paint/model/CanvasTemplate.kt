/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 模板工程数据模型 (纯 Kotlin, 无 Android 依赖, 可单测)。
 *
 * 用户基于模板可以快速创建包含特定图层结构、分辨率、DPI、色彩模式或预置内容 (如纸纹/分镜) 的画布。
 */
data class CanvasTemplate(
    val id: String,
    val name: String,
    val category: String = "未分类",
    val filePath: String,
    val previewPath: String = "",
    val width: Int = 1080,
    val height: Int = 1920,
    val dpi: Int = 300,
    val layerCount: Int = 1,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isAnimation: Boolean = false,
)
