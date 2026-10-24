/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import android.content.SharedPreferences
import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.BrushPresetInfo
import com.reverie.paint.core.KppHelper
import com.reverie.paint.core.LegacyBrushParamsCodec
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ReverieCoreBridge
import com.reverie.paint.core.prefs
import com.reverie.paint.core.persistBrushParams
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次性记录修复迁移（计划 PLAN_brush_parity.md §4 S2.3 + S2.4）：v2 期间被污染/残缺的
 * brushParams 记录修回「上游等价」形态。只改记录数据，不动任何引擎/UI 链路行为。
 *
 * [runOnce]（迁移主入口，挂点在 `loadBrushPresets` 的 IO 块结束与
 * `loadBrushPresetsAfterAssets` 之间，保证在首次 selectBrushPreset 之前完成）：
 * - prefs `brush_parity_v1_done` 为 true 直接返回；否则跑完置 true（无论有没有修任何东西）。
 * - 遍历名字全集（[BrushIdentity] 注册表 ∪ vm.brushParams 的 key），对每个
 *   [BrushStoreBootstrap.managedEntry] 命中的预设：
 *   - ABR 家族（[isAbrFamily]）且官方 bp（[OfficialBp.forId]）取到：
 *     - 无记录或未定制 → 新记录 = 官方 bp，元数据四字段（author/isAuthorLocked/
 *       description/version）保留旧记录的值（kind="metadata"/"missing"）；
 *     - 已定制 → 先把旧记录序列化备份到 `brush_store/parity_backup/`（只写不覆盖，
 *       文件名经 [BrushIdentity.dirNameFor] 清洗），再用官方 bp 叠加覆盖
 *       （[BrushOverrideStore] controls 经 [PresetPropertyMap.applyToParams]），APP_ONLY
 *       控件按旧记录相对快照 appBaseline 的偏离回填（[applyAppOnlyCopy]），元数据保留，
 *       isCustomized=true（kind="abr_edited"）；
 *   - ABR 家族且 bp 取不到（W-09 之前导入、无 abrParams）：不修，记入 prefs
 *     StringSet `brush_parity_reimport` 重导名单（kind="parity_no_bp"）；
 *   - 非 ABR 且已定制：同样备份后记入 prefs StringSet `brush_parity_repair_pending`
 *     延迟到选笔时修复（kind="nonabr_pending"）；其余跳过。
 * - 有写回则 [PaintViewModel.persistBrushParams]；收尾 log "parity_repair_done"。
 *
 * [repairPendingForSelect]（延迟修复，挂点在 selectBrushPreset 的 runCore 渲染块开头）：
 * prefs pending 集合命中时，复刻上游未定制 else 分支的输入（引擎 defaults/kpp 解析/
 * 未定制语义的 effectiveCompOp 等）经 [OfficialUiBaseline.nonCustomized] 重建基线，再按
 * 与 ABR 相同的方式叠加覆盖与 APP_ONLY 回填后写回。本次选笔链已捕获的局部 saved 不受
 * 影响（首次命中那一笔用旧记录，计划规定的挂点位置），写回对后续选择生效。整个函数
 * runCatching 包裹：任何异常 log "parity_repair_failed" 后从集合移除该项，不影响选笔。
 */
object ParityRepair {

    private const val KEY_DONE = "brush_parity_v1_done"
    private const val KEY_PENDING = "brush_parity_repair_pending"
    private const val KEY_REIMPORT = "brush_parity_reimport"

    /** 备份目录：filesDir/brush_store/parity_backup（[BrushStoreBootstrap.STORE_DIR] 之下） */
    const val BACKUP_SUBDIR = "parity_backup"

    /** 与 [PresetReadback] 的 METADATA 同一集合（那边是 private，这里显式列出） */
    private val METADATA_IDS =
        setOf(ControlId.AUTHOR, ControlId.AUTHOR_LOCKED, ControlId.DESCRIPTION, ControlId.VERSION)

    // ------------------------------------------------------------ 迁移主入口

    suspend fun runOnce(vm: PaintViewModel) {
        val prefs = vm.prefs()
        if (prefs.getBoolean(KEY_DONE, false)) return

        var repaired = 0
        var pendingCount = 0
        var noBpCount = 0
        val pendingNames = LinkedHashSet<String>()
        val reimportNames = LinkedHashSet<String>()

        val writes = LinkedHashMap<String, BrushParams>()
        withContext(Dispatchers.IO) {
            val backupDir = File(File(vm.appContext.filesDir, BrushStoreBootstrap.STORE_DIR), BACKUP_SUBDIR)
            val names = (BrushIdentity.all().map { it.name } + vm.brushParams.keys).toSet()
            for (name in names.sorted()) {
                val entry = BrushStoreBootstrap.managedEntry(name) ?: continue
                val rec = vm.brushParams[name]
                val bp = OfficialBp.forId(entry.id)
                if (isAbrFamily(entry.id)) {
                    if (bp == null) {
                        // W-09 之前导入、无 abrParams：无法重建，进重导名单
                        reimportNames += name
                        noBpCount++
                        BrushDiagnostics.log("parity_no_bp", "name" to name)
                        continue
                    }
                    if (rec == null || rec.isCustomized != true) {
                        val kind = if (rec == null) "missing" else "metadata"
                        writes[name] = keepMetadata(bp, rec)
                        repaired++
                        BrushDiagnostics.log("parity_repair", "name" to name, "id" to entry.id, "kind" to kind)
                    } else {
                        backupRecord(backupDir, entry.id, name, rec)
                        var fixed = PresetPropertyMap.applyToParams(bp, controlsOf(entry.id, name))
                        fixed = applyAppOnlyCopy(fixed, rec, entry.id)
                        fixed = keepMetadata(fixed, rec)
                        fixed = fixed.copy(
                            isCustomized = true,
                            dynamicsCustomized = bp.dynamicsCustomized || rec.dynamicsCustomized,
                            smudgeCustomized = rec.smudgeCustomized,
                            spacingCustomized = rec.spacingCustomized,
                        )
                        writes[name] = fixed
                        repaired++
                        BrushDiagnostics.log("parity_repair", "name" to name, "id" to entry.id, "kind" to "abr_edited")
                    }
                } else {
                    if (rec != null && rec.isCustomized == true) {
                        backupRecord(backupDir, entry.id, name, rec)
                        pendingNames += name
                        pendingCount++
                        BrushDiagnostics.log("parity_repair", "name" to name, "id" to entry.id, "kind" to "nonabr_pending")
                    }
                    // 非 ABR 且无记录/未定制：跳过
                }
            }
        }

        // Compose 状态写在主线程（runOnce 的调用方是 viewModelScope 主线程协程）
        if (writes.isNotEmpty()) {
            for ((name, p) in writes) vm.brushParams[name] = p
            vm.persistBrushParams()
        }

        val edit = prefs.edit()
            .putStringSet(KEY_PENDING, addToSet(prefs, KEY_PENDING, pendingNames))
        if (reimportNames.isNotEmpty()) {
            edit.putStringSet(KEY_REIMPORT, addToSet(prefs, KEY_REIMPORT, reimportNames))
        }
        edit.putBoolean(KEY_DONE, true).apply()
        BrushDiagnostics.log(
            "parity_repair_done",
            "repaired" to repaired, "pending" to pendingCount, "no_bp" to noBpCount,
        )
    }

    // ------------------------------------------------------------ 延迟修复（选笔渲染块开头）

    fun repairPendingForSelect(vm: PaintViewModel, index: Int, preset: BrushPresetInfo?) {
        if (preset == null) return
        val prefs = vm.prefs()
        if (prefs.getStringSet(KEY_PENDING, null)?.contains(preset.name) != true) return
        val entry = BrushStoreBootstrap.managedEntry(preset.name) ?: return
        val result = runCatching {
            val rec = vm.brushParams[preset.name]
                ?: error("record_missing")
            // 复刻上游未定制 else 分支的输入（渲染线程上，此处就是）
            val d = ReverieCoreBridge.brushPresetDefaults(index)
            val kppFile = File(File(vm.appContext.filesDir, "paintoppresets"), "${preset.name}.kpp")
            val parsed = if (kppFile.exists()) KppHelper.parseKppFile(kppFile) else KppHelper.KppParsedAttributes()
            val saved = vm.brushParams[preset.name]
            val isBuiltIn = preset.isBuiltIn
            // 未定制语义的 effectiveCompOp：不把污染记录的 compositeOp 当 savedOp 用
            val eraserPreset = com.reverie.paint.core.BrushStoreVm.isEraserPreset(preset)
            val effectiveCompOp = if (vm.currentToolId == "eraser" || eraserPreset) {
                "erase"
            } else {
                ReverieCoreBridge.brushPresetCompositeOp(index)
            }
            val engineTipFilename = if (index >= 0) ReverieCoreBridge.brushPresetTipFilename(index) else ""
            val enginePaintOpId = if (index >= 0) ReverieCoreBridge.brushPresetPaintOpId(index) else "paintbrush"
            val baseline = OfficialUiBaseline.nonCustomized(
                d, parsed, saved, isBuiltIn, effectiveCompOp, engineTipFilename, enginePaintOpId,
            )
            var fixed = PresetPropertyMap.applyToParams(baseline, controlsOf(entry.id, preset.name))
            fixed = applyAppOnlyCopy(fixed, rec, entry.id)
            fixed = keepMetadata(fixed, rec)
            // 非 ABR：标志全用 rec 的值
            fixed = fixed.copy(
                isCustomized = true,
                dynamicsCustomized = rec.dynamicsCustomized,
                smudgeCustomized = rec.smudgeCustomized,
                spacingCustomized = rec.spacingCustomized,
            )
            vm.brushParams[preset.name] = fixed
            vm.persistBrushParams()
        }
        // 无论成败都移出 pending：失败时也不能让每次选笔都重复尝试
        removeFromSet(prefs, KEY_PENDING, preset.name)
        result.fold(
            onSuccess = {
                BrushDiagnostics.log("parity_repair", "name" to preset.name, "id" to entry.id, "kind" to "nonabr_fixed")
            },
            onFailure = {
                BrushDiagnostics.log(
                    "parity_repair_failed",
                    "name" to preset.name, "err" to it.javaClass.simpleName, "msg" to (it.message ?: ""),
                )
            },
        )
    }

    // ------------------------------------------------------------ 私有工具

    /**
     * ABR 家族判定：[PresetSnapshotStore.source] 的 provenance=="abr"；=="duplicate"
     * 时沿 `from` 链找根判 provenance（深度 ≤10、visited 防环）。内置预设 source 为
     * null → 非 ABR。
     */
    private fun isAbrFamily(id: String): Boolean {
        var cur = id
        val visited = HashSet<String>()
        for (depth in 0..10) {
            if (!visited.add(cur)) return false
            val source = PresetSnapshotStore.source(cur) ?: return false
            when (source.optString("provenance", "")) {
                "abr" -> return true
                "duplicate" -> {
                    val from = source.optString("from", "")
                    if (from.isEmpty() || from == cur) return false
                    cur = from
                }
                else -> return false
            }
        }
        return false
    }

    /** 覆盖值 → ControlId 表；fromKey 落空的键丢弃并 log */
    private fun controlsOf(entryId: String, name: String): Map<ControlId, Any> {
        val raw = BrushOverrideStore.get(entryId).controls
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<ControlId, Any>()
        for ((k, v) in raw) {
            val cid = ControlId.fromKey(k)
            if (cid == null) {
                BrushDiagnostics.log("parity_unknown_control", "name" to name, "id" to entryId, "key" to k)
            } else {
                out[cid] = v
            }
        }
        return out
    }

    /**
     * APP_ONLY 拷贝：对 APP_ONLY 且非元数据四项的每个控件，若旧记录该字段值与快照
     * appBaseline（[PresetReadback.baseline]，读不到即默认 BrushParams）不同，把旧值
     * set 进 [fixed]（[PresetPropertyMap.spec] 的单控件 get/set 是公开 API）。
     */
    private fun applyAppOnlyCopy(fixed: BrushParams, rec: BrushParams, entryId: String): BrushParams {
        val base = PresetReadback.baseline(entryId)
        var p = fixed
        for (cid in ControlId.entries) {
            if (cid.klass != ControlClass.APP_ONLY || cid in METADATA_IDS) continue
            val sp = PresetPropertyMap.spec(cid)
            val recVal = sp.get(rec)
            if (!PresetPropertyMap.same(cid, recVal, sp.get(base))) {
                p = sp.set(p, recVal)
            }
        }
        return p
    }

    /** 元数据四字段保留旧记录的值（rec 为 null 时原样返回） */
    private fun keepMetadata(target: BrushParams, rec: BrushParams?): BrushParams {
        if (rec == null) return target
        var p = target
        for (cid in METADATA_IDS) {
            val sp = PresetPropertyMap.spec(cid)
            p = sp.set(p, sp.get(rec))
        }
        return p
    }

    /** 旧记录序列化备份：`parity_backup/<清洗后的 id>.json`（已存在则跳过不覆盖） */
    private fun backupRecord(backupDir: File, entryId: String, name: String, rec: BrushParams) {
        runCatching {
            val f = File(backupDir, BrushIdentity.dirNameFor(entryId) + ".json")
            if (f.exists()) return
            AtomicFiles.writeText(f, LegacyBrushParamsCodec.encode(name, rec).toString(1))
            BrushDiagnostics.log("parity_backup", "name" to name, "id" to entryId)
        }.onFailure {
            BrushDiagnostics.log("parity_backup_failed", "name" to name, "id" to entryId, "err" to it.javaClass.simpleName)
        }
    }

    private fun addToSet(prefs: SharedPreferences, key: String, add: Set<String>): Set<String> =
        (prefs.getStringSet(key, emptySet()) ?: emptySet()) + add

    private fun removeFromSet(prefs: SharedPreferences, key: String, value: String) {
        val cur = prefs.getStringSet(key, null) ?: return
        if (value !in cur) return
        prefs.edit().putStringSet(key, cur - value).apply()
    }
}
