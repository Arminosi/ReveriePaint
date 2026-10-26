/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

/**
 * 工具槽（`toolBrushStates`）里记录的预设，如何映射到当前预设表（#136 A）。
 *
 * 背景：`presetIndex` 指向的是 native 表里「文件名排序后的位置」。导入、删除、ABR 批量入库
 * 都会重建这张表，同一个索引就换了主人——实测：`idx=0` 十分钟前是 `Airbrush (Lur_Noise's...)`，
 * 删了几个预设之后变成 `Airbrush_eraser`，于是「橡皮」莫名其妙变成喷漆样式。
 *
 * 因此工具槽以**名字**为身份（`ToolBrushState.presetName`），索引只在解析时临时用：
 * 1. 名字命中 → 用它（重排安全）；
 * 2. 名字缺失（老数据，prefs 里只有 `pi`）→ 退回索引，并把命中的名字回填，下次即按名字走；
 * 3. 都不命中（预设已被删除）→ null，由调用方决定默认笔，绝不拿「表里的第一个」顶替。
 *
 * 纯函数，JVM 单测直接可用。
 */
internal object ToolPresetMemory {

    fun resolve(
        storedName: String,
        storedIndex: Int,
        presets: List<BrushPresetInfo>,
    ): BrushPresetInfo? {
        if (storedName.isNotBlank()) {
            presets.firstOrNull { it.name == storedName }?.let { return it }
            // 名字失效（被删除/改名）：不要退回索引——那个位置现在可能是另一支笔。
            return null
        }
        if (storedIndex < 0) return null
        return presets.firstOrNull { it.index == storedIndex }
    }
}
