/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams

/**
 * T3 oracle：上游 v1.5.6（commit 49f0f78）四条链的**独立**转写（PLAN_brush_parity.md §5 T3）。
 * 只被测试使用，产品代码不引用；与产品链路无 import 关系（[OBp] 仅是数据搬运视图）。
 *
 * 转写方式：从 `git show 49f0f78:<path>` 原文逐行照抄控制流，`ReverieCoreBridge.<setter>(...)`
 * 换成 `calls += Call("<setter>", listOf(args))`；Breadcrumbs/prefs/recorder/
 * fetchBrushOutlinePolygons 等非引擎 setter 调用不记录。每段函数注明原文行号。
 *
 * 来源文件与区间：
 *  - select   ：app/src/main/java/com/reverie/paint/core/PaintViewModelBrush.kt 977-1131
 *  - restore  ：同上 1140-1274
 *  - reload   ：app/src/main/java/com/reverie/paint/core/PaintViewModelBrushParams.kt 642-718
 *  - replay   ：app/src/main/java/com/reverie/paint/core/PlaybackEngine.kt 267-273 + 306-369
 */
internal data class Call(val name: String, val args: List<Any?>)

/**
 * 链条读取的 BrushParams 字段子集（与产品 [BrushParams] 字段一一对应，仅搬运不加工）。
 */
internal data class OBp(
    val isCustomized: Boolean = false,
    val size: Double = 20.0,
    val opacity: Double = 1.0,
    val flow: Double = 1.0,
    val spacing: Double = 0.1,
    val spacingCustomized: Boolean = false,
    val angle: Double = 0.0,
    val rotation: Double = 0.0,
    val scatter: Double = 0.0,
    val dynamicsCustomized: Boolean = false,
    val fade: Double = 1.0,
    val softness: Double = 1.0,
    val ratio: Double = 1.0,
    val sharpness: Double = 0.0,
    val compositeOp: String = "normal",
    val dynamicOptions: Map<String, String> = emptyMap(),
    val pressureEnabled: Boolean = true,
    val pressureSize: Double = 1.0,
    val pressureOpacity: Double = 1.0,
    val pressureFlow: Double = 1.0,
    val pressureCurve: Int = 0,
    val followDirection: Boolean = false,
    val jitterAngle: Double = 0.0,
    val jitterSize: Double = 0.0,
    val randomFlipX: Boolean = false,
    val randomFlipY: Boolean = false,
    val antiAliasing: Int = 1,
    val smudgeCustomized: Boolean = false,
    val smudgeRate: Double = 0.5,
    val smudgeLength: Double = 0.5,
    val airbrush: Boolean = false,
    val airbrushRate: Double = 30.0,
    val tipAsset: String = "",
    val textureEnabled: Boolean = false,
    val textureScale: Double = 1.0,
    val textureStrength: Double = 0.5,
    val textureMode: String = "multiply",
    val texturePattern: String = "",
    val rotationSensor: String = "drawingangle",
) {
    companion object {
        fun of(p: BrushParams): OBp = OBp(
            isCustomized = p.isCustomized,
            size = p.size,
            opacity = p.opacity,
            flow = p.flow,
            spacing = p.spacing,
            spacingCustomized = p.spacingCustomized,
            angle = p.angle,
            rotation = p.rotation,
            scatter = p.scatter,
            dynamicsCustomized = p.dynamicsCustomized,
            fade = p.fade,
            softness = p.softness,
            ratio = p.ratio,
            sharpness = p.sharpness,
            compositeOp = p.compositeOp,
            dynamicOptions = p.dynamicOptions,
            pressureEnabled = p.pressureEnabled,
            pressureSize = p.pressureSize,
            pressureOpacity = p.pressureOpacity,
            pressureFlow = p.pressureFlow,
            pressureCurve = p.pressureCurve,
            followDirection = p.followDirection,
            jitterAngle = p.jitterAngle,
            jitterSize = p.jitterSize,
            randomFlipX = p.randomFlipX,
            randomFlipY = p.randomFlipY,
            antiAliasing = p.antiAliasing,
            smudgeCustomized = p.smudgeCustomized,
            smudgeRate = p.smudgeRate,
            smudgeLength = p.smudgeLength,
            airbrush = p.airbrush,
            airbrushRate = p.airbrushRate,
            tipAsset = p.tipAsset,
            textureEnabled = p.textureEnabled,
            textureScale = p.textureScale,
            textureStrength = p.textureStrength,
            textureMode = p.textureMode,
            texturePattern = p.texturePattern,
            rotationSensor = p.rotationSensor,
        )
    }
}

internal object UpstreamChainOracle {

    /** PaintViewModelBrush.kt:748-752 resolvePresetSize 原样转写 */
    private fun resolvePresetSize(engineSize: Double, savedSize: Double?): Double {
        if (engineSize.isFinite() && engineSize > 1.0) return engineSize
        if (savedSize != null && savedSize.isFinite() && savedSize > 1.0) return savedSize
        return if (engineSize.isFinite()) engineSize else 20.0
    }

    /** PaintViewModelBrush.kt:783（restore 1151-1153 同式）eraser 判定原样转写 */
    private fun isEraserPreset(group: String?, name: String?): Boolean =
        group == "橡皮擦" ||
            (name?.startsWith("a)_Eraser", ignoreCase = true) == true) ||
            (name?.contains("Eraser", ignoreCase = true) == true)

    /**
     * 定制分支的 OptionDynamics 循环单条参数。
     * select 1024-1047 / restore 1192-1215 / reload 676-701 原样转写；
     * [brushDynamicOptionsEnabled] 为 VM 状态 `brushDynamicOptions[opt]?.enabled` 的布尔视图，
     * 传 null 表示该分支不用它（select 未定制分支 1106-1110 与 replay 344-349 的兜底恒 `true`）。
     */
    private fun optionDynamicsArgs(
        opt: String,
        xml: String,
        saved: OBp,
        brushDynamicOptionsEnabled: Map<String, Boolean>?,
    ): List<Any?> {
        val sensorId = Regex("""id="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1) ?: "pressure"
        val curve = Regex("""<curve>([^<]*)</curve>""").find(xml)?.groupValues?.getOrNull(1) ?: "0,0;1,1;"
        val enabledAttr = Regex("""enabled="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull()
        val strengthAttr = Regex("""strength="([^"]+)"""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val strength = strengthAttr ?: when (opt.lowercase()) {
            "size" -> saved.pressureSize
            "opacity" -> saved.pressureOpacity
            "flow" -> saved.pressureFlow
            else -> 1.0
        }
        val isEnabled = enabledAttr ?: when (opt.lowercase()) {
            "size" -> saved.pressureEnabled && saved.pressureSize > 0.001
            "opacity" -> saved.pressureEnabled && saved.pressureOpacity > 0.001
            "flow" -> saved.pressureEnabled && saved.pressureFlow > 0.001
            else -> brushDynamicOptionsEnabled?.get(opt) ?: true
        }
        return listOf(opt, isEnabled, sensorId, curve, strength)
    }

    /**
     * 定制链主体（select 1008-1084 == restore 1176-1252 逐行相同）原样转写。
     * [origSize]/[origOpacity]/[origFlow] 为 restore 前置的三个 size/opacity/flow 调用；
     * select 无此三调用（size/opacity/flow 在链前段已发），传 null 跳过。
     */
    private fun customizedTail(
        calls: MutableList<Call>,
        saved: OBp,
        effectiveCompOp: String,
        brushRotationSensor: String,
        brushDynamicOptions: Map<String, Boolean>,
        origSize: Double?,
        origOpacity: Double?,
        origFlow: Double?,
    ) {
        if (origSize != null) calls += Call("setBrushSize", listOf(origSize))
        if (origOpacity != null) calls += Call("setBrushOpacity", listOf(origOpacity))
        if (origFlow != null) calls += Call("setBrushFlow", listOf(origFlow))
        if (saved.spacingCustomized) {
            calls += Call("setBrushSpacing", listOf(saved.spacing))
        }
        val effAngle = ((saved.angle + saved.rotation) % 360.0).let { if (it < 0.0) it + 360.0 else it }
        calls += Call("setBrushAngle", listOf(effAngle))
        if (saved.dynamicsCustomized) {
            calls += Call("setBrushScatter", listOf(saved.scatter))
        }
        calls += Call("setBrushFade", listOf(saved.fade))
        calls += Call("setBrushSoftness", listOf(saved.softness))
        calls += Call("setBrushRatio", listOf(saved.ratio))
        calls += Call("setBrushSharpness", listOf(saved.sharpness))
        calls += Call("setBrushRotation", listOf(saved.rotation))
        calls += Call("setBrushCompositeOp", listOf(effectiveCompOp))
        if (saved.dynamicsCustomized) {
            if (saved.dynamicOptions.isNotEmpty()) {
                for ((opt, xml) in saved.dynamicOptions) {
                    calls += Call("setBrushOptionDynamics", optionDynamicsArgs(opt, xml, saved, brushDynamicOptions))
                }
            } else {
                calls += Call(
                    "setBrushPressureDynamics",
                    listOf(saved.pressureEnabled, saved.pressureSize, saved.pressureOpacity, saved.pressureFlow, saved.pressureCurve),
                )
            }
        }
        if (saved.followDirection) {
            calls += Call("setBrushFollowDirection", listOf(true))
        } else if (saved.dynamicsCustomized && brushRotationSensor.isBlank()) {
            calls += Call("setBrushFollowDirection", listOf(false))
        }
        calls += Call("setBrushJitter", listOf(saved.jitterAngle, saved.jitterSize))
        calls += Call("setBrushMirror", listOf(saved.randomFlipX, saved.randomFlipY))
        calls += Call("setBrushAntiAliasing", listOf(saved.antiAliasing))
        if (saved.smudgeCustomized) {
            calls += Call("setBrushSmudgeRate", listOf(saved.smudgeRate))
            calls += Call("setBrushSmudgeLength", listOf(saved.smudgeLength))
        }
        val effectiveAirbrushRate = if (saved.airbrushRate >= 5.0) saved.airbrushRate else 30.0
        calls += Call("setBrushAirbrush", listOf(saved.airbrush, effectiveAirbrushRate))
        if (saved.tipAsset.isNotEmpty()) {
            calls += Call("setBrushTipAsset", listOf(saved.tipAsset))
        } else {
            calls += Call("setBrushTipAsset", listOf(""))
        }
        calls += Call(
            "setBrushTexture",
            listOf(saved.textureEnabled, saved.textureScale, saved.textureStrength, saved.textureMode, saved.texturePattern),
        )
    }

    /**
     * 上游 selectBrushPreset 引擎 setter 链（PaintViewModelBrush.kt 977-1131）原样转写。
     * 前置（754-976 的 UI 状态推导）只保留链条消费的输入：
     * effectiveCompOp 按 788-794 原式在函数内推导；brushRotationSensor（865/963 处由
     * 上一支笔/新 kpp 解析写入 VM）与 brushDynamicOptions（868 applySensorXmlToDynamicOptions
     * 的产物）作为 VM 状态入参传入——「链读上一支笔 sensor」的怪癖因此由入参表达。
     * 986-988 的 prefs 落盘与 1131 的 fetchBrushOutlinePolygons 非引擎 setter，不记录。
     */
    fun oracleSelect(
        calls: MutableList<Call>,
        index: Int,
        presetGroup: String?,
        presetName: String?,
        saved: OBp?,
        currentToolIdIsEraser: Boolean,
        nativeCompOp: String,
        brushColor: Int,
        brushSecondaryColor: Int,
        mem: DoubleArray,
        d: DoubleArray,
        parsedPaintOpSize: Double?,
        parsedTipAsset: String?,
        brushRotationSensor: String,
        brushDynamicOptions: Map<String, Boolean>,
    ) {
        val isEraserPreset = isEraserPreset(presetGroup, presetName)
        val isCustomized = saved?.isCustomized == true
        val effectiveCompOp = if (currentToolIdIsEraser || isEraserPreset) {
            "erase"
        } else {
            val savedOp = saved?.compositeOp
            if (isCustomized && !savedOp.isNullOrBlank() && savedOp != "erase") savedOp else nativeCompOp
        }
        // 977-989: if (ReverieCoreBridge.loadBrushPreset(index)) { ... }（引擎调用测试中恒成功）
        calls += Call("loadBrushPreset", listOf(index))
        calls += Call("setPresetIsEraser", listOf(currentToolIdIsEraser || isEraserPreset))
        calls += Call("setBrushCompositeOp", listOf(effectiveCompOp))
        calls += Call("setBrushColor", listOf(brushColor))
        calls += Call("setBrushSecondaryColor", listOf(brushSecondaryColor))

        // 994-1006: size → opacity → flow（size 在 tip 之前）
        val effSize = mem.getOrNull(0) ?: (if (isCustomized) saved?.size else null)
        val finalSize = effSize ?: resolvePresetSize(d.getOrNull(0) ?: 0.0, saved?.size ?: parsedPaintOpSize)
        if (finalSize.isFinite() && finalSize > 0.0) {
            calls += Call("setBrushSize", listOf(finalSize))
        }
        val effOpacity = mem.getOrNull(1) ?: (if (isCustomized) saved?.opacity else null) ?: d.getOrNull(1)?.takeIf { it.isFinite() }
        val effFlow = mem.getOrNull(2) ?: (if (isCustomized) saved?.flow else null) ?: d.getOrNull(2)?.takeIf { it.isFinite() }
        if (effOpacity != null && effOpacity.isFinite()) {
            calls += Call("setBrushOpacity", listOf(effOpacity.coerceIn(0.0, 1.0)))
        }
        if (effFlow != null && effFlow.isFinite()) {
            calls += Call("setBrushFlow", listOf(effFlow.coerceIn(0.0, 1.0)))
        }

        if (saved != null && isCustomized) {
            customizedTail(calls, saved, effectiveCompOp, brushRotationSensor, brushDynamicOptions, null, null, null)
        } else {
            // 1085-1130 未定制分支
            calls += Call("setBrushCompositeOp", listOf(effectiveCompOp))
            val effTip = saved?.tipAsset?.takeIf { it.isNotEmpty() } ?: parsedTipAsset
            if (!effTip.isNullOrEmpty()) {
                calls += Call("setBrushTipAsset", listOf(effTip))
            } else {
                calls += Call("setBrushTipAsset", listOf(""))
            }
            if (saved?.dynamicsCustomized == true) {
                if (saved.dynamicOptions.isNotEmpty()) {
                    for ((opt, xml) in saved.dynamicOptions) {
                        calls += Call("setBrushOptionDynamics", optionDynamicsArgs(opt, xml, saved, null))
                    }
                } else {
                    calls += Call(
                        "setBrushPressureDynamics",
                        listOf(saved.pressureEnabled, saved.pressureSize, saved.pressureOpacity, saved.pressureFlow, saved.pressureCurve),
                    )
                }
            }
        }
    }

    /**
     * 上游 restoreCurrentBrushToNative（PaintViewModelBrush.kt 1140-1274）原样转写。
     * reloadPreset && origPreset >= 0 走完整重载链（定制长链/未定制短链），setToolMode 收尾；
     * 否则短链（eraser/comp/size/opacity/flow/mode）。
     */
    fun oracleRestore(
        calls: MutableList<Call>,
        reloadPreset: Boolean,
        origPreset: Int,
        presetGroup: String?,
        presetName: String?,
        saved: OBp?,
        origSize: Double,
        origOpacity: Double,
        origFlow: Double,
        origOp: String,
        origMode: Int,
        origIsEraser: Boolean,
        nativeCompOp: String,
        brushColor: Int,
        brushSecondaryColor: Int,
        brushRotationSensor: String,
        brushDynamicOptions: Map<String, Boolean>,
    ) {
        if (reloadPreset && origPreset >= 0) {
            val isEraserPreset = isEraserPreset(presetGroup, presetName)
            val isCustomized = saved?.isCustomized == true
            val effectiveCompOp = if (origIsEraser || isEraserPreset) {
                "erase"
            } else {
                val savedOp = saved?.compositeOp
                if (isCustomized && !savedOp.isNullOrBlank() && savedOp != "erase") savedOp else nativeCompOp
            }

            // 1164-1170: if (loadBrushPreset(origPreset)) { ... }
            calls += Call("loadBrushPreset", listOf(origPreset))
            calls += Call("setPresetIsEraser", listOf(origIsEraser || isEraserPreset))
            calls += Call("setBrushCompositeOp", listOf(effectiveCompOp))
            calls += Call("setBrushColor", listOf(brushColor))
            calls += Call("setBrushSecondaryColor", listOf(brushSecondaryColor))

            if (saved != null && isCustomized) {
                customizedTail(calls, saved, effectiveCompOp, brushRotationSensor, brushDynamicOptions, origSize, origOpacity, origFlow)
            } else {
                // 1253-1263 未定制短链
                calls += Call("setBrushSize", listOf(origSize))
                calls += Call("setBrushOpacity", listOf(origOpacity))
                calls += Call("setBrushFlow", listOf(origFlow))
                calls += Call("setBrushCompositeOp", listOf(effectiveCompOp))
                if (saved?.tipAsset?.isNotEmpty() == true) {
                    calls += Call("setBrushTipAsset", listOf(saved.tipAsset))
                } else {
                    calls += Call("setBrushTipAsset", listOf(""))
                }
            }
            calls += Call("setToolMode", listOf(origMode))
        } else {
            calls += Call("setPresetIsEraser", listOf(origIsEraser))
            calls += Call("setBrushCompositeOp", listOf(origOp))
            calls += Call("setBrushSize", listOf(origSize))
            calls += Call("setBrushOpacity", listOf(origOpacity))
            calls += Call("setBrushFlow", listOf(origFlow))
            calls += Call("setToolMode", listOf(origMode))
        }
    }

    /**
     * 上游 saveBrushParam 引擎重载链（PaintViewModelBrushParams.kt 642-718）原样转写。
     * 与 select 定制链的差异：tip 仅在非空时下发（无 else ""）、smudge/airbrush 无条件、
     * airbrushRate 不做 >=5 兜底、texture 在 follow 之前、compositeOp 用 VM 的 brushCompositeOp。
     */
    fun oracleReload(
        calls: MutableList<Call>,
        pSnapshot: OBp,
        targetIdx: Int,
        isEraserPreset: Boolean,
        brushColor: Int,
        brushSecondaryColor: Int,
        brushCompositeOp: String,
        brushRotationSensor: String,
        brushDynamicOptions: Map<String, Boolean>,
    ) {
        calls += Call("loadBrushPreset", listOf(targetIdx))
        if (pSnapshot.tipAsset.isNotBlank()) {
            calls += Call("setBrushTipAsset", listOf(pSnapshot.tipAsset))
        }
        calls += Call("setPresetIsEraser", listOf(isEraserPreset))
        calls += Call("setBrushColor", listOf(brushColor))
        calls += Call("setBrushSecondaryColor", listOf(brushSecondaryColor))
        calls += Call("setBrushSize", listOf(pSnapshot.size))
        calls += Call("setBrushOpacity", listOf(pSnapshot.opacity))
        calls += Call("setBrushFlow", listOf(pSnapshot.flow))
        if (pSnapshot.spacingCustomized) {
            calls += Call("setBrushSpacing", listOf(pSnapshot.spacing))
        }
        val effAngle = ((pSnapshot.angle + pSnapshot.rotation) % 360.0).let { if (it < 0.0) it + 360.0 else it }
        calls += Call("setBrushAngle", listOf(effAngle))
        if (pSnapshot.dynamicsCustomized) {
            calls += Call("setBrushScatter", listOf(pSnapshot.scatter))
        }
        calls += Call("setBrushFade", listOf(pSnapshot.fade))
        calls += Call("setBrushSoftness", listOf(pSnapshot.softness))
        calls += Call("setBrushRatio", listOf(pSnapshot.ratio))
        calls += Call("setBrushSharpness", listOf(pSnapshot.sharpness))
        calls += Call("setBrushRotation", listOf(pSnapshot.rotation))
        calls += Call("setBrushCompositeOp", listOf(brushCompositeOp))
        if (pSnapshot.dynamicsCustomized) {
            if (pSnapshot.dynamicOptions.isNotEmpty()) {
                for ((opt, xml) in pSnapshot.dynamicOptions) {
                    calls += Call("setBrushOptionDynamics", optionDynamicsArgs(opt, xml, pSnapshot, brushDynamicOptions))
                }
            } else {
                calls += Call(
                    "setBrushPressureDynamics",
                    listOf(pSnapshot.pressureEnabled, pSnapshot.pressureSize, pSnapshot.pressureOpacity, pSnapshot.pressureFlow, pSnapshot.pressureCurve),
                )
            }
        }
        calls += Call(
            "setBrushTexture",
            listOf(pSnapshot.textureEnabled, pSnapshot.textureScale, pSnapshot.textureStrength, pSnapshot.textureMode, pSnapshot.texturePattern),
        )
        if (pSnapshot.followDirection) {
            calls += Call("setBrushFollowDirection", listOf(true))
        } else if (pSnapshot.dynamicsCustomized && brushRotationSensor.isBlank()) {
            calls += Call("setBrushFollowDirection", listOf(false))
        }
        calls += Call("setBrushMirror", listOf(pSnapshot.randomFlipX, pSnapshot.randomFlipY))
        calls += Call("setBrushAntiAliasing", listOf(pSnapshot.antiAliasing))
        calls += Call("setBrushJitter", listOf(pSnapshot.jitterAngle, pSnapshot.jitterSize))
        calls += Call("setBrushSmudgeRate", listOf(pSnapshot.smudgeRate))
        calls += Call("setBrushSmudgeLength", listOf(pSnapshot.smudgeLength))
        calls += Call("setBrushAirbrush", listOf(pSnapshot.airbrush, pSnapshot.airbrushRate))
    }

    /**
     * 上游 applyReplayPresetSetupLocked（PlaybackEngine.kt 306-369）+
     * sendPresetEraserFlagLocked（267-273）原样转写。
     * 与 select 定制链的差异：tip 仅非空下发、jitter 带 >0 条件、texture 仅 enabled、
     * fade/softness/ratio/sharpness 带 isFinite 条件、无 Mirror/FollowDirection/Spacing/Size。
     */
    fun oracleReplaySetup(
        calls: MutableList<Call>,
        target: Int,
        presetGroup: String?,
        presetName: String?,
        saved: OBp?,
    ) {
        if (target < 0) return
        calls += Call("loadBrushPreset", listOf(target))
        // sendPresetEraserFlagLocked(target)（267-273）
        val isEraser = presetGroup == "橡皮擦" ||
            presetName?.startsWith("a)_Eraser", ignoreCase = true) == true ||
            presetName?.contains("Eraser", ignoreCase = true) == true
        calls += Call("setPresetIsEraser", listOf(isEraser))
        if (saved != null) {
            if (saved.tipAsset.isNotBlank()) {
                calls += Call("setBrushTipAsset", listOf(saved.tipAsset))
            }
            if (saved.dynamicsCustomized) {
                if (saved.dynamicOptions.isNotEmpty()) {
                    for ((opt, xml) in saved.dynamicOptions) {
                        calls += Call("setBrushOptionDynamics", optionDynamicsArgs(opt, xml, saved, null))
                    }
                } else {
                    calls += Call(
                        "setBrushPressureDynamics",
                        listOf(saved.pressureEnabled, saved.pressureSize, saved.pressureOpacity, saved.pressureFlow, saved.pressureCurve),
                    )
                }
            }
            if (saved.fade.isFinite()) calls += Call("setBrushFade", listOf(saved.fade))
            if (saved.softness.isFinite()) calls += Call("setBrushSoftness", listOf(saved.softness))
            if (saved.ratio.isFinite()) calls += Call("setBrushRatio", listOf(saved.ratio))
            if (saved.sharpness.isFinite()) calls += Call("setBrushSharpness", listOf(saved.sharpness))
            if (saved.jitterAngle > 0.0 || saved.jitterSize > 0.0) {
                calls += Call("setBrushJitter", listOf(saved.jitterAngle, saved.jitterSize))
            }
            if (saved.textureEnabled) {
                calls += Call(
                    "setBrushTexture",
                    listOf(saved.textureEnabled, saved.textureScale, saved.textureStrength, saved.textureMode, saved.texturePattern),
                )
            }
        }
    }
}
