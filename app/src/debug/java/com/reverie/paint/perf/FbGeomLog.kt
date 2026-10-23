/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.os.SystemClock
import android.util.Log
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.FrontBufferReadyGate

/**
 * 前缓冲真墨的几何日志 (开发/真机定位用, **debug 构建专属**; release 源集是同签名空实现)。
 *
 * 缺墨类问题 (按住运笔时某块没有笔迹、抬笔才恢复) 的成因很多: 槽位图没清、贴图矩形没烤到、
 * 坐标被夹掉、槽位原点错位、设备不保留前缓冲内容…… 需要把"脏区 → 屏幕矩形 → 槽位原点/尺寸"
 * 整条链路打出来再和肉眼现象对齐。只在 [PerfTrace.enabled] 打开时记录, 逐帧日志限频 100ms:
 *
 *     adb shell setprop debug.reverie.perf 1
 *     adb logcat -s FbGeomLog:I FrontBufferProbe:I
 */
internal object FbGeomLog {
    private const val TAG = "FbGeomLog"
    private const val FRAME_MIN_INTERVAL_MS = 100L
    private const val MAX_RECTS_LOGGED = 4

    private var lastFrameMs = 0L
    private var frameSeq = 0L

    /** 每一笔落笔时一行: 设备能力 + 视图尺寸 + 当前 pose。UI 线程调用。 */
    fun strokeBegin(strokeSeq: Long, held: Boolean, viewW: Int, viewH: Int, pose: CanvasPose?) {
        if (!PerfTrace.enabled) return
        lastFrameMs = 0L
        frameSeq = 0L
        val sb = StringBuilder(192)
        sb.append("ev=stroke_begin seq=").append(strokeSeq)
        sb.append(" held=").append(held)
        sb.append(" preserve=").append(FrontBufferProbe.preservedContentState())
        sb.append(" view=").append(viewW).append('x').append(viewH)
        if (pose != null) {
            sb.append(" pose[cx=").append(fmt(pose.centerX))
            sb.append(",cy=").append(fmt(pose.centerY))
            sb.append(",s=").append(fmt(pose.scale))
            sb.append(",rot=").append(fmt(pose.rotation))
            sb.append(",sign=").append(pose.signX.toInt()).append('/').append(pose.signY.toInt())
            sb.append(",bmp=").append(pose.bmpW).append('x').append(pose.bmpH)
            sb.append(']')
        }
        Log.i(TAG, sb.toString())
    }

    /**
     * 落笔时的前缓冲就绪门控快照 (L-02/L-03)。UI 线程, 每笔一行, 紧跟 stroke_begin。
     *
     *     ev=stroke_ready seq=.. held=.. decision=READY_EVENTS|READY_FALLBACK|NOT_READY gate=on
     *       mask=0x1f/0x1f missing=[..] inferred=[idle,hz] t=+812ms fallbackIn=2388ms
     *       sv=0,0 1600x2560 view=1600x2560 stable=5 hz=120.0/120.0
     *       render=4096x3072 grid=4096x3072 gen=3 invalid=false
     *       proj[zoom=..,fit=..,pan=..,..,rot=..] merger[sched=..,followUp=..,inFlight=..] loading=false
     *
     * `t` 是距静默窗起点 (进画布 / 工程加载完成) 的毫秒数。
     */
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
        if (!PerfTrace.enabled) return
        val sb = StringBuilder(384)
        sb.append("ev=stroke_ready seq=").append(strokeSeq)
        sb.append(" held=").append(held)
        sb.append(" decision=").append(
            when (decision) {
                FrontBufferReadyGate.READY_EVENTS -> "READY_EVENTS"
                FrontBufferReadyGate.READY_FALLBACK -> "READY_FALLBACK"
                else -> "NOT_READY"
            },
        )
        sb.append(" gate=").append(if (gateEnabled) "on" else "off")
        sb.append(" mask=0x").append(Integer.toHexString(mask))
            .append("/0x").append(Integer.toHexString(FrontBufferReadyGate.ALL))
        sb.append(" missing=[")
        appendConds(sb, mask.inv() and FrontBufferReadyGate.ALL)
        sb.append("] inferred=[")
        appendConds(sb, inferredMask)
        sb.append(']')
        sb.append(" t=+").append(msSinceQuietStart).append("ms")
        sb.append(" fallbackIn=").append(if (msUntilFallback > 0L) msUntilFallback else 0L).append("ms")
        sb.append(" sv=").append(surfaceX).append(',').append(surfaceY)
            .append(' ').append(surfaceW).append('x').append(surfaceH)
        sb.append(" view=").append(viewW).append('x').append(viewH)
        sb.append(" stable=").append(stableFrames)
        sb.append(" hz=").append(fmt(refreshHz)).append('/').append(fmt(targetRefreshHz))
        sb.append(" render=").append(renderW).append('x').append(renderH)
        sb.append(" grid=").append(gridW).append('x').append(gridH)
        sb.append(" gen=").append(tileGeneration)
        sb.append(" invalid=").append(bufferInvalid)
        sb.append(" proj[zoom=").append(fmt(zoom))
        sb.append(",fit=").append(fmt(fitScale))
        sb.append(",pan=").append(fmt(panX)).append(',').append(fmt(panY))
        sb.append(",rot=").append(fmt(rotation)).append(']')
        sb.append(" merger[sched=").append(renderScheduled)
        sb.append(",followUp=").append(renderFollowUp)
        sb.append(",inFlight=").append(renderInFlight).append(']')
        sb.append(" loading=").append(loading)
        Log.i(TAG, sb.toString())
    }

    private fun appendConds(sb: StringBuilder, bits: Int) {
        var first = true
        for (i in COND_NAMES.indices) {
            if (bits and (1 shl i) == 0) continue
            if (!first) sb.append(',')
            sb.append(COND_NAMES[i])
            first = false
        }
    }

    /** 与 FrontBufferReadyGate.COND_* 位序一致。 */
    private val COND_NAMES = arrayOf("tiles", "idle", "geom", "hz", "notLoading")

    /** 每一帧真墨贴图一行 (限频)。渲染线程调用。 */
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
        if (!PerfTrace.enabled) return
        val now = SystemClock.uptimeMillis()
        if (lastFrameMs != 0L && now - lastFrameMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameMs = now
        frameSeq++
        val sb = StringBuilder(320)
        sb.append("ev=ink_frame#").append(frameSeq)
        sb.append(" pending=").append(pendingCount)
        sb.append(" baked=").append(bakedCount)
        sb.append(" clipped=").append(clippedCount)
        sb.append(" origin=").append(originL).append(',').append(originT)
        sb.append(" req=").append(reqW).append('x').append(reqH)
        sb.append(" slot=").append(slotW).append('x').append(slotH)
        sb.append(" canvas=[").append(canvasBounds[0]).append(',').append(canvasBounds[1]).append(',')
            .append(canvasBounds[2]).append(',').append(canvasBounds[3]).append(']')
        val n = minOf(bakedCount, MAX_RECTS_LOGGED)
        for (i in 0 until n) {
            val d = i * 4
            sb.append(" r").append(i)
            sb.append("[bmp=").append(bmpRects[d]).append(',').append(bmpRects[d + 1])
            sb.append('-').append(bmpRects[d + 2]).append(',').append(bmpRects[d + 3])
            sb.append(" scr=").append(screenRects[d]).append(',').append(screenRects[d + 1])
            sb.append('-').append(screenRects[d + 2]).append(',').append(screenRects[d + 3])
            sb.append(']')
        }
        if (bakedCount > n) sb.append(" (+").append(bakedCount - n).append(" more)")
        Log.i(TAG, sb.toString())
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
