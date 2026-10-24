/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.model.QuickShapePressure
import com.reverie.paint.model.QuickShapeResult
import kotlin.math.PI

internal data class QuickShapeDraft(
    val original: FloatArray,
    val layer: Int,
    val frame: Int,
    val width: Int,
    val height: Int,
    val documentCreatedTime: Long,
    val recognizedShape: QuickShapeResult,
    var nativeOriginalActive: Boolean = true,
)

/**
 * 抬笔且速创形状识别成功时触发。
 * 原手绘笔画在底层原生引擎中保持未提交状态（nativeOriginalActive = true），
 * 屏幕直接显示原笔迹；顶层 Compose 叠加层绘制画笔同色 0.55 半透明等宽框架与控制手柄。
 * @return true 表示成功进入速创编辑态；false 表示前置条件未满足（调用方应兜底提交原笔画）。
 */
internal fun PaintViewModel.beginQuickShape(shape: QuickShapeResult, original: FloatArray): Boolean {
    if (original.size < 12 || renderHandler == null) return false
    quickShapeCapture = null
    quickShapeDraft = QuickShapeDraft(
        original = original,
        layer = currentLayerIndex,
        frame = anim.currentTime,
        width = docWidth,
        height = docHeight,
        documentCreatedTime = canvasCreatedTime,
        recognizedShape = shape,
        nativeOriginalActive = true,
    )
    stopAirbrush()
    disarmStrokeStartKick()
    activeStrokeToolOverride = null
    activeStrokePresetSwitched = false

    val snapped = QuickShapeGeometry.snapLine(shape, quickShapeAngleSnapEnabled)
    val active = if (quickShapeCurvedContourEnabled &&
        (snapped.type == com.reverie.paint.model.QuickShapeType.QUADRILATERAL || snapped.type == com.reverie.paint.model.QuickShapeType.CONTOUR)) {
        QuickShapeGeometry.setContourCurved(snapped, true)
    } else snapped
    activeQuickShape = active
    isQuickShapeEditing = true
    return true
}

/** 预览线宽: 用原笔迹的平均压感折算出笔尖尺寸, 而不是满笔宽 */
internal val PaintViewModel.quickShapePreviewWidth: Float
    get() {
        val base = brushSize.toFloat().coerceAtLeast(1f)
        val orig = quickShapeDraft?.original ?: return base
        val avgP = QuickShapePressure.average(orig)
        if (!brushPressureEnabled || brushPressureSize <= 0.001) return base
        val frac = (1f - brushPressureSize.toFloat()) +
            brushPressureSize.toFloat() * avgP.coerceIn(0.05f, 1f)
        return (base * frac).coerceAtLeast(1f)
    }

/** 控制点拖动结束或微调按钮点击后占位（预览完全由顶层 Compose 叠加层即时绘制） */
@Suppress("UnusedReceiverParameter")
internal fun PaintViewModel.updateQuickShapePreview() {}

internal fun PaintViewModel.commitQuickShape() = finishQuickShape(restoreOriginal = false)

/** Cancel means keep the freehand stroke, not silently delete the user's drawing. */
internal fun PaintViewModel.cancelQuickShape() = finishQuickShape(restoreOriginal = true)

private fun PaintViewModel.finishQuickShape(restoreOriginal: Boolean) {
    val draft = quickShapeDraft ?: return
    val shape = activeQuickShape ?: return
    if (quickShapeCommitting || renderHandler == null) return

    // The editor blocks tool/layer/frame changes; guard external document changes as well.
    if (currentLayerIndex != draft.layer || anim.currentTime != draft.frame ||
        docWidth != draft.width || docHeight != draft.height || canvasCreatedTime != draft.documentCreatedTime) {
        showActionToast(R.string.quick_shape_context_changed, R.drawable.ic_line)
        if (draft.nativeOriginalActive) {
            touchCancel()
            draft.nativeOriginalActive = false
        }
        activeQuickShape = null
        quickShapeDraft = null
        isQuickShapeEditing = false
        return
    }

    if (restoreOriginal && draft.nativeOriginalActive) {
        // Keep the original native transaction (including random brush texture, smoothing and taper).
        // Replaying the captured samples here would not reliably reproduce the same pixels.
        quickShapeCommitting = true
        touchEnd()
        runCore(render = false, after = {
            quickShapeCommitting = false
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
            invalidateCanvasFromRender()
        }) {}
        return
    }

    val samples = if (restoreOriginal) {
        draft.original
    } else {
        val dense = QuickShapePressure.stroke(
            draft.original,
            draft.recognizedShape,
            shape,
            perPointPressure = quickShapePerPointPressureEnabled,
        )
        if (dense.size >= 12) dense else {
            val path = QuickShapeGeometry.outline(shape)
            if (path.size < 2 || path.any { !it.x.isFinite() || !it.y.isFinite() }) {
                showActionToast(R.string.quick_shape_failed, R.drawable.ic_line)
                return
            }
            val avgPressure = QuickShapePressure.average(draft.original)
            FloatArray(path.size * 6).also { out ->
                path.forEachIndexed { i, p ->
                    out[i * 6] = p.x; out[i * 6 + 1] = p.y; out[i * 6 + 2] = avgPressure
                    out[i * 6 + 3] = draft.original[3]; out[i * 6 + 4] = draft.original[4]
                }
            }
        }
    }
    if (samples.size < 12) {
        showActionToast(R.string.quick_shape_failed, R.drawable.ic_line)
        return
    }

    quickShapeCommitting = true
    if (draft.nativeOriginalActive) {
        touchCancel()
        draft.nativeOriginalActive = false
    }
    isModified = true
    onPaintingActivity()
    var success = false
    runCore(after = {
        quickShapeCommitting = false
        if (success) {
            totalStrokes++
            strokesSinceLastAutoSave++
            lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
            notifyLayerChanged(forceThumbs = true, immediateRender = true, pixelChanged = true)
            invalidateCanvasFromRender()
        } else {
            showActionToast(R.string.quick_shape_failed, R.drawable.ic_line)
        }
    }) {
        try {
            ReverieCoreBridge.setToolMode(0)
            ReverieCoreBridge.touchStrokeStartWithSensors(
                samples[0].toDouble(), samples[1].toDouble(),
                samples[2].toDouble(), samples[3].toDouble(), samples[4].toDouble(), 0.0
            )
            // A single render-thread operation avoids overflowing the UI stroke queue on long curves.
            val batch = FloatArray(PaintViewModel.STROKE_BATCH_CAPACITY * 6)
            var offset = 6
            while (offset < samples.size) {
                val length = minOf(batch.size, samples.size - offset)
                samples.copyInto(batch, 0, offset, offset + length)
                ReverieCoreBridge.touchStrokeMoveBatch(batch, length / 6)
                offset += length
            }
            ReverieCoreBridge.touchStrokeEnd()
            realInkSink?.onStrokeCommitted()
            success = true

            // Reuse the established stroke event format; no new .revp or replay opcode.
            if (recorder.recording) {
                try {
                    recorder.strokeStart(samples[0], samples[1], samples[2])
                    for (i in 6 until samples.size step 6) {
                        recorder.strokeMove(samples[i], samples[i + 1], samples[i + 2])
                    }
                    recorder.strokeEnd()
                } catch (recErr: Throwable) {
                    android.util.Log.e("ReverieRec", "QuickShape recording stroke failed", recErr)
                }
            }
        } catch (t: Throwable) {
            if (!success) {
                ReverieCoreBridge.touchStrokeCancel()
            }
            android.util.Log.e("ReverieCore", "QuickShape brush commit failed", t)
        }
        scheduleRender(immediate = true)
    }
}

/**
 * 胶囊轴向按钮: 精确摆到指定朝向 (水平 0 / 垂直 90 / 45 度), 不受识别期容差限制。
 * 只改 activeQuickShape, 由编辑器叠加层即时重绘; 提交路径不变。
 */
internal fun PaintViewModel.snapQuickShapeToAngle(targetDeg: Float) {
    if (!targetDeg.isFinite()) return
    val shape = activeQuickShape ?: return
    val updated = QuickShapeGeometry.snappedToAngle(shape, targetDeg)
    if (updated != shape) activeQuickShape = updated
}

/** 胶囊旋转按钮: 逆时针为正, 单位度 */
internal fun PaintViewModel.rotateQuickShapeBy(deltaDeg: Float) {
    if (!deltaDeg.isFinite()) return
    val shape = activeQuickShape ?: return
    activeQuickShape = QuickShapeGeometry.rotatedBy(shape, deltaDeg * (PI.toFloat() / 180f))
}

/** 胶囊缩放按钮: factor > 1 放大 */
internal fun PaintViewModel.scaleQuickShapeBy(factor: Float) {
    if (!factor.isFinite() || factor <= 0f) return
    val shape = activeQuickShape ?: return
    activeQuickShape = QuickShapeGeometry.scaledBy(shape, factor)
}
