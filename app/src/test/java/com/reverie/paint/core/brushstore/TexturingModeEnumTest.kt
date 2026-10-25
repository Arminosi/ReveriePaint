/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-3：纹理模式按 Krita KisTextureOptionData.h:29-46 的 16 个枚举名 */
class TexturingModeEnumTest {

    @Test
    fun names_matchKritaEnumOrder() {
        assertEquals(16, TextureModes.NAMES.size)
        assertEquals("multiply", TextureModes.nameOf(0))
        assertEquals("subtract", TextureModes.nameOf(1))
        assertEquals("color_dodge", TextureModes.nameOf(6))
        assertEquals("hard_mix_softer_photoshop", TextureModes.nameOf(11))
        assertEquals("linear_height_photoshop", TextureModes.nameOf(15))
        TextureModes.NAMES.forEachIndexed { i, n -> assertEquals(i, TextureModes.toInt(n)) }
    }

    @Test
    fun legacyNames_mapToOldWriterCodes() {
        // 旧写入器表（KppHelper:953-965）：dodge→6, burn→7, hard_light→10, soft_light→11
        assertEquals(6, TextureModes.toInt("dodge"))
        assertEquals(7, TextureModes.toInt("burn"))
        assertEquals(10, TextureModes.toInt("hard_light"))
        assertEquals(11, TextureModes.toInt("soft_light"))
        assertEquals(0, TextureModes.toInt("no_such_mode"))
        assertEquals(0, TextureModes.toInt(null))
        assertEquals(5, TextureModes.toInt("5"))
    }

    @Test
    fun assets_onlyUseValidCodes_andRoundTripThroughControl() {
        val spec = PresetPropertyMap.spec(ControlId.TEXTURE_MODE)
        var seen = 0
        for ((name, xml) in KppFixtures.all) {
            if (!KppXml.has(xml, "Texture/Pattern/TexturingMode")) continue
            seen++
            val code = spec.read!!(xml) as Int
            assertTrue("$name: $code", code in 0..15)
            for (target in 0..15) {
                val out = spec.write!!(xml, target, PresetPropertyMap.readParams(xml))
                assertEquals(name, target, spec.read!!(out))
            }
        }
        assertTrue("资产里应有纹理模式键", seen > 0)
    }
}
