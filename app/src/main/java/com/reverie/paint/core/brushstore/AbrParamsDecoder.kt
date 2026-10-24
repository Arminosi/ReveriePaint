/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrParser
import org.json.JSONObject

/**
 * [com.reverie.paint.core.AbrPresetConverter.abrParamsJson] 的逆解码：
 * 把 source.json `abrParams` 键里的 JSONObject 还原成 [AbrParser.AbrPresetInfo]，
 * 供 [OfficialBp] 第三级 fallback 重新走一遍 ABR 导入换算。
 *
 * 逐键显式 `opt*` 读取（不依赖反射——release 包会混淆字段名），每个键的缺省值
 * 与 [AbrParser.AbrPresetInfo] 构造默认值严格一致：
 * name=""、tipUuid=null、tipIndex=-1、diameter=30.0、spacing=0.25、angle=0.0、
 * roundness=1.0、hardness=1.0、scatter=0.0、各 pressure 系与 tilt 系 Boolean=false、
 * 各 jitter/min*=0.0、followDirection/flipX/flipY/isComputed/airbrush=false、
 * compositeOp="normal"、dualBrush/texture/colorDynamics=null。
 *
 * tipUuid 的空值还原：abrParamsJson 以 `JSONObject.NULL` 写出缺失的 tipUuid，
 * org.json 的 `optString` 会把 NULL 哨兵读成字面量 "null"，因此这里同时把
 * "" 与 "null" 还原为 null（tipUuid 实际取值为 UUID 串，不存在歧义）。
 *
 * abrParamsJson 未持久化、还原时取构造默认值的字段清单：**无** —— 对照
 * AbrPresetConverter.kt 174-208 行与 AbrPresetInfo 全部 32 个字段，逐一写
 * 出，没有遗漏字段。
 */
object AbrParamsDecoder {

    fun decode(o: JSONObject): AbrParser.AbrPresetInfo {
        return AbrParser.AbrPresetInfo(
            name = o.optString("name", ""),
            tipUuid = optNullableString(o, "tipUuid"),
            tipIndex = o.optInt("tipIndex", -1),
            diameter = o.optDouble("diameter", 30.0),
            spacing = o.optDouble("spacing", 0.25),
            angle = o.optDouble("angle", 0.0),
            roundness = o.optDouble("roundness", 1.0),
            hardness = o.optDouble("hardness", 1.0),
            scatter = o.optDouble("scatter", 0.0),
            pressureSize = o.optBoolean("pressureSize", false),
            pressureOpacity = o.optBoolean("pressureOpacity", false),
            pressureFlow = o.optBoolean("pressureFlow", false),
            tiltSize = o.optBoolean("tiltSize", false),
            tiltOpacity = o.optBoolean("tiltOpacity", false),
            tiltFlow = o.optBoolean("tiltFlow", false),
            tiltAngle = o.optBoolean("tiltAngle", false),
            sizeJitter = o.optDouble("sizeJitter", 0.0),
            angleJitter = o.optDouble("angleJitter", 0.0),
            roundnessJitter = o.optDouble("roundnessJitter", 0.0),
            opacityJitter = o.optDouble("opacityJitter", 0.0),
            flowJitter = o.optDouble("flowJitter", 0.0),
            minDiameterRatio = o.optDouble("minDiameterRatio", 0.0),
            minRoundness = o.optDouble("minRoundness", 0.0),
            followDirection = o.optBoolean("followDirection", false),
            flipX = o.optBoolean("flipX", false),
            flipY = o.optBoolean("flipY", false),
            isComputed = o.optBoolean("isComputed", false),
            airbrush = o.optBoolean("airbrush", false),
            compositeOp = o.optString("compositeOp", "normal"),
            dualBrush = o.optJSONObject("dualBrush")?.let { d ->
                AbrParser.AbrDualBrushInfo(
                    tipUuid = optNullableString(d, "tipUuid"),
                    diameter = d.optDouble("diameter", 30.0),
                    spacing = d.optDouble("spacing", 0.25),
                    scatter = d.optDouble("scatter", 0.0),
                    compositeOp = d.optString("compositeOp", "multiply"),
                    flipX = d.optBoolean("flipX", false),
                    flipY = d.optBoolean("flipY", false),
                )
            },
            texture = o.optJSONObject("texture")?.let { t ->
                AbrParser.AbrTextureInfo(
                    patternName = t.optString("patternName", ""),
                    patternUuid = t.optString("patternUuid", ""),
                    scale = t.optDouble("scale", 1.0),
                    depth = t.optDouble("depth", 0.5),
                    mode = t.optString("mode", "multiply"),
                    invert = t.optBoolean("invert", false),
                )
            },
            colorDynamics = o.optJSONObject("colorDynamics")?.let { c ->
                AbrParser.AbrColorDynamicsInfo(
                    hueJitter = c.optDouble("hueJitter", 0.0),
                    satJitter = c.optDouble("satJitter", 0.0),
                    valJitter = c.optDouble("valJitter", 0.0),
                    secondaryMix = c.optDouble("secondaryMix", 0.0),
                )
            },
        )
    }

    /** optString 对 JSONObject.NULL 返回字面量 "null"，这里统一还原为 Kotlin null */
    private fun optNullableString(o: JSONObject, key: String): String? {
        val s = o.optString(key, "")
        return if (s.isEmpty() || s == "null") null else s
    }
}
