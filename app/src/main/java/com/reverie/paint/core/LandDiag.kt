/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 横屏首笔诊断。只记录，不改变绘制、按住或失效行为。
 * logcat -s LandDiag:I
 */
internal object LandDiag {
    private const val TAG = "LandDiag"

    @Volatile
    var armed: Boolean = false

    private val gate = Any()
    private val inPlaceAccum = AtomicInteger(0)
    private val tornAccum = AtomicInteger(0)
    private val invCount = AtomicInteger(0)

    @Volatile
    private var firstTrigger: String = ""

    private val ink = IntArray(MAX_PACKETS * 4)
    private var packetTotal = 0
    private var spanL = Int.MAX_VALUE
    private var spanT = Int.MAX_VALUE
    private var spanR = Int.MIN_VALUE
    private var spanB = Int.MIN_VALUE
    private var gapCount = 0
    private val gapLines = ArrayList<String>(8)
    private var shotEarly = false
    private var shotFinal = false

    /** 新的一笔按住开始。清掉上一笔没打出来的矩形。 */
    fun beginHold() {
        synchronized(gate) {
            clearPacketsLocked()
            armed = true
        }
    }

    /**
     * 按住结束。全部墨矩形都参与断档计数，不只留头尾。
     * 断档 = 新矩形离当前笔迹包围盒超过 [CLUSTER_PAD_PX]，中间没有连上。
     */
    fun endHold() {
        val summary: String
        val lines: List<String>
        synchronized(gate) {
            armed = false
            if (packetTotal == 0) {
                clearPacketsLocked()
                return
            }
            val stored = packetTotal.coerceAtMost(MAX_PACKETS)
            val sb = StringBuilder(180)
            sb.append("ev=rects pk=").append(packetTotal)
                .append(" stored=").append(stored)
                .append(" gaps=").append(gapCount)
                .append(" span=").append(spanL).append(',').append(spanT).append(',')
                .append(spanR).append(',').append(spanB)
                .append(" t=").append(SystemClock.uptimeMillis())
            summary = sb.toString()
            val built = ArrayList<String>(gapLines.size + 2)
            if (stored > 0) built.add(rectLine("first", 0))
            if (stored > 1) built.add(rectLine("last", stored - 1))
            built.addAll(gapLines)
            lines = built
            clearPacketsLocked()
        }
        Log.i(TAG, summary)
        for (line in lines) Log.i(TAG, line)
    }

    /** 这一笔最多存两张槽位图：笔迹拉过 400px 时一张，抬笔包一张。 */
    fun takeShot(finalPacket: Boolean): Boolean {
        synchronized(gate) {
            if (!armed) return false
            if (finalPacket) {
                if (shotFinal) return false
                shotFinal = true
                return true
            }
            if (shotEarly) return false
            if (spanR == Int.MIN_VALUE || spanR - spanL < 400) return false
            shotEarly = true
            return true
        }
    }

    /**
     * 已经提交到前缓冲的一包。只抄墨脏区（前 [baked] 块），不抄光标和导引线足迹。
     * 调用方马上会复用这几个数组，所以这里先复制。
     */
    fun noteCompose(
        finalPacket: Boolean,
        baked: Int,
        packetN: Int,
        screenRects: IntArray,
        bmpRects: IntArray,
        packXY: IntArray,
        slotW: Int,
        slotH: Int,
    ) {
        if (!armed || baked <= 0) return
        val n = baked.coerceAtMost(MAX_INK_RECTS)
        val screen = IntArray(n * 4)
        val bmp = IntArray(n * 4)
        val pack = IntArray(n * 2)
        System.arraycopy(screenRects, 0, screen, 0, n * 4)
        System.arraycopy(bmpRects, 0, bmp, 0, n * 4)
        System.arraycopy(packXY, 0, pack, 0, n * 2)
        val l = screen[0]
        val t = screen[1]
        val r = screen[2]
        val b = screen[3]
        synchronized(gate) {
            if (!armed) return
            if (packetTotal > 0 &&
                (r < spanL - CLUSTER_PAD_PX || l > spanR + CLUSTER_PAD_PX ||
                    b < spanT - CLUSTER_PAD_PX || t > spanB + CLUSTER_PAD_PX)
            ) {
                gapCount++
                if (gapLines.size < 8) {
                    gapLines.add(
                        "ev=gap i=$packetTotal s=$l,$t,$r,$b b=${bmp[0]},${bmp[1]},${bmp[2]},${bmp[3]} " +
                            "slot=${slotW}x$slotH f=${if (finalPacket) 1 else 0}",
                    )
                }
            }
            if (l < spanL) spanL = l
            if (t < spanT) spanT = t
            if (r > spanR) spanR = r
            if (b > spanB) spanB = b
            if (packetTotal < MAX_PACKETS) {
                val o = packetTotal * 4
                ink[o] = l
                ink[o + 1] = t
                ink[o + 2] = r
                ink[o + 3] = b
            }
            packetTotal++
        }
    }

    private fun rectLine(tag: String, index: Int): String {
        val o = index * 4
        return "ev=rect which=$tag i=$index s=${ink[o]},${ink[o + 1]},${ink[o + 2]},${ink[o + 3]}"
    }

    private fun clearPacketsLocked() {
        packetTotal = 0
        gapCount = 0
        gapLines.clear()
        spanL = Int.MAX_VALUE
        spanT = Int.MAX_VALUE
        spanR = Int.MIN_VALUE
        spanB = Int.MIN_VALUE
        shotEarly = false
        shotFinal = false
    }

    private fun summarize(kept: List<ComposePkt>): String {
        val boxes = ArrayList<ClusterBox>(4)
        var rects = 0
        for (pkt in kept) {
            val n = pkt.screen.size / 4
            rects += n
            for (i in 0 until n) {
                val s = i * 4
                absorb(boxes, pkt.screen[s], pkt.screen[s + 1], pkt.screen[s + 2], pkt.screen[s + 3])
            }
        }
        val dropMid = (packetTotal - kept.size).coerceAtLeast(0)
        val sb = StringBuilder(160)
        sb.append("ev=rects pk=").append(packetTotal)
            .append(" kept=").append(kept.size)
            .append(" dropMid=").append(dropMid)
            .append(" ink=").append(rects)
            .append(" clusters=").append(boxes.size)
        for (i in boxes.indices) {
            val c = boxes[i]
            sb.append(" c").append(i).append('=')
                .append(c.l).append(',').append(c.t).append(',').append(c.r).append(',').append(c.b)
                .append(" n=").append(c.n)
        }
        sb.append(" t=").append(SystemClock.uptimeMillis())
        return sb.toString()
    }

    /** 相邻或相距不超过 [CLUSTER_PAD_PX] 的屏幕矩形并成一团。一条连续笔迹是一团。 */
    private fun absorb(boxes: MutableList<ClusterBox>, l: Int, t: Int, r: Int, b: Int) {
        for (c in boxes) {
            if (r < c.l - CLUSTER_PAD_PX || l > c.r + CLUSTER_PAD_PX ||
                b < c.t - CLUSTER_PAD_PX || t > c.b + CLUSTER_PAD_PX
            ) {
                continue
            }
            if (l < c.l) c.l = l
            if (t < c.t) c.t = t
            if (r > c.r) c.r = r
            if (b > c.b) c.b = b
            c.n++
            return
        }
        boxes.add(ClusterBox(l, t, r, b, 1))
    }

    private class ClusterBox(var l: Int, var t: Int, var r: Int, var b: Int, var n: Int)

    private class ComposePkt(
        val finalPacket: Boolean,
        val baked: Int,
        val packetN: Int,
        val slotW: Int,
        val slotH: Int,
        val screen: IntArray,
        val bmp: IntArray,
        val pack: IntArray,
    ) {
        fun line(): String {
            val sb = StringBuilder(256)
            sb.append("ev=rect f=").append(if (finalPacket) 1 else 0)
                .append(" ink=").append(screen.size / 4).append('/').append(baked)
                .append(" n=").append(packetN)
                .append(" slot=").append(slotW).append('x').append(slotH).append(' ')
            val m = screen.size / 4
            for (i in 0 until m) {
                val s = i * 4
                val p = i * 2
                if (i > 0) sb.append(';')
                sb.append(screen[s]).append(',').append(screen[s + 1]).append(',')
                    .append(screen[s + 2]).append(',').append(screen[s + 3])
                sb.append('/').append(bmp[s]).append(',').append(bmp[s + 1]).append(',')
                    .append(bmp[s + 2]).append(',').append(bmp[s + 3])
                sb.append('@').append(pack[p]).append(',').append(pack[p + 1])
            }
            return sb.toString()
        }
    }

    fun noteRender(inPlace: Int, torn: Boolean) {
        if (!armed) return
        if (inPlace > 0) inPlaceAccum.addAndGet(inPlace)
        if (torn) tornAccum.incrementAndGet()
    }

    /** 按住期间的 invalidate / postInvalidate。同一帧只保留第一次调用栈。 */
    fun noteInvalidate(trigger: String) {
        if (!armed) return
        if (invCount.getAndIncrement() == 0) firstTrigger = trigger
    }

    fun shortCaller(): String {
        val stack = Throwable().stackTrace
        for (i in 2 until stack.size) {
            val e = stack[i]
            val cn = e.className
            if (!cn.startsWith("com.reverie.paint")) continue
            if (cn.endsWith("LandDiag")) continue
            val m = e.methodName
            if (m == "invalidate" || m == "postInvalidate" || m == "postInvalidateOnAnimation" ||
                m == "shortCaller" || m == "noteInvalidate"
            ) {
                continue
            }
            val simple = cn.substringAfterLast('.')
            return "$simple.$m:${e.lineNumber}"
        }
        val e = stack.getOrNull(3) ?: return "unknown"
        return e.className.substringAfterLast('.') + "." + e.methodName + ":" + e.lineNumber
    }

    fun holdDraw(drewTiles: Boolean, generation: Int, tileRev: Long, interacting: Boolean) {
        val ip = inPlaceAccum.getAndSet(0)
        val torn = tornAccum.getAndSet(0)
        val n = invCount.getAndSet(0)
        val trigger = if (n == 0) "parent-redraw" else firstTrigger.ifEmpty { "invalidate" }
        firstTrigger = ""
        Log.i(
            TAG,
            "ev=hold_draw tiles=${if (drewTiles) 1 else 0} gen=$generation rev=$tileRev " +
                "inPlace=$ip torn=$torn interacting=${if (interacting) 1 else 0} " +
                "inv=$n trigger=$trigger t=${SystemClock.uptimeMillis()}",
        )
    }

    private const val MAX_PACKETS = 640
    private const val MAX_INK_RECTS = 8
    private const val CLUSTER_PAD_PX = 48
}
