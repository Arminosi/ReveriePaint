/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.PerfTrace
import com.reverie.paint.model.FrontBufferHolePolicy
import com.reverie.paint.model.HoleMaskGrid

/**
 * 前缓冲低延迟路径的功能开关。进程内只读一次系统属性, 改完需重启 App (`adb shell am force-stop`):
 *
 * ```
 * adb shell setprop debug.reverie.fb_ready_gate 0      # 关闭 L-02 就绪门控 (退回 3.2s 静默窗)
 * adb shell setprop debug.reverie.fb_holes 0           # 关闭孔洞 (前缓冲盖住画布装饰, 旧行为)
 * adb shell setprop debug.reverie.fb_hole_mode hmin    # compose (默认: 覆盖层画进前缓冲) | hmin: 挖孔+窗口重画 (A/B) | e: 命中孔洞即整笔交还主画布
 * adb shell setprop debug.reverie.fb_hole_margin 2     # 掩码膨胀像素
 * adb shell setprop debug.reverie.fb_hole_max_cov 35   # 掩码占屏 > 35% 时这一笔不按住
 * adb shell setprop debug.reverie.fb_snap_hold 1       # 1a: 吸附线笔画也按住 (默认 0 = 走常规路径, 严格同图)
 * adb shell setprop debug.reverie.fb_strict 1          # 严格同一性: 关预测笔尖 (默认 1)
 * adb shell setprop debug.reverie.fb_clear_delay 0     # 抬笔清前缓冲再多等的帧数
 * ```
 */
internal object FrontBufferFeatureFlags {
    /** L-02: 事件驱动的就绪门控 (每笔落笔评估一次), 关闭时退回原 3.2s 静默窗。 */
    val readyGate: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_ready_gate", 1) != 0 }

    /** H-1/H-2: 孔洞模式 ([FrontBufferHolePolicy.MODE_OFF] / HMIN / E / COMPOSE)。 */
    val holeMode: Int by lazy {
        FrontBufferHolePolicy.parseMode(
            PerfTrace.debugPropInt("debug.reverie.fb_holes", 1),
            PerfTrace.debugPropString("debug.reverie.fb_hole_mode", "compose"),
        )
    }

    val holeMargin: Int by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_hole_margin", HoleMaskGrid.DEFAULT_MARGIN).coerceIn(0, 16)
    }

    /** 掩码占屏比例上限 (0..1)。 */
    val holeMaxCoverage: Float by lazy {
        PerfTrace.debugPropInt("debug.reverie.fb_hole_max_cov", 35).coerceIn(0, 100) / 100f
    }

    /** H-3: 严格同一性 —— 预测笔尖 (窗口里没有的像素) 不画。 */
    val strict: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_strict", 1) != 0 }

    /** H-4: 抬笔后窗口帧提交再多等的帧数。 */
    val clearDelayFrames: Int by lazy { PerfTrace.debugPropInt("debug.reverie.fb_clear_delay", 0).coerceIn(0, 8) }

    /**
     * 1a: 吸附导引线笔画按住 (前缓冲孔外画导引线)。默认关: 两个表面无法原子同步, 抬笔/E 交还/笔画中
     * 导引线跨孔边界可能有 1 帧错位, 且前缓冲经 SurfaceFlinger 叠加的半透明导引线与窗口直接混合有
     * ±1 舍入差, 不能保证逐像素同图。
     */
    val snapHold: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_snap_hold", 0) != 0 }
}
