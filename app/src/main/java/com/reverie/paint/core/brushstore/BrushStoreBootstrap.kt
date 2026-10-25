/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import java.io.File

/**
 * 启动编排（执行文档 §2.4 启动流程）：打开存储 → 同步出厂快照 → [迁移] → materializeAll。
 * 调用方随后照旧 `loadBrushPresetsFromDir(paintoppresets)`，不改 JNI。
 *
 * [active] 为 true 时 VM 走 v2 路径；需同时满足：开关打开、存储打开成功、迁移已完成。
 */
object BrushStoreBootstrap {
    const val STORE_DIR = "brush_store"
    private const val STAMP_FILE = "factory_stamp"

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var presetDir: File? = null
        private set

    /** 当前 brush_store 根目录（run 成功后有效；回放缓存等放在其下） */
    @Volatile
    var storeRootDir: File? = null
        private set

    fun storeRoot(filesDir: File): File = File(filesDir, STORE_DIR)

    fun openStores(storeRoot: File) {
        storeRoot.mkdirs()
        PresetSnapshotStore.open(storeRoot)
        BrushOverrideStore.open(storeRoot)
        BrushIdentity.open(storeRoot)
    }

    /** 出厂版本号 = 资产清单（文件名 + 各自 md5）的 md5，与 versionCode 无关，资产一变就变 */
    fun factoryVersion(files: Map<String, ByteArray>): String =
        BrushDiagnostics.md5(
            files.keys.sorted().joinToString("\n") { "$it:${BrushDiagnostics.md5(files.getValue(it))}" }.toByteArray(),
        )

    fun isFactoryPreset(fileName: String): Boolean =
        fileName.endsWith(".kpp", ignoreCase = true) || fileName.endsWith(".myb", ignoreCase = true)

    /**
     * 同步出厂快照并注册内置 identity。[installStamp] 未变且当前版本存在时不读资产（启动快路径）。
     * 返回 (当前版本, 是否换了版本)。
     */
    fun syncFactory(storeRoot: File, installStamp: String, readAssets: () -> Map<String, ByteArray>): Pair<String, Boolean> {
        val stampFile = File(storeRoot, STAMP_FILE)
        val cur = PresetSnapshotStore.currentFactoryVersion()
        if (cur != null && PresetSnapshotStore.factoryDir(cur).isDirectory &&
            stampFile.takeIf { it.exists() }?.readText()?.trim() == installStamp
        ) {
            ensureBuiltinIdentities(PresetSnapshotStore.factoryDir(cur).list()?.toList().orEmpty())
            return cur to false
        }
        val files = readAssets().filterKeys { isFactoryPreset(it) }
        val v = factoryVersion(files)
        val changed = v != cur
        if (changed) {
            PresetSnapshotStore.installFactory(v, files)
            PresetSnapshotStore.setCurrentFactoryVersion(v)
            // W-10：旧→新的变更/删除策略，须在 ensureBuiltinIdentities 删除下线 identity 之前
            if (cur != null && PresetSnapshotStore.factoryDir(cur).isDirectory) {
                runCatching { BuiltinSnapshotMigrator.onFactoryChanged(cur, v) }
                    .onFailure { BrushDiagnostics.log("upgrade_failed", "err" to it.toString()) }
            }
            PresetSnapshotStore.pruneFactory(setOfNotNull(v, cur))
            BrushDiagnostics.log("factory_install", "version" to v, "previous" to cur, "files" to files.size)
        }
        ensureBuiltinIdentities(files.keys)
        AtomicFiles.writeText(stampFile, installStamp)
        return v to changed
    }

    /** 每个出厂文件对应一个 `builtin:<name>`；已不在出厂清单里的内置 identity 删除 */
    fun ensureBuiltinIdentities(fileNames: Collection<String>) {
        val wanted = fileNames.filter { isFactoryPreset(it) }.map { fn ->
            val name = fn.substringBeforeLast('.')
            BrushIdentity.Entry(BrushIdentity.builtinId(name), name, fn.substringAfterLast('.').lowercase(), BrushIdentity.Kind.BUILTIN)
        }
        val missing = wanted.filter { BrushIdentity.entry(it.id) != it }
        BrushIdentity.registerAll(missing)
        val keep = wanted.mapTo(HashSet()) { it.id }
        for (e in BrushIdentity.all()) {
            if (e.kind == BrushIdentity.Kind.BUILTIN && e.id !in keep) {
                BrushIdentity.remove(e.id)
                BrushDiagnostics.log("builtin_removed", "id" to e.id)
            }
        }
    }

    /** 启动：同步出厂 → [migrate]（只在需要时）→ materializeAll。任何异常都退回旧路径（active=false）。 */
    fun run(
        filesDir: File,
        presetDir: File,
        enabled: Boolean,
        installStamp: String,
        readAssets: () -> Map<String, ByteArray>,
        migrate: () -> Boolean,
    ): Boolean {
        active = false
        if (!enabled) {
            BrushDiagnostics.log("store_disabled")
            return false
        }
        return try {
            val t0 = System.nanoTime()
            val root = storeRoot(filesDir)
            openStores(root)
            val first = PresetSnapshotStore.currentFactoryVersion() == null
            val (version, _) = syncFactory(root, installStamp, readAssets)
            if (first) runCatching { BuiltinSnapshotMigrator.logDrift(presetDir, version) }
            if (!migrate()) {
                BrushDiagnostics.log("store_inactive", "reason" to "migration_not_done")
                return false
            }
            val results = EffectivePresetBuilder.materializeAll(presetDir)
            this.presetDir = presetDir
            this.storeRootDir = root
            active = true
            BrushDiagnostics.log(
                "store_ready",
                "presets" to results.size,
                "written" to results.count { it.written },
                "ms" to (System.nanoTime() - t0) / 1_000_000,
            )
            true
        } catch (t: Throwable) {
            BrushDiagnostics.log("store_failed", "err" to t.toString())
            false
        }
    }

    /** 测试用 */
    fun resetForTest() {
        active = false
        presetDir = null
        storeRootDir = null
        PresetReadback.clearBaselineCache()
    }

    /** v2 管理的、可走新路径的预设（目前只有 .kpp；.myb 映射 NEEDS_EVIDENCE，仍走旧路径） */
    fun managedEntry(name: String): BrushIdentity.Entry? {
        if (!active) return null
        val e = BrushIdentity.entryForName(name) ?: return null
        if (!e.ext.equals("kpp", ignoreCase = true)) return null
        if (!PresetSnapshotStore.has(e.id)) return null
        return e
    }
}
