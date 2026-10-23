/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.FrontBufferReadyGate
import com.reverie.paint.model.HoleMaskGrid

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

    // ================= H-min 孔洞 (H-7) =================
    //
    //     adb shell setprop debug.reverie.perf 1
    //     adb logcat -s FbGeomLog:I
    //     ev=hole_mask  gen=.. 1600x2560 holePx=.. cov=0.031 build=1.84ms      (后台线程, 几何变化后)
    //     ev=hole_arm   seq=.. mode=hmin arm=WITH_MASK cov=0.031
    //     ev=hole_end   seq=.. packets=.. hit=.. rects=.. hitRects=.. hitArea=..px
    //                   test=avg/max us window=n avg/max us front=n avg/max us handback=.. idViolations=..
    //
    // 一致性自检 (逐包扫备份层孔洞像素必须全 0, 很慢, 只为验收):
    //     adb shell setprop debug.reverie.fb_identity_check 1   (重启应用生效)
    //     ev=identity_mismatch seq=.. rect=l,t-r,b bad=.. (每笔最多 8 行)

    /** 一致性自检开关 (进程内只读一次, 不依赖 PerfTrace)。 */
    val identityCheckEnabled: Boolean by lazy { PerfTrace.debugPropInt("debug.reverie.fb_identity_check", 0) == 1 }

    /** 热路径计时是否值得取 nanoTime。 */
    val timingEnabled: Boolean get() = PerfTrace.enabled

    private val holeLock = Any()
    private var hSeq = 0L
    private var hPackets = 0L
    private var hHitPackets = 0L
    private var hRects = 0L
    private var hHitRects = 0L
    private var hHitArea = 0L
    private var hTestNs = 0L
    private var hTestMax = 0L
    private var hWindowN = 0L
    private var hWindowNs = 0L
    private var hWindowMax = 0L
    private var hFrontN = 0L
    private var hFrontNs = 0L
    private var hFrontMax = 0L
    private var hHandback = 0L
    private var hRedrawReq = 0L
    private var hIdBad = 0L
    private var hIdLines = 0
    private var idRow: IntArray = IntArray(0)

    fun holeMaskBuilt(gen: Int, w: Int, h: Int, holePixels: Long, coverage: Float, buildNs: Long) {
        if (!PerfTrace.enabled) return
        Log.i(TAG, "ev=hole_mask gen=$gen ${w}x$h holePx=$holePixels cov=${fmt3(coverage)} build=${ms(buildNs)}ms")
    }

    fun holeArm(strokeSeq: Long, mode: Int, arm: Int, coverage: Float) {
        if (!PerfTrace.enabled) return
        synchronized(holeLock) {
            hSeq = strokeSeq
            hPackets = 0; hHitPackets = 0; hRects = 0; hHitRects = 0; hHitArea = 0
            hTestNs = 0; hTestMax = 0; hWindowN = 0; hWindowNs = 0; hWindowMax = 0
            hFrontN = 0; hFrontNs = 0; hFrontMax = 0; hHandback = 0; hRedrawReq = 0; hIdBad = 0; hIdLines = 0
        }
        val m = when (mode) { 1 -> "hmin"; 2 -> "e"; else -> "off" }
        val a = when (arm) { 0 -> "NO_HOLES"; 1 -> "WITH_MASK"; else -> "NORMAL" }
        Log.i(TAG, "ev=hole_arm seq=$strokeSeq mode=$m arm=$a cov=${fmt3(coverage)}")
    }

    /** 渲染线程, 每包一次 (只累加)。[decision] 见 FrontBufferHolePolicy.PACKET_*。 */
    fun holePacket(strokeSeq: Long, rects: Int, hitRects: Int, hitArea: Long, testNs: Long, decision: Int) {
        if (!PerfTrace.enabled) return
        synchronized(holeLock) {
            if (strokeSeq != hSeq) return
            hPackets++
            hRects += rects
            if (hitRects > 0) hHitPackets++
            hHitRects += hitRects
            hHitArea += hitArea
            hTestNs += testNs
            if (testNs > hTestMax) hTestMax = testNs
            if (decision == 2) hHandback++
        }
    }

    fun holeRedrawRequested() {
        if (!PerfTrace.enabled) return
        synchronized(holeLock) { hRedrawReq++ }
    }

    /** UI 线程: 按住且有孔洞时主窗口 onDraw 的耗时 (录制侧)。 */
    fun holeWindowDraw(ns: Long) {
        if (!PerfTrace.enabled) return
        synchronized(holeLock) {
            hWindowN++
            hWindowNs += ns
            if (ns > hWindowMax) hWindowMax = ns
        }
    }

    /** GL 线程: 有孔洞的笔画每个前缓冲包的绘制耗时 (CPU 侧, 不含 GPU)。 */
    fun frontPresented(ns: Long) {
        if (!PerfTrace.enabled) return
        synchronized(holeLock) {
            hFrontN++
            hFrontNs += ns
            if (ns > hFrontMax) hFrontMax = ns
        }
    }

    fun holeStrokeEnd(strokeSeq: Long) {
        if (!PerfTrace.enabled) return
        val line = synchronized(holeLock) {
            if (strokeSeq != hSeq || hPackets == 0L && hFrontN == 0L) return
            hSeq = -1
            "ev=hole_end seq=$strokeSeq packets=$hPackets hit=$hHitPackets rects=$hRects hitRects=$hHitRects " +
                "hitArea=${hHitArea}px ratio=${fmt3(if (hPackets > 0) hHitPackets.toFloat() / hPackets else 0f)} " +
                "test=${us(avg(hTestNs, hPackets))}/${us(hTestMax)}us " +
                "window=$hWindowN ${us(avg(hWindowNs, hWindowN))}/${us(hWindowMax)}us " +
                "front=$hFrontN ${us(avg(hFrontNs, hFrontN))}/${us(hFrontMax)}us " +
                "redrawReq=$hRedrawReq handback=$hHandback idViolations=$hIdBad"
        }
        Log.i(TAG, line)
    }

    /**
     * GL 线程, 仅 [identityCheckEnabled]: 前缓冲备份层 [layer] (与前缓冲逐像素相同) 在孔洞像素上
     * 必须完全透明。违反则记一行 (每笔最多 8 行) 并计数。
     */
    fun identityCheck(layer: Bitmap, grid: HoleMaskGrid, rects: IntArray, count: Int) {
        if (!identityCheckEnabled) return
        val lw = layer.width
        val lh = layer.height
        for (i in 0 until count) {
            val d = i * 4
            val l = rects[d].coerceIn(0, lw)
            val t = rects[d + 1].coerceIn(0, lh)
            val r = rects[d + 2].coerceIn(0, lw)
            val b = rects[d + 3].coerceIn(0, lh)
            if (r <= l || b <= t || !grid.anyExact(l, t, r, b)) continue
            val w = r - l
            if (idRow.size < w) idRow = IntArray(w)
            var bad = 0
            for (y in t until b) {
                layer.getPixels(idRow, 0, w, l, y, w, 1)
                for (x in 0 until w) {
                    if (idRow[x] ushr 24 != 0 && grid.isHole(l + x, y)) bad++
                }
            }
            if (bad > 0) {
                val log = synchronized(holeLock) {
                    hIdBad += bad
                    hIdLines++ < 8
                }
                if (log) Log.w(TAG, "ev=identity_mismatch seq=$hSeq rect=$l,$t-$r,$b bad=$bad")
            }
        }
    }

    private fun avg(sum: Long, n: Long): Long = if (n > 0) sum / n else 0L
    private fun us(ns: Long): String = String.format(java.util.Locale.US, "%.1f", ns / 1000f)
    private fun ms(ns: Long): String = String.format(java.util.Locale.US, "%.2f", ns / 1_000_000f)
    private fun fmt3(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
