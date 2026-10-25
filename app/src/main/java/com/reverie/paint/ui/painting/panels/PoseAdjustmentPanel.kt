/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.reset3DJointAngles
import com.reverie.paint.core.set3DJointAngle
import com.reverie.paint.core.set3DPosePreset
import com.reverie.paint.model.Model3DState
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

private data class JointDef(
    val key: String,
    val titleRes: Int,
    val min: Float,
    val max: Float,
)

/**
 * 3D 人偶与模型专属骨骼关节姿态调节面板
 */
@Composable
fun PoseAdjustmentPanel(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val state = vm.currentLayer3DState ?: run {
        onDismiss()
        return
    }

    val config = LocalConfiguration.current
    val isPhone = config.screenWidthDp < 600 || config.screenWidthDp < config.screenHeightDp
    val isCat = state.modelType.equals("cat", ignoreCase = true)

    val presets = if (isCat) {
        listOf(
            "stand" to R.string.layer_3d_cat_stand,
            "sit" to R.string.layer_3d_cat_sit,
            "loaf" to R.string.layer_3d_cat_loaf,
            "stretch" to R.string.layer_3d_cat_stretch,
        )
    } else {
        listOf(
            "stand" to R.string.layer_3d_pose_stand,
            "sit" to R.string.layer_3d_pose_sit,
            "action" to R.string.layer_3d_pose_action,
            "crossed" to R.string.layer_3d_pose_crossed,
            "hips" to R.string.layer_3d_pose_hips,
            "kneel" to R.string.layer_3d_pose_kneel,
        )
    }

    val jointDefs = if (isCat) {
        listOf(
            JointDef("cat_head_pitch", R.string.layer_3d_joint_cat_head_pitch, -35f, 35f),
            JointDef("cat_head_yaw", R.string.layer_3d_joint_cat_head_yaw, -45f, 45f),
            JointDef("cat_spine_arch", R.string.layer_3d_joint_cat_spine, -30f, 40f),
            JointDef("cat_fl_leg", R.string.layer_3d_joint_cat_fl_leg, -60f, 60f),
            JointDef("cat_fr_leg", R.string.layer_3d_joint_cat_fr_leg, -60f, 60f),
            JointDef("cat_hl_leg", R.string.layer_3d_joint_cat_hl_leg, -70f, 60f),
            JointDef("cat_hr_leg", R.string.layer_3d_joint_cat_hr_leg, -70f, 60f),
            JointDef("cat_tail_pitch", R.string.layer_3d_joint_cat_tail_pitch, -70f, 70f),
            JointDef("cat_tail_yaw", R.string.layer_3d_joint_cat_tail_yaw, -60f, 60f),
        )
    } else {
        listOf(
            JointDef("head_pitch", R.string.layer_3d_joint_head_pitch, -45f, 45f),
            JointDef("head_yaw", R.string.layer_3d_joint_head_yaw, -60f, 60f),
            JointDef("spine_pitch", R.string.layer_3d_joint_spine_pitch, -30f, 40f),
            JointDef("spine_yaw", R.string.layer_3d_joint_spine_yaw, -45f, 45f),
            JointDef("l_arm_pitch", R.string.layer_3d_joint_l_arm_pitch, -90f, 90f),
            JointDef("l_arm_yaw", R.string.layer_3d_joint_l_arm_yaw, -90f, 90f),
            JointDef("l_elbow", R.string.layer_3d_joint_l_elbow, 0f, 140f),
            JointDef("r_arm_pitch", R.string.layer_3d_joint_r_arm_pitch, -90f, 90f),
            JointDef("r_arm_yaw", R.string.layer_3d_joint_r_arm_yaw, -90f, 90f),
            JointDef("r_elbow", R.string.layer_3d_joint_r_elbow, 0f, 140f),
            JointDef("l_thigh_pitch", R.string.layer_3d_joint_l_thigh_pitch, -90f, 60f),
            JointDef("l_thigh_yaw", R.string.layer_3d_joint_l_thigh_yaw, -45f, 45f),
            JointDef("l_knee", R.string.layer_3d_joint_l_knee, 0f, 140f),
            JointDef("r_thigh_pitch", R.string.layer_3d_joint_r_thigh_pitch, -90f, 60f),
            JointDef("r_thigh_yaw", R.string.layer_3d_joint_r_thigh_yaw, -45f, 45f),
            JointDef("r_knee", R.string.layer_3d_joint_r_knee, 0f, 140f),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = if (isPhone) Alignment.BottomCenter else Alignment.Center,
        ) {
            val sheetShape = if (isPhone) {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            } else {
                RoundedCornerShape(20.dp)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(
                        if (isPhone) {
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        } else {
                            Modifier
                                .widthIn(max = 460.dp)
                                .wrapContentHeight()
                        }
                    )
                    .clickable(enabled = false) {}
                    .shadow(16.dp, sheetShape, ambientColor = Color.Black.copy(alpha = 0.3f))
                    .clip(sheetShape)
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border.copy(alpha = 0.4f), sheetShape)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
            ) {
                // 顶部胶囊抓手
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Morandi.icon.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 顶部标题与重置按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.layer_3d_pose_rigging),
                            color = Morandi.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.layer_3d_pose_joints),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 重置微调按钮
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Morandi.panelHi)
                                .clickable { vm.reset3DJointAngles() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.layer_3d_pose_reset_joints),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // 关闭按钮
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Morandi.panelHi.copy(alpha = 0.5f))
                                .clickable(onClick = onDismiss),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_x),
                                contentDescription = stringResource(R.string.common_close),
                                tint = Morandi.subText,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 1. 快速姿态预设胶囊栏 (Preset Poses)
                Text(
                    text = stringResource(R.string.layer_3d_pose_presets),
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(presets) { (presetKey, titleRes) ->
                        val isSelected = state.posePreset.equals(presetKey, ignoreCase = true)
                        val bg = if (isSelected) Morandi.accent else Morandi.panelHi
                        val txtColor = if (isSelected) Color.White else Morandi.text
                        val borderMod = if (isSelected) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg)
                                .then(borderMod)
                                .clickable { vm.set3DPosePreset(presetKey) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(titleRes),
                                color = txtColor,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. 骨骼关节点微调滑块列表
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                ) {
                    items(jointDefs) { joint ->
                        val angle = state.jointAngles[joint.key] ?: 0f
                        val angleFrac = ((angle - joint.min) / (joint.max - joint.min)).coerceIn(0f, 1f)
                        val sign = if (angle > 0) "+" else ""

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Morandi.panelHi.copy(alpha = 0.4f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(joint.titleRes),
                                    color = Morandi.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { vm.set3DJointAngle(joint.key, 0f) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$sign${angle.roundToInt()}°",
                                        color = if (kotlin.math.abs(angle) > 0.01f) Morandi.accent else Morandi.subText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            ReSlider(
                                value = angleFrac,
                                onValue = { frac ->
                                    val newAngle = joint.min + frac * (joint.max - joint.min)
                                    vm.set3DJointAngle(joint.key, newAngle)
                                },
                                height = 18,
                            )
                        }
                    }
                }
            }
        }
    }
}
