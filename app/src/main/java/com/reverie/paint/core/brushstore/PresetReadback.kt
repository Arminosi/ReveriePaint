/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper

/**
 * 有效字节 → 工作台 UI 值（执行文档 W-05）。VM 的 brush* 字段在 v2 下只从这里赋值，
 * 因而 UI 显示的就是引擎刚加载的那份 kpp。
 *
 * APP_ONLY 控件（streamline / taper / 作者…）不在 kpp 里，取自 [appBase]（旧 brushParams 记录或默认值）。
 */
object PresetReadback {

    /** 只保留 [legacy] 中 APP_ONLY 控件的值，其余字段为 [BrushParams] 默认值 */
    fun appBase(legacy: BrushParams?): BrushParams {
        var p = BrushParams()
        if (legacy == null) return p
        for (id in ControlId.entries) {
            if (id.klass != ControlClass.APP_ONLY) continue
            val sp = PresetPropertyMap.spec(id)
            p = sp.set(p, sp.get(legacy))
        }
        return p
    }

    fun fromXml(xml: String?, appBase: BrushParams = BrushParams()): BrushParams =
        PresetPropertyMap.readParams(xml, appBase).copy(
            isCustomized = true,
            dynamicsCustomized = true,
            smudgeCustomized = true,
            spacingCustomized = true,
        )

    fun fromEffectiveBytes(bytes: ByteArray, ext: String = "kpp", appBase: BrushParams = BrushParams()): BrushParams {
        val xml = if (ext.equals("kpp", ignoreCase = true)) KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) else null
        return fromXml(xml, appBase)
    }

    /** 每个适用控件的回读值（与覆盖逐项比对用） */
    fun controlValues(bytes: ByteArray): Map<ControlId, Any> {
        val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) ?: return emptyMap()
        return PresetPropertyMap.readValues(xml)
    }
}
