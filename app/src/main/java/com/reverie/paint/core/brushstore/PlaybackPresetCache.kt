/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingReader
import java.io.File

/**
 * 回放用的预设解析（执行文档 W-12）。
 *
 * 录制：v2 管理的预设在切换时记 `T_PRESET_SELECT_V2`（idx、name、presetId、有效字节 md5），
 * 并把这份有效字节存进 `brush_store/playback_cache/<md5>.kpp`（按 mtime 做 LRU，上限 [MAX_ENTRIES]）。
 *
 * 回放：
 * - 当前有效字节 md5 == 录制时 md5 → [Resolution.EXACT]：只 loadBrushPreset + 橡皮擦标志，**不再按 brushParams 逐个 setter 补参数**；
 * - md5 不同但缓存里有 → [Resolution.CACHED]：录制时的字节还在，但没有"从字节加载"的 JNI（W-14，需重编 .so），
 *   暂时同样按当前有效字节加载，并记 `playback_md5_mismatch`；
 * - 缓存也没有 → [Resolution.MISSING]：退回当前有效字节（selectAndApply 语义）。
 *
 * 旧录制（`T_PRESET_SELECT`，没有 md5）不经过这里，保持原回放路径。
 */
object PlaybackPresetCache {
    const val DIR = "playback_cache"
    const val MAX_ENTRIES = 128

    enum class Resolution { EXACT, CACHED, MISSING }

    data class Select(val index: Int, val name: String, val presetId: String, val md5: String)

    fun write(b: RecordingBuffer, s: Select) {
        b.u16(if (s.index < 0) 0xFFFF else s.index.coerceIn(0, 0xFFFE))
        b.str(s.name)
        b.str(s.presetId)
        b.str(s.md5)
    }

    fun read(r: RecordingReader): Select {
        val idx = r.u16()
        val name = r.str()
        val id = r.str()
        val md5 = r.str()
        return Select(if (idx == 0xFFFF) -1 else idx, name, id, md5)
    }

    fun cacheDir(storeRoot: File) = File(storeRoot, DIR)

    fun cacheFile(storeRoot: File, md5: String) = File(cacheDir(storeRoot), "$md5.kpp")

    /**
     * 录制时调用：v2 管理的预设返回 [Select] 并把有效字节放进缓存；否则返回 null（调用方记旧事件）。
     */
    fun recordSelect(index: Int, name: String): Select? {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return null
        val root = BrushStoreBootstrap.storeRootDir ?: return null
        val presetDir = BrushStoreBootstrap.presetDir ?: return null
        val eff = File(presetDir, "$name.${e.ext}")
        if (!eff.isFile) return null
        val bytes = eff.readBytes()
        val md5 = BrushDiagnostics.md5(bytes)
        val f = cacheFile(root, md5)
        try {
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) tmp.delete()
                prune(root)
            } else {
                f.setLastModified(System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            BrushDiagnostics.log("playback_cache_failed", "name" to name, "err" to t.toString())
        }
        BrushDiagnostics.log("playback_record", "name" to name, "id" to e.id, "md5" to md5)
        return Select(index, name, e.id, md5)
    }

    fun resolve(s: Select): Resolution {
        val root = BrushStoreBootstrap.storeRootDir
        val presetDir = BrushStoreBootstrap.presetDir
        val e = BrushStoreBootstrap.managedEntry(s.name)
        if (e != null && presetDir != null) {
            val eff = File(presetDir, "${s.name}.${e.ext}")
            if (eff.isFile && BrushDiagnostics.md5(eff.readBytes()) == s.md5) return Resolution.EXACT
        }
        val cached = root?.let { cacheFile(it, s.md5) }
        if (cached != null && cached.isFile) {
            cached.setLastModified(System.currentTimeMillis())
            return Resolution.CACHED
        }
        return Resolution.MISSING
    }

    /**
     * 回放执行：按名字找到的原生索引 [target] 上加载；只调用 loadBrushPreset 与橡皮擦标志（无 REBUILD setter）。
     */
    fun replay(engine: BrushEngineApplier.EngineFacade, target: Int, s: Select, isEraser: Boolean): Resolution {
        val res = resolve(s)
        if (res != Resolution.EXACT) {
            BrushDiagnostics.log("playback_md5_mismatch", "name" to s.name, "id" to s.presetId, "md5" to s.md5, "resolution" to res.name)
        }
        if (target >= 0 && engine.loadBrushPreset(target)) engine.setPresetIsEraser(isEraser)
        return res
    }

    /** LRU：超过上限时按 mtime 删最旧的 */
    fun prune(storeRoot: File, max: Int = MAX_ENTRIES) {
        val files = cacheDir(storeRoot).listFiles { f -> f.isFile && f.name.endsWith(".kpp") } ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }.take(files.size - max).forEach { it.delete() }
    }
}
