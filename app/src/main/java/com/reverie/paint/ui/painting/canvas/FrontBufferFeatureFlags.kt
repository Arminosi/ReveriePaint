/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.PerfTrace

/**
 * 前缓冲低延迟路径的功能开关 (默认全开)。进程内只读一次系统属性, 改完需重启 App:
 *
 * ```
 * adb shell setprop debug.reverie.fb_ready_gate 0   # 关闭 L-02 就绪门控 (退回 3.2s 静默窗)
 * ```
 */
internal object FrontBufferFeatureFlags {
    /** L-02: 事件驱动的就绪门控 (每笔落笔评估一次), 关闭时退回原 3.2s 静默窗。 */
    val readyGate: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_ready_gate", 1) != 0 }
}
