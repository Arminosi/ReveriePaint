/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Test

/** KppXml 的 indexOf 扫描必须与参考正则 [KppXml.paramPattern] 结果一致（全部真实 kpp、全部参数名） */
class KppXmlScanTest {

    private val nameAttr = Regex("""<param\b[^>]*\bname="([^"]*)"""")

    @Test
    fun indexScan_matchesReferenceRegex_onAllAssets() {
        var checked = 0
        for ((stem, xml) in KppFixtures.all) {
            val names = nameAttr.findAll(xml).map { it.groupValues[1] }.toSet() + setOf("NoSuchKey", "Size", "Texture/Pattern/Pattern")
            for (n in names) {
                val ref = KppXml.paramPattern(n).findAll(xml).lastOrNull()?.groupValues?.get(1)
                val refDecoded = ref?.let { b ->
                    val s = b.indexOf("<![CDATA[")
                    val e = b.lastIndexOf("]]>")
                    if (s >= 0 && e > s) b.substring(s + 9, e) else b.replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                }
                assertEquals("$stem/$n get", refDecoded, KppXml.get(xml, n))
                assertEquals("$stem/$n has", ref != null, KppXml.has(xml, n))
                assertEquals("$stem/$n remove", KppXml.paramPattern(n).replace(xml, ""), KppXml.remove(xml, n))
                checked++
            }
        }
        assert(checked > 268 * 20) { "检查数过少: $checked" }
    }

    @Test
    fun set_onDuplicates_keepsFirstPosition_removesRest() {
        val xml = """<Preset><param name="A" type="string">1</param><param type="internal" name="A">2</param></Preset>"""
        val out = KppXml.set(xml, "A", "3")
        assertEquals("""<Preset><param type="string" name="A"><![CDATA[3]]></param></Preset>""", out)
        assertEquals("2", KppXml.get(xml, "A"))
    }
}
