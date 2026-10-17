/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Interaction3DMode
import com.reverie.paint.model.RenderMode3D
import com.reverie.paint.ui.theme.Morandi

/**
 * 3D 参考图层专属视口悬浮微型控制条
 */
@Composable
fun Floating3DToolbar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val state = vm.currentLayer3DState ?: return

    AnimatedVisibility(
        visible = vm.isCurrentLayer3D,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.15f))
                .clip(RoundedCornerShape(24.dp))
                .background(Morandi.panel.copy(alpha = 0.94f))
                .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 3D 标牌
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.accent.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.layer_3d_tag),
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // 1. 手势操作模式切换 (旋转 / 平移 / 打光)
                ToolbarModeButton(
                    icon = R.drawable.ic_rotate_cw,
                    label = stringResource(R.string.layer_3d_mode_rotate),
                    active = vm.active3DInteractionMode == Interaction3DMode.ROTATE,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.ROTATE },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_move,
                    label = stringResource(R.string.layer_3d_mode_pan),
                    active = vm.active3DInteractionMode == Interaction3DMode.PAN,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.PAN },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_sun,
                    label = stringResource(R.string.layer_3d_mode_light),
                    active = vm.active3DInteractionMode == Interaction3DMode.LIGHT,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.LIGHT },
                )

                // 竖直分割线
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(Morandi.border.copy(alpha = 0.4f))
                        .padding(horizontal = 2.dp)
                )

                // 2. 着色风格切换 (素模 / 光影 / 线框)
                ToolbarModeButton(
                    icon = R.drawable.ic_circle,
                    label = stringResource(R.string.layer_3d_style_clay),
                    active = state.renderMode == RenderMode3D.CLAY,
                    onClick = { vm.set3DRenderMode(RenderMode3D.CLAY) },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_contrast,
                    label = stringResource(R.string.layer_3d_style_shading),
                    active = state.renderMode == RenderMode3D.SHADING,
                    onClick = { vm.set3DRenderMode(RenderMode3D.SHADING) },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_grid,
                    label = stringResource(R.string.layer_3d_style_wireframe),
                    active = state.renderMode == RenderMode3D.WIREFRAME,
                    onClick = { vm.set3DRenderMode(RenderMode3D.WIREFRAME) },
                )

                // 竖直分割线
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(Morandi.border.copy(alpha = 0.4f))
                        .padding(horizontal = 2.dp)
                )

                // 3. 辅助功能按钮 (复位 / 提取线稿 / 栅格化)
                ToolbarModeButton(
                    icon = R.drawable.ic_refresh,
                    label = stringResource(R.string.layer_3d_mode_reset),
                    active = false,
                    onClick = { vm.reset3DView() },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_magicwand,
                    label = stringResource(R.string.layer_3d_extract_outline),
                    active = false,
                    onClick = { vm.extract3DOutlines(state.layerId) },
                )
                ToolbarModeButton(
                    icon = R.drawable.ic_layers,
                    label = stringResource(R.string.layer_3d_rasterize),
                    active = false,
                    onClick = { vm.rasterize3DLayer(state.layerId) },
                )
            }
        }
    }
}

@Composable
private fun ToolbarModeButton(
    resId: Int? = null,
    icon: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent
    val tint = if (active) Morandi.accent else Morandi.subText

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
