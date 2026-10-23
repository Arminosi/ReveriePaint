/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader

/**
 * 绘画中辅助吸附动态导引线 (透视灭点射线 / 等轴测 / 2D 网格高亮) 的绘制。
 *
 * 几何由 CanvasTouchView.computeDynamicGuide 算出, 写成 [SEG_SIZE] 个 float 的屏幕坐标段:
 * `x1, y1, x2, y2, vpX, vpY`。主画布 (onDraw) 与前缓冲 (真墨按住时, GL 线程) 各持一个实例,
 * 画笔不跨线程共享。
 */
internal class DynamicGuidePainter(density: Float) {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = android.graphics.Color.argb(70, 0x5A, 0x6E, 0x8A) // Morandi misty blue glow
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = android.graphics.Color.argb(220, 0x78, 0x88, 0x9F) // Morandi accentHi crisp line
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val vpGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = android.graphics.Color.argb(140, 0x5A, 0x6E, 0x8A)
    }
    private val vpCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = android.graphics.Color.argb(255, 0x78, 0x88, 0x9F)
    }
    private val vpCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }
    private val glowR = 14f * density
    private val coreR = 7f * density
    private val centerR = 3.5f * density

    /** 线段足迹半宽 (最粗的光晕笔 + 抗锯齿余量), 擦除/失效判定用。 */
    val linePad: Float = glowPaint.strokeWidth * 0.5f + 2f

    /** 灭点圆足迹半边 (最大的光晕圆 + 笔宽 + 抗锯齿余量)。 */
    val vpHalf: Float = glowR + vpGlowPaint.strokeWidth * 0.5f + 2f

    // H-min 孔洞拼接: 同一组画笔的"只画孔内 (DST_IN)"/"只画孔外 (DST_OUT)"副本。几何、笔宽、
    // 虚线、alpha 全部照抄; 颜色改由不透明常量色着色器给出 (paint alpha 照旧调制), 再与二值 A8
    // 掩码合成 —— 等价于"先画再 DST_IN/DST_OUT 掩码", 但不需要离屏层。按掩码位图身份缓存。
    private val basePaints = arrayOf(glowPaint, linePaint, vpGlowPaint, vpCorePaint, vpCenterPaint)
    private val maskedPaints = arrayOfNulls<Paint>(basePaints.size)
    private var maskedFor: Bitmap? = null
    private var maskedMode: PorterDuff.Mode? = null

    /** 按 [kind] 画 [seg] (从 [offset] 起)。[kind] 取 [KIND_NONE] / [KIND_LINE] / [KIND_LINE_VP]。 */
    fun draw(canvas: Canvas, kind: Int, seg: FloatArray, offset: Int = 0) {
        drawWith(canvas, kind, seg, offset, glowPaint, linePaint, vpGlowPaint, vpCorePaint, vpCenterPaint)
    }

    /**
     * 同 [draw], 但只保留二值掩码 [mask] (屏幕坐标, 左上角对齐 (0,0)) 内 ([PorterDuff.Mode.DST_IN])
     * 或外 ([PorterDuff.Mode.DST_OUT]) 的部分。两半像素互补, 拼起来与 [draw] 相同。
     */
    fun drawMasked(canvas: Canvas, kind: Int, seg: FloatArray, mask: Bitmap, mode: PorterDuff.Mode, offset: Int = 0) {
        if (kind != KIND_LINE && kind != KIND_LINE_VP) return
        if (mask.isRecycled) return
        if (maskedFor !== mask || maskedMode != mode) {
            maskedFor = mask
            maskedMode = mode
            val maskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            for (i in basePaints.indices) {
                val src = basePaints[i]
                val c = src.color
                val rgb = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
                // 两端同色的线性渐变 = 常量色着色器 (android.graphics.ColorShader 不在公开 SDK)。
                val color = android.graphics.LinearGradient(0f, 0f, 1f, 0f, rgb, rgb, Shader.TileMode.CLAMP)
                maskedPaints[i] = Paint(src).apply { shader = ComposeShader(color, maskShader, mode) }
            }
        }
        drawWith(canvas, kind, seg, offset, maskedPaints[0]!!, maskedPaints[1]!!, maskedPaints[2]!!,
            maskedPaints[3]!!, maskedPaints[4]!!)
    }

    private fun drawWith(
        canvas: Canvas,
        kind: Int,
        seg: FloatArray,
        offset: Int,
        glowPaint: Paint,
        linePaint: Paint,
        vpGlowPaint: Paint,
        vpCorePaint: Paint,
        vpCenterPaint: Paint,
    ) {
        if (kind != KIND_LINE && kind != KIND_LINE_VP) return
        val x1 = seg[offset]
        val y1 = seg[offset + 1]
        val x2 = seg[offset + 2]
        val y2 = seg[offset + 3]
        canvas.drawLine(x1, y1, x2, y2, glowPaint)
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        if (kind == KIND_LINE_VP) {
            val vx = seg[offset + 4]
            val vy = seg[offset + 5]
            canvas.drawCircle(vx, vy, glowR, vpGlowPaint)
            canvas.drawCircle(vx, vy, coreR, vpCorePaint)
            canvas.drawCircle(vx, vy, centerR, vpCenterPaint)
        }
    }

    companion object {
        const val SEG_SIZE = 6
        const val KIND_NONE = 0
        const val KIND_LINE = 1
        const val KIND_LINE_VP = 2
    }
}
