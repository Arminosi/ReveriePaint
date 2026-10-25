/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams

/**
 * 重置时「旧记录回到出生态」的决策（#136）。
 *
 * 生效范围：`provenance ∈ {import, abr, duplicate, create, retired}`。
 * `migrated` / `migrated_possibly_modified` 与内置预设、以及未知取值一律 [Action.NotApplicable]，
 * 由调用方交回上游旧逻辑：
 * - 内置没有 source.json；
 * - migrated 是 v2 之前导入的老笔刷，迁移时没有留下 baseline，重置只能把 APP_ONLY
 *   （streamline / taper / 压力曲线 / size 上下限 / speedSize）永久落回默认，
 *   而 source.json 写后只读、无法事后补。要正确支持得先加 sidecar，见 #136 的后续项。
 *
 * 「出生态」按各条路径的实际产物取：
 * - `import`：bundle/zip 导入不写记录 → [Action.Delete]；单文件 kpp 导入写一条**未定制**的
 *   元数据记录（author/description）→ [Action.Restore] 时用 [PresetReadback.metadataOnly] 保住元数据。
 * - `abr`：导入时写的就是官方 bp（`BrushImportPipeline.importAbr` 同时把它写进 source.json 的
 *   `officialBp`），[OfficialBp.forId] 第 1 级取回同一对象 → [Action.Restore]；取不到
 *   （数据残缺）时不猜，[Action.NotApplicable]。
 * - `duplicate`：复制时快照 = 源快照、覆盖 = 源覆盖、`officialBp` 与 `appBaseline` 一并拷贝
 *   （`BrushImportPipeline.duplicate`），所以出生态就是**源的出生态**。ABR 谱系走 [OfficialBp]
 *   的 `from` 链（第 4 级）取回官方 bp；内置/import 根会自然落空，退回元数据。
 * - `create` / `retired`：快照就是创建/下线那一刻的文件，没有 baseline，元数据之外没有可还原的
 *   APP_ONLY → 只保留元数据（create 的「原创创作者」、retired 的旧作者），全默认则删记录。
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

    // duplicate / create 在 BrushImportPipeline 里是字面量（没有集中常量），这里显式声明，
    // 避免两处字符串各写各的。
    private const val PROVENANCE_DUPLICATE = "duplicate"
    private const val PROVENANCE_CREATE = "create"

    private val WHITELIST = setOf(
        BrushImportPipeline.PROVENANCE_IMPORT,
        BrushImportPipeline.PROVENANCE_ABR,
        PROVENANCE_DUPLICATE,
        PROVENANCE_CREATE,
        BuiltinSnapshotMigrator.PROVENANCE_RETIRED,
    )

    /** 需要向 [OfficialBp] 求官方 bp 的来源：ABR 本体，以及复制了 ABR 的副本 */
    private val OFFICIAL_BP_SOURCES = setOf(
        BrushImportPipeline.PROVENANCE_ABR,
        PROVENANCE_DUPLICATE,
    )

    /** 快照 source.json 的 provenance；内置（无 source.json）返回 null */
    fun provenance(id: String): String? =
        PresetSnapshotStore.source(id)?.optString("provenance")?.takeIf { it.isNotBlank() }

    fun actionFor(id: String, legacy: BrushParams?): Action {
        // 显式枚举白名单：provenance 还有 migrated / migrated_possibly_modified 等取值，
        // 前缀匹配会误伤。
        val prov = provenance(id) ?: return Action.NotApplicable
        if (prov !in WHITELIST) return Action.NotApplicable
        if (prov in OFFICIAL_BP_SOURCES) {
            OfficialBp.forId(id)?.let { return Action.Restore(it) }
            // abr 但 officialBp 残缺：不猜，交回上游（保住 abrWithoutOfficialBp 的既有语义）。
            // duplicate 取不到官方 bp 是正常情况（源是内置或 bundle 导入），落到下面的元数据。
            if (prov == BrushImportPipeline.PROVENANCE_ABR) return Action.NotApplicable
        }
        val meta = PresetReadback.metadataOnly(legacy, PresetReadback.baseline(id))
        return if (meta == null) Action.Delete else Action.Restore(meta)
    }
}
