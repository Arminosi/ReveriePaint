/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import java.io.File

/**
 * v2 下一次工作台编辑的提交（执行文档 W-05）：VM 参数 → [BrushEngineApplier.mergeEdit] → 覆盖 → materialize。
 *
 * 编辑先 [stage]（只记最新一份 VM 参数，天然累积），由防抖任务或切换预设前的 [flush] 提交。
 * 所有提交串行（同一把锁），保证"读有效文件 → 合并 → 写有效文件"不交错。
 */
object BrushEditSession {

    data class Commit(
        val id: String,
        val changedKeys: Set<String>,
        /** 改动里含 REBUILD 控件：引擎需要重新 loadBrushPreset 才能生效 */
        val needsReload: Boolean,
        val result: EffectivePresetBuilder.Result?,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pair<BrushParams, BrushParams>>()

    /** 记下 [id] 的最新编辑；[appBase] 为 APP_ONLY 基线（只用于回读，不进覆盖） */
    fun stage(id: String, edited: BrushParams, appBase: BrushParams) {
        synchronized(lock) { pending[id] = edited to appBase }
    }

    fun hasPending(id: String): Boolean = synchronized(lock) { id in pending }

    fun discard(id: String) {
        synchronized(lock) { pending.remove(id) }
    }

    /** 提交 [id] 的待提交编辑；没有返回 null */
    fun flush(id: String, presetDir: File): Commit? = synchronized(lock) {
        val (edited, base) = pending.remove(id) ?: return null
        commitLocked(id, edited, base, presetDir)
    }

    fun flushAll(presetDir: File): List<Commit> = synchronized(lock) {
        val ids = pending.keys.toList()
        ids.mapNotNull { id -> pending.remove(id)?.let { (e, b) -> commitLocked(id, e, b, presetDir) } }
    }

    fun commit(id: String, edited: BrushParams, appBase: BrushParams, presetDir: File): Commit = synchronized(lock) {
        pending.remove(id)
        commitLocked(id, edited, appBase, presetDir)
    }

    private fun commitLocked(id: String, edited: BrushParams, appBase: BrushParams, presetDir: File): Commit {
        val snap = PresetSnapshotStore.read(id)
        val entry = BrushIdentity.entry(id)
        if (snap == null || entry == null || !entry.ext.equals("kpp", ignoreCase = true)) {
            BrushDiagnostics.log("commit_skipped", "id" to id, "reason" to if (snap == null) "no_snapshot" else "not_kpp")
            return Commit(id, emptySet(), false, null)
        }
        val snapXml = KppHelper.readPresetXml(snap, stripEmbeddedResources = true)
        val target = EffectivePresetBuilder.targetFile(presetDir, entry)
        val effXml = if (target.exists()) KppHelper.readPresetXml(target.readBytes(), stripEmbeddedResources = true) else snapXml
        val cur = BrushOverrideStore.get(id)
        val merged = BrushEngineApplier.mergeEdit(snapXml, effXml ?: snapXml, cur, edited, appBase)
        val changed = changedKeys(cur.controls, merged.controls)
        if (changed.isNotEmpty()) BrushOverrideStore.replace(id, merged)
        val res = EffectivePresetBuilder.materialize(id, presetDir)
        val reload = changed.any { ControlId.fromKey(it)?.klass == ControlClass.REBUILD }
        BrushDiagnostics.log("commit", "id" to id, "changed" to changed.joinToString(","), "reload" to reload)
        return Commit(id, changed, reload, res)
    }

    fun changedKeys(a: Map<String, Any>, b: Map<String, Any>): Set<String> {
        val out = LinkedHashSet<String>()
        for (k in a.keys + b.keys) {
            val id = ControlId.fromKey(k)
            val va = a[k]
            val vb = b[k]
            val same = if (id != null) PresetPropertyMap.same(id, va, vb) else va == vb
            if (!same) out += k
        }
        return out
    }

    /** 在提交锁内执行（回滚、重置与防抖提交互斥） */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    /** 重置（W-06）：丢弃待提交编辑、清空覆盖、有效文件回到快照字节 */
    fun reset(id: String, presetDir: File): EffectivePresetBuilder.Result? = synchronized(lock) {
        pending.remove(id)
        BrushOverrideStore.clear(id)
        val r = EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("reset", "id" to id, "md5" to r?.md5)
        r
    }

    /** 测试用 */
    fun resetForTest() {
        synchronized(lock) { pending.clear() }
    }
}
