/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.edit
import com.reverie.paint.core.stylus.StylusAction
import com.reverie.paint.model.SpenAirActions
import com.reverie.paint.model.SpenAirGesture

/**
 * 处理三星 S Pen 隔空手势按键事件。
 * 1. event.action != KeyEvent.ACTION_DOWN 或 event.repeatCount > 0 → false
 * 2. SpenAirGesture.resolve(...) 为 null → false (非隔空键，放行走常规链路)
 * 3. strokeActive → true (落笔中吞掉事件防状态污染，不执行动作)
 * 4. 查绑定为 "none" → true (吞掉但不执行)
 * 5. executeStylusAction(...) → true
 */
fun PaintViewModel.handleSpenAirKeyEvent(event: KeyEvent, strokeActive: Boolean): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
    val gesture = SpenAirGesture.resolve(
        event.keyCode,
        event.isCtrlPressed,
        event.isAltPressed,
        event.isShiftPressed,
    ) ?: return false

    if (strokeActive) return true

    val binding = spenAirBindingFor(gesture)
    if (binding.equals("none", ignoreCase = true)) return true

    executeStylusAction(StylusAction.fromActionId(binding))
    return true
}

/** 查询指定手势的当前动作绑定 id */
fun PaintViewModel.spenAirBindingFor(gesture: SpenAirGesture): String = when (gesture) {
    SpenAirGesture.CLICK -> spenAirClickAction
    SpenAirGesture.DOUBLE_CLICK -> spenAirDoubleClickAction
    SpenAirGesture.SWIPE_LEFT -> spenAirSwipeLeftAction
    SpenAirGesture.SWIPE_RIGHT -> spenAirSwipeRightAction
    SpenAirGesture.SWIPE_UP -> spenAirSwipeUpAction
    SpenAirGesture.SWIPE_DOWN -> spenAirSwipeDownAction
    SpenAirGesture.CIRCLE_CW -> spenAirCircleCwAction
    SpenAirGesture.CIRCLE_CCW -> spenAirCircleCcwAction
}

/** 对应 SharedPreferences 持久化 key (形如 "spenAirClickAction") */
internal fun spenAirPrefsKey(gesture: SpenAirGesture): String = when (gesture) {
    SpenAirGesture.CLICK -> "spenAirClickAction"
    SpenAirGesture.DOUBLE_CLICK -> "spenAirDoubleClickAction"
    SpenAirGesture.SWIPE_LEFT -> "spenAirSwipeLeftAction"
    SpenAirGesture.SWIPE_RIGHT -> "spenAirSwipeRightAction"
    SpenAirGesture.SWIPE_UP -> "spenAirSwipeUpAction"
    SpenAirGesture.SWIPE_DOWN -> "spenAirSwipeDownAction"
    SpenAirGesture.CIRCLE_CW -> "spenAirCircleCwAction"
    SpenAirGesture.CIRCLE_CCW -> "spenAirCircleCcwAction"
}

/** 更新隔空手势动作绑定并持久化到 SharedPreferences (paint_prefs) */
fun PaintViewModel.updateSpenAirBinding(gesture: SpenAirGesture, actionId: String) {
    if (!SpenAirActions.isAllowed(actionId)) return
    when (gesture) {
        SpenAirGesture.CLICK -> spenAirClickAction = actionId
        SpenAirGesture.DOUBLE_CLICK -> spenAirDoubleClickAction = actionId
        SpenAirGesture.SWIPE_LEFT -> spenAirSwipeLeftAction = actionId
        SpenAirGesture.SWIPE_RIGHT -> spenAirSwipeRightAction = actionId
        SpenAirGesture.SWIPE_UP -> spenAirSwipeUpAction = actionId
        SpenAirGesture.SWIPE_DOWN -> spenAirSwipeDownAction = actionId
        SpenAirGesture.CIRCLE_CW -> spenAirCircleCwAction = actionId
        SpenAirGesture.CIRCLE_CCW -> spenAirCircleCcwAction = actionId
    }
    // 与现有 prefs() 扩展惯例一致: 设置 UI 调用时 ViewModel 必然已完成 context 注入
    appContext.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        .edit { putString(spenAirPrefsKey(gesture), actionId) }
}

/** 打开系统 S Pen 隔空操作设置页 (三级回退，FLAG_ACTIVITY_NEW_TASK) */
fun openSpenAirSystemSettings(context: Context) {
    val intents = listOf(
        Intent("com.samsung.android.service.aircommand.AIR_ACTIONS_SETTINGS"),
        Intent("com.samsung.settings.SPEN_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Throwable) {
            // 继续尝试下一个备选 Intent
        }
    }
}

/** 重置隔空手势为默认映射并持久化到 SharedPreferences (paint_prefs) */
fun PaintViewModel.resetSpenAirBindings() {
    SpenAirActions.DEFAULT_BINDINGS.forEach { (gesture, defaultAction) ->
        updateSpenAirBinding(gesture, defaultAction)
    }
}

