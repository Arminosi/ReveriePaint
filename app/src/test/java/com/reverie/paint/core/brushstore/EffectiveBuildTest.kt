/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** EffectiveBuildIdentityTest / EffectiveBuildIdempotentTest（执行文档 W-04），全部用真实内置 kpp */
class EffectiveBuildTest {

    @Test
    fun `EffectiveBuildIdentity 覆盖为空或只有 APP_ONLY 时原样返回快照字节`() {
        val appOnly = BrushOverrideStore.Overrides(controls = mapOf("streamline" to 0.5, "author" to "x"))
        for ((stem, _) in KppFixtures.all) {
            val snap = KppFixtures.bytes(stem)
            assertSame(stem, snap, EffectivePresetBuilder.build(snap, "kpp", BrushOverrideStore.Overrides()))
            assertSame(stem, snap, EffectivePresetBuilder.build(snap, "kpp", appOnly))
        }
        assertTrue(KppFixtures.all.size >= 268)
    }

    @Test
    fun `myb 快照没有 XML 映射时原样回退`() {
        val bytes = byteArrayOf(1, 2, 3)
        val o = BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.5))
        assertSame(bytes, EffectivePresetBuilder.build(bytes, "myb", o))
    }

    @Test
    fun `EffectiveBuildIdempotent 覆盖生效且重复施加稳定，快照字节不被改动`() {
        var changedCount = 0
        for ((stem, _) in KppFixtures.all) {
            val snap = KppFixtures.bytes(stem)
            val snapCopy = snap.copyOf()
            val xml0 = KppHelper.readPresetXml(snap, stripEmbeddedResources = true)!!
            val edits = OverrideFixtures.editsFor(xml0)
            if (edits.isEmpty()) continue
            val o = OverrideFixtures.overrides(edits)
            val b1 = EffectivePresetBuilder.build(snap, "kpp", o)
            val b2 = EffectivePresetBuilder.build(b1, "kpp", o)
            assertArrayEquals("$stem 快照被改写", snapCopy, snap)
            assertNotEquals("$stem 覆盖未生效", BrushDiagnostics.md5(snap), BrushDiagnostics.md5(b1))
            assertEquals(
                "$stem build(build(x)) 不稳定",
                KppHelper.readPresetXml(b1, false),
                KppHelper.readPresetXml(b2, false),
            )
            // 内嵌资源原样保留
            val full0 = KppHelper.readPresetXml(snap, false)!!
            val full1 = KppHelper.readPresetXml(b1, false)!!
            val res0 = full0.substringAfter("<resources", "")
            assertEquals("$stem 内嵌资源变化", res0, full1.substringAfter("<resources", ""))
            changedCount++
        }
        assertTrue("参与的预设太少: $changedCount", changedCount >= 260)
    }
}
