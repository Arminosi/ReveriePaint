/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.ReverieCoreBridge
import kotlin.math.abs

/**
 * 引擎侧唯一施加路径（执行文档 W-04）。
 *
 * v2 下 kpp 语义全部来自 `paintoppresets/<name>.kpp`（有效字节），这里在 `loadBrushPreset` 之后
 * 只补"非 preset 状态"：颜色、副色、橡皮擦标志、橡皮擦的 compositeOp 覆盖，以及 LIVE 控件
 * （工具级尺寸记忆、`ReverieRotation` 这类引擎不读的附加旋转）。**不调用任何 REBUILD 类 setter。**
 *
 * 引擎调用经 [EngineFacade] 收口，单测用假实现断言调用集合。
 */
object BrushEngineApplier {

    /** 允许的引擎入口；故意不含任何 REBUILD 类 setter（纹理、动态、抖动、涂抹、喷枪…） */
    interface EngineFacade {
        fun loadBrushPreset(index: Int): Boolean
        fun setPresetIsEraser(isEraser: Boolean)
        fun setBrushCompositeOp(op: String)
        fun setBrushColor(color: String)
        fun setBrushSecondaryColor(color: String)
        fun setBrushSize(v: Double)
        fun setBrushOpacity(v: Double)
        fun setBrushFlow(v: Double)
        fun setBrushAngle(deg: Double)
    }

    object NativeEngine : EngineFacade {
        override fun loadBrushPreset(index: Int) = ReverieCoreBridge.loadBrushPreset(index)
        override fun setPresetIsEraser(isEraser: Boolean) = ReverieCoreBridge.setPresetIsEraser(isEraser)
        override fun setBrushCompositeOp(op: String) = ReverieCoreBridge.setBrushCompositeOp(op)
        override fun setBrushColor(color: String) = ReverieCoreBridge.setBrushColor(color)
        override fun setBrushSecondaryColor(color: String) = ReverieCoreBridge.setBrushSecondaryColor(color)
        override fun setBrushSize(v: Double) = ReverieCoreBridge.setBrushSize(v)
        override fun setBrushOpacity(v: Double) = ReverieCoreBridge.setBrushOpacity(v)
        override fun setBrushFlow(v: Double) = ReverieCoreBridge.setBrushFlow(v)
        override fun setBrushAngle(deg: Double) = ReverieCoreBridge.setBrushAngle(deg)
    }

    /** loadBrushPreset 之后要补的状态；null 表示不动（保持 kpp 自带值） */
    data class NonPresetState(
        val isEraser: Boolean,
        val color: String,
        val secondaryColor: String,
        val compositeOverride: String? = null,
        val size: Double? = null,
        val opacity: Double? = null,
        val flow: Double? = null,
        val angleDeg: Double? = null,
    )

    fun selectAndApply(engine: EngineFacade, index: Int, st: NonPresetState, id: String? = null): Boolean {
        val t0 = System.nanoTime()
        if (!engine.loadBrushPreset(index)) {
            BrushDiagnostics.log("apply_load_failed", "index" to index, "id" to id)
            return false
        }
        applyAfterLoad(engine, st)
        BrushDiagnostics.log("apply", "index" to index, "id" to id, "ms" to (System.nanoTime() - t0) / 1_000_000)
        return true
    }

    fun applyAfterLoad(engine: EngineFacade, st: NonPresetState) {
        engine.setPresetIsEraser(st.isEraser)
        st.compositeOverride?.let { engine.setBrushCompositeOp(it) }
        engine.setBrushColor(st.color)
        engine.setBrushSecondaryColor(st.secondaryColor)
        st.size?.takeIf { it.isFinite() && it > 0.0 }?.let { engine.setBrushSize(it) }
        st.opacity?.takeIf { it.isFinite() }?.let { engine.setBrushOpacity(it.coerceIn(0.0, 1.0)) }
        st.flow?.takeIf { it.isFinite() }?.let { engine.setBrushFlow(it.coerceIn(0.0, 1.0)) }
        st.angleDeg?.takeIf { it.isFinite() }?.let { engine.setBrushAngle(it) }
    }

    /**
     * 附加旋转（ReverieRotation）引擎不读，只能经 setBrushAngle 施加：rotation 为 0 时返回 null（保持 kpp 角度）。
     */
    fun liveAngle(eff: BrushParams): Double? {
        if (abs(eff.rotation) < 1e-9) return null
        val a = (eff.angle + eff.rotation) % 360.0
        return if (a < 0.0) a + 360.0 else a
    }

    /**
     * 把一次 VM 编辑合并进覆盖：
     * 1. 与"当前有效字节的回读"比较，找出这次真正改动的控件（未触碰的控件不产生覆盖）；
     * 2. 合并进已有覆盖；
     * 3. 改回快照值的控件从覆盖里删掉（覆盖保持最小）。
     * APP_ONLY 控件不进覆盖（仍由旧 brushParams 保存）。
     */
    fun mergeEdit(
        snapshotXml: String?,
        effectiveXml: String?,
        current: BrushOverrideStore.Overrides,
        edited: BrushParams,
        appBase: BrushParams = BrushParams(),
    ): BrushOverrideStore.Overrides {
        val ctx = effectiveXml?.let { PresetContext.of(it) } ?: PresetContext.MYPAINT
        val effP = PresetReadback.fromXml(effectiveXml, appBase)
        val snapP = PresetReadback.fromXml(snapshotXml, appBase)
        val changed = PresetPropertyMap.diff(effP, edited, ctx).filter { (id, v) ->
            if (id.klass == ControlClass.APP_ONLY) return@filter false
            if (id.curveOption != null) {
                // VM 会为预设里不存在的标准通道补一条"未启用"的线性曲线，这不是编辑
                val before = PresetPropertyMap.spec(id).get(effP) as String
                val c = CurveValue.parse(v as String)
                if (before.isBlank() && c != null && !c.enabled) return@filter false
            }
            true
        }
        if (changed.isEmpty()) return current
        val controls = LinkedHashMap(current.controls)
        val dirty = LinkedHashSet(current.dirty)
        for ((id, v) in changed) {
            if (PresetPropertyMap.same(id, PresetPropertyMap.spec(id).get(snapP), v)) {
                controls.remove(id.key)
                dirty.remove(id.key)
            } else {
                controls[id.key] = BrushOverrideStore.normalize(v)
                dirty += id.key
            }
        }
        BrushDiagnostics.log("override_merge", "changed" to changed.keys.joinToString(",") { it.key }, "total" to controls.size)
        return current.copy(controls = controls, dirty = dirty)
    }
}
