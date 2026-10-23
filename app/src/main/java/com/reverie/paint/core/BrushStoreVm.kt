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
import com.reverie.paint.core.brushstore.PresetReadback
import kotlinx.coroutines.Dispatchers
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

    /**
     * v2 保存一次编辑：VM 参数暂存，防抖后与有效字节回读做差、并入覆盖、materialize；
     * 改动含 REBUILD 控件时重新 loadBrushPreset（不再逐个 setter 回放）。返回 false 表示不归 v2 管。
     */
    fun onSaved(vm: PaintViewModel, name: String, p: BrushParams, isEraserPreset: Boolean, immediate: Boolean): Boolean {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return false
        val dir = presetDir() ?: return false
        BrushEditSession.stage(e.id, p, PresetReadback.appBase(p))
        val targetIdx = vm.brushPresetIndex
        pendingKppReloadJob?.cancel()
        pendingKppReloadJob = vm.viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(120L)
            val c = BrushEditSession.flush(e.id, dir) ?: return@launch
            if (!c.needsReload) return@launch
            vm.runCore(render = false) {
                if (vm.brushPresetIndex != targetIdx) return@runCore
                val isEraser = vm.currentToolId == "eraser" || isEraserPreset
                BrushEngineApplier.selectAndApply(
                    BrushEngineApplier.NativeEngine,
                    targetIdx,
                    nonPresetState(vm, isEraser, vm.brushSize, vm.brushOpacity, vm.brushFlow, p),
                    e.id,
                )
                vm.fetchBrushOutlinePolygons()
            }
        }
        return true
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
     * 重置 = 清空覆盖（有效文件回到快照：内置为出厂、导入为导入时）+ 删掉旧记录里的调参（元数据保留）
     * + 清掉各工具对该预设的尺寸记忆。返回 false 表示不归 v2 管。调用方负责持久化与重新选中。
     */
    fun reset(vm: PaintViewModel, name: String): Boolean {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return false
        val dir = presetDir() ?: return false
        pendingKppReloadJob?.cancel()
        BrushEditSession.reset(e.id, dir) ?: return false
        val keep = PresetReadback.metadataOnly(vm.brushParams[name])
        if (keep != null) vm.brushParams[name] = keep else vm.brushParams.remove(name)
        return true
    }

    /** v2 已修改：有覆盖，或旧记录里有 APP_ONLY 调参；null 表示不归 v2 管 */
    fun isModified(vm: PaintViewModel, name: String): Boolean? {
        val e = BrushStoreBootstrap.managedEntry(name) ?: return null
        // 先读 brushParams（Compose 状态）：每次保存都会写它，列表角标借此重组
        val app = PresetReadback.appOnlyModified(vm.brushParams[name])
        return app || !BrushOverrideStore.get(e.id).isEmpty() || BrushEditSession.hasPending(e.id)
    }
}
