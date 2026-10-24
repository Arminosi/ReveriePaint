/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.quickShapePreviewWidth
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.model.QuickShapeViewportGesture
import com.reverie.paint.ui.painting.panels.QuickShapeTopBar
import com.reverie.paint.ui.theme.Morandi

/** Modal editor prevents document/tool changes under an unfinished brush stroke. */
@Composable
internal fun QuickShapeEditor(
    vm: PaintViewModel,
    zoom: State<Float>, rotation: State<Float>, panX: State<Float>, panY: State<Float>,
    fitScale: Float,
    onViewTransform: (zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit = { _, _, _, _ -> },
    topPadding: androidx.compose.ui.unit.Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val shape = vm.activeQuickShape ?: return
    var reshapePointer by remember { mutableStateOf<PointerId?>(null) }
    val transform = remember { CanvasViewTransform() }
    val viewportGesture = remember { QuickShapeViewportGesture() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale by rememberUpdatedState(fitScale)
    val density = LocalDensity.current.density
    val outline = remember(shape) { QuickShapeGeometry.outline(shape) }
    val handles = remember(shape, vm.quickShapeBoxHandlesEnabled) {
        QuickShapeGeometry.handles(shape, vm.quickShapeBoxHandlesEnabled)
    }
    val outlineColor = remember(vm.brushColor) {
        val parsed = runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }.getOrDefault(Morandi.accent)
        parsed.copy(alpha = 0.55f)
    }
    val path = remember { Path() }
    val point = remember { FloatArray(2) }
    fun updateTransform() = transform.update(size.width, size.height, panX.value, panY.value,
        zoom.value, scale, rotation.value, vm.renderW, vm.renderH, vm.docWidth, vm.docHeight,
        vm.viewFlipX, vm.viewFlipY)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(vm) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                updateTransform()
                val original = vm.activeQuickShape ?: return@awaitEachGesture
                transform.screenToDoc(down.position.x, down.position.y, point)
                val from = Point2D(point[0], point[1])
                var handle = -1
                var nearest = 24f * density
                QuickShapeGeometry.handles(original, vm.quickShapeBoxHandlesEnabled).forEachIndexed { i, p ->
                    transform.docToScreen(p.x, p.y, point)
                    val distance = (Offset(point[0], point[1]) - down.position).getDistance()
                    if (distance < nearest) { nearest = distance; handle = i }
                }
                val reshape = reshapePointer != null
                var cancelled = vm.quickShapeCommitting
                // 编辑器是 zIndex 最高的全屏 overlay, 双指事件到不了 CanvasTouchView。
                // 单指仍然抓手柄/拖形状; 双指改为驱动画布平移/缩放, 避免控制点被
                // 顶部胶囊盖住时连画布都无法移动 (编辑期间画布不再被锁死)。
                var twoFinger = false
                viewportGesture.reset()
                do {
                    val event = awaitPointerEvent()
                    var first: PointerInputChange? = null
                    var second: PointerInputChange? = null
                    // The held contour modifier is not a viewport finger; avoid a per-event List.
                    for (index in event.changes.indices) {
                        val pointer = event.changes[index]
                        if (!pointer.pressed || pointer.id == reshapePointer) continue
                        if (first == null) first = pointer else { second = pointer; break }
                    }
                    if (first != null && second != null) {
                        twoFinger = true
                        cancelled = true
                        if (viewportGesture.update(
                                first.id.value, first.position.x, first.position.y,
                                second.id.value, second.position.x, second.position.y,
                                size.width, size.height, zoom.value, panX.value, panY.value,
                                vm.isViewTransformLocked,
                            )) {
                            onViewTransform(
                                viewportGesture.zoom, rotation.value, viewportGesture.panX, viewportGesture.panY,
                            )
                            updateTransform()
                        }
                    } else {
                        viewportGesture.reset()
                    }
                    if (!twoFinger) {
                        if (event.changes.count { it.pressed && it.id != reshapePointer } > 1 ||
                            (reshape && reshapePointer == null)) cancelled = true
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (!cancelled && !vm.quickShapeCommitting && change != null &&
                            (change.pressed || change.previousPressed)) {
                            transform.screenToDoc(change.position.x, change.position.y, point)
                            vm.activeQuickShape = QuickShapeGeometry.drag(original, handle, from, Point2D(point[0], point[1]),
                                vm.quickShapeAngleSnapEnabled, reshape, vm.quickShapeBoxHandlesEnabled,
                                original.contourCurved)
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })

            }
        }) {
            updateTransform()

            val outlineWidth = (vm.quickShapePreviewWidth * zoom.value * fitScale).coerceAtLeast(1f)

            // 顶层半透明等宽形状框：无论开启逐点压感与否，指示框均严格等宽、无压感、半透明
            path.reset()
            outline.forEachIndexed { i, p ->
                transform.docToScreen(p.x, p.y, point)
                if (i == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
            }
            drawPath(
                path = path,
                color = outlineColor,
                style = Stroke(
                    width = outlineWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // 控制点手柄
            handles.forEachIndexed { i, p ->
                transform.docToScreen(p.x, p.y, point)
                val pos = Offset(point[0], point[1])
                drawCircle(Morandi.panel.copy(alpha = 0.75f), 7f * density, pos)
                drawCircle(Morandi.accent.copy(alpha = 0.85f), 7f * density, pos, style = Stroke(1.5f * density))
                if (i == QuickShapeGeometry.rotationHandle(shape, vm.quickShapeBoxHandlesEnabled)) {
                    drawCircle(Morandi.accent, 3f * density, pos)
                }
            }
        }
        QuickShapeTopBar(vm, Modifier.align(Alignment.TopCenter).padding(top = topPadding, start = 12.dp, end = 12.dp),
            reshapeHeld = reshapePointer != null, onReshapePointer = { reshapePointer = it })
    }
}
