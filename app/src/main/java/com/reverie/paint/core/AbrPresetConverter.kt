/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.json.JSONObject

/**
 * ABR 单个预设 → kpp 的转换（W-09 从 importSingleBrushInternal 原样抽出，行为不变）。
 *
 * 转换结果的 kpp 字节就是 ABR 预设的快照（重置 = 回到这份字节）；[abrParamsJson] 把 AbrParser 的原始结构
 * 和 [CONVERTER_VERSION] 一起写进 source.json，供将来"按新转换器重新转换"（D-5：只提示，不自动执行）与诊断。
 */
internal object AbrPresetConverter {
    /** 转换逻辑有改动（会改变产出字节）时 +1 */
    const val CONVERTER_VERSION = 1

    data class Converted(val bp: BrushParams, val kppBytes: ByteArray, val tipScale: Double?)

    fun convert(
        preset: AbrParser.AbrPresetInfo,
        candidateName: String,
        matchedTip: AbrParser.AbrDecodedTip?,
        matchedTipFileName: String,
        tipUuidToFileName: Map<String, String>,
        packBaseName: String,
    ): Converted {
        val previewBytes = AbrParser.encodePreviewPng(
            tip = matchedTip,
            diameter = preset.diameter,
            roundness = preset.roundness,
        )

        // 映射倾斜与动力学传感器
        val sizeSensor = when {
            preset.tiltSize -> "declination"
            preset.sizeJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val opacitySensor = when {
            preset.tiltOpacity -> "declination"
            preset.opacityJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val flowSensor = when {
            preset.tiltFlow -> "declination"
            preset.flowJitter > 0.05 -> "fuzzy"
            else -> "pressure"
        }
        val rotationSensor = when {
            preset.followDirection -> "drawingangle"
            preset.tiltAngle -> "ascension"
            preset.angleJitter > 0.05 -> "fuzzy"
            else -> "drawingangle"
        }

        val dynamicOptions = mutableMapOf<String, String>()
        if (preset.tiltSize) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.sizeJitter > 0.05) {
            dynamicOptions["Size"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (preset.tiltOpacity) {
            dynamicOptions["Opacity"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.opacityJitter > 0.05) {
            dynamicOptions["Opacity"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (preset.tiltFlow) {
            dynamicOptions["Flow"] = """<!DOCTYPE params><params id="declination"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.flowJitter > 0.05) {
            dynamicOptions["Flow"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (preset.followDirection) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params fanCornersStep="30" fanCornersEnabled="0" angleOffset="0" id="drawingangle"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.tiltAngle) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="ascension"><curve>0,0;1,1;</curve></params>"""
        } else if (preset.angleJitter > 0.05) {
            dynamicOptions["Rotation"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }
        if (preset.scatter > 0.001) {
            dynamicOptions["Scatter"] = """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>"""
        }

        // 映射双重画笔 (Dual Brush -> MaskingBrush)
        val dual = preset.dualBrush
        val hasDual = dual != null
        val dualTipFileName = dual?.tipUuid?.let { tipUuidToFileName[it] } ?: ""
        val maskingSizeRatio = if (hasDual && preset.diameter > 0) {
            (dual!!.diameter / preset.diameter).coerceIn(0.05, 5.0)
        } else 1.0

        // 映射纹理贴图 (Texture)
        val tex = preset.texture
        val hasTexture = tex != null

        // 映射色彩动力学 (Color Dynamics)
        val cd = preset.colorDynamics

        val hasPressureDynamics = preset.pressureSize || preset.pressureOpacity || preset.pressureFlow ||
            preset.tiltSize || preset.tiltOpacity || preset.tiltFlow || preset.tiltAngle
        val hasDynamics = hasPressureDynamics || preset.scatter > 0.001 || preset.followDirection ||
            preset.flipX || preset.flipY || preset.sizeJitter > 0.001 || preset.angleJitter > 0.001 ||
            preset.opacityJitter > 0.001 || preset.flowJitter > 0.001 || dynamicOptions.isNotEmpty() ||
            hasDual || hasTexture || cd != null

        val bp = BrushParams(
            size = preset.diameter,
            opacity = 1.0,
            flow = 1.0,
            spacing = preset.spacing,
            angle = preset.angle,
            scatter = preset.scatter,
            ratio = preset.roundness,
            followDirection = preset.followDirection,
            randomFlipX = preset.flipX,
            randomFlipY = preset.flipY,
            pressureEnabled = hasPressureDynamics,
            pressureSize = if (preset.pressureSize || preset.tiltSize) 1.0 else 0.0,
            pressureOpacity = if (preset.pressureOpacity || preset.tiltOpacity) 1.0 else 0.0,
            pressureFlow = if (preset.pressureFlow || preset.tiltFlow) 1.0 else 0.0,
            sizeSensor = sizeSensor,
            opacitySensor = opacitySensor,
            flowSensor = flowSensor,
            rotationSensor = rotationSensor,
            scatterSensor = "fuzzy",
            jitterAngle = preset.angleJitter,
            jitterSize = preset.sizeJitter,
            dynamicOptions = dynamicOptions,
            maskingEnabled = hasDual,
            maskingCompositeOp = dual?.compositeOp ?: "multiply",
            maskingSizeRatio = maskingSizeRatio,
            maskingSpacing = dual?.spacing ?: 0.1,
            maskingTipAsset = dualTipFileName,
            textureEnabled = hasTexture,
            textureScale = tex?.scale ?: 1.0,
            textureStrength = tex?.depth ?: 0.5,
            textureMode = tex?.mode ?: "multiply",
            texturePattern = tex?.patternName ?: "",
            hueJitter = cd?.hueJitter ?: 0.0,
            satJitter = cd?.satJitter ?: 0.0,
            valJitter = cd?.valJitter ?: 0.0,
            secondaryMix = cd?.secondaryMix ?: 0.0,
            airbrush = preset.airbrush,
            tipAsset = matchedTipFileName,
            paintOpId = "paintbrush",
            compositeOp = preset.compositeOp,
            author = "外部创作者 (ABR)",
            isAuthorLocked = true,
            description = "导入自 Photoshop ABR 笔刷包: $packBaseName",
            isCustomized = true,
            dynamicsCustomized = hasDynamics,
            smudgeCustomized = false,
        )

        // File-backed tips report `max(tipW, tipH) * scale` as the brush size
        // (KisScalingSizeBrush::userEffectiveSize), so `scale` is the only way to
        // make that number match the diameter the ABR declares. Without this the
        // size silently becomes the raw tip pixel size, and a preset declaring
        // diameter=80 shows up as 282 for a 282x282 tip.
        val tipScale = if (matchedTip != null && preset.diameter > 0.0 &&
            matchedTip.width > 0 && matchedTip.height > 0
        ) {
            (preset.diameter / maxOf(matchedTip.width, matchedTip.height))
                .coerceIn(0.01, 8.0)
        } else {
            null
        }
        val kppBytes = KppHelper.updateKppBytes(previewBytes, candidateName, bp, tipScale)
        return Converted(bp, kppBytes, tipScale)
    }

    /** AbrParser 原始结构（显式键名，不依赖反射——release 包会混淆字段名） */
    fun abrParamsJson(p: AbrParser.AbrPresetInfo): JSONObject {
        val o = JSONObject()
            .put("name", p.name).put("tipUuid", p.tipUuid ?: JSONObject.NULL).put("tipIndex", p.tipIndex)
            .put("diameter", p.diameter).put("spacing", p.spacing).put("angle", p.angle)
            .put("roundness", p.roundness).put("hardness", p.hardness).put("scatter", p.scatter)
            .put("pressureSize", p.pressureSize).put("pressureOpacity", p.pressureOpacity).put("pressureFlow", p.pressureFlow)
            .put("tiltSize", p.tiltSize).put("tiltOpacity", p.tiltOpacity).put("tiltFlow", p.tiltFlow).put("tiltAngle", p.tiltAngle)
            .put("sizeJitter", p.sizeJitter).put("angleJitter", p.angleJitter).put("roundnessJitter", p.roundnessJitter)
            .put("opacityJitter", p.opacityJitter).put("flowJitter", p.flowJitter)
            .put("minDiameterRatio", p.minDiameterRatio).put("minRoundness", p.minRoundness)
            .put("followDirection", p.followDirection).put("flipX", p.flipX).put("flipY", p.flipY)
            .put("isComputed", p.isComputed).put("airbrush", p.airbrush).put("compositeOp", p.compositeOp)
        p.dualBrush?.let { d ->
            o.put(
                "dualBrush",
                JSONObject().put("tipUuid", d.tipUuid ?: JSONObject.NULL).put("diameter", d.diameter).put("spacing", d.spacing)
                    .put("scatter", d.scatter).put("compositeOp", d.compositeOp).put("flipX", d.flipX).put("flipY", d.flipY),
            )
        }
        p.texture?.let { t ->
            o.put(
                "texture",
                JSONObject().put("patternName", t.patternName).put("patternUuid", t.patternUuid).put("scale", t.scale)
                    .put("depth", t.depth).put("mode", t.mode).put("invert", t.invert),
            )
        }
        p.colorDynamics?.let { c ->
            o.put(
                "colorDynamics",
                JSONObject().put("hueJitter", c.hueJitter).put("satJitter", c.satJitter).put("valJitter", c.valJitter)
                    .put("secondaryMix", c.secondaryMix),
            )
        }
        return o
    }
}
