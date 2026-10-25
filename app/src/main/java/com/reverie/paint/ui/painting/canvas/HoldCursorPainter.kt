/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 按住真墨期间的笔刷光标绘制 (H-3 单一来源)。
 *
 * 前缓冲 (GL 线程) 在孔洞外画、主窗口 (UI 线程) 在孔洞内画, 两边必须是**同一个函数、同一组
 * 参数** ([RealInkController.cursorX]/[RealInkController.cursorY]/[RealInkController.cursorR]/
 * [RealInkController.cursorStyle] + 对称镜像点), 拼起来才是一个完整光标。Paint 非线程安全,
 * 每个线程各持一个实例。
 */
internal class HoldCursorPainter(private val density: Float) {
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = Color.BLACK
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.WHITE
    }
    private val fillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fillWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** 单个光标外接正方形的半边长 (含描边与抗锯齿余量)。 */
    fun half(r: Float): Float = maxOf(r, 7f * density) + 2f

    fun drawAll(canvas: Canvas, x: Float, y: Float, r: Float, style: Int, symX: FloatArray, symY: FloatArray, symCount: Int) {
        if (r <= 0f) return
        drawSingle(canvas, x, y, r, style)
        for (i in 0 until symCount) drawSingle(canvas, symX[i], symY[i], r, style)
    }

    fun drawSingle(canvas: Canvas, x: Float, y: Float, r: Float, cursorStyle: Int) {
        if (!x.isFinite() || !y.isFinite()) return
        val d = density
        when (cursorStyle) {
            1 -> {
                val len = 7f * d
                canvas.drawLine(x - len, y, x + len, y, black)
                canvas.drawLine(x, y - len, x, y + len, black)
                canvas.drawLine(x - len, y, x + len, y, white)
                canvas.drawLine(x, y - len, x, y + len, white)
            }
            2 -> {
                canvas.drawCircle(x, y, 3f * d, fillBlack)
                canvas.drawCircle(x, y, 1.8f * d, fillWhite)
            }
            else -> {
                canvas.drawCircle(x, y, r + 0.8f, black)
                canvas.drawCircle(x, y, r, white)
                if (cursorStyle == 5) {
                    val len = 4.5f * d
                    canvas.drawLine(x - len, y, x + len, y, black)
                    canvas.drawLine(x, y - len, x, y + len, black)
                    canvas.drawLine(x - len, y, x + len, y, white)
                    canvas.drawLine(x, y - len, x, y + len, white)
                }
            }
        }
    }

    companion object {
        /**
         * 全部光标 (主光标 + 对称镜像) 的屏幕包围盒写进 [out] (`l, t, r, b`, 向外取整)。
         * 没有有效光标返回 false。
         */
        fun bounds(x: Float, y: Float, half: Float, symX: FloatArray, symY: FloatArray, symCount: Int, out: IntArray): Boolean {
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            if (x.isFinite() && y.isFinite()) {
                l = x - half; t = y - half; r = x + half; b = y + half
            }
            for (i in 0 until symCount) {
                val sx = symX[i]
                val sy = symY[i]
                if (!sx.isFinite() || !sy.isFinite()) continue
                if (sx - half < l) l = sx - half
                if (sy - half < t) t = sy - half
                if (sx + half > r) r = sx + half
                if (sy + half > b) b = sy + half
            }
            if (l > r) return false
            out[0] = kotlin.math.floor(l).toInt()
            out[1] = kotlin.math.floor(t).toInt()
            out[2] = kotlin.math.ceil(r).toInt()
            out[3] = kotlin.math.ceil(b).toInt()
            return true
        }
    }
}
