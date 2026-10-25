/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.LegacyBrushParamsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 记录修复纯公式（计划 PLAN_brush_parity.md §5 T4）：repairAbrRecord / repairNonAbrRecord
 * 与 backupRecord 在 JVM 上直接验证，不触 prefs / vm / 引擎。
 */
class ParityRepairTest {

    // ------------------------------------------------------------ ABR：metadataOnly 型

    /** rec 只有元数据四字段非默认且未定制 → 结果 == bp，元数据保留 rec 的值 */
    @Test
    fun `ABR metadataOnly记录修复等于官方bp加rec元数据`() {
        val rec = BrushParams(
            author = " external ",
            isAuthorLocked = true,
            description = "导入自 abr",
            version = "2.1",
        )
        val fixed = repairAbrRecord(BrushParams(), rec, emptyMap(), BrushParams())
        assertEquals(
            BrushParams(
                author = " external ",
                isAuthorLocked = true,
                description = "导入自 abr",
                version = "2.1",
            ),
            fixed,
        )
    }

    /** rec 为 null（missing）→ 结果就是官方 bp 本身 */
    @Test
    fun `ABR 无记录时修复结果为官方bp`() {
        assertEquals(BrushParams(), repairAbrRecord(BrushParams(), null, emptyMap(), BrushParams()))
    }

    // ------------------------------------------------------------ ABR：污染记录修复

    /**
     * 回读污染（rec.jitterSize=1.0）被丢弃：修复结果里 jitterSize 用官方 bp 的 0.30；
     * 覆盖 spacing=0.5 叠上去；标志按记录修复公式取值。
     */
    @Test
    fun `ABR 污染记录修复回官方bp叠加覆盖`() {
        val bp = BrushParams(jitterSize = 0.30)
        val rec = BrushParams(
            isCustomized = true,
            jitterSize = 1.0,
            spacingCustomized = true,
        )
        val controls = mapOf<ControlId, Any>(ControlId.SPACING to 0.5)
        val fixed = repairAbrRecord(bp, rec, controls, BrushParams())
        assertEquals(0.30, fixed.jitterSize, 1e-9)
        assertEquals(0.5, fixed.spacing, 1e-9)
        // spacingCustomized 取记录值
        assertTrue(fixed.spacingCustomized)
        // smudgeCustomized 取记录值
        assertEquals(rec.smudgeCustomized, fixed.smudgeCustomized)
        // dynamicsCustomized = bp.dc || rec.dc
        assertEquals(bp.dynamicsCustomized || rec.dynamicsCustomized, fixed.dynamicsCustomized)
        assertTrue(fixed.isCustomized)
    }

    /** APP_ONLY 偏离回填：rec 相对 appBaseline 的偏离 set 进结果，与基线一致的不回填 */
    @Test
    fun `ABR APP_ONLY偏离按appBaseline回填`() {
        val rec = BrushParams(
            isCustomized = true,
            streamline = 0.7,
            taper = 0.4,
            author = "K",
        )
        // taper 与导入时基线一致 → 不回填（保持官方 bp 默认 0.0）；streamline/author 偏离 → 回填
        val appBase = BrushParams(taper = 0.4)
        val fixed = repairAbrRecord(BrushParams(), rec, emptyMap(), appBase)
        assertEquals(0.7, fixed.streamline, 1e-9)
        assertEquals("K", fixed.author)
        assertEquals(0.0, fixed.taper, 1e-9)
    }

    // ------------------------------------------------------------ 非 ABR

    /** 非 ABR：标志全用 rec 的值，字段来自基线 + 覆盖 */
    @Test
    fun `非ABR修复标志全用rec值`() {
        val baseline = BrushParams(jitterSize = 0.2)
        val rec = BrushParams(
            isCustomized = true,
            dynamicsCustomized = true,
            smudgeCustomized = false,
            spacingCustomized = true,
        )
        val controls = mapOf<ControlId, Any>(ControlId.OPACITY to 0.6)
        val fixed = repairNonAbrRecord(baseline, rec, controls, BrushParams())
        assertEquals(0.6, fixed.opacity, 1e-9)
        assertEquals(0.2, fixed.jitterSize, 1e-9)
        assertTrue(fixed.isCustomized)
        assertTrue(fixed.dynamicsCustomized)
        assertFalse(fixed.smudgeCustomized)
        assertTrue(fixed.spacingCustomized)
    }

    // ------------------------------------------------------------ 备份

    @Test
    fun `备份写入文件且内容为编码记录`() {
        val dir = Files.createTempDirectory("parity_backup").toFile()
        val rec = BrushParams(size = 42.0, isCustomized = true)
        ParityRepair.backupRecord(dir, "imp_test:x/y", "Hard Charcoal 01", rec)
        val f = File(dir, BrushIdentity.dirNameFor("imp_test:x/y") + ".json")
        assertTrue(f.exists())
        assertEquals(LegacyBrushParamsCodec.encode("Hard Charcoal 01", rec).toString(1), f.readText())
    }

    @Test
    fun `备份已存在时不覆盖`() {
        val dir = Files.createTempDirectory("parity_backup").toFile()
        val rec = BrushParams(size = 42.0, isCustomized = true)
        ParityRepair.backupRecord(dir, "imp_x", "n", rec)
        val f = File(dir, "imp_x.json")
        assertTrue(f.exists())
        // 固定 mtime 为过去的时刻；二次调用若重写文件，mtime 会变成当前时间
        f.setLastModified(1_000_000L)
        ParityRepair.backupRecord(dir, "imp_x", "n", rec)
        assertEquals(1_000_000L, f.lastModified())
        assertEquals(LegacyBrushParamsCodec.encode("n", rec).toString(1), f.readText())
    }

    // ------------------------------------------------------------ 幂等

    @Test
    fun `ABR 修复同一输入幂等`() {
        val bp = BrushParams(jitterSize = 0.3, spacing = 0.05)
        val rec = BrushParams(isCustomized = true, jitterSize = 1.0, spacingCustomized = true, author = "A")
        val controls = mapOf<ControlId, Any>(ControlId.SPACING to 0.5)
        val appBase = BrushParams(streamline = 0.4)
        assertEquals(repairAbrRecord(bp, rec, controls, appBase), repairAbrRecord(bp, rec, controls, appBase))
    }

    @Test
    fun `非ABR修复同一输入幂等`() {
        val baseline = BrushParams(jitterSize = 0.2)
        val rec = BrushParams(isCustomized = true, spacingCustomized = true)
        val controls = mapOf<ControlId, Any>(ControlId.OPACITY to 0.6)
        assertEquals(
            repairNonAbrRecord(baseline, rec, controls, BrushParams()),
            repairNonAbrRecord(baseline, rec, controls, BrushParams()),
        )
    }
}
