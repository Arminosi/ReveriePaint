/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UseSameCurve + commonCurve 的资产里，Krita 用 commonCurve；写曲线必须同步它，读曲线必须优先读它 */
class CommonCurveTest {

    private val curve = "0,0;0.25,0.6;1,1;"

    @Test
    fun curveWrite_syncsCommonCurve() {
        var checked = 0
        for ((name, xml) in KppFixtures.all) {
            for (id in ControlId.entries.filter { it.curveOption != null }) {
                val opt = id.curveOption!!
                if (KppSensors.commonCurve(xml, opt) == null) continue
                val cur = CurveValue.parse(PresetPropertyMap.readCurve(xml, opt)!!.encode())!!
                val v = cur.copy(curve = curve, enabled = true)
                val out = PresetPropertyMap.writeCurve(xml, opt, v)
                assertEquals("$name/$opt", curve, KppXml.get(out, "${opt}commonCurve"))
                val back = PresetPropertyMap.readCurve(out, opt)!!
                assertTrue("$name/$opt: $back", back.sameAs(if (opt == "Size") v else v.copy(strength = 1.0)))
                checked++
            }
        }
        assertTrue("应至少覆盖一个带 commonCurve 的资产", checked > 0)
    }

    @Test
    fun curveRead_prefersCommonCurve() {
        for ((name, xml) in KppFixtures.all) {
            val common = KppSensors.commonCurve(xml, "Size") ?: continue
            val v = PresetPropertyMap.readCurve(xml, "Size")!!
            assertEquals(name, CurveValue.normalizeCurve(common), v.curve)
        }
    }
}
