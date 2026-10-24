/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper

/**
 * 上游 v1.5.6 `PaintViewModelBrush.kt` 880-970 行（selectBrushPreset 未定制 `} else {`
 * 分支）的纯函数转写：给定引擎默认值数组与 kpp 解析结果，还原"未定制时 UI 会落到
 * 的参数基线"。供 S2b/S2.3 修复对齐用，不替换 selectBrushPreset 旧链里的原文。
 *
 * 逐字转写规则与差异说明：
 * - 每条 `brushXxx = …` VM 赋值对应转为 BrushParams 同名字段（字段对应关系按
 *   `PaintViewModelBrushParams.kt` 555-626 行 saveBrushParam 的构造表）；
 * - `d.size >= 8` / `>= 3` 两个分支与 [resolvePresetSize]（PVMB 748-752，此处为
 *   同文私有副本——原函数是 PaintViewModel 成员，纯函数上下文无法引用）逐字保留；
 * - 分支条件未命中时上游沿用 VM 既有值，这里对应"保持 BrushParams 构造默认值"。
 *
 * 上游变量 → 函数参数映射（KDoc 逐个注明）：
 * - `d` = 上游 `ReverieCoreBridge.brushPresetDefaults(index)`（引擎数据由调用方取好传入）；
 * - `parsed` = 上游 `KppHelper.parseKppFile(kppFile)`（kpp 不存在时调用方传 `KppParsedAttributes()`）；
 * - `saved` = 上游同名 `saved: BrushParams?`；
 * - `isBuiltIn` = 上游 selectBrushPreset 作用域内的 `isBuiltIn`；
 * - `effectiveCompOp` = 上游 `effectiveCompOp`；
 * - `engineTipFilename` = 上游 `if (index >= 0) ReverieCoreBridge.brushPresetTipFilename(index) else ""`
 *   的求值结果（JNI 调用留在调用方，本文件不依赖引擎类；index < 0 时传 ""）；
 * - `enginePaintOpId` = 上游 `if (index >= 0) ReverieCoreBridge.brushPresetPaintOpId(index) else "paintbrush"`
 *   的求值结果（同上，index < 0 时传 "paintbrush"）。
 *
 * 上游分支里不落 BrushParams 字段、转写时跳过的语句：
 * - `applySensorXmlToDynamicOptions(parsed.sensorXml, parsed)`：只写 VM 的 UI 曲线
 *   状态 Map（DynamicOptionConfig），对应 BrushParams.dynamicOptions 在未定制分支
 *   没有直接赋值 → 保持默认 emptyMap；
 * - 分支之后的 `applyToolParamMemoryOverlay` / `checkBrushSizeLimit` 不在 880-970
 *   转写范围内。
 * 上游分支未赋值的 BrushParams 字段（dynamicOptions、isCustomized、dynamicsCustomized、
 * smudgeCustomized、spacingCustomized）保持构造默认值。
 */
object OfficialUiBaseline {

    fun nonCustomized(
        d: DoubleArray,
        parsed: KppHelper.KppParsedAttributes,
        saved: BrushParams?,
        isBuiltIn: Boolean,
        effectiveCompOp: String,
        engineTipFilename: String,
        enginePaintOpId: String,
    ): BrushParams {
        val base = BrushParams()
        // 仅这 10 个字段受 d.size 分支条件控制，其余在分支外无条件赋值
        var size = base.size
        var opacity = base.opacity
        var flow = base.flow
        var spacing = base.spacing
        var airbrush = base.airbrush
        var airbrushRate = base.airbrushRate
        var smudgeRate = base.smudgeRate
        var smudgeLength = base.smudgeLength
        var colorRate = base.colorRate
        var smudgeMode = base.smudgeMode
        if (d.size >= 8) {
            size = resolvePresetSize(d[0], saved?.size ?: parsed.paintOpSize)
            opacity = d[1].coerceIn(0.0, 1.0)
            flow = d[2].coerceIn(0.0, 1.0)
            val rawSp = d[3]
            spacing = rawSp.coerceIn(0.01, 2.5)
            airbrush = d[4] > 0.5
            val defaultRate = d[5]
            airbrushRate = if (defaultRate >= 5.0) defaultRate else 30.0
            smudgeRate = d[6]
            smudgeLength = d[7]
            colorRate = d[6]
            smudgeMode = 0
        } else if (d.size >= 3) {
            size = resolvePresetSize(d[0], saved?.size ?: parsed.paintOpSize)
            opacity = d[1].coerceIn(0.0, 1.0)
            flow = d[2].coerceIn(0.0, 1.0)
            spacing = 0.1
        }
        val followDirection = (d.getOrNull(17) ?: 0.0) > 0.5
        val rawSc = d.getOrNull(9) ?: 0.0
        return BrushParams(
            size = size,
            opacity = opacity,
            flow = flow,
            spacing = spacing,
            angle = d.getOrNull(8) ?: 0.0,
            scatter = if (rawSc >= 2.0) 0.0 else rawSc.coerceIn(0.0, 1.0),
            // SoftnessValue 缺省即 1.0(neutral/不改动笔尖羽化)；引擎侧兜底报告的是 0.5，
            // 那是"半羽化"而不是中性值，所以这里不拿 d[10] 兜底，直接落到 neutral。
            softness = parsed.softness ?: KppHelper.SOFTNESS_NEUTRAL,
            ratio = parsed.ratio ?: d.getOrNull(11) ?: 1.0,
            sharpness = d.getOrNull(12) ?: 0.0,
            // 附加旋转偏角默认初始为 0.0 度，不读取 Krita 的 RotationValue 强度乘数 (1.0)
            rotation = 0.0,
            pressureSize = parsed.pressureSize ?: d.getOrNull(14) ?: 1.0,
            pressureOpacity = parsed.pressureOpacity ?: d.getOrNull(15) ?: 0.0,
            pressureFlow = parsed.pressureFlow ?: d.getOrNull(16) ?: 0.0,
            followDirection = followDirection,
            randomFlipX = (d.getOrNull(18) ?: 0.0) > 0.5,
            randomFlipY = (d.getOrNull(19) ?: 0.0) > 0.5,
            antiAliasing = parsed.antiAliasing ?: if ((d.getOrNull(20) ?: 1.0) > 0.5) 1 else 0,

            compositeOp = effectiveCompOp,
            minSizeLimit = 1.0,
            maxSizeLimit = maxOf(500.0, size),
            author = if (isBuiltIn) "Krita" else (saved?.author ?: "外部创作者 (分享)"),
            isAuthorLocked = if (isBuiltIn) true else (saved?.isAuthorLocked ?: true),
            tipAsset = if (engineTipFilename.isNotBlank()) engineTipFilename else (saved?.tipAsset ?: ""),
            paintOpId = enginePaintOpId,

            fade = parsed.fade ?: KppHelper.FADE_SOLID,
            tipShape = parsed.tipShape ?: 0,
            streamline = 0.0,
            taper = 0.0,
            textureEnabled = parsed.textureEnabled ?: false,
            textureScale = parsed.textureScale ?: 1.0,
            textureStrength = parsed.textureStrength ?: 0.5,
            textureMode = parsed.textureMode ?: "multiply",
            texturePattern = parsed.texturePattern ?: "",
            hueJitter = parsed.hueJitter ?: 0.0,
            satJitter = parsed.satJitter ?: 0.0,
            valJitter = parsed.valJitter ?: 0.0,
            secondaryMix = parsed.secondaryMix ?: 0.0,
            pressureColorMix = false,
            pressureEnabled = true,
            speedSize = 0.0,
            pressureCurve = 0,
            spikes = parsed.spikes ?: 2,
            jitterAngle = 0.0,
            jitterSize = 0.0,

            maskingEnabled = parsed.maskingEnabled ?: false,
            maskingCompositeOp = parsed.maskingCompositeOp ?: "multiply",
            maskingSizeRatio = parsed.maskingSizeRatio ?: 1.0,
            maskingSpacing = parsed.maskingSpacing ?: 0.1,
            maskingTipAsset = parsed.maskingTipAsset ?: "",
            maskingTipShape = 0,
            maskingFade = 0.0,
            maskingSoftness = 1.0,

            rotationSensor = parsed.rotationSensor ?: (if (followDirection) "drawingangle" else ""),
            scatterSensor = parsed.scatterSensor ?: "fuzzy",
            sizeSensor = parsed.sizeSensor ?: "pressure",
            opacitySensor = parsed.opacitySensor ?: "pressure",
            flowSensor = parsed.flowSensor ?: "pressure",

            description = saved?.description ?: "",
            version = saved?.version ?: "1.0",
        )
    }

    /**
     * 逐字副本：上游 PaintViewModelBrush.kt 748-752（当前工作区 608-612 行）的
     * PaintViewModel.resolvePresetSize。原函数是 VM 成员，纯函数上下文无法引用。
     */
    private fun resolvePresetSize(engineSize: Double, savedSize: Double?): Double {
        if (engineSize.isFinite() && engineSize > 1.0) return engineSize
        if (savedSize != null && savedSize.isFinite() && savedSize > 1.0) return savedSize
        return if (engineSize.isFinite()) engineSize else 20.0
    }
}
