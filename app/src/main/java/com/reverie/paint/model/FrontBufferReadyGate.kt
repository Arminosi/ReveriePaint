/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲就绪门控。
 * 瓦片已是最终版 (tilesFinal, 不是加载中的低清), 不在加载, 且已度过新建/加载画布后的静默窗口。
 */
internal object FrontBufferReadyGate {
    const val COND_TILES = 1
    const val COND_NOT_LOADING = 1 shl 4
    const val ALL = COND_TILES or COND_NOT_LOADING
    const val INFERRED = 0

    /** 判定结果。 */
    const val NOT_READY = 0
    const val READY = 1
    const val READY_EVENTS = 1
    const val READY_FALLBACK = 2

    fun conditionMask(tilesFinal: Boolean, loading: Boolean): Int {
        var m = 0
        if (tilesFinal) m = m or COND_TILES
        if (!loading) m = m or COND_NOT_LOADING
        return m
    }

    /** 显示网格是否已是最终尺寸。 */
    fun tilesFinal(gridW: Int, gridH: Int, renderW: Int, renderH: Int, bufferInvalid: Boolean): Boolean =
        !bufferInvalid && renderW > 0 && renderH > 0 && gridW == renderW && gridH == renderH

    /**
     * 落笔时的判定。[gateEnabled] = false 时完全不检查直接 READY (=0 调试开关)。
     * 静默窗口未结束时返回 NOT_READY。
     */
    fun decide(nowMs: Long, quietUntilMs: Long, tilesFinal: Boolean, loading: Boolean, gateEnabled: Boolean): Int {
        if (!gateEnabled) return READY
        if (nowMs < quietUntilMs) return NOT_READY
        return if (tilesFinal && !loading) READY else NOT_READY
    }

    fun isReady(decision: Int): Boolean = decision != NOT_READY
}
