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

    /** 预设元数据：重置时保留（导入时的作者/说明属于预设本身，不是用户调参） */
    private val METADATA = setOf(ControlId.AUTHOR, ControlId.AUTHOR_LOCKED, ControlId.DESCRIPTION, ControlId.VERSION)

    /** 旧记录里是否有偏离默认值的 APP_ONLY 调参（不含元数据）——v2 下"已修改"的另一半依据 */
    fun appOnlyModified(legacy: BrushParams?, base: BrushParams = BrushParams()): Boolean {
        if (legacy == null) return false
        val d = base
        return ControlId.entries.any { id ->
            id.klass == ControlClass.APP_ONLY && id !in METADATA &&
                PresetPropertyMap.spec(id).let { !PresetPropertyMap.same(id, it.get(legacy), it.get(d)) }
        }
    }

    /**
     * 重置后的旧记录：[base]（快照自带的 APP_ONLY 基线，见 [baseline]）+ 元数据；
     * 两者都是默认值时返回 null（删除记录）
     */
    fun metadataOnly(legacy: BrushParams?, base: BrushParams = BrushParams()): BrushParams? {
        val d = BrushParams()
        var any = base != d
        if (legacy == null) return if (any) base else null
        var p = base
        for (id in METADATA) {
            val sp = PresetPropertyMap.spec(id)
            val v = sp.get(legacy)
            if (!PresetPropertyMap.same(id, v, sp.get(d))) {
                p = sp.set(p, v)
                any = true
            }
        }
        return if (any) p else null
    }

    /** 快照 source.json 里的 APP_ONLY 基线（W-09：ABR 无压感动态时 pressureEnabled=false 等），按 id 缓存（快照不可变） */
    private val baselineCache = java.util.concurrent.ConcurrentHashMap<String, BrushParams>()

    /**
     * 该预设"出厂/导入时"的 APP_ONLY 值：重置回到它，偏离它才算已修改。
     * 只认标量 APP_ONLY 非元数据控件；没有 appBaseline 时就是 [BrushParams] 默认值。
     */
    fun baseline(id: String): BrushParams = baselineCache.getOrPut(id) {
        baselineFromJson(PresetSnapshotStore.source(id)?.optJSONObject("appBaseline"))
    }

    fun baselineFromJson(j: org.json.JSONObject?): BrushParams {
        var p = BrushParams()
        if (j == null) return p
        for (k in j.keys()) {
            val id = ControlId.entries.firstOrNull { it.key == k && it.klass == ControlClass.APP_ONLY && it !in METADATA } ?: continue
            p = runCatching { PresetPropertyMap.spec(id).let { sp -> sp.set(p, j.get(k)) } }.getOrDefault(p)
        }
        return p
    }

    /** [p] 中偏离默认值的标量 APP_ONLY 非元数据控件，写进 source.json 的 appBaseline */
    fun baselineJson(p: BrushParams): org.json.JSONObject {
        val d = BrushParams()
        val o = org.json.JSONObject()
        for (id in ControlId.entries) {
            if (id.klass != ControlClass.APP_ONLY || id in METADATA) continue
            val sp = PresetPropertyMap.spec(id)
            val v = sp.get(p)
            if ((v is Boolean || v is Number || v is String) && !PresetPropertyMap.same(id, v, sp.get(d))) o.put(id.key, v)
        }
        return o
    }

    fun clearBaselineCache(id: String? = null) {
        if (id == null) baselineCache.clear() else baselineCache.remove(id)
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
