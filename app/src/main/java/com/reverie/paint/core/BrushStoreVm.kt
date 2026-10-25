/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.lifecycle.viewModelScope
import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.BrushEditSession
import com.reverie.paint.core.brushstore.BrushEngineApplier
import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushOverrideStore
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import com.reverie.paint.core.brushstore.ImportedResetRecord
import com.reverie.paint.core.brushstore.PresetReadback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2 笔刷存储与 [PaintViewModel] 的接线（执行文档 W-05/W-06）。
 *
 * 只对 [BrushStoreBootstrap.managedEntry] 返回非空的预设生效（v2 已激活、有快照、.kpp）；
 * 其余情况各入口返回 false，调用方继续走旧路径。
 *
 * 不变式：选中预设后 VM 的 brush* 字段 == 有效 kpp 的回读（APP_ONLY 取旧记录），
 * 引擎 == loadBrushPreset(有效 kpp) + [BrushEngineApplier] 补的非 preset 状态。
 */
internal object BrushStoreVm {

    fun isEraserPreset(p: BrushPresetInfo?): Boolean =
        p?.group == "橡皮擦" || p?.name?.startsWith("a)_Eraser", ignoreCase = true) == true ||
            p?.name?.contains("Eraser", ignoreCase = true) == true

    private fun presetDir(): File? = BrushStoreBootstrap.presetDir

    /** 有效字节回读（APP_ONLY 取旧 brushParams 记录） */
    fun readback(vm: PaintViewModel, e: BrushIdentity.Entry): Pair<BrushParams, File>? {
        val dir = presetDir() ?: return null
        val f = EffectivePresetBuilder.targetFile(dir, e)
        if (!f.exists()) EffectivePresetBuilder.materialize(e.id, dir)
        if (!f.exists()) return null
        val eff = PresetReadback.fromEffectiveBytes(f.readBytes(), e.ext, PresetReadback.appBase(vm.brushParams[e.name]))
        return eff to f
    }

    private fun nonPresetState(
        vm: PaintViewModel,
        isEraser: Boolean,
        size: Double?,
        opacity: Double?,
        flow: Double?,
        eff: BrushParams,
    ) = BrushEngineApplier.NonPresetState(
        isEraser = isEraser,
        color = vm.brushColor,
        secondaryColor = vm.brushSecondaryColor,
        compositeOverride = if (isEraser) "erase" else null,
        size = size,
        opacity = opacity,
        flow = flow,
        angleDeg = BrushEngineApplier.liveAngle(eff),
    )

    /** v2 选择预设；返回 false 表示不归 v2 管 */
    fun select(vm: PaintViewModel, index: Int, preset: BrushPresetInfo?): Boolean {
        if (preset == null) return false
        val e = BrushStoreBootstrap.managedEntry(preset.name) ?: return false
        val dir = presetDir() ?: return false
        // 上一个预设的待提交编辑先落盘，免得被取消的防抖任务吞掉
        BrushEditSession.flushAll(dir)
        val (eff, file) = readback(vm, e) ?: return false
        val parsed = KppHelper.parseKppFile(file)
        val isEraser = vm.currentToolId == "eraser" || isEraserPreset(preset)
        // size/opacity/flow 的工具记忆由 after 里的 applyToolParamMemoryOverlay 统一推 UI+引擎（含 NaN 防护）
        val st = nonPresetState(vm, isEraser, null, null, null, eff)
        val compOp = if (isEraser) "erase" else eff.compositeOp.ifBlank { "normal" }
        BrushDiagnostics.log("select", "id" to e.id, "index" to index, "eraser" to isEraser)
        vm.runCore(after = {
            applyToUi(vm, eff, parsed, preset.isBuiltIn, compOp)
            vm.applyToolParamMemoryOverlay(preset.name)
            vm.checkBrushSizeLimit()
        }) {
            if (BrushEngineApplier.selectAndApply(BrushEngineApplier.NativeEngine, index, st, e.id)) {
                Breadcrumbs.record("Brush", "Load preset [$index]: ${preset.name} (v2)")
                try {
                    vm.prefs().edit().putInt("last_brush_preset_index", index).apply()
                } catch (_: Exception) {
                }
            }
            vm.fetchBrushOutlinePolygons()
        }
        return true
    }

    /**
     * #136 A 取证：选笔/恢复入口的记录与索引快照（只读，不改任何状态）。
     *
     * 用来区分「记录层 / 笔尖名层 / 索引层」三种致因：`rec/dc/dyn` 看记录是否被写坏，
     * `tip` 看笔尖名，`presetIdx/vmIdx/toolIdx/origIdx` 看索引是否漂移。
     */
    private fun selectEvidence(vm: PaintViewModel, name: String, presetIdx: Int, origIdx: Int?): List<Pair<String, Any?>> {
        val r = vm.brushParams[name]
        val out = mutableListOf<Pair<String, Any?>>(
            "name" to name,
            "presetIdx" to presetIdx,
            "vmIdx" to vm.brushPresetIndex,
            "toolIdx" to (vm.toolBrushStates[vm.currentToolId]?.presetIndex ?: -1),
            "rec" to (r != null),
            "cust" to (r?.isCustomized ?: false),
            "dc" to (r?.dynamicsCustomized ?: false),
            "dyn" to (r?.dynamicOptions?.size ?: 0),
            "tip" to (r?.tipAsset ?: "-"),
        )
        if (origIdx != null) out += "origIdx" to origIdx
        return out
    }

    /** v2 选笔前的落盘准备：不碰引擎、不写 UI（引擎与 UI 由上游链负责） */
    fun prepareSelect(vm: PaintViewModel, preset: BrushPresetInfo?) {
        if (preset == null) return
        if (BrushStoreBootstrap.managedEntry(preset.name) == null) return
        val dir = presetDir() ?: return
        // 上一支笔的待提交编辑必须先落盘，且必须先于 loadBrushPreset（同步执行）
        BrushEditSession.flushAll(dir)
        BrushDiagnostics.log("v2_prepare_select", *selectEvidence(vm, preset.name, preset.index, null).toTypedArray())
    }

    /** v2 橡皮恢复前的落盘准备：仅同步 flush 当前预设的待提交编辑，无引擎调用 */
    fun prepareRestore(vm: PaintViewModel, preset: BrushPresetInfo?) {
        val e = preset?.let { BrushStoreBootstrap.managedEntry(it.name) } ?: return
        val dir = presetDir() ?: return
        BrushEditSession.flush(e.id, dir)
        BrushDiagnostics.log(
            "v2_prepare_restore",
            *selectEvidence(vm, preset.name, preset.index, vm.activeStrokeOriginalPresetIndex).toTypedArray(),
        )
    }

    /** 每次选笔 after 完成时捕获的 UI 基线（id → 上游 after 值），供 mergeEdit 判定"用户真正改了什么" */
    private val uiBaseline = HashMap<String, BrushParams>()

    /** 供 [com.reverie.paint.core.brushstore.BrushEditSession] 读取基线（不直接暴露可变 Map） */
    internal fun uiBaselineOf(id: String): BrushParams? = uiBaseline[id]
    internal fun setUiBaseline(id: String, p: BrushParams) { uiBaseline[id] = p }

    fun captureUiBaseline(vm: PaintViewModel, name: String?) {
        if (name.isNullOrBlank()) return
        val e = BrushStoreBootstrap.managedEntry(name) ?: return
        val existing = vm.brushParams[name]
        val preset = vm.brushPresets.firstOrNull { it.name == name }
        uiBaseline[e.id] = vm.currentBrushParamsFromVm(
            name,
            existing?.dynamicsCustomized == true,
            existing?.smudgeCustomized == true,
            existing?.spacingCustomized == true,
            existing,
            isEraserPreset(preset),
        )
        BrushDiagnostics.log("v2_capture_ui_baseline", "name" to name)
    }

    /** 回读值原样赋给 UI（不做旧路径那些 coerce/兜底，否则下一次编辑会把兜底值写成覆盖） */
    fun applyToUi(vm: PaintViewModel, s: BrushParams, parsed: KppHelper.KppParsedAttributes, isBuiltIn: Boolean, compOp: String) {
        vm.brushSize = s.size
        vm.brushOpacity = s.opacity
        vm.brushFlow = s.flow
        vm.brushSpacing = s.spacing
        vm.brushAngle = s.angle
        vm.brushScatter = s.scatter
        vm.brushFade = s.fade
        vm.brushSoftness = s.softness
        vm.brushRatio = s.ratio
        vm.brushSharpness = s.sharpness
        vm.brushRotation = s.rotation
        vm.brushCompositeOp = compOp
        vm.brushAntiAliasing = s.antiAliasing
        vm.brushTipShape = s.tipShape
        vm.brushRandomFlipX = s.randomFlipX
        vm.brushRandomFlipY = s.randomFlipY
        vm.brushFollowDirection = s.followDirection
        vm.brushStreamline = s.streamline
        vm.brushTaper = s.taper
        vm.brushTextureEnabled = s.textureEnabled
        vm.brushTextureScale = s.textureScale
        vm.brushTextureStrength = s.textureStrength
        vm.brushTextureMode = s.textureMode
        vm.brushTexturePattern = s.texturePattern
        vm.brushHueJitter = s.hueJitter
        vm.brushSatJitter = s.satJitter
        vm.brushValJitter = s.valJitter
        vm.brushSecondaryMix = s.secondaryMix
        vm.brushPressureColorMix = s.pressureColorMix
        vm.brushPressureEnabled = s.pressureEnabled
        vm.brushPressureSize = s.pressureSize
        vm.brushPressureOpacity = s.pressureOpacity
        vm.brushPressureFlow = s.pressureFlow
        vm.brushSpeedSize = s.speedSize
        vm.brushPressureCurve = s.pressureCurve
        vm.brushMinSizeLimit = s.minSizeLimit
        vm.brushMaxSizeLimit = s.maxSizeLimit
        vm.brushTipAsset = s.tipAsset
        vm.brushPaintOpId = s.paintOpId
        vm.brushAirbrush = s.airbrush
        vm.brushAirbrushRate = s.airbrushRate
        vm.brushSmudgeRate = s.smudgeRate
        vm.brushSmudgeLength = s.smudgeLength
        vm.brushSpikes = s.spikes
        vm.brushJitterAngle = s.jitterAngle
        vm.brushJitterSize = s.jitterSize
        vm.brushMaskingEnabled = s.maskingEnabled
        vm.brushMaskingCompositeOp = s.maskingCompositeOp
        vm.brushMaskingSizeRatio = s.maskingSizeRatio
        vm.brushMaskingSpacing = s.maskingSpacing
        vm.brushMaskingTipAsset = s.maskingTipAsset
        vm.brushMaskingTipShape = s.maskingTipShape
        vm.brushMaskingFade = s.maskingFade
        vm.brushMaskingSoftness = s.maskingSoftness
        vm.brushRotationSensor = s.rotationSensor
        vm.brushScatterSensor = s.scatterSensor
        vm.brushSizeSensor = s.sizeSensor
        vm.brushOpacitySensor = s.opacitySensor
        vm.brushFlowSensor = s.flowSensor
        vm.brushAuthor = if (isBuiltIn) "Krita" else s.author
        vm.brushIsAuthorLocked = if (isBuiltIn) true else s.isAuthorLocked
        vm.brushDescription = s.description
        vm.brushVersion = s.version
        vm.brushColorRate = s.colorRate
        vm.brushSmudgeMode = s.smudgeMode
        // 曲线 UI 只从有效 kpp 灌入（覆盖已经写进去了）
        vm.applySensorXmlToDynamicOptions(parsed.sensorXml, parsed)
    }

    /** v2 保存：只 stage + 120ms 防抖落盘（flush → mergeEdit → materialize），不碰引擎 */
    private var persistJob: Job? = null

    fun onSavedPersist(vm: PaintViewModel, name: String, p: BrushParams) {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return
        val dir = presetDir() ?: return
        BrushEditSession.stage(e.id, p, PresetReadback.appBase(p))
        persistJob?.cancel()
        persistJob = vm.viewModelScope.launch(Dispatchers.Default) {
            delay(120L)
            val c = BrushEditSession.flush(e.id, dir) ?: return@launch
            if (c.needsReload) BrushDiagnostics.log("v2_persist_needs_reload", "name" to name, "keys" to c.changedKeys.joinToString(","))
        }
    }

    /** 旧重载块 loadBrushPreset 之前同步调用：取消/抢先防抖任务并立即落盘，保证加载的是含本次编辑的有效字节 */
    fun flushNow(name: String) {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return
        val dir = presetDir() ?: return
        persistJob?.cancel()
        persistJob = null
        BrushEditSession.flush(e.id, dir)
    }

    /** 临时覆盖（物理橡皮等）结束后重新加载原预设；返回 false 表示不归 v2 管。须在渲染线程调用。 */
    fun restoreOnRenderThread(
        vm: PaintViewModel,
        preset: BrushPresetInfo?,
        index: Int,
        isEraserTool: Boolean,
        size: Double,
        opacity: Double,
        flow: Double,
    ): Boolean {
        if (preset == null) return false
        val e = BrushStoreBootstrap.managedEntry(preset.name) ?: return false
        val (eff, _) = readback(vm, e) ?: return false
        val isEraser = isEraserTool || isEraserPreset(preset)
        BrushEngineApplier.selectAndApply(
            BrushEngineApplier.NativeEngine,
            index,
            nonPresetState(vm, isEraser, size, opacity, flow, eff),
            e.id,
        )
        return true
    }

    // ------------------------------------------------------------ 工作台快照 / 回滚（W-05）

    /** 进入工作台时记下覆盖；返回 null 表示不归 v2 管 */
    fun captureStudio(name: String): BrushOverrideStore.Overrides? {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return null
        presetDir()?.let { BrushEditSession.flush(e.id, it) }
        return BrushOverrideStore.get(e.id)
    }

    fun studioChanged(name: String, initial: BrushOverrideStore.Overrides): Boolean {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return false
        if (BrushEditSession.hasPending(e.id)) presetDir()?.let { BrushEditSession.flush(e.id, it) }
        return BrushOverrideStore.get(e.id) != initial
    }

    /** 回滚：丢弃待提交编辑，覆盖恢复为进入时的值并重新生成有效文件 */
    fun revertStudio(name: String, initial: BrushOverrideStore.Overrides): Boolean {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return false
        val dir = presetDir() ?: return false
        pendingKppReloadJob?.cancel()
        BrushEditSession.locked {
            BrushEditSession.discard(e.id)
            BrushOverrideStore.replace(e.id, initial)
            EffectivePresetBuilder.materialize(e.id, dir)
        }
        BrushDiagnostics.log("studio_revert", "id" to e.id, "controls" to initial.controls.size)
        return true
    }

    // ------------------------------------------------------------ 重置 / 已修改（W-06）

    /**
     * v2 重置。分两段，必须主线程调用（[PaintViewModel.brushParams] 是 Compose 状态）。
     *
     * 1. 存储复位：对所有 managed 预设无条件执行（清覆盖、物化回快照、取消待提交、清 uiBaseline）。
     *    [BrushEditSession.reset] 是仓库里唯一清 [BrushOverrideStore] 的地方，跳过它会让
     *    [isModified] 的角标不消失、旧覆盖在下次保存时复活，所以这一段不能按 provenance 早退。
     * 2. 记录回到导入态：只对 provenance ∈ {import, abr} 生效（[ImportedResetRecord]）。
     *    命中则落盘记录、清工具参数记忆并返回 true 短路调用方的上游旧逻辑；
     *    未命中返回 false，调用方继续走上游，行为与改动前逐字节一致。
     */
    fun reset(vm: PaintViewModel, name: String): Boolean {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return false
        val dir = presetDir() ?: return false
        pendingKppReloadJob?.cancel()
        persistJob?.cancel()
        BrushEditSession.reset(e.id, dir) ?: return false
        uiBaseline.remove(e.id)
        val action = ImportedResetRecord.actionFor(e.id, vm.brushParams[name])
        when (action) {
            is ImportedResetRecord.Action.NotApplicable -> {
                BrushDiagnostics.log("reset_record", "name" to name, "id" to e.id, "action" to "not_applicable")
                return false
            }
            is ImportedResetRecord.Action.Delete -> {
                vm.brushParams.remove(name)
                BrushDiagnostics.log("reset_record", "name" to name, "id" to e.id, "action" to "delete")
            }
            is ImportedResetRecord.Action.Restore -> {
                vm.brushParams[name] = action.p
                BrushDiagnostics.log(
                    "reset_record",
                    "name" to name, "id" to e.id, "action" to "restore",
                    "dc" to action.p.dynamicsCustomized, "dyn" to action.p.dynamicOptions.size,
                )
            }
        }
        vm.persistBrushParams()
        vm.toolBrushStates = vm.toolBrushStates.mapValues { (_, s) -> s.copy(paramMemory = s.paramMemory - name) }
        vm.persistToolBrushStates()
        return true
    }

    /** v2 已修改：有覆盖，或旧记录里有 APP_ONLY 调参；null 表示不归 v2 管 */
    fun isModified(vm: PaintViewModel, name: String): Boolean? {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return null
        // 先读 brushParams（Compose 状态）：每次保存都会写它，列表角标借此重组
        val app = PresetReadback.appOnlyModified(vm.brushParams[name], PresetReadback.baseline(e.id))
        return app || BrushOverrideStore.get(e.id).controls.isNotEmpty() || BrushEditSession.hasPending(e.id)
    }
}
