/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.LegacyBrushParamsCodec
import org.json.JSONObject

/**
 * 官方 brushParams 记录的 JSON 编解码：与 persistBrushParams/loadBrushParams 同键同愈合
 * （逐字委托 [LegacyBrushParamsCodec]，decode 走默认 Heal = loadBrushParams 常规路径），
 * 供 source.json 的 officialBp 键使用。名字用空串占位（BrushParams 本身不携带名字）。
 */
object OfficialBpJson {
    fun toJson(p: BrushParams): JSONObject = LegacyBrushParamsCodec.encode("", p)

    fun fromJson(o: JSONObject): BrushParams = LegacyBrushParamsCodec.decode(o)
}
