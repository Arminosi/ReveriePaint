/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.LocalBackupStatus
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.ConflictResolution
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LocalBackupExportDialog(
    vm: PaintViewModel,
    onConfirmExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val state = vm.localBackupState
    val context = LocalContext.current

    val estimates = remember(context) {
        BackupCategory.values().associateWith { LocalBackupEngine.estimateCategory(context, it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.backup_export_dialog_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                    )
                    val allSelected = state.selectedExportCategories.size == BackupCategory.values().size
                    Text(
                        text = stringResource(if (allSelected) R.string.backup_deselect_all else R.string.backup_select_all),
                        fontSize = 13.sp,
                        color = colors.accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                state.selectedExportCategories = if (allSelected) {
                                    emptySet()
                                } else {
                                    BackupCategory.values().toSet()
                                }
                            }
                            .padding(4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.backup_export_dialog_desc),
                    fontSize = 13.sp,
                    color = colors.subText,
                    lineHeight = 18.sp,
                )

                Spacer(Modifier.height(16.dp))

                for (cat in BackupCategory.values()) {
                    val isChecked = state.selectedExportCategories.contains(cat)
                    val estimate = estimates[cat]
                    BackupCategoryItemRow(
                        category = cat,
                        isChecked = isChecked,
                        estimateText = estimate?.description ?: "",
                        onClick = {
                            state.selectedExportCategories = if (isChecked) {
                                state.selectedExportCategories - cat
                            } else {
                                state.selectedExportCategories + cat
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        textColor = colors.subText,
                    )
                    Spacer(Modifier.width(10.dp))
                    ReTextButton(
                        text = stringResource(R.string.backup_export_btn),
                        onClick = onConfirmExport,
                        primary = true,
                        enabled = state.selectedExportCategories.isNotEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalBackupRestoreDialog(
    vm: PaintViewModel,
    onConfirmRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val state = vm.localBackupState
    val inspection = state.inspectionResult ?: return

    val availableCategories = remember(inspection) {
        inspection.manifest.categories.mapNotNull { BackupCategory.fromIdOrNull(it) }
    }

    val dateFormatted = remember(inspection.manifest.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(inspection.manifest.createdAt))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.backup_restore_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.backup_restore_dialog_desc),
                    fontSize = 13.sp,
                    color = colors.subText,
                )

                Spacer(Modifier.height(12.dp))

                // 备份文件信息卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.panelHi.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.backup_meta_created_at, dateFormatted),
                        fontSize = 12.sp,
                        color = colors.subText,
                    )
                    if (inspection.manifest.appVersion.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.backup_meta_app_version, inspection.manifest.appVersion),
                            fontSize = 12.sp,
                            color = colors.subText,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 可选恢复的类别清单
                for (cat in availableCategories) {
                    val isChecked = state.selectedRestoreCategories.contains(cat)
                    val detail = when (cat) {
                        BackupCategory.PROJECTS -> "${inspection.manifest.projects.size} 个画作工程"
                        BackupCategory.AUTOSAVE -> "${inspection.manifest.autosaves.size} 个自动保存草稿"
                        BackupCategory.BRUSHES -> "${inspection.manifest.brushPresets.size} 个预设, ${inspection.manifest.brushTips.size + inspection.manifest.brushPatterns.size} 个笔尖纹理"
                        BackupCategory.PALETTES -> "${inspection.manifest.palettesCount} 个色板"
                        BackupCategory.SETTINGS_LAYOUT -> "通用偏好与界面布局"
                        BackupCategory.SHORTCUTS_STYLUS -> "${inspection.manifest.shortcutsCount} 组快捷键与按键映射"
                        BackupCategory.REFERENCES_FONTS -> "参考图与用户字体"
                    }
                    BackupCategoryItemRow(
                        category = cat,
                        isChecked = isChecked,
                        estimateText = detail,
                        onClick = {
                            state.selectedRestoreCategories = if (isChecked) {
                                state.selectedRestoreCategories - cat
                            } else {
                                state.selectedRestoreCategories + cat
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 冲突解决: 画作同名处理
                if (inspection.existingProjectConflicts.isNotEmpty() &&
                    state.selectedRestoreCategories.contains(BackupCategory.PROJECTS)
                ) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.panelHi.copy(alpha = 0.4f))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.backup_conflict_projects_title) + " (${inspection.existingProjectConflicts.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.text,
                        )
                        Spacer(Modifier.height(8.dp))
                        ConflictRadioRow(
                            title = stringResource(R.string.backup_conflict_keep_copy),
                            selected = state.projectConflictResolution == ConflictResolution.KEEP_BOTH_COPY,
                            onClick = { state.projectConflictResolution = ConflictResolution.KEEP_BOTH_COPY },
                        )
                        Spacer(Modifier.height(6.dp))
                        ConflictRadioRow(
                            title = stringResource(R.string.backup_conflict_overwrite),
                            selected = state.projectConflictResolution == ConflictResolution.OVERWRITE,
                            onClick = { state.projectConflictResolution = ConflictResolution.OVERWRITE },
                        )
                    }
                }

                // 冲突解决: 笔刷预设同名处理
                if (inspection.existingBrushConflicts.isNotEmpty() &&
                    state.selectedRestoreCategories.contains(BackupCategory.BRUSHES)
                ) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.panelHi.copy(alpha = 0.4f))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.backup_conflict_brushes_title) + " (${inspection.existingBrushConflicts.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.text,
                        )
                        Spacer(Modifier.height(8.dp))
                        ConflictRadioRow(
                            title = stringResource(R.string.backup_conflict_skip_brushes),
                            selected = state.brushConflictResolution == ConflictResolution.SKIP_EXISTING,
                            onClick = { state.brushConflictResolution = ConflictResolution.SKIP_EXISTING },
                        )
                        Spacer(Modifier.height(6.dp))
                        ConflictRadioRow(
                            title = stringResource(R.string.backup_conflict_overwrite_brushes),
                            selected = state.brushConflictResolution == ConflictResolution.OVERWRITE,
                            onClick = { state.brushConflictResolution = ConflictResolution.OVERWRITE },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        textColor = colors.subText,
                    )
                    Spacer(Modifier.width(10.dp))
                    ReTextButton(
                        text = stringResource(R.string.backup_action_restore_confirm),
                        onClick = onConfirmRestore,
                        primary = true,
                        enabled = state.selectedRestoreCategories.isNotEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LocalBackupProgressDialog(vm: PaintViewModel) {
    val colors = Theme.current
    val state = vm.localBackupState
    val isExporting = state.status == LocalBackupStatus.EXPORTING
    val isRestoring = state.status == LocalBackupStatus.RESTORING

    if (!isExporting && !isRestoring) return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = colors.accent,
                    strokeWidth = 3.5.dp,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(
                        if (isExporting) R.string.backup_progress_exporting else R.string.backup_progress_restoring,
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text,
                )
                if (state.currentItem.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.currentItem,
                        fontSize = 12.sp,
                        color = colors.subText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupCategoryItemRow(
    category: BackupCategory,
    isChecked: Boolean,
    estimateText: String,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }

    val titleRes = when (category) {
        BackupCategory.PROJECTS -> R.string.backup_cat_projects
        BackupCategory.AUTOSAVE -> R.string.backup_cat_autosave
        BackupCategory.BRUSHES -> R.string.backup_cat_brushes
        BackupCategory.PALETTES -> R.string.backup_cat_palettes
        BackupCategory.SETTINGS_LAYOUT -> R.string.backup_cat_settings
        BackupCategory.SHORTCUTS_STYLUS -> R.string.backup_cat_shortcuts
        BackupCategory.REFERENCES_FONTS -> R.string.backup_cat_references
    }

    val descRes = when (category) {
        BackupCategory.PROJECTS -> R.string.backup_cat_projects_desc
        BackupCategory.AUTOSAVE -> R.string.backup_cat_autosave_desc
        BackupCategory.BRUSHES -> R.string.backup_cat_brushes_desc
        BackupCategory.PALETTES -> R.string.backup_cat_palettes_desc
        BackupCategory.SETTINGS_LAYOUT -> R.string.backup_cat_settings_desc
        BackupCategory.SHORTCUTS_STYLUS -> R.string.backup_cat_shortcuts_desc
        BackupCategory.REFERENCES_FONTS -> R.string.backup_cat_references_desc
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isChecked) colors.accent.copy(alpha = 0.08f) else colors.panelHi.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = if (isChecked) colors.accent.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .pressScale(interaction, pressedScale = 0.98f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (isChecked) colors.accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (isChecked) colors.accent else colors.subText.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isChecked) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.onAccent),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(titleRes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                )
                if (estimateText.isNotEmpty()) {
                    Text(
                        text = estimateText,
                        fontSize = 11.sp,
                        color = if (isChecked) colors.accent else colors.subText,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(descRes),
                fontSize = 11.sp,
                color = colors.subText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConflictRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (selected) colors.accent else colors.subText.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            color = if (selected) colors.text else colors.subText,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
