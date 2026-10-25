/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.reverie.paint.R
import com.reverie.paint.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

private val meshCache = ConcurrentHashMap<String, Mesh3D>()

/**
 * 获取或构造 3D 网格对象
 */
internal fun PaintViewModel.getMeshForState(state: Model3DState): Mesh3D {
    val anglesKey = if (state.jointAngles.isEmpty()) "" else state.jointAngles.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    val key = "${state.modelType}#${state.posePreset}#$anglesKey"
    meshCache[key]?.let { return it }

    val builtin = BuiltinModel3D.fromId(state.modelType)
    val mesh = when (builtin) {
        BuiltinModel3D.ANIME_BOY -> Mesh3D.createAnimeMannequin(isFemale = false, posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.ANIME_GIRL -> Mesh3D.createAnimeMannequin(isFemale = true, posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.CAT -> Mesh3D.createCatModel(posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.BODY_MANNEQUIN -> Mesh3D.createAnimeMannequin(isFemale = false, posePreset = state.posePreset, angles = state.jointAngles)
        BuiltinModel3D.BODY_SIT -> Mesh3D.createAnimeMannequin(isFemale = false, posePreset = "sit", angles = state.jointAngles)
        BuiltinModel3D.HEAD_MANNEQUIN -> Mesh3D.createHeadMannequin()
        BuiltinModel3D.CUBE -> Mesh3D.createCube(1.8f)
        BuiltinModel3D.SPHERE -> Mesh3D.createSphere(1.2f, 18, 24)
        BuiltinModel3D.CYLINDER -> Mesh3D.createCylinder(1.0f, 2.0f, 24)
        null -> {
            val rawKey = state.modelType
            if (rawKey.startsWith("file:")) {
                try {
                    val file = java.io.File(rawKey.substring(5))
                    if (file.exists()) {
                        file.inputStream().use { stream ->
                            Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                        }
                    } else {
                        Mesh3D.createCube(1.8f)
                    }
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse OBJ from file: $rawKey", e)
                    Mesh3D.createCube(1.8f)
                }
            } else if (rawKey.startsWith("uri:")) {
                try {
                    val uri = Uri.parse(rawKey.substring(4))
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                    } ?: Mesh3D.createCube(1.8f)
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse OBJ from uri: $rawKey", e)
                    Mesh3D.createCube(1.8f)
                }
            } else {
                Mesh3D.createCube(1.8f)
            }
        }
    }

    if (meshCache.size > 40) {
        meshCache.clear()
    }
    meshCache[key] = mesh
    return mesh
}

/**
 * 切换当前 3D 模型的姿态预设
 */
internal fun PaintViewModel.set3DPosePreset(preset: String) {
    val state = currentLayer3DState ?: return
    val updated = state.copy(posePreset = preset, jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 调整指定骨骼关节的微调角度
 */
internal fun PaintViewModel.set3DJointAngle(jointKey: String, angle: Float) {
    val state = currentLayer3DState ?: return
    val newAngles = state.jointAngles.toMutableMap()
    if (kotlin.math.abs(angle) < 0.01f) {
        newAngles.remove(jointKey)
    } else {
        newAngles[jointKey] = angle
    }
    val updated = state.copy(jointAngles = newAngles)
    update3DState(updated)
}

/**
 * 重置当前 3D 模型的骨骼微调角度
 */
internal fun PaintViewModel.reset3DJointAngles() {
    val state = currentLayer3DState ?: return
    val updated = state.copy(jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 新建内置 3D 参考图层
 */
internal fun PaintViewModel.create3DLayer(
    builtin: BuiltinModel3D = BuiltinModel3D.ANIME_BOY,
    customName: String? = null,
) {
    val displayName = customName ?: (getString(R.string.layer_3d_tag) + " (" + builtin.displayNameRes + ")")
    clearLayerSelection()

    runCore(after = {
        syncLayersFromNative()
        val curIdx = currentLayerIndex
        val layer = layers.getOrNull(curIdx)
        if (layer != null) {
            val initialState = Model3DState(
                layerId = layer.id,
                modelType = builtin.id,
                rotX = 18f,
                rotY = -35f,
                rotZ = 0f,
                panX = 0f,
                panY = 0f,
                zoom = 1.0f,
                lightYaw = 45f,
                lightPitch = 35f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            ensure3DLayerClearedInCore(layer.id)
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/**
 * 导入外部 OBJ 模型创建 3D 参考图层
 */
internal fun PaintViewModel.create3DLayerFromObj(
    uri: Uri,
    fileName: String,
) {
    val cleanName = fileName.removeSuffix(".obj")
    val displayName = getString(R.string.layer_3d_tag) + " (" + cleanName + ")"

    // 本地持久化缓存，防止设备重启或临时授权失效后丢失外部 3D 模型
    val modelsDir = java.io.File(appContext.filesDir, "3d_models").apply { mkdirs() }
    val safeFileName = "obj_${System.currentTimeMillis()}_$fileName"
    val localFile = java.io.File(modelsDir, safeFileName)
    try {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        Log.e("RP_3D", "Failed to cache OBJ file to app internal storage", e)
    }

    val modelPathKey = if (localFile.exists() && localFile.length() > 0) {
        "file:${localFile.absolutePath}"
    } else {
        "uri:$uri"
    }

    try {
        val stream = if (localFile.exists() && localFile.length() > 0) {
            localFile.inputStream()
        } else {
            appContext.contentResolver.openInputStream(uri)
        }
        stream?.use { s ->
            val mesh = Mesh3D.parseObj(BufferedReader(InputStreamReader(s)))
            meshCache[modelPathKey] = mesh
        }
    } catch (e: Exception) {
        Log.e("RP_3D", "Failed to parse imported OBJ file", e)
        return
    }

    clearLayerSelection()
    runCore(after = {
        syncLayersFromNative()
        val curIdx = currentLayerIndex
        val layer = layers.getOrNull(curIdx)
        if (layer != null) {
            val initialState = Model3DState(
                layerId = layer.id,
                modelType = modelPathKey,
                rotX = 15f,
                rotY = -30f,
                zoom = 1.0f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            ensure3DLayerClearedInCore(layer.id)
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/**
 * 确保 3D 参考图层在底层 Krita 引擎中保持清空状态 (避免图层位图与实时硬件 Canvas 投影发生重影叠画)
 */
internal fun PaintViewModel.ensure3DLayerClearedInCore(layerId: Long) {
    val idx = layers.indexOfFirst { it.id == layerId }
    if (idx >= 0) {
        runCore(render = true, after = { notifyLayerChanged(forceThumbs = false) }) {
            ReverieCoreBridge.clearLayer(idx)
        }
    }
}

/**
 * 处理单指拖拽 3D 手势变换
 */
internal fun PaintViewModel.handle3DGesture(deltaX: Float, deltaY: Float) {
    val state = currentLayer3DState ?: return
    when (active3DInteractionMode) {
        Interaction3DMode.ROTATE -> {
            val newRotY = (state.rotY + deltaX * 0.45f) % 360f
            val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
            val updated = state.copy(rotX = newRotX, rotY = newRotY)
            update3DState(updated)
        }
        Interaction3DMode.PAN -> {
            val factor = 0.0035f / state.zoom.coerceAtLeast(0.1f)
            val newPanX = (state.panX + deltaX * factor).coerceIn(-5.0f, 5.0f)
            val newPanY = (state.panY - deltaY * factor).coerceIn(-5.0f, 5.0f)
            val updated = state.copy(panX = newPanX, panY = newPanY)
            update3DState(updated)
        }
        Interaction3DMode.LIGHT -> {
            val newYaw = (state.lightYaw + deltaX * 0.45f).coerceIn(-120f, 120f)
            val newPitch = (state.lightPitch - deltaY * 0.45f).coerceIn(-75f, 75f)
            val updated = state.copy(lightYaw = newYaw, lightPitch = newPitch)
            update3DState(updated)
        }
    }
}

/**
 * 按比例缩放当前 3D 模型
 */
internal fun PaintViewModel.zoom3DModel(scaleRatio: Float) {
    val state = currentLayer3DState ?: return
    val newZoom = (state.zoom * scaleRatio).coerceIn(0.1f, 10.0f)
    val updated = state.copy(zoom = newZoom)
    update3DState(updated)
}

/**
 * 还原当前 3D 模型缩放到 100%
 */
internal fun PaintViewModel.reset3DZoom() {
    val state = currentLayer3DState ?: return
    val updated = state.copy(zoom = 1.0f)
    update3DState(updated)
}

/**
 * 更新 3D 状态 (仅更新轻量内存模型，驱动视口硬件 Canvas 120Hz 实时重绘)
 */
internal fun PaintViewModel.update3DState(updated: Model3DState) {
    active3DLayers = active3DLayers + (updated.layerId to updated)
}

/**
 * 复位当前 3D 图层视角
 */
internal fun PaintViewModel.reset3DView() {
    val state = currentLayer3DState ?: return
    val reset = state.copy(
        rotX = 18f,
        rotY = -35f,
        rotZ = 0f,
        panX = 0f,
        panY = 0f,
        zoom = 1.0f,
        lightYaw = 45f,
        lightPitch = 35f,
    )
    update3DState(reset)
}

/**
 * 切换 3D 图层渲染风格 (素模 / 光影 / 线框)
 */
internal fun PaintViewModel.set3DRenderMode(mode: RenderMode3D) {
    val state = currentLayer3DState ?: return
    val updated = state.copy(renderMode = mode)
    update3DState(updated)
}

/**
 * 异步将 3D 烘焙结果写回底层 Krita 颜料图层 (在工作线程渲染，彻底杜绝主线程阻塞)
 */
internal fun PaintViewModel.commit3DLayerPixels(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val layerIdx = layers.indexOfFirst { it.id == layerId }
    if (layerIdx < 0) return

    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val mesh = getMeshForState(state)
        val w = docWidth.coerceAtLeast(1)
        val h = docHeight.coerceAtLeast(1)

        val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h) ?: return@launch
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

        runCore(render = true, after = {
            notifyLayerChanged(forceThumbs = false)
        }) {
            val targetIdx = layers.indexOfFirst { it.id == layerId }
            if (targetIdx >= 0) {
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(targetIdx)
                ReverieCoreBridge.clearLayer(targetIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
            }
            stampBmp.recycle()
            if (rendered !== stampBmp && !rendered.isRecycled) {
                rendered.recycle()
            }
        }
    }
}

/**
 * 栅格化当前 3D 图层 (将其转化为普通光栅像素图层，解除 3D 绑定)
 */
internal fun PaintViewModel.rasterize3DLayer(layerId: Long) {
    commit3DLayerPixels(layerId)
    active3DLayers = active3DLayers - layerId
    live3DPreviewBitmap = null
    showActionToast(R.string.toast_3d_rasterized, R.drawable.ic_layers)
}

/**
 * 同步将所有活跃 3D 参考图层渲染并烘焙至底层 Krita 颜料图层 (在导出与持久化前调用)
 */
internal fun PaintViewModel.commitAllActive3DLayersSync() {
    for ((layerId, state) in active3DLayers) {
        val layerIdx = layers.indexOfFirst { it.id == layerId }
        if (layerIdx >= 0) {
            val mesh = getMeshForState(state)
            val w = docWidth.coerceAtLeast(1)
            val h = docHeight.coerceAtLeast(1)
            val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h)
            if (rendered != null) {
                val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)
                ReverieCoreBridge.clearLayer(layerIdx)
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(layerIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
                stampBmp.recycle()
                if (rendered !== stampBmp && !rendered.isRecycled) {
                    rendered.recycle()
                }
            }
        }
    }
}

/**
 * 提取当前 3D 图层的轮廓线稿，在新图层中创建线稿层
 */
internal fun PaintViewModel.extract3DOutlines(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val mesh = getMeshForState(state)
    val wireState = state.copy(renderMode = RenderMode3D.WIREFRAME, wireframeColor = 0xFF1A1A1AL)

    val w = docWidth.coerceAtLeast(1)
    val h = docHeight.coerceAtLeast(1)
    val rendered = gl3dRenderer.renderToBitmap(mesh, wireState, w, h) ?: return
    val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

    val outlineLayerName = getString(R.string.layer_3d_extract_outline)
    runCore(render = true, after = {
        syncLayersFromNative()
        showActionToast(R.string.toast_3d_outline_extracted, R.drawable.ic_magicwand)
    }) {
        ReverieCoreBridge.addLayer(outlineLayerName)
        ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
        stampBmp.recycle()
        if (rendered !== stampBmp && !rendered.isRecycled) {
            rendered.recycle()
        }
    }
}
