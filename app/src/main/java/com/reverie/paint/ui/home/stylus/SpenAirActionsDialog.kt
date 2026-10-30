/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.openSpenAirSystemSettings
import com.reverie.paint.core.resetSpenAirBindings
import com.reverie.paint.core.spenAirBindingFor
import com.reverie.paint.core.updateSpenAirBinding
import com.reverie.paint.model.SpenAirGesture
import com.reverie.paint.ui.theme.Theme

private val SPEN_AIR_ACTION_IDS = listOf(
    "toggle_eraser",
    "undo",
    "redo",
    "tool_picker",
    "toggle_last_tool",
    "tool_color",
    "brush_size_inc",
    "brush_size_dec",
    "brush_opacity_inc",
    "brush_opacity_dec",
    "zoom_in",
    "zoom_out",
    "flip_canvas",
    "layer_prev",
    "layer_next",
    "toggle_quick_menu",
    "toggle_ref_window",
    "none",
)

private fun actionLabelRes(actionId: String): Int = when (actionId) {
    "toggle_eraser" -> R.string.stylus_action_switch_brush_eraser
    "undo" -> R.string.stylus_action_undo
    "redo" -> R.string.stylus_action_redo
    "tool_picker" -> R.string.stylus_action_eyedropper
    "toggle_last_tool" -> R.string.stylus_action_prev_tool
    "tool_color" -> R.string.stylus_action_quick_palette
    "brush_size_inc" -> R.string.stylus_action_brush_size_inc
    "brush_size_dec" -> R.string.stylus_action_brush_size_dec
    "brush_opacity_inc" -> R.string.stylus_action_brush_opacity_inc
    "brush_opacity_dec" -> R.string.stylus_action_brush_opacity_dec
    "zoom_in" -> R.string.stylus_action_zoom_in
    "zoom_out" -> R.string.stylus_action_zoom_out
    "flip_canvas" -> R.string.stylus_action_flip
    "layer_prev" -> R.string.stylus_action_layer_prev
    "layer_next" -> R.string.stylus_action_layer_next
    "toggle_quick_menu" -> R.string.stylus_action_quick_menu
    "toggle_ref_window" -> R.string.stylus_action_ref_window
    "none" -> R.string.stylus_action_none
    else -> R.string.stylus_action_none
}

private fun gestureLabelRes(gesture: SpenAirGesture): Int = when (gesture) {
    SpenAirGesture.CLICK -> R.string.spen_gesture_click
    SpenAirGesture.DOUBLE_CLICK -> R.string.spen_gesture_double_click
    SpenAirGesture.SWIPE_LEFT -> R.string.spen_gesture_swipe_left
    SpenAirGesture.SWIPE_RIGHT -> R.string.spen_gesture_swipe_right
    SpenAirGesture.SWIPE_UP -> R.string.spen_gesture_swipe_up
    SpenAirGesture.SWIPE_DOWN -> R.string.spen_gesture_swipe_down
    SpenAirGesture.CIRCLE_CW -> R.string.spen_gesture_circle_cw
    SpenAirGesture.CIRCLE_CCW -> R.string.spen_gesture_circle_ccw
}

@Composable
internal fun SpenAirActionsDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val actionLabels = remember {
        SPEN_AIR_ACTION_IDS.map { actionLabelRes(it) }
    }
    val actionTexts = actionLabels.map { stringResource(it) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                StylusDialogHeader(
                    badgeText = "Air",
                    title = stringResource(R.string.spen_air_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(12.dp))

                // 系统设置入口
                StylusDialogCard {
                    StylusDialogNavItem(
                        iconRes = R.drawable.ic_settings,
                        title = stringResource(R.string.spen_air_open_system_settings),
                        onClick = { openSpenAirSystemSettings(context) },
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 提示卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.accent.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_help_circle),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.spen_air_hint),
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(6.dp))

                // 动作映射
                StylusDialogSectionTitle(stringResource(R.string.spen_air_subtitle))
                StylusDialogCard {
                    val gestures = SpenAirGesture.entries
                    gestures.forEachIndexed { index, gesture ->
                        if (index > 0) {
                            StylusDialogDivider()
                        }
                        val currentActionId = vm.spenAirBindingFor(gesture)
                        val currentTitle = stringResource(actionLabelRes(currentActionId))
                        StylusDialogDropdownItem(
                            title = stringResource(gestureLabelRes(gesture)),
                            currentText = currentTitle,
                            options = actionTexts,
                            onSelect = { idx ->
                                vm.updateSpenAirBinding(gesture, SPEN_AIR_ACTION_IDS[idx])
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 恢复默认映射
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.panelHi)
                        .clickable { vm.resetSpenAirBindings() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.spen_air_reset_default),
                        color = colors.subText,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(10.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
