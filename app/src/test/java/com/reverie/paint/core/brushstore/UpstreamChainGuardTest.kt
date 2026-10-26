/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1 机械守护（PLAN_brush_parity.md §5 T1）：
 * 把上游 v1.5.6（49f0f78）五个关键函数区间固化为切片资源（app/src/test/resources/upstream_49f0f78/），
 * 断言当前源码去掉「登记过的允许差异」后与上游切片逐行相同（仅去行尾空白比较）。
 *
 * 登记的允许差异（主智能体裁定的最终清单，来源 PLAN §5 T1 第 185 行 + S2.3/S2b 追加登记）：
 *  a) 全区域：`com.reverie.paint.core.brushstore.BrushTextureLive.apply(` 还原为 `ReverieCoreBridge.setBrushTexture(`
 *  b) select 区域跳过：captureUiBaseline 一行；S2.4 注释 + ParityRepair.repairPendingForSelect 两行；
 *     防御性登记 BrushStoreVm.prepareSelect 一行（正常在切片边界之前，不应出现）
 *  c) save 区域跳过：v2 注释 + onSavedPersist 两行、flushNow 一行；并把
 *     `val p = currentBrushParamsFromVm(...)` 一行替换为同文件 currentBrushParamsFromVm 函数体中
 *     `BrushParams(` 到对应 `)` 的构造表达式（逐行，参数名还原 dc/sc/spc，命名参数标签位保持）
 *  d) restore 区域跳过：BrushStoreVm.prepareRestore 一行（S4）
 *  e) reset 区域跳过：v2 注释 + 条件短路行（`if (BrushStoreVm.reset(...)) return`）两行（S6.1 / #136）
 *  f) replay_setup 区域：无额外登记
 * 除以上登记外，任何一行差异都必须 FAIL。
 */
class UpstreamChainGuardTest {

    private val resDir = "src/test/resources/upstream_49f0f78"

    private val fileBrush = "src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt"
    private val fileParams = "src/main/java/com/reverie/paint/core/PaintViewModelBrushParams.kt"
    private val filePlayback = "src/main/java/com/reverie/paint/core/PlaybackEngine.kt"

    private val textureLive = "com.reverie.paint.core.brushstore.BrushTextureLive.apply("
    private val textureUpstream = "ReverieCoreBridge.setBrushTexture("

    private val selectAnchor = "val saved = if (preset != null) brushParams[preset.name] else null"
    private val saveValP = "val p = currentBrushParamsFromVm(name, dc, sc, spc, existing, isEraserPreset)"

    // ---- 登记的允许跳过行（按 trim 后全文相等匹配） ----
    private val skipSelect = setOf(
        "BrushStoreVm.captureUiBaseline(this, preset?.name)",
        "// S2.4 非 ABR 延迟修复：pending 命中时用上游未定制基线重建记录（只影响后续选择）",
        "com.reverie.paint.core.brushstore.ParityRepair.repairPendingForSelect(this, index, preset)",
        "BrushStoreVm.prepareSelect(this, preset)",
    )
    private val skipRestore = setOf(
        "BrushStoreVm.prepareRestore(this, preset)",
    )
    private val skipReset = setOf(
        "// v2：先复位存储；白名单（import/abr）连记录一起回到导入态并短路，其余继续走上游逻辑",
        "if (BrushStoreVm.reset(this, presetName)) return",
    )
    private val skipSave = setOf(
        "// v2：只落盘（stage + 防抖 commit/materialize），引擎重载由下面的旧块负责",
        "BrushStoreVm.onSavedPersist(this, name, p)",
        "BrushStoreVm.flushNow(name)",
    )

    // ---- 工具 ----

    /** 读切片资源：跳过首行来源注释 */
    private fun loadSlice(fileName: String): List<String> {
        val f = File(resDir, fileName)
        assertTrue("切片资源不存在: ${f.absolutePath}", f.isFile)
        val lines = f.readLines()
        assertTrue("切片资源为空: $fileName", lines.size > 1)
        return lines.drop(1)
    }

    private fun loadSource(path: String): List<String> {
        val f = File(path)
        assertTrue("源码文件不存在: ${f.absolutePath}", f.isFile)
        return f.readLines()
    }

    /**
     * 用切片正文首行内容（去缩进）在当前源码中定位区域起点；
     * 若首行锚点匹配多处（如 select 的 `val saved = ...` 在 select 与 restore 中都出现），
     * 机械地向后扩锚（取切片正文前 k 行连续去缩进匹配），直到唯一；始终不唯一则 FAIL。
     */
    private fun locateStart(srcLines: List<String>, body: List<String>, region: String): Int {
        var k = 1
        while (k <= body.size) {
            val window = body.take(k).map { it.trim() }
            val matches = (0..srcLines.size - k).filter { idx ->
                (0 until k).all { w -> srcLines[idx + w].trim() == window[w] }
            }
            when {
                matches.size == 1 -> return matches[0]
                matches.isEmpty() -> throw AssertionError(
                    "[$region] 区域定位失败：切片正文前 $k 行在当前源码中无匹配，锚点串:\n" +
                        window.joinToString("\n") { "    $it" }
                )
                else -> k++
            }
        }
        throw AssertionError("[$region] 区域定位失败：切片正文整体在当前源码中仍多处匹配")
    }

    /**
     * save 抽取回填：从同一源码文件中读出 currentBrushParamsFromVm 函数体里
     * `BrushParams(` 到对应 `)` 的构造表达式（逐行），把参数名替换回局部名
     * （dynamicsCustomized→dc、smudgeCustomized→sc、spacingCustomized→spc），
     * 命名参数标签位（行首 `dynamicsCustomized =` 等）保持不变。
     * 返回的第一行不带缩进前缀（由调用方拼 `val p = `）。
     */
    private fun extractBrushParamsExpression(srcLines: List<String>): List<String> {
        val funIdx = srcLines.indexOfFirst { it.contains("fun PaintViewModel.currentBrushParamsFromVm(") }
        assertTrue("未找到 currentBrushParamsFromVm 函数", funIdx >= 0)
        var start = -1
        for (k in funIdx until minOf(funIdx + 40, srcLines.size)) {
            if (srcLines[k].trim() == "BrushParams(") { start = k; break }
        }
        assertTrue("未找到 currentBrushParamsFromVm 中的 BrushParams( 表达式起点", start >= 0)
        var depth = 0
        var end = -1
        for (k in start until srcLines.size) {
            depth += srcLines[k].count { it == '(' } - srcLines[k].count { it == ')' }
            if (depth == 0) { end = k; break }
        }
        assertTrue("BrushParams( 表达式括号不闭合", end > start)
        return (start..end).map { substituteParamNames(srcLines[it]) }
    }

    private val labelRe = Regex("^(\\s*)(dynamicsCustomized|smudgeCustomized|spacingCustomized)(\\s*=)(.*)$")

    private fun substituteParamNames(line: String): String {
        val m = labelRe.find(line) ?: return substituteNames(line)
        // 命名参数标签位保持原名，右侧值做替换
        return m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + substituteNames(m.groupValues[4])
    }

    private fun substituteNames(s: String): String = s
        .replace(Regex("\\bdynamicsCustomized\\b"), "dc")
        .replace(Regex("\\bsmudgeCustomized\\b"), "sc")
        .replace(Regex("\\bspacingCustomized\\b"), "spc")

    /**
     * 核心守护：从区域起点开始逐行走当前源码，跳过登记行、做纹理行还原与 save 抽取回填，
     * 与切片正文逐行（去行尾空白）比较；任何登记外差异即 FAIL（报区域名、切片行号、双方行内容）。
     */
    private fun guard(
        region: String,
        sourcePath: String,
        sliceFile: String,
        skips: Set<String>,
        saveBackfill: Boolean = false,
    ) {
        val body = loadSlice(sliceFile)
        val srcLines = loadSource(sourcePath)
        val startIdx = locateStart(srcLines, body, region)
        val backfill: List<String>? = if (saveBackfill) extractBrushParamsExpression(srcLines) else null

        var j = 0 // 切片正文指针
        var i = startIdx // 当前源码指针
        var lastConsumed = -1
        while (j < body.size) {
            if (i >= srcLines.size) {
                throw AssertionError("[$region] 当前源码在切片第 ${j + 1} 行前结束（切片还有 ${body.size - j} 行未匹配）")
            }
            val raw = srcLines[i]
            val trimmed = raw.trim()
            if (trimmed in skips) { i++; continue }

            if (saveBackfill && trimmed == saveValP) {
                val indent = raw.takeWhile { it == ' ' }
                val block = listOf(indent + "val p = " + backfill!!.first().trim()) + backfill.drop(1)
                for (bl in block) {
                    if (j >= body.size) {
                        throw AssertionError("[$region] 抽取回填块超出切片长度（切片共 ${body.size} 行）")
                    }
                    val up = body[j]
                    if (bl.trimEnd() != up.trimEnd()) {
                        failCompare(region, j, up, bl, "（抽取回填块内）")
                    }
                    j++; lastConsumed = i
                }
                i++; continue
            }

            val cur = raw.replace(textureLive, textureUpstream)
            val up = body[j]
            if (cur.trimEnd() != up.trimEnd()) {
                failCompare(region, j, up, cur, "")
            }
            j++; lastConsumed = i; i++
        }
        assertEquals(
            "[$region] 区域末行锚点不符：当前源码第 ${lastConsumed + 1} 行应为切片末行「${body.last().trim()}」",
            body.last().trim(),
            srcLines[lastConsumed].trim(),
        )
    }

    private fun failCompare(region: String, j: Int, upstream: String, current: String, note: String): Nothing =
        throw AssertionError(
            "[$region]$note 与上游切片出现登记外差异：\n" +
                "  切片正文第 ${j + 1} 行: |${upstream.trimEnd()}|\n" +
                "  当前行            : |${current.trimEnd()}|"
        )

    /** 用花括号深度提取某函数的完整文本（从含 decl 的行起，到深度归零止） */
    private fun extractFunction(srcLines: List<String>, declContains: String): List<String> {
        val start = srcLines.indexOfFirst { it.contains(declContains) }
        assertTrue("未找到函数声明: $declContains", start >= 0)
        var depth = 0
        var opened = false
        var end = -1
        for (k in start until srcLines.size) {
            depth += srcLines[k].count { it == '{' } - srcLines[k].count { it == '}' }
            if (srcLines[k].contains('{')) opened = true
            if (opened && depth <= 0) { end = k; break }
        }
        assertTrue("函数括号不闭合: $declContains", end >= start)
        return srcLines.subList(start, end + 1).toList()
    }

    // ---- 五个逐行守护用例 ----

    @Test
    fun `select 链与上游逐行相同`() {
        guard("select", fileBrush, "select_784_1133.kt.txt", skipSelect)
    }

    @Test
    fun `restore 链与上游逐行相同`() {
        guard("restore", fileBrush, "restore_1140_1274.kt.txt", skipRestore)
    }

    @Test
    fun `reset 旧逻辑与上游逐行相同`() {
        guard("reset", fileBrush, "reset_586_627.kt.txt", skipReset)
    }

    @Test
    fun `save 旧块与上游逐行相同（含抽取回填）`() {
        guard("save", fileParams, "save_541_724.kt.txt", skipSave, saveBackfill = true)
    }

    @Test
    fun `replay setup 与上游逐行相同`() {
        guard("replay_setup", filePlayback, "replay_setup_306_369.kt.txt", emptySet())
    }

    // ---- 两个独立断言 ----

    /** 目的：确认 selectBrushPreset 中 653 行的 BrushStoreVm.select 后 return 早退确已移除 */
    @Test
    fun `selectBrushPreset 中 BrushStoreVm select 之后 3 行内不得出现 return`() {
        val fn = extractFunction(loadSource(fileBrush), "fun PaintViewModel.selectBrushPreset(")
        val lines = fn.map { it.trim() }
        for (idx in lines.indices) {
            if (!lines[idx].contains("BrushStoreVm.select(")) continue
            for (off in 1..3) {
                val t = lines.getOrNull(idx + off) ?: break
                assertTrue(
                    "selectBrushPreset 中 BrushStoreVm.select( 之后 $off 行出现早退 return:\n    $t",
                    !t.startsWith("return"),
                )
            }
        }
    }

    /** 目的：回放 V2 分支与 V1 同走上游 setup 链，不再走 PlaybackPresetCache.replay */
    @Test
    fun `replayPresetSelectLocked 的 v2 分支走上游 setup 且不走 PlaybackPresetCache replay`() {
        val fn = extractFunction(loadSource(filePlayback), "fun PaintViewModel.replayPresetSelectLocked(")
        val branchStart = fn.indexOfFirst { it.contains("if (v2 != null)") }
        assertTrue("replayPresetSelectLocked 中未找到 if (v2 != null) 分支", branchStart >= 0)
        var depth = 0
        var branchEnd = -1
        for (k in branchStart until fn.size) {
            depth += fn[k].count { it == '{' } - fn[k].count { it == '}' }
            if (depth == 0) { branchEnd = k; break }
        }
        assertTrue("v2 != null 分支括号不闭合", branchEnd > branchStart)
        val branch = fn.subList(branchStart, branchEnd + 1).joinToString("\n")
        assertTrue(
            "v2 != null 分支内必须包含 applyReplayPresetSetupLocked(target)",
            branch.contains("applyReplayPresetSetupLocked(target)"),
        )
        assertTrue(
            "v2 != null 分支内不得再走 PlaybackPresetCache.replay(",
            !branch.contains("PlaybackPresetCache.replay("),
        )
    }
}
