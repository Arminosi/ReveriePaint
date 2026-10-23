/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲就绪门控 (L-02)。进画布后的静默窗 ([FrontBufferQuiet], 3.2s) 是按 Tab S9 实测取的固定上沿;
 * 门控在**每一笔落笔时评估一次**, 条件全部满足就提前放行, 否则仍等到静默窗结束 (兜底, 行为与原来一致)。
 * 判定结果在落笔时锁定, 一笔之内不会中途切换路径 (未就绪时落下的笔整笔走主画布正常路径)。
 *
 * 条件 (位掩码, 写进 FbGeomLog 便于真机对照):
 *  - [COND_TILES]       显示瓦片网格已发布且等于最终渲染尺寸, 没有待重建标记;
 *  - [COND_RENDER_IDLE] 渲染线程没有排队/重试中的渲染 (**推断**: UI 线程看不到引擎内部队列,
 *                       用 renderScheduled 与"脏区未清完的 4ms 重试"两个标志近似);
 *  - [COND_GEOM_STABLE] 前缓冲 SurfaceView 的屏幕位置/尺寸、画布 View 尺寸与画布 pose 连续 2 帧不变;
 *  - [COND_REFRESH]     屏幕当前刷新率已到目标 (面板最高档 - 容差) (**推断**: 只读 Display.refreshRate);
 *  - [COND_NOT_LOADING] 加载遮罩已关闭。
 *
 * 纯 JVM、零分配, 可单测。
 */
internal object FrontBufferReadyGate {
    const val COND_TILES = 1
    const val COND_RENDER_IDLE = 1 shl 1
    const val COND_GEOM_STABLE = 1 shl 2
    const val COND_REFRESH = 1 shl 3
    const val COND_NOT_LOADING = 1 shl 4
    const val ALL = COND_TILES or COND_RENDER_IDLE or COND_GEOM_STABLE or COND_REFRESH or COND_NOT_LOADING

    /** 由间接信号推断 (非直接观测) 的条件位, 日志里单独标出。 */
    const val INFERRED = COND_RENDER_IDLE or COND_REFRESH

    /** 几何需要连续不变的帧数。 */
    const val STABLE_FRAMES_REQUIRED = 2

    /** 刷新率容差 (Hz): 120Hz 面板上报 119.88 之类的值仍算到位。 */
    const val REFRESH_TOLERANCE_HZ = 5f

    /** 判定结果。 */
    const val NOT_READY = 0

    /** 条件全部满足, 静默窗内提前放行。 */
    const val READY_EVENTS = 1

    /** 静默窗已结束 (兜底放行, 与原行为一致)。 */
    const val READY_FALLBACK = 2

    fun conditionMask(
        tilesFinal: Boolean,
        renderIdle: Boolean,
        stableFrames: Int,
        refreshRateHz: Float,
        targetRefreshHz: Float,
        loading: Boolean,
    ): Int {
        var m = 0
        if (tilesFinal) m = m or COND_TILES
        if (renderIdle) m = m or COND_RENDER_IDLE
        if (stableFrames >= STABLE_FRAMES_REQUIRED) m = m or COND_GEOM_STABLE
        if (refreshAtTarget(refreshRateHz, targetRefreshHz)) m = m or COND_REFRESH
        if (!loading) m = m or COND_NOT_LOADING
        return m
    }

    /** 拿不到目标值 (<= 0 或非有限) 时不以刷新率阻拦。 */
    fun refreshAtTarget(current: Float, target: Float): Boolean {
        if (!(target > 0f) || !target.isFinite()) return true
        return current.isFinite() && current >= target - REFRESH_TOLERANCE_HZ
    }

    /** 显示网格是否已是最终尺寸。 */
    fun tilesFinal(gridW: Int, gridH: Int, renderW: Int, renderH: Int, bufferInvalid: Boolean): Boolean =
        !bufferInvalid && renderW > 0 && renderH > 0 && gridW == renderW && gridH == renderH

    /**
     * 落笔时的判定。[gateEnabled] = false 时完全退回原静默窗 (门控只会让就绪**提前**, 不会推迟兜底)。
     */
    fun decide(nowMs: Long, quietUntilMs: Long, mask: Int, gateEnabled: Boolean): Int = when {
        nowMs >= quietUntilMs -> READY_FALLBACK
        gateEnabled && (mask and ALL) == ALL -> READY_EVENTS
        else -> NOT_READY
    }

    fun isReady(decision: Int): Boolean = decision != NOT_READY
}

/**
 * 连续"几何签名不变"的帧计数 (Choreographer 每帧喂一次)。签名不同就从 0 重新计, 首帧也是 0。
 * 只存 8 个 int, 零分配。
 */
internal class StableFrameCounter {
    private val last = IntArray(SIG_SIZE)
    private var has = false

    /** 当前已连续不变的帧数。 */
    var stableFrames = 0
        private set

    fun reset() {
        has = false
        stableFrames = 0
    }

    /** 喂一帧签名 (至多 [SIG_SIZE] 个值, 不足补 0), 返回 [stableFrames]。 */
    fun feed(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int = 0, h: Int = 0): Int {
        val same = has && last[0] == a && last[1] == b && last[2] == c && last[3] == d &&
            last[4] == e && last[5] == f && last[6] == g && last[7] == h
        last[0] = a
        last[1] = b
        last[2] = c
        last[3] = d
        last[4] = e
        last[5] = f
        last[6] = g
        last[7] = h
        has = true
        stableFrames = if (same) stableFrames + 1 else 0
        return stableFrames
    }

    companion object {
        const val SIG_SIZE = 8
    }
}
