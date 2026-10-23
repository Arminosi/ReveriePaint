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

    /**
     * 辅助绘图是否可能画动态吸附线 (透视 2D / 等距 / 二维网格)。吸附线画在墨之上 (窗口第 2.5 段),
     * 默认走常规路径 ([snapForcesNormal]); `debug.reverie.fb_snap_hold=1` 时按住, 由前缓冲在孔洞外画、
     * 窗口在孔洞内补 (不挖孔), 为能擦掉旧位置, 这类笔画从首帧起维护备份层。
     */
    fun snapLinesPossible(assistedDrawing: Boolean, perspective2d: Boolean, isoOrGrid: Boolean): Boolean =
        assistedDrawing && (perspective2d || isoOrGrid)

    /**
     * 1a 开关 (`debug.reverie.fb_snap_hold`, 默认关): 关时可能出现吸附线的笔画一律走常规路径
     * (严格同一性); 开时按住, 前缓冲孔外画导引线 (A/B 用, 有两表面不同步的残留风险)。
     */
    fun snapForcesNormal(snapPossible: Boolean, snapHoldEnabled: Boolean): Boolean =
        snapPossible && !snapHoldEnabled

    /** 只有真按住画导引线 (开关开) 时, 才为导引线从首帧维护备份层。 */
    fun snapNeedsExtras(snapPossible: Boolean, snapHoldEnabled: Boolean): Boolean =
        snapPossible && snapHoldEnabled

    /**
     * 窗口画导引线的方式: 按住中, 或松开后前缓冲上的导引线还没被擦掉 (E 交还 / 抬笔追赶期间),
     * 窗口只画孔洞内与避让区内那一半 —— 同一像素永远只有一个表面在画导引线, 不会叠出双倍 alpha。
     * 前缓冲擦掉之后 ([guideClearedNeedsRedraw]) 窗口再画完整导引线。开关关时前缓冲从不画导引线,
     * 窗口永远画完整导引线 (与 1a 之前逐字节相同)。
     */
    fun windowGuideHolesOnly(snapHoldEnabled: Boolean, held: Boolean, frontGuideOnScreen: Boolean): Boolean =
        snapHoldEnabled && (held || frontGuideOnScreen)

    /** 前缓冲包里是否带导引线: 只有开关打开时。 */
    fun frontDrawsGuide(snapHoldEnabled: Boolean): Boolean = snapHoldEnabled

    /** 前缓冲导引线由"在屏"变为"已擦"且已不按住: 窗口要补画一帧完整导引线。 */
    fun guideClearedNeedsRedraw(wasOnScreen: Boolean, nowOnScreen: Boolean, held: Boolean): Boolean =
        wasOnScreen && !nowOnScreen && !held

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

    /**
     * 掩码过期时是否跳过防抖立即重建: 视图已静止 ([settled], 无视图动画) 且距上一次发现过期
     * 已隔 [quietMs] 以上 —— 即 "静止后的第一次变化" (手势抬起那一帧)。连续变化中的帧仍防抖。
     */
    fun buildImmediately(settled: Boolean, nowMs: Long, lastStaleMs: Long, quietMs: Long): Boolean =
        settled && nowMs - lastStaleMs >= quietMs
}
