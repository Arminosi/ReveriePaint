/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams

/**
 * 重置时「旧记录回到导入态」的决策（#136）。
 *
 * 只对本次导入路径生效：`provenance ∈ {import, abr}`。
 * 其余（内置、migrated、migrated_possibly_modified、retired、duplicate、create）一律
 * [Action.NotApplicable]，由调用方交回上游旧逻辑，保持改动前行为。
 *
 * 这里的「导入态」按导入路径的实际产物取：
 * - `import`：bundle/zip 导入不写记录 → [Action.Delete]；单文件 kpp 导入写一条**未定制**的
 *   元数据记录（author/description）→ [Action.Restore] 时用 [PresetReadback.metadataOnly] 保住元数据。
 * - `abr`：导入时写的就是官方 bp（`BrushImportPipeline.importAbr` 同时把它写进 source.json 的
 *   `officialBp`），[OfficialBp.forId] 第 1 级取回同一对象 → [Action.Restore]；取不到
 *   （数据残缺）时不猜，[Action.NotApplicable]。
 *
 * 纯 JVM：不依赖 Android / Compose / [com.reverie.paint.core.PaintViewModel]。
 */
internal object ImportedResetRecord {

    sealed interface Action {
        /** 导入态没有记录（bundle/zip）：删除旧记录 */
        object Delete : Action

        /** 不归本规则管：调用方继续走上游旧逻辑 */
        object NotApplicable : Action

        /** 导入态记录：整体替换为 [p] */
        data class Restore(val p: BrushParams) : Action
    }

    private val WHITELIST = setOf(
        BrushImportPipeline.PROVENANCE_IMPORT,
        BrushImportPipeline.PROVENANCE_ABR,
    )

    /** 快照 source.json 的 provenance；内置（无 source.json）返回 null */
    fun provenance(id: String): String? =
        PresetSnapshotStore.source(id)?.optString("provenance")?.takeIf { it.isNotBlank() }

    fun actionFor(id: String, legacy: BrushParams?): Action {
        // 显式枚举白名单：provenance 还有 migrated / migrated_possibly_modified / retired /
        // duplicate / create 等取值，前缀匹配会误伤。
        val prov = provenance(id) ?: return Action.NotApplicable
        if (prov !in WHITELIST) return Action.NotApplicable
        if (prov == BrushImportPipeline.PROVENANCE_ABR) {
            val bp = OfficialBp.forId(id) ?: return Action.NotApplicable
            return Action.Restore(bp)
        }
        val meta = PresetReadback.metadataOnly(legacy, PresetReadback.baseline(id))
        return if (meta == null) Action.Delete else Action.Restore(meta)
    }
}
