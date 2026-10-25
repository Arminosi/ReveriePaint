/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #136 A：工具槽里的预设如何解析。
 *
 * 锁住的核心行为是「名字优先、索引只在老数据时兜底」——导入/删除预设会让 native 表重排，
 * 只认索引就会让橡皮槽指到别的笔（实测：idx=0 从 Airbrush 包变成 Airbrush_eraser）。
 */
class ToolPresetMemoryTest {

    private fun preset(index: Int, name: String, group: String = "基础") =
        BrushPresetInfo(index = index, name = name, thumbBytes = ByteArray(0), group = group)

    // 删过预设之后的表：每支笔都换了位置
    private val shifted = listOf(
        preset(0, "Airbrush_eraser", "橡皮擦"),
        preset(47, "FX_splat_starfield", "特效与滤镜"),
        preset(66, "G pen", "勾线"),
        preset(144, "a)_Eraser_Circle", "橡皮擦"),
    )

    @Test
    fun nameWinsOverStaleIndex() {
        // 橡皮槽里存的是「a)_Eraser_Circle + 旧的 47」；47 现在是 FX_splat_starfield
        val hit = ToolPresetMemory.resolve("a)_Eraser_Circle", 47, shifted)
        assertEquals("必须按名字找到 144，而不是按索引拿到 47 的星野", 144, hit?.index)
        assertEquals("a)_Eraser_Circle", hit?.name)
    }

    @Test
    fun legacyRecordWithoutNameFallsBackToIndex() {
        // 老数据只有 "pi"：按索引取，下一次写槽时会把名字补上
        val hit = ToolPresetMemory.resolve("", 66, shifted)
        assertEquals(66, hit?.index)
        assertEquals("G pen", hit?.name)
    }

    @Test
    fun deletedNameNeverFallsBackToIndex() {
        // 名字存在但预设已被删除：绝不退回索引（那个位置现在是别人的笔）
        assertNull(ToolPresetMemory.resolve("Ultra Soft Pencil", 0, shifted))
    }

    @Test
    fun noNameAndNoIndexResolvesToNothing() {
        assertNull(ToolPresetMemory.resolve("", -1, shifted))
        assertNull(ToolPresetMemory.resolve("", 999, shifted))
    }

    @Test
    fun nameMatchIgnoresIndexCompletely() {
        // 索引完全失效（-1）但名字还能找到：照样命中
        val hit = ToolPresetMemory.resolve("G pen", -1, shifted)
        assertEquals(66, hit?.index)
    }
}
