/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.create3DLayer
import com.reverie.paint.core.create3DLayerFromObj
import com.reverie.paint.model.BuiltinModel3D
import com.reverie.paint.ui.theme.Morandi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Model3DPickerSheet(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val objPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "mesh.obj"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: "mesh.obj"
                }
            }
            vm.create3DLayerFromObj(uri, fileName)
            onDismiss()
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .wrapContentHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Morandi.panel)
            .border(1.dp, Morandi.border.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.layer_3d_picker_title),
                    color = Morandi.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
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

            Spacer(modifier = Modifier.height(16.dp))

            // 预设模型卡片网格
            val builtins = listOf(
                BuiltinModel3D.CUBE to R.drawable.ic_rect,
                BuiltinModel3D.HEAD_MANNEQUIN to R.drawable.ic_author,
                BuiltinModel3D.SPHERE to R.drawable.ic_circle,
                BuiltinModel3D.CYLINDER to R.drawable.ic_sliders,
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(builtins) { (model, iconRes) ->
                    val name = when (model) {
                        BuiltinModel3D.CUBE -> stringResource(R.string.layer_3d_model_cube)
                        BuiltinModel3D.HEAD_MANNEQUIN -> stringResource(R.string.layer_3d_model_head)
                        BuiltinModel3D.SPHERE -> stringResource(R.string.layer_3d_model_sphere)
                        BuiltinModel3D.CYLINDER -> stringResource(R.string.layer_3d_model_cylinder)
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi)
                            .border(1.dp, Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable {
                                vm.create3DLayer(model)
                                onDismiss()
                            }
                            .padding(12.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = name,
                                tint = Morandi.accent,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = name,
                                color = Morandi.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 导入外部 OBJ 卡片
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.accent.copy(alpha = 0.12f))
                    .border(1.dp, Morandi.accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable {
                        objPickerLauncher.launch(arrayOf("*/*"))
                    }
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_import),
                        contentDescription = stringResource(R.string.layer_3d_import_obj),
                        tint = Morandi.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.layer_3d_import_obj),
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
