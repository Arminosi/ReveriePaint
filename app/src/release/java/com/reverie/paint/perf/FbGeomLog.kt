/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.graphics.Bitmap
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.HoleMaskGrid

/**
 * 前缓冲真墨几何日志在**正式版里不存在**: 与 debug 版同签名的空实现
 * (理由同 [PerfHud]: release 未开 R8, 常量分支不会被消除)。
 */
@Suppress("UNUSED_PARAMETER")
internal object FbGeomLog {
    fun strokeBegin(strokeSeq: Long, held: Boolean, viewW: Int, viewH: Int, pose: CanvasPose?) {
    }

    fun strokeReady(
        strokeSeq: Long,
        held: Boolean,
        decision: Int,
        mask: Int,
        inferredMask: Int,
        gateEnabled: Boolean,
        msSinceQuietStart: Long,
        msUntilFallback: Long,
        surfaceX: Int,
        surfaceY: Int,
        surfaceW: Int,
        surfaceH: Int,
        viewW: Int,
        viewH: Int,
        stableFrames: Int,
        refreshHz: Float,
        targetRefreshHz: Float,
        renderW: Int,
        renderH: Int,
        gridW: Int,
        gridH: Int,
        tileGeneration: Int,
        bufferInvalid: Boolean,
        zoom: Float,
        fitScale: Float,
        panX: Float,
        panY: Float,
        rotation: Float,
        renderScheduled: Boolean,
        renderFollowUp: Boolean,
        renderInFlight: Boolean,
        loading: Boolean,
    ) {
    }

    fun presentFrame(
        pendingCount: Int,
        bakedCount: Int,
        originL: Int,
        originT: Int,
        reqW: Int,
        reqH: Int,
        slotW: Int,
        slotH: Int,
        screenRects: IntArray,
        bmpRects: IntArray,
        canvasBounds: IntArray,
        clippedCount: Int,
    ) {
    }

    const val identityCheckEnabled: Boolean = false
    const val timingEnabled: Boolean = false

    fun holeMaskBuilt(gen: Int, w: Int, h: Int, holePixels: Long, coverage: Float, buildNs: Long) {
    }

    fun holeArm(strokeSeq: Long, mode: Int, arm: Int, coverage: Float) {
    }

    fun holePacket(strokeSeq: Long, rects: Int, hitRects: Int, hitArea: Long, testNs: Long, decision: Int) {
    }

    fun holeRedrawRequested() {
    }

    fun holeWindowDraw(ns: Long) {
    }

    fun frontPresented(ns: Long) {
    }

    fun holeStrokeEnd(strokeSeq: Long) {
    }

    fun identityCheck(layer: Bitmap, grid: HoleMaskGrid, rects: IntArray, count: Int) {
    }

    fun composePacket(strokeSeq: Long, footRects: Int, bakePx: Long, slotPx: Long, bakeNs: Long) {
    }

    fun composeIdentityCheck(slot: Bitmap, packXY: IntArray, rects: IntArray, count: Int, docInner: IntArray) {
    }
}
