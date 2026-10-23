/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 旧数据迁移（执行文档 W-07）：旧 `brushParams`（每个预设一份全量快照）→ 稀疏覆盖。
 *
 * 1. 备份 `brush_params` 原文与 `paintoppresets/` 到 `brush_store/legacy_backup/<时间戳>/`（只留最近 [KEEP_BACKUPS] 份，D-9）；
 * 2. 磁盘上没有 identity 的非内置 .kpp 以**当前文件**作为导入快照（provenance=migrated；
 *    含 `ReverieRotation` 且不是 ABR 转换的标 migrated_possibly_modified，原件可能被旧版改写过，有损）；
 * 3. 逐条旧记录分类 K0..K6，只把"真实编辑"写成覆盖。
 *
 * 旧记录本身**不改写**：v2 只从中读 APP_ONLY 与元数据；保留全量记录让 v2 开关关闭时旧路径照常工作。
 * 本对象可重复执行（已有 identity 的文件不再导入，覆盖按记录重新计算后整体替换）。
 */
object LegacyBrushParamsMigrator {
    const val BACKUP_DIR = "legacy_backup"
    const val KEEP_BACKUPS = 2
    const val AUTHOR_DEFAULT = "ReveriePaint"
    const val AUTHOR_ABR = "外部创作者 (ABR)"
    const val PROVENANCE_MIGRATED = "migrated"
    const val PROVENANCE_POSSIBLY_MODIFIED = "migrated_possibly_modified"

    /** size/opacity/flow 经过引擎读回，容差放宽（执行文档 W-07 S2） */
    private const val LOOSE_TOL = 1e-3
    private val LOOSE = setOf(ControlId.SIZE, ControlId.OPACITY, ControlId.FLOW)

    enum class Kind {
        /** 内置、记录未自定义（等价无记录） */
        K0_BUILTIN_PLAIN,
        K1_BUILTIN_EDITED,
        K2_RESET_UNEDITED,
        K3_RESET_THEN_EDITED,
        K4_EDITED,
        K5_IMPORT_EMPTY,
        K6_ABR,

        /** 名字没有 v2 identity（.myb、文件已丢失）：留给旧路径 */
        UNMANAGED,
    }

    data class Outcome(val name: String, val kind: Kind, val controls: Set<String>)

    data class Report(
        val outcomes: List<Outcome>,
        /** 新导入为快照的磁盘文件：名字 → provenance */
        val adopted: Map<String, String>,
        val backupDir: File?,
    ) {
        fun kindOf(name: String): Kind? = outcomes.firstOrNull { it.name == name }?.kind
        fun controlsOf(name: String): Set<String> = outcomes.firstOrNull { it.name == name }?.controls.orEmpty()
    }

    /**
     * 执行迁移。须在 [BrushStoreBootstrap.syncFactory] 之后（内置 identity 已注册）、materializeAll 之前调用。
     * [legacy] 为 VM 已加载的 brushParams；[legacyRaw] 为 prefs 原文（只用于备份）。
     */
    fun run(storeRoot: File, presetDir: File, legacy: Map<String, BrushParams>, legacyRaw: String?, timestamp: String): Report {
        val backup = backup(storeRoot, presetDir, legacyRaw, timestamp)
        val adopted = adoptDiskPresets(presetDir, legacy)
        val outcomes = ArrayList<Outcome>()
        for ((name, bp) in legacy) {
            val o = migrateRecord(name, bp)
            outcomes += o
            BrushDiagnostics.log("migrate_record", "name" to name, "kind" to o.kind.name, "controls" to o.controls.joinToString(","))
        }
        BrushDiagnostics.log(
            "migrate_done",
            "records" to legacy.size,
            "adopted" to adopted.size,
            "overrides" to outcomes.count { it.controls.isNotEmpty() },
            "backup" to backup?.name,
        )
        return Report(outcomes, adopted, backup)
    }

    // ------------------------------------------------------------------ 备份

    fun backup(storeRoot: File, presetDir: File, legacyRaw: String?, timestamp: String): File? {
        val root = File(storeRoot, BACKUP_DIR)
        val dir = File(root, timestamp)
        dir.mkdirs()
        AtomicFiles.writeText(File(dir, "brush_params.json"), legacyRaw ?: "")
        val out = File(dir, "paintoppresets").apply { mkdirs() }
        presetDir.listFiles()?.filter { it.isFile && BrushStoreBootstrap.isFactoryPreset(it.name) }?.forEach { f ->
            f.copyTo(File(out, f.name), overwrite = true)
        }
        // D-9：只保留最近 KEEP_BACKUPS 份
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(KEEP_BACKUPS)?.forEach {
            it.deleteRecursively()
            BrushDiagnostics.log("migrate_backup_pruned", "dir" to it.name)
        }
        BrushDiagnostics.log("migrate_backup", "dir" to dir.name, "presets" to (out.list()?.size ?: 0))
        return dir
    }

    // ------------------------------------------------------------------ 非内置文件 → 导入快照

    /**
     * App 自己生成的 kpp（ABR 转换 / 图片笔尖 / 新建，KppHelper.buildMinimalPresetXml）天然带 ReverieRotation，
     * 且紧跟在 `RotationValue=1` 之后；旧版 updateParam 改写 Krita 原件时则追加到文件尾。
     * 只有后者算"原件可能被改写"（启发式，见执行文档 §10 偏差）。
     */
    private val TEMPLATE_SIGNATURE = Regex(
        """name="RotationValue"><!\[CDATA\[1]]></param>\s*<param type="string" name="ReverieRotation">""",
    )

    fun isPossiblyModified(xml: String?, legacyAuthor: String? = null): Boolean {
        if (xml == null) return false
        if (!xml.contains("name=\"ReverieRotation\"")) return false
        if (legacyAuthor == AUTHOR_ABR) return false
        return !TEMPLATE_SIGNATURE.containsMatchIn(xml)
    }

    fun adoptDiskPresets(presetDir: File, legacy: Map<String, BrushParams> = emptyMap()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val files = presetDir.listFiles()?.filter { it.isFile && it.name.endsWith(".kpp", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        for (f in files) {
            val name = f.nameWithoutExtension
            if (BrushIdentity.entryForName(name) != null) continue
            val bytes = f.readBytes()
            val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true)
            if (xml == null) {
                BrushDiagnostics.log("migrate_adopt_skipped", "name" to name, "reason" to "unreadable")
                continue
            }
            val prov = if (isPossiblyModified(xml, legacy[name]?.author)) PROVENANCE_POSSIBLY_MODIFIED else PROVENANCE_MIGRATED
            val id = BrushIdentity.newImportedId()
            PresetSnapshotStore.putImported(
                id, "kpp", bytes,
                JSONObject().put("provenance", prov).put("pack", "").put("file", f.name).put("md5", BrushDiagnostics.md5(bytes)),
            )
            BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED))
            out[name] = prov
            BrushDiagnostics.log("migrate_adopt", "name" to name, "id" to id, "provenance" to prov)
        }
        return out
    }

    // ------------------------------------------------------------------ 记录分类

    /** S1：只有 d8f2e258 的合成记录（及其后续编辑）会带默认作者且未锁定 */
    fun isResetFingerprint(bp: BrushParams): Boolean = bp.author == AUTHOR_DEFAULT && !bp.isAuthorLocked

    fun migrateRecord(name: String, bp: BrushParams): Outcome {
        val e = BrushIdentity.entryForName(name)
        val snap = e?.let { PresetSnapshotStore.read(it.id) }
        if (e == null || snap == null || !e.ext.equals("kpp", ignoreCase = true)) return Outcome(name, Kind.UNMANAGED, emptySet())
        val snapXml = KppHelper.readPresetXml(snap, stripEmbeddedResources = true) ?: return Outcome(name, Kind.UNMANAGED, emptySet())
        val app = PresetReadback.appBase(bp)
        val snapP = PresetReadback.fromXml(snapXml, app)
        val builtin = e.kind == BrushIdentity.Kind.BUILTIN

        val kind: Kind
        val edited: BrushParams?
        when {
            builtin && !bp.isCustomized -> { kind = Kind.K0_BUILTIN_PLAIN; edited = null }
            builtin -> { kind = Kind.K1_BUILTIN_EDITED; edited = legacyView(bp, snapP) }
            !bp.isCustomized -> { kind = Kind.K5_IMPORT_EMPTY; edited = null }
            isResetFingerprint(bp) -> {
                // K2/K3：只有与合成默认值不同的字段才是用户的真实编辑
                val synthView = legacyView(synthesizeD8f2e258(snapXml, bp.tipAsset), snapP)
                val recView = legacyView(bp, snapP)
                var p = snapP
                var any = false
                for (id in comparable(snapXml)) {
                    val sp = PresetPropertyMap.spec(id)
                    if (!sameLoose(id, sp.get(recView), sp.get(synthView))) {
                        p = sp.set(p, sp.get(recView))
                        any = true
                    }
                }
                kind = if (any) Kind.K3_RESET_THEN_EDITED else Kind.K2_RESET_UNEDITED
                edited = if (any) PresetPropertyMap.deriveFields(p) else null
            }
            bp.author == AUTHOR_ABR -> { kind = Kind.K6_ABR; edited = legacyView(bp, snapP) }
            else -> { kind = Kind.K4_EDITED; edited = legacyView(bp, snapP) }
        }
        if (edited == null) {
            BrushOverrideStore.clear(e.id)
            return Outcome(name, kind, emptySet())
        }
        // 与快照比较：只留不同的（等于快照的不进覆盖）；size/opacity/flow 用宽容差
        var tight = snapP
        for (id in comparable(snapXml)) {
            val sp = PresetPropertyMap.spec(id)
            if (!sameLoose(id, sp.get(edited), sp.get(snapP))) tight = sp.set(tight, sp.get(edited))
        }
        val o = BrushEngineApplier.mergeEdit(snapXml, snapXml, BrushOverrideStore.Overrides(), PresetPropertyMap.deriveFields(tight), app)
        if (o.isEmpty()) BrushOverrideStore.clear(e.id) else BrushOverrideStore.replace(e.id, o)
        return Outcome(name, kind, o.controls.keys)
    }

    private fun comparable(xml: String): List<ControlId> {
        val ctx = PresetContext.of(xml)
        return ControlId.entries.filter { it.klass != ControlClass.APP_ONLY && PresetPropertyMap.spec(it).applicable(ctx) }
    }

    private fun sameLoose(id: ControlId, a: Any?, b: Any?): Boolean {
        if (id in LOOSE && a is Number && b is Number) return abs(a.toDouble() - b.toDouble()) <= LOOSE_TOL
        return PresetPropertyMap.same(id, a, b)
    }

    /**
     * 旧选笔路径（selectBrushPreset 自定义分支）对这条记录实际显示/下发的值：以快照回读 [snapP] 为底，
     * 按旧路径的门控叠加记录字段——间距仅 spc、散布仅 dc、曲线仅 dc 且非空、笔尖/引擎仅非空、
     * 混合模式仅非空且非 erase；`smudgeRate`（旧 UI 实际驱动 ColorRate）→ COLOR_RATE（D-2）。
     */
    fun legacyView(bp: BrushParams, snapP: BrushParams): BrushParams {
        if (abs(bp.colorRate - bp.smudgeRate) > PresetPropertyMap.TOL) {
            BrushDiagnostics.log("migrate_color_rate", "colorRate" to bp.colorRate, "smudgeRate" to bp.smudgeRate)
        }
        val curves = if (bp.dynamicsCustomized && bp.dynamicOptions.isNotEmpty()) snapP.dynamicOptions + bp.dynamicOptions else snapP.dynamicOptions
        return PresetPropertyMap.deriveFields(
            bp.copy(
                spacing = if (bp.spacingCustomized) bp.spacing else snapP.spacing,
                scatter = if (bp.dynamicsCustomized) bp.scatter.coerceIn(0.0, 1.0) else snapP.scatter,
                dynamicOptions = curves,
                tipAsset = bp.tipAsset.ifBlank { snapP.tipAsset },
                paintOpId = if (bp.paintOpId.isBlank() || bp.paintOpId == "defaultpaintop") snapP.paintOpId else bp.paintOpId,
                compositeOp = if (bp.compositeOp.isNotBlank() && bp.compositeOp != "erase") bp.compositeOp else snapP.compositeOp,
                colorRate = bp.smudgeRate,
            ),
        )
    }

    /**
     * d8f2e258 的"重置"为非内置预设合成的记录（PVB 旧 resetSingleBrushPresetState 非 asset 分支，原样搬来）。
     * 原代码的 opacity/flow/spacing/angle 取自引擎 `brushPresetDefaults`；迁移在引擎加载前运行，
     * 这里用同一文件的 XML 回读代替（引擎默认值就是从该 XML 读出的）。
     */
    fun synthesizeD8f2e258(snapXml: String, legacyTip: String): BrushParams {
        val parsed = KppHelper.parseKppAttributes(snapXml)
        val r = PresetReadback.fromXml(snapXml)
        val baseSize = parsed.paintOpSize ?: r.size
        return BrushParams(
            size = if (baseSize > 0.0) baseSize else 20.0,
            opacity = r.opacity.coerceIn(0.0, 1.0),
            flow = r.flow.coerceIn(0.0, 1.0),
            spacing = r.spacing.coerceIn(0.01, 2.5),
            angle = r.angle,
            ratio = parsed.ratio ?: r.ratio,
            tipAsset = parsed.tipAsset ?: legacyTip,
            fade = parsed.fade ?: KppHelper.FADE_SOLID,
            softness = parsed.softness ?: KppHelper.SOFTNESS_NEUTRAL,
            isCustomized = true,
            dynamicsCustomized = parsed.sensorXml.isNotEmpty(),
        )
    }
}
