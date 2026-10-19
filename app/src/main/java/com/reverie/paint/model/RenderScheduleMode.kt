/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 渲染与性能调度策略。
 * 控制运笔批处理频率、渲染节流间隔与合成调度，平衡跟手度与设备功耗发热。
 */
enum class RenderScheduleMode(
    val id: Int,
    val minRenderIntervalMs: Long,
    val maxStrokeMergeSamples: Int,
) {
    /**
     * 极速响应：无节流间隔，随屏幕最高刷新率（90Hz/120Hz+）实时绘制，极致超低延迟，但功耗与发热最高。
     */
    PERFORMANCE(id = 0, minRenderIntervalMs = 0L, maxStrokeMergeSamples = 32),

    /**
     * 均衡模式（推荐默认）：智能平滑调度，运笔渲染节流至 ~90fps（~11ms），兼顾出墨流畅感与发热控制。
     */
    BALANCED(id = 1, minRenderIntervalMs = 11L, maxStrokeMergeSamples = 32),

    /**
     * 节能温控：限制渲染刷新率至 ~50-60fps（~18ms），增大批合并，显著降低芯片持续高负荷发热与耗电。
     */
    POWER_SAVER(id = 2, minRenderIntervalMs = 18L, maxStrokeMergeSamples = 48);

    companion object {
        fun fromId(id: Int): RenderScheduleMode {
            return entries.firstOrNull { it.id == id } ?: BALANCED
        }
    }
}
