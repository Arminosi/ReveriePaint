/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.HoleMaskGrid
import com.reverie.paint.perf.FbGeomLog
import com.reverie.paint.core.PaintViewModel
import java.nio.ByteBuffer

/**
 * H-1 前缓冲孔洞掩码 (一份不可变快照)。
 *
 * [bitmap] 为视图尺寸的 ALPHA_8, 孔洞像素 0xFF、其余 0 (与 [grid] 逐像素一致, 同一次构建产出);
 * 覆盖层用它做 DST_OUT, 渲染线程用 [grid] 判定每块脏区是否命中。
 * 什么装饰都不可见时 [bitmap] = null 且 [grid] 为空。
 */
internal class HoleMask(
    val bitmap: Bitmap?,
    val grid: HoleMaskGrid,
    val pose: CanvasPose,
    val viewW: Int,
    val viewH: Int,
    val gen: Int,
) {
    val isEmpty: Boolean get() = bitmap == null || grid.isEmpty
}

/**
 * 孔洞掩码的构建: **唯一几何来源**是 [FrontBufferDecorRecorder] —— 与窗口 (CanvasOverlay /
 * drawCanvas) 调用同一批绘制函数录成 Picture, 再在后台线程光栅化成 A8、二值化 + 膨胀。
 *
 * 线程:
 *  - UI 线程: [scheduleIfStale] (drawCanvas 每帧调用, 只做引用比较), [readyFor], 录制 Picture;
 *  - "reverie-holes" 线程: 光栅化 + [HoleMaskGrid.build] + 发布。
 * 关键路径上零额外工作: 运笔按住期间不调度 (视图不重画, 装饰也不会变)。
 */
internal class FrontBufferHoleMaskBuilder(private val view: CanvasTouchView) {
    private val recorder = FrontBufferDecorRecorder(view)

    /** 最近一次构建完成的掩码 (后台线程写, UI 线程读)。 */
    @Volatile
    var published: HoleMask? = null
        private set

    // UI 线程写, 后台线程读 (作废判断)
    @Volatile
    private var gen = 0
    private var scheduled = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // 后台线程复用
    private var scratch: ByteArray = ByteArray(0)

    private val buildRunnable = object : Runnable {
        override fun run() {
            // 运笔中不在 UI 线程录制 (哪怕只有几十微秒): 推迟到抬笔之后。
            if (view.strokeInProgress) {
                view.postDelayed(this, DEBOUNCE_MS)
                return
            }
            scheduled = false
            buildNow()
        }
    }

    private fun isTransform(): Boolean = view.tool == com.reverie.paint.model.Tool.TRANSFORM

    /** 装饰状态变了就防抖 [DEBOUNCE_MS] 后重建 (UI 线程)。缩放/平移手势期间一直往后推。 */
    fun scheduleIfStale(v: PaintViewModel, pose: CanvasPose) {
        if (!recorder.isStale(v, pose, isTransform())) return
        if (scheduled) view.removeCallbacks(buildRunnable)
        scheduled = true
        view.postDelayed(buildRunnable, DEBOUNCE_MS)
    }

    /**
     * 落笔时取与当前状态一致的掩码 (UI 线程); 过期或还没建好返回 null。
     */
    fun readyFor(v: PaintViewModel, pose: CanvasPose): HoleMask? {
        if (recorder.isStale(v, pose, isTransform())) return null
        val m = published ?: return null
        if (m.gen != gen || m.pose != pose || m.viewW != view.width || m.viewH != view.height) return null
        return m
    }

    /** 当前是否有装饰可见 (掩码过期时决定无孔按住还是常规路径)。 */
    fun anyDecorVisible(v: PaintViewModel, pose: CanvasPose): Boolean = recorder.anyVisible(v, pose, isTransform())

    /** 立即重建 (UI 线程; 落笔发现过期时调用, 下一笔就能用上)。 */
    fun buildNow() {
        val v = view.vm ?: return
        val pose = view.realInk.pose ?: return
        val tr = isTransform()
        if (!recorder.isStale(v, pose, tr)) return
        if (scheduled) {
            view.removeCallbacks(buildRunnable)
            scheduled = false
        }
        val g = ++gen
        val w = view.width
        val h = view.height
        val decor = recorder.record(v, pose, tr, forMask = true)
        if (decor == null || w <= 0 || h <= 0) {
            published = HoleMask(null, HoleMaskGrid.EMPTY, pose, w, h, g)
            return
        }
        val margin = FrontBufferFeatureFlags.holeMargin
        ensureHandler().post { rasterize(decor, pose, w, h, g, margin) }
    }

    private fun rasterize(decor: FrontBufferDecor, pose: CanvasPose, w: Int, h: Int, g: Int, margin: Int) {
        if (g != gen) return // 已有更新的请求 (gen 只增, 读到旧值最多多建一次)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        decor.under?.let { c.drawPicture(it) }
        decor.over?.let { c.drawPicture(it) }
        val stride = bmp.rowBytes
        val size = stride * h
        if (scratch.size < size) scratch = ByteArray(size)
        val buf = ByteBuffer.wrap(scratch, 0, size)
        bmp.copyPixelsToBuffer(buf)
        val grid = HoleMaskGrid.build(scratch, stride, w, h, margin)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        bmp.prepareToDraw()
        val mask = if (grid.isEmpty) {
            bmp.recycle()
            HoleMask(null, grid, pose, w, h, g)
        } else {
            HoleMask(bmp, grid, pose, w, h, g)
        }
        published = mask
        FbGeomLog.holeMaskBuilt(g, w, h, grid.holePixels, grid.coverage, SystemClock.elapsedRealtimeNanos() - t0)
    }

    private fun ensureHandler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("reverie-holes").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    /** 视图分离时停后台线程 (UI 线程)。 */
    fun shutdown() {
        if (scheduled) view.removeCallbacks(buildRunnable)
        scheduled = false
        thread?.quitSafely()
        thread = null
        handler = null
        recorder.invalidate()
    }

    companion object {
        const val DEBOUNCE_MS = 100L
    }
}
