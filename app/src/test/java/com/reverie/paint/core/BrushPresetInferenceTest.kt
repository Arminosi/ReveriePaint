/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushPresetInferenceTest {

    @Test
    fun `Krita 默认预设与新增 RGBA 及 WaterC 正确推导分组`() {
        assertEquals("橡皮擦", inferBrushGroup("a)_Eraser_Circle"))
        assertEquals("基础", inferBrushGroup("b)_Basic-5_Size_default"))
        assertEquals("基础", inferBrushGroup("b)_Airbrush_Soft"))
        assertEquals("铅笔", inferBrushGroup("c)_Pencil-1_Hard"))
        assertEquals("铅笔", inferBrushGroup("h)_Charcoal_Pencil_Medium"))
        assertEquals("勾线", inferBrushGroup("d)_Ink-3_Gpen"))
        assertEquals("马克笔", inferBrushGroup("e)_Marker_Chisel_Smooth"))
        assertEquals("绘画", inferBrushGroup("f)_Bristles-1_Details"))
        assertEquals("绘画", inferBrushGroup("g)_Dry_Bristles"))
        assertEquals("水彩", inferBrushGroup("i)_Wet_Paint"))
        assertEquals("水彩", inferBrushGroup("j)_Watercolor_Texture"))
        assertEquals("水彩", inferBrushGroup("j)_WaterC_Basic_Lines-Dry"))
        assertEquals("水彩", inferBrushGroup("j)_WaterC_Water-Pattern"))
        assertEquals("混合", inferBrushGroup("k)_Blender_Basic"))
        assertEquals("特效与滤镜", inferBrushGroup("l)_Adjust_Color"))
        assertEquals("特效与滤镜", inferBrushGroup("x)_Filter_Blur"))
        assertEquals("形状", inferBrushGroup("t)_Shapes_Fill"))
        assertEquals("像素画", inferBrushGroup("u)_Pixel_Art"))
        assertEquals("速写", inferBrushGroup("v)_Sketching-1_Chrome_Thin"))
        assertEquals("纹理与排线", inferBrushGroup("w)_Texture_Normal_Map"))
        assertEquals("纹理与排线", inferBrushGroup("y)_Texture_Noise"))
        assertEquals("印章与喷溅", inferBrushGroup("z)_Stamp_Sparkles"))

        // RGBA / 厚涂预设归入绘画
        assertEquals("绘画", inferBrushGroup("m)_RGBA_01_Thick-dry"))
        assertEquals("绘画", inferBrushGroup("m)_RGBA_02_Thickpaint"))
        assertEquals("绘画", inferBrushGroup("m)_RGBA_03_Rake"))
        assertEquals("绘画", inferBrushGroup("m)_RGBA_04_Impasto"))
        assertEquals("绘画", inferBrushGroup("m)_RGBA_05_Impasto-details"))
        assertEquals("绘画", inferBrushGroup("m)_RGBA_06_Rock"))

        // MyPaint 预设分组验证
        assertEquals("铅笔", inferBrushGroup("c)_Pencil_1_Sketch_(mypaint)"))
        assertEquals("铅笔", inferBrushGroup("c)_Pencil_2b_(mypaint)"))
        assertEquals("勾线", inferBrushGroup("d)_Ink_pen_(mypaint)"))
        assertEquals("马克笔", inferBrushGroup("e)_Marker_Medium_(mypaint)"))
        assertEquals("马克笔", inferBrushGroup("e)_Marker_Plain_(mypaint)"))
        assertEquals("水彩", inferBrushGroup("i)_Wet_Knife_Plus_(mypaint)"))
        assertEquals("水彩", inferBrushGroup("i)_Wet_Paint_Plus_(mypaint)"))
    }
}
