/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * H-min 孔洞的纯决策 (无 Android 依赖, 可单测)。
 *
 * 模式 ([MODE_*], 由 `debug.reverie.fb_holes` / `debug.reverie.fb_hole_mode` 决定):
 *  - [MODE_OFF]: 不建孔洞 (相当于 L-01 撤掉后的 A0: 覆盖层在按住期间被墨盖住, 仅供对照);
 *  - [MODE_HMIN]: 前缓冲在孔洞内清 0, 命中孔洞的帧让窗口重画 (默认);
 *  - [MODE_E]: 命中孔洞即把这一笔交还常规路径 (零额外工作, A/B 对照)。
 */
object FrontBufferHolePolicy {
    const val MODE_OFF = 0
    const val MODE_HMIN = 1
    const val MODE_E = 2

    /** 落笔判定结果。 */
    const val ARM_NO_HOLES = 0 // 当前没有任何覆盖层: 零代价
    const val ARM_WITH_MASK = 1 // 有孔洞, 掩码就绪
    const val ARM_NORMAL = 2 // 这一笔不按住, 走常规路径 (掩码未就绪/过期/覆盖过大/其他工具覆盖层)

    /** 每包判定结果。 */
    const val PACKET_PLAIN = 0 // 未命中: 与无孔洞时完全相同, 零额外工作
    const val PACKET_HOLE_REDRAW = 1 // H-min 命中: 照常贴墨, 孔洞清 0, 窗口重画命中区
    const val PACKET_HANDBACK = 2 // E 命中: 不贴这包, 释放按住, 窗口接管

    fun parseMode(holesProp: Int, modeProp: String?): Int {
        if (holesProp == 0) return MODE_OFF
        return when (modeProp?.trim()?.lowercase()) {
            "e" -> MODE_E
            "off" -> MODE_OFF
            else -> MODE_HMIN
        }
    }

    /**
     * 落笔时决定孔洞策略。
     *
     * @param decorVisible 当前是否有任何画在画布之上的覆盖层 (辅助线/网格/蚂蚁线/吸附线…)
     * @param maskReady 已发布的掩码与当前覆盖层状态 (含视图位姿) 一致
     * @param coverage 掩码占屏比例
     * @param maxCoverage 超过即不按住 (例如 4x 以上像素网格铺满屏幕)
     * @param otherOverlayActive 其他工具自绘覆盖层 (形状/文字/变换手柄…) 正在显示, 掩码不覆盖它们
     */
    fun decideArm(
        mode: Int,
        decorVisible: Boolean,
        maskReady: Boolean,
        coverage: Float,
        maxCoverage: Float,
        otherOverlayActive: Boolean,
    ): Int {
        if (mode == MODE_OFF) return ARM_NO_HOLES
        if (otherOverlayActive) return ARM_NORMAL
        if (!decorVisible) return ARM_NO_HOLES
        if (!maskReady) return ARM_NORMAL
        if (!(coverage <= maxCoverage)) return ARM_NORMAL
        return ARM_WITH_MASK
    }

    fun decidePacket(mode: Int, hit: Boolean): Int = when {
        !hit || mode == MODE_OFF -> PACKET_PLAIN
        mode == MODE_E -> PACKET_HANDBACK
        else -> PACKET_HOLE_REDRAW
    }

    /**
     * 抬笔清前缓冲 (H-4): 窗口帧提交 (FrameCommitCallback) 之后再等 [extraFrames] 帧。
     * 返回 true 表示这一次提交回调就可以清。
     */
    fun clearAfterCommit(commitsSinceCatchUp: Int, extraFrames: Int): Boolean =
        commitsSinceCatchUp > extraFrames.coerceAtLeast(0)
}
