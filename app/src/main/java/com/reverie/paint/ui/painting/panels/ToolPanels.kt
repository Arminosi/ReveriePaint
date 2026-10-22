/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi

@Composable
private fun getGradientTypeOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_grad_linear, androidx.compose.ui.res.stringResource(R.string.gradient_type_linear)),
    ToolDropdownItemData(1, R.drawable.ic_grad_radial, androidx.compose.ui.res.stringResource(R.string.gradient_type_radial)),
    ToolDropdownItemData(2, R.drawable.ic_grad_angle, androidx.compose.ui.res.stringResource(R.string.gradient_type_angle)),
    ToolDropdownItemData(3, R.drawable.ic_grad_symmetric, androidx.compose.ui.res.stringResource(R.string.gradient_type_symmetric)),
    ToolDropdownItemData(4, R.drawable.ic_grad_diamond, androidx.compose.ui.res.stringResource(R.string.gradient_type_diamond)),
)

@Composable
private fun getGradientRepeatOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_repeat_none, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_none)),
    ToolDropdownItemData(1, R.drawable.ic_repeat_loop, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_loop)),
    ToolDropdownItemData(2, R.drawable.ic_repeat_mirror, androidx.compose.ui.res.stringResource(R.string.gradient_repeat_mirror)),
)

@Composable
private fun getGradientBlendOptions(): List<ToolDropdownItemData<String>> = listOf(
    ToolDropdownItemData("normal", R.drawable.ic_blend_normal, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_normal)),
    ToolDropdownItemData("multiply", R.drawable.ic_blend_multiply, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_multiply)),
    ToolDropdownItemData("screen", R.drawable.ic_blend_screen, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_screen)),
    ToolDropdownItemData("overlay", R.drawable.ic_layers, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_overlay)),
    ToolDropdownItemData("soft_light", R.drawable.ic_blend_softlight, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_soft_light)),
    ToolDropdownItemData("hard_light", R.drawable.ic_blend_hardlight, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_hard_light)),
    ToolDropdownItemData("dodge", R.drawable.ic_blend_dodge, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_dodge)),
    ToolDropdownItemData("burn", R.drawable.ic_blend_burn, androidx.compose.ui.res.stringResource(R.string.brush_studio_blend_burn)),
)

@Composable
private fun getFillSampleOptions(): List<ToolDropdownItemData<Int>> = listOf(
    ToolDropdownItemData(0, R.drawable.ic_rect, androidx.compose.ui.res.stringResource(R.string.selection_current_layer)),
    ToolDropdownItemData(1, R.drawable.ic_layers, androidx.compose.ui.res.stringResource(R.string.selection_all_layers)),
)

/** Gradient tool options: type, repeat, reverse, multi-stops, blend mode, opacity, handles confirm/cancel */
@Composable
fun GradientPanel(
    vm: PaintViewModel,
    type: Int,
    onType: (Int) -> Unit,
    repeat: Int = vm.gradientRepeat,
    onRepeat: (Int) -> Unit = { vm.updateGradientRepeat(it) },
    reverse: Boolean = vm.gradientReverse,
    onReverse: (Boolean) -> Unit = { vm.updateGradientReverse(it) },
    hazeState: HazeState? = null,
) {
    val state = vm.gradientState
    var showPresetDialog by remember { mutableStateOf(false) }
    var propsOpen by remember { mutableStateOf(false) }

    val fgColor = remember(vm.brushColor) {
        try { Color(android.graphics.Color.parseColor(vm.brushColor)) } catch (_: Exception) { Color.Black }
    }
    val bgColor = remember(vm.brushSecondaryColor) {
        try { Color(android.graphics.Color.parseColor(vm.brushSecondaryColor)) } catch (_: Exception) { Color.White }
    }

    val gradientTypeOptions = getGradientTypeOptions()
    val gradientRepeatOptions = getGradientRepeatOptions()
    val gradientBlendOptions = getGradientBlendOptions()

    if (showPresetDialog) {
        GradientPickerDialog(
            gradientState = state,
            initialFgColor = fgColor,
            initialBgColor = bgColor,
            onDismiss = { showPresetDialog = false },
        )
    }

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(if (propsOpen) 320.dp else androidx.compose.ui.unit.Dp.Unspecified),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 渐变色预览胶囊 (点击打开色板库弹窗)
                val effectiveStops = state.getEffectiveStops(fgColor, bgColor)
                val brushColors = if (effectiveStops.size >= 2) {
                    effectiveStops.map { it.position to Color(it.colorArgb) }.toTypedArray()
                } else {
                    arrayOf(0f to Color.Black, 1f to Color.White)
                }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .width(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(colorStops = brushColors))
                        .border(1.dp, Morandi.panelHi, RoundedCornerShape(8.dp))
                        .noRippleClickable { showPresetDialog = true },
                )

                // 动态快捷槽: 前->透
                ToolFloatChip(
                    label = androidx.compose.ui.res.stringResource(R.string.gradient_dynamic_fg_trans),
                    selected = state.isDynamicFgTrans,
                    onClick = { state.setDynamicFgTrans() },
                )

                // 动态快捷槽: 前->背
                ToolFloatChip(
                    label = androidx.compose.ui.res.stringResource(R.string.gradient_dynamic_fg_bg),
                    selected = state.isDynamicFgBg,
                    onClick = { state.setDynamicFgBg() },
                )

                // 渐变类型下拉 (5种类型)
                ToolBubbleDropdown(
                    items = gradientTypeOptions,
                    selected = type,
                    onSelect = onType,
                )

                // 重复模式下拉
                ToolBubbleDropdown(
                    items = gradientRepeatOptions,
                    selected = repeat,
                    onSelect = onRepeat,
                )

                // 反向按钮
                ToolActionButton(
                    iconRes = R.drawable.ic_refresh,
                    label = androidx.compose.ui.res.stringResource(R.string.gradient_reverse),
                    active = reverse,
                    onClick = { onReverse(!reverse) },
                )

                // 高级属性展开 (Sliders)
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) androidx.compose.ui.res.stringResource(R.string.selection_collapse) else androidx.compose.ui.res.stringResource(R.string.selection_props),
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )

                // 确认 / 取消动作 (仅当手柄处于活跃微调状态时显示)
                if (state.active) {
                    ToolActionButton(
                        iconRes = R.drawable.ic_check,
                        label = androidx.compose.ui.res.stringResource(R.string.confirm),
                        primary = true,
                        onClick = { vm.commitActiveGradient() },
                    )
                    ToolActionButton(
                        iconRes = R.drawable.ic_x,
                        label = androidx.compose.ui.res.stringResource(R.string.cancel),
                        danger = true,
                        onClick = { vm.cancelActiveGradient() },
                    )
                }
            }

            // 展开高级属性 (不透明度、混合模式、防断层仿色抖动)
            if (propsOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolBubbleDropdown(
                        items = gradientBlendOptions,
                        selected = state.blendMode,
                        onSelect = { state.blendMode = it },
                    )

                    ToolFloatChip(
                        label = androidx.compose.ui.res.stringResource(R.string.gradient_dither),
                        selected = state.dither,
                        onClick = { state.dither = !state.dither },
                    )
                }

                ToolFloatSlider(
                    label = androidx.compose.ui.res.stringResource(R.string.gradient_opacity),
                    valueText = "${(state.opacity * 100).toInt()}%",
                    range = 1f..100f,
                    value = (state.opacity * 100f).coerceIn(1f, 100f),
                    onValue = { state.opacity = it / 100f },
                )
            }
        }
    }
}

/** Fill tool options: color tolerance (threshold), sample layers, expand, feather, and close gap */
@Composable
fun FillPanel(
    vm: PaintViewModel,
    tolerance: Int = vm.fillTolerance,
    onTolerance: (Int) -> Unit = { vm.updateFillTolerance(it) },
    sampleLayers: Int = vm.fillSampleLayers,
    onSampleLayers: (Int) -> Unit = { vm.updateFillSampleLayers(it) },
    expand: Int = vm.fillExpand,
    onExpand: (Int) -> Unit = { vm.updateFillExpand(it) },
    feather: Int = vm.fillFeather,
    onFeather: (Int) -> Unit = { vm.updateFillFeather(it) },
    closeGap: Int = vm.fillCloseGap,
    onCloseGap: (Int) -> Unit = { vm.updateFillCloseGap(it) },
    hazeState: HazeState? = null,
) {
    var propsOpen by remember { mutableStateOf(false) }
    var showPatterns by remember { mutableStateOf(false) }
    if (showPatterns) PatternPickerDialog(
        onSelect = { vm.fillPattern = it },
        onDismiss = { showPatterns = false },
        onUseColor = { vm.fillPattern = null },
    )
    val fillSampleOptions = getFillSampleOptions()

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(320.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolActionButton(
                    iconRes = R.drawable.ic_grid,
                    label = androidx.compose.ui.res.stringResource(R.string.pattern_title),
                    active = vm.fillPattern != null,
                    onClick = { showPatterns = true },
                )
                ToolBubbleDropdown(
                    items = fillSampleOptions,
                    selected = sampleLayers,
                    labelOverride = if (sampleLayers == 0) androidx.compose.ui.res.stringResource(R.string.fill_sample_current_short) else androidx.compose.ui.res.stringResource(R.string.fill_sample_all_short),
                    onSelect = onSampleLayers,
                    active = true,
                )
                Box(modifier = Modifier.weight(1f)) {
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_tolerance),
                        valueText = "$tolerance",
                        range = 1f..100f,
                        value = tolerance.toFloat().coerceIn(1f, 100f),
                        onValue = { onTolerance(it.toInt()) },
                    )
                }
                ToolActionButton(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) androidx.compose.ui.res.stringResource(R.string.selection_collapse) else androidx.compose.ui.res.stringResource(R.string.selection_props),
                    active = propsOpen,
                    onClick = { propsOpen = !propsOpen },
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    if (vm.fillPattern != null) PatternFillProperties(vm)
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_expand),
                        valueText = "${expand}px",
                        range = -16f..32f,
                        value = expand.toFloat().coerceIn(-16f, 32f),
                        onValue = { onExpand(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_feather),
                        valueText = "${feather}px",
                        range = 0f..32f,
                        value = feather.toFloat().coerceIn(0f, 32f),
                        onValue = { onFeather(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                    ToolFloatSlider(
                        label = androidx.compose.ui.res.stringResource(R.string.fill_close_gap),
                        valueText = "${closeGap}px",
                        range = 0f..16f,
                        value = closeGap.toFloat().coerceIn(0f, 16f),
                        onValue = { onCloseGap(it.toInt()) },
                        labelWidth = 48.dp,
                    )
                }
            }
        }
    }
}

/** Liquify panel moved to its own file */
@Composable
fun LiquifyPanelStub() {}
