/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.GradientPresetManager
import com.reverie.paint.core.GradientState
import com.reverie.paint.model.GradientColorStop
import com.reverie.paint.model.GradientPreset
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import java.util.UUID
import kotlin.math.hypot

private class EditableStop(
    initialPos: Float,
    initialColor: Color,
) {
    var pos by mutableFloatStateOf(initialPos)
    var color by mutableStateOf(initialColor)
}

private val HUE_COLORS = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)

private val QUICK_COLORS = listOf(
    Color.Black,
    Color.White,
    Color(0xFF9E9E9E), // Gray
    Color(0xFFE53935), // Red
    Color(0xFFFF9800), // Orange
    Color(0xFFFFEB3B), // Yellow
    Color(0xFF4CAF50), // Green
    Color(0xFF00BCD4), // Cyan
    Color(0xFF2196F3), // Blue
    Color(0xFF9C27B0), // Purple
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradientPickerDialog(
    gradientState: GradientState,
    initialFgColor: Color,
    initialBgColor: Color,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentEffective = remember {
        gradientState.getEffectiveStops(initialFgColor, initialBgColor)
    }

    val stops = remember {
        mutableStateListOf<EditableStop>().apply {
            currentEffective.forEach {
                add(EditableStop(it.position, Color(it.colorArgb)))
            }
        }
    }

    var selectedStopIndex by remember { mutableIntStateOf(0) }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    var customPresets by remember { mutableStateOf(GradientPresetManager.loadUserPresets(context)) }
    var selectedPresetTab by remember { mutableIntStateOf(0) } // 0: 经典预设, 1: 自定义预设

    val sortedStops = stops.sortedBy { it.pos }
    val colorStopsForBrush = if (sortedStops.size >= 2) {
        sortedStops.map { it.pos.coerceIn(0f, 1f) to it.color }.toTypedArray()
    } else {
        arrayOf(0f to Color.Black, 1f to Color.White)
    }

    fun applyStopsToState() {
        val finalStops = stops.sortedBy { it.pos }.map {
            GradientColorStop(it.pos.coerceIn(0f, 1f), it.color.toArgb())
        }
        gradientState.setCustomStops(finalStops)
    }

    Dialog(onDismissRequest = {
        applyStopsToState()
        onDismiss()
    }) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Morandi.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. 顶部标题栏与关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.gradient_edit_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Morandi.text,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.cancel),
                        tint = Morandi.subText,
                        modifier = Modifier
                            .size(24.dp)
                            .noRippleClickable {
                                applyStopsToState()
                                onDismiss()
                            },
                    )
                }

                // 2. 渐变条全幅预览
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.horizontalGradient(colorStops = colorStopsForBrush))
                        .border(1.dp, Morandi.panelHi, RoundedCornerShape(10.dp)),
                )

                // 3. 多色标滑动轨道 (支持点击空白处添加色标，点击手柄选中调色，拖动手柄移动)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .onGloballyPositioned { trackWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
                        .pointerInput(stops) {
                            detectTapGestures { offset ->
                                val clickPos = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                // 插值计算点击位置的颜色
                                val interpolatedColor = interpolateColorAt(sortedStops, clickPos)
                                val newStop = EditableStop(clickPos, interpolatedColor)
                                stops.add(newStop)
                                selectedStopIndex = stops.lastIndex
                            }
                        },
                ) {
                    // 轨道基线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Morandi.panelHi),
                    )

                    // 色标手柄列表
                    stops.forEachIndexed { idx, stop ->
                        val isSelected = idx == selectedStopIndex
                        Box(
                            modifier = Modifier
                                .offset { IntOffset((stop.pos * trackWidthPx - 12.dp.toPx()).toInt(), 3.dp.toPx().toInt()) }
                                .size(24.dp)
                                .shadow(if (isSelected) 6.dp else 2.dp, CircleShape)
                                .clip(CircleShape)
                                .background(Morandi.panel)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.5.dp,
                                    color = if (isSelected) Morandi.accent else Morandi.panelHi,
                                    shape = CircleShape,
                                )
                                .padding(3.5.dp)
                                .clip(CircleShape)
                                .background(stop.color)
                                .pointerInput(idx) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        selectedStopIndex = idx
                                        var isDrag = false
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (change.pressed) {
                                                val dragDelta = change.position - down.position
                                                if (!isDrag && hypot(dragDelta.x, dragDelta.y) > 4f) {
                                                    isDrag = true
                                                }
                                                if (isDrag) {
                                                    val delta = (change.position.x - change.previousPosition.x) / trackWidthPx
                                                    stop.pos = (stop.pos + delta).coerceIn(0f, 1f)
                                                    change.consume()
                                                }
                                            } else {
                                                selectedStopIndex = idx
                                                break
                                            }
                                        }
                                    }
                                },
                        )
                    }
                }

                // 4. 当前选中色标控制与内嵌调色板
                val currentStop = stops.getOrNull(selectedStopIndex)
                if (currentStop != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 色标信息及动作栏 (位置 / 均匀分布 / 反转 / 删除)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(currentStop.color)
                                        .border(1.5.dp, Morandi.panelHi, CircleShape),
                                )
                                Text(
                                    text = stringResource(R.string.gradient_stop_pos_format, (currentStop.pos * 100).toInt()),
                                    fontSize = 13.sp,
                                    color = Morandi.text,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 均匀分布色标
                                ToolActionButton(
                                    iconRes = R.drawable.ic_distribute,
                                    label = stringResource(R.string.gradient_distribute_stops),
                                    onClick = {
                                        if (stops.size >= 2) {
                                            val sorted = stops.sortedBy { it.pos }
                                            sorted.forEachIndexed { i, s ->
                                                s.pos = i.toFloat() / (sorted.size - 1)
                                            }
                                        }
                                    },
                                )

                                // 反转渐变
                                ToolActionButton(
                                    iconRes = R.drawable.ic_refresh,
                                    label = stringResource(R.string.gradient_reverse_btn),
                                    onClick = {
                                        stops.forEach { it.pos = 1f - it.pos }
                                    },
                                )

                                // 删除色标
                                if (stops.size > 2) {
                                    ToolActionButton(
                                        iconRes = R.drawable.ic_trash,
                                        label = stringResource(R.string.gradient_delete_stop),
                                        danger = true,
                                        onClick = {
                                            if (stops.size > 2 && selectedStopIndex in stops.indices) {
                                                stops.removeAt(selectedStopIndex)
                                                selectedStopIndex = stops.lastIndex.coerceAtLeast(0)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        // 内嵌色彩调节器 (SV 色盘 + 色相滑块 + 常用快速色)
                        InlineStopColorEditor(
                            color = currentStop.color,
                            onColorChanged = { newCol ->
                                currentStop.color = newCol
                            },
                        )
                    }
                }

                // 5. 预设标签切换 (经典预设 / 自定义预设)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.5f))
                        .padding(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedPresetTab == 0) Morandi.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .noRippleClickable { selectedPresetTab = 0 }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.gradient_builtin_presets),
                            fontSize = 13.sp,
                            fontWeight = if (selectedPresetTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedPresetTab == 0) Morandi.accent else Morandi.subText,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedPresetTab == 1) Morandi.accent.copy(alpha = 0.15f) else Color.Transparent)
                            .noRippleClickable { selectedPresetTab = 1 }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.gradient_custom_presets),
                            fontSize = 13.sp,
                            fontWeight = if (selectedPresetTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedPresetTab == 1) Morandi.accent else Morandi.subText,
                        )
                    }
                }

                // 6. 预设网格展示
                if (selectedPresetTab == 0) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 2,
                    ) {
                        GradientPresetManager.BUILTIN_PRESETS.forEach { preset ->
                            val pBrush = Brush.horizontalGradient(
                                colorStops = preset.stops.map { it.position to Color(it.colorArgb) }.toTypedArray(),
                            )
                            val presetName = preset.nameRes?.let { stringResource(it) } ?: preset.nameStr ?: ""
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(pBrush)
                                    .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp))
                                    .clickable {
                                        stops.clear()
                                        preset.stops.forEach {
                                            stops.add(EditableStop(it.position, Color(it.colorArgb)))
                                        }
                                        selectedStopIndex = 0
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = presetName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .shadow(2.dp)
                                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 保存当前渐变为预设按钮
                        ReTextButton(
                            text = stringResource(R.string.gradient_save_preset),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val newPreset = GradientPreset(
                                    id = UUID.randomUUID().toString(),
                                    nameStr = "Custom ${customPresets.size + 1}",
                                    stops = stops.sortedBy { it.pos }.map {
                                        GradientColorStop(it.pos, it.color.toArgb())
                                    },
                                    isBuiltIn = false,
                                )
                                val updated = customPresets + newPreset
                                customPresets = updated
                                GradientPresetManager.saveUserPresets(context, updated)
                            },
                        )

                        if (customPresets.isEmpty()) {
                            Text(
                                text = stringResource(R.string.gradient_custom_presets),
                                color = Morandi.subText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = 2,
                            ) {
                                customPresets.forEach { preset ->
                                    val pBrush = Brush.horizontalGradient(
                                        colorStops = preset.stops.map { it.position to Color(it.colorArgb) }.toTypedArray(),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(pBrush)
                                            .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp))
                                            .clickable {
                                                stops.clear()
                                                preset.stops.forEach {
                                                    stops.add(EditableStop(it.position, Color(it.colorArgb)))
                                                }
                                                selectedStopIndex = 0
                                            }
                                            .padding(horizontal = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = preset.nameStr ?: "",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                        Icon(
                                            painter = painterResource(R.drawable.ic_x),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .noRippleClickable {
                                                    val updated = customPresets.filter { it.id != preset.id }
                                                    customPresets = updated
                                                    GradientPresetManager.saveUserPresets(context, updated)
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 7. 底部确定按钮
                ReTextButton(
                    text = stringResource(R.string.confirm),
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        applyStopsToState()
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * 内嵌式色标调色板 (SV 饱和度/明度 Canvas + 彩虹色相滑块 + 快捷色标)
 */
@Composable
private fun InlineStopColorEditor(
    color: Color,
    onColorChanged: (Color) -> Unit,
) {
    val hsv = remember(color) {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            ),
            arr
        )
        arr
    }
    var hue by remember(color) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(color) { mutableFloatStateOf(hsv[1]) }
    var valB by remember(color) { mutableFloatStateOf(hsv[2]) }

    val emitColor: (Float, Float, Float) -> Unit = { newHue, newSat, newVal ->
        hue = newHue
        sat = newSat
        valB = newVal
        val cInt = android.graphics.Color.HSVToColor(floatArrayOf(newHue, newSat, newVal))
        onColorChanged(Color(cInt))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 2D 饱和度 / 明度区域 (SV Canvas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp))
                .pointerInput(hue) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val newSat = (down.position.x / w).coerceIn(0f, 1f)
                        val newVal = (1f - (down.position.y / h)).coerceIn(0f, 1f)
                        emitColor(hue, newSat, newVal)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val s = (change.position.x / w).coerceIn(0f, 1f)
                            val v = (1f - (change.position.y / h)).coerceIn(0f, 1f)
                            emitColor(hue, s, v)
                            change.consume()
                        }
                    }
                }
        ) {
            val pureHueColor = remember(hue) {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Layer 1: 白 -> 纯色相
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White, pureHueColor),
                        startX = 0f,
                        endX = w
                    ),
                    size = size
                )
                // Layer 2: 透明 -> 黑
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = h
                    ),
                    size = size
                )
                // 光标指示圈
                val cursorOffset = Offset(sat * w, (1f - valB) * h)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = 7.dp.toPx(),
                    center = cursorOffset,
                    style = Stroke(1.5.dp.toPx())
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.5.dp.toPx(),
                    center = cursorOffset,
                    style = Stroke(2.dp.toPx())
                )
            }
        }

        // 彩虹色相滑块 (Hue Slider)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(HUE_COLORS))
                .border(1.dp, Morandi.panelHi, RoundedCornerShape(6.dp))
                .pointerInput(sat, valB) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val newHue = ((down.position.x / w).coerceIn(0f, 1f) * 360f).coerceIn(0f, 360f)
                        emitColor(newHue, sat, valB)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val h = ((change.position.x / w).coerceIn(0f, 1f) * 360f).coerceIn(0f, 360f)
                            emitColor(h, sat, valB)
                            change.consume()
                        }
                    }
                }
        ) {
            // 色相滑块手柄指示线
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = (hue / 360f).coerceIn(0f, 1f) * size.width
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = Offset(cx, size.height / 2f),
                    style = Stroke(2.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = 10.5.dp.toPx(),
                    center = Offset(cx, size.height / 2f),
                    style = Stroke(1.dp.toPx())
                )
            }
        }

        // 快速色块行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QUICK_COLORS.forEach { qc ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(qc)
                        .border(1.dp, Morandi.panelHi, CircleShape)
                        .clickable { onColorChanged(qc) }
                )
            }
        }
    }
}

/**
 * 辅助函数：根据多色标列表在线性空间插值计算位置 pos 的颜色
 */
private fun interpolateColorAt(sortedStops: List<EditableStop>, pos: Float): Color {
    if (sortedStops.isEmpty()) return Color.White
    if (pos <= sortedStops.first().pos) return sortedStops.first().color
    if (pos >= sortedStops.last().pos) return sortedStops.last().color

    for (i in 0 until sortedStops.size - 1) {
        val s1 = sortedStops[i]
        val s2 = sortedStops[i + 1]
        if (pos in s1.pos..s2.pos) {
            val span = (s2.pos - s1.pos).coerceAtLeast(0.0001f)
            val factor = ((pos - s1.pos) / span).coerceIn(0f, 1f)
            return Color(
                red = s1.color.red + (s2.color.red - s1.color.red) * factor,
                green = s1.color.green + (s2.color.green - s1.color.green) * factor,
                blue = s1.color.blue + (s2.color.blue - s1.color.blue) * factor,
                alpha = s1.color.alpha + (s2.color.alpha - s1.color.alpha) * factor,
            )
        }
    }
    return sortedStops.last().color
}
