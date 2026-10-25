/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多传感器（sensorslist）的选项：开关某一个传感器不能把其它传感器冲掉（旧原生 setter 的单传感器替换问题） */
class SensorsListMergeTest {

    private fun ids(xml: String, opt: String) = KppSensors.sensorIds(KppSensors.sensorXml(xml, opt))

    @Test
    fun inkPrecision03_softnessListSurvivesCurveEdit() {
        val xml = KppFixtures.xml("ink_precision_03")
        val before = ids(xml, "Softness")
        assertTrue(before.size >= 2)
        val first = before.first()
        val v = PresetPropertyMap.readCurve(xml, "Softness")!!.copy(curve = "0,0;0.4,0.8;1,1;")
        val out = PresetPropertyMap.writeCurve(xml, "Softness", v)
        assertEquals(before, ids(out, "Softness"))
        val s = KppSensors.sensorXml(out, "Softness")!!
        assertEquals("0,0;0.4,0.8;1,1;", KppSensors.sensorCurve(s, first))
        // 其它子传感器的曲线不变
        val s0 = KppSensors.sensorXml(xml, "Softness")!!
        for (other in before.drop(1)) assertEquals(KppSensors.sensorCurve(s0, other), KppSensors.sensorCurve(s, other))
    }

    @Test
    fun rotationList_jitterAndFollowDirectionMerge() {
        // 资产里 drawingangle 在列表中的 Rotation 选项都未勾选：先验证未生效时写 false 是空操作，再勾选后验证合并
        val raw = KppFixtures.all.filter { (_, x) -> ids(x, "Rotation").size >= 2 && "drawingangle" in ids(x, "Rotation") }
        assertTrue(raw.isNotEmpty())
        val followSpec = PresetPropertyMap.spec(ControlId.FOLLOW_DIRECTION)
        for ((name, x) in raw) {
            if (!(KppSensors.isChecked(x, "Rotation") && KppSensors.useCurve(x, "Rotation"))) {
                assertEquals(name, x, followSpec.write!!(x, false, BrushParams()))
            }
        }
        val withList = raw.map { (n, x) -> n to KppXml.set(KppXml.set(x, "PressureRotation", "true"), "RotationUseCurve", "true") }
        val jitter = PresetPropertyMap.spec(ControlId.JITTER_ANGLE)
        val follow = PresetPropertyMap.spec(ControlId.FOLLOW_DIRECTION)
        for ((name, xml) in withList) {
            val before = ids(xml, "Rotation")
            val j = jitter.write!!(xml, 0.3, BrushParams())
            assertTrue(name, ids(j, "Rotation").containsAll(before + "fuzzy"))
            assertEquals(name, 0.3, jitter.read!!(j) as Double, 1e-9)

            val off = follow.write!!(xml, false, BrushParams())
            assertEquals(name, before - "drawingangle", ids(off, "Rotation"))
            assertFalse(name, follow.read!!(off) as Boolean)
            assertTrue(name, KppSensors.isChecked(off, "Rotation"))

            val on = follow.write!!(off, true, BrushParams())
            assertTrue(name, ids(on, "Rotation").containsAll(before))
            assertTrue(name, follow.read!!(on) as Boolean)
        }
    }

    @Test
    fun mirror_andMixPressure_mergeIntoExistingSensors() {
        val flipX = PresetPropertyMap.spec(ControlId.RANDOM_FLIP_X)
        val mixP = PresetPropertyMap.spec(ControlId.MIX_PRESSURE)
        for ((name, xml) in KppFixtures.all) {
            val m0 = ids(xml, "Mirror")
            val out = flipX.write!!(xml, true, BrushParams())
            assertTrue(name, flipX.read!!(out) as Boolean)
            if (KppSensors.isChecked(xml, "Mirror")) assertTrue(name, ids(out, "Mirror").containsAll(m0))

            val x0 = ids(xml, "Mix")
            val mo = mixP.write!!(xml, true, BrushParams())
            assertTrue(name, mixP.read!!(mo) as Boolean)
            if (KppSensors.isChecked(xml, "Mix")) assertTrue(name, ids(mo, "Mix").containsAll(x0))
        }
    }
}
