/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import java.io.File

/**
 * 导出/分享取字节的唯一入口（执行文档 W-11）：**只读**，从不改写 `paintoppresets/` 里的源文件。
 *
 * - v2 管理的预设：有效字节 = 快照 + 覆盖（[EffectivePresetBuilder.buildFor]），与引擎加载的那份一致；
 * - 其余（v2 关闭、.myb 等）：磁盘或 asset 字节 + 旧 brushParams 记录，在内存里套用（旧行为是 updateKppFile 写回源文件）。
 *
 * 结果写到 [stage] 的临时目录后交给 KritaBundleManager。
 */
internal object BrushExportSource {

    fun effectiveBytes(name: String, presetDir: File, legacy: BrushParams?, asset: (String) -> ByteArray?): ByteArray? {
        BrushStoreBootstrap.managedEntry(name)?.let { e ->
            val b = EffectivePresetBuilder.buildFor(e.id)
            if (b != null) return b
        }
        val src = File(presetDir, "$name.kpp")
        val raw = if (src.isFile) src.readBytes() else asset("paintoppresets/$name.kpp") ?: return null
        return if (legacy != null) KppHelper.updateKppBytes(raw, name, legacy) else raw
    }

    /** 笔尖文件名：有效字节里的 brush_definition 为准；旧路径下记录里的 tipAsset 优先（与旧行为一致） */
    fun tipName(name: String, bytes: ByteArray, legacy: BrushParams?): String? {
        val managed = BrushStoreBootstrap.managedEntry(name) != null
        return if (managed) {
            KppHelper.extractTipAssetFilename(bytes)
        } else {
            legacy?.tipAsset?.ifBlank { null } ?: KppHelper.extractTipAssetFilename(bytes)
        }
    }

    fun assetReader(ctx: android.content.Context): (String) -> ByteArray? = { path ->
        try { ctx.assets.open(path).use { it.readBytes() } } catch (_: Exception) { null }
    }

    /** 写到 [stagingDir]/<name>.kpp（每次先清空同名旧文件） */
    fun stage(name: String, bytes: ByteArray, stagingDir: File): File {
        stagingDir.mkdirs()
        val f = File(stagingDir, "$name.kpp")
        f.writeBytes(bytes)
        BrushDiagnostics.log("export_stage", "name" to name, "md5" to BrushDiagnostics.md5(bytes))
        return f
    }
}
