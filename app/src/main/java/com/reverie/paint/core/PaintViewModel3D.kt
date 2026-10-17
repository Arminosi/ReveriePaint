/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
    val key = state.modelType
    meshCache[key]?.let { return it }

    val builtin = BuiltinModel3D.fromId(key)
    val mesh = when (builtin) {
        BuiltinModel3D.CUBE -> Mesh3D.createCube(1.8f)
        BuiltinModel3D.SPHERE -> Mesh3D.createSphere(1.2f, 18, 24)
        BuiltinModel3D.CYLINDER -> Mesh3D.createCylinder(1.0f, 2.0f, 24)
        BuiltinModel3D.HEAD_MANNEQUIN -> Mesh3D.createHeadMannequin()
        null -> {
            if (key.startsWith("uri:")) {
                try {
                    val uri = Uri.parse(key.substring(4))
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                    } ?: Mesh3D.createCube(1.8f)
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse OBJ from uri: $key", e)
                    Mesh3D.createCube(1.8f)
                }
            } else {
                Mesh3D.createCube(1.8f)
            }
        }
    }

    meshCache[key] = mesh
    return mesh
}

/**
 * 新建内置 3D 参考图层
 */
internal fun PaintViewModel.create3DLayer(
    builtin: BuiltinModel3D = BuiltinModel3D.CUBE,
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
            commit3DLayerPixels(layer.id)
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
    val displayName = getString(R.string.layer_3d_tag) + " (" + fileName.removeSuffix(".obj") + ")"
    val uriKey = "uri:$uri"

    try {
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            val mesh = Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
            meshCache[uriKey] = mesh
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
                modelType = uriKey,
                rotX = 15f,
                rotY = -30f,
                zoom = 1.0f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            commit3DLayerPixels(layer.id)
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/**
 * 处理单指拖拽 3D 手势变换
 */
internal fun PaintViewModel.handle3DGesture(deltaX: Float, deltaY: Float) {
    val state = currentLayer3DState ?: return
    when (active3DInteractionMode) {
        Interaction3DMode.ROTATE -> {
            val newRotY = state.rotY + deltaX * 0.45f
            val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
            val updated = state.copy(rotX = newRotX, rotY = newRotY)
            update3DState(updated)
        }
        Interaction3DMode.PAN -> {
            val factor = 2.0f / docWidth.coerceAtLeast(1)
            val newPanX = (state.panX + deltaX * factor).coerceIn(-2.5f, 2.5f)
            val newPanY = (state.panY - deltaY * factor).coerceIn(-2.5f, 2.5f)
            val updated = state.copy(panX = newPanX, panY = newPanY)
            update3DState(updated)
        }
        Interaction3DMode.LIGHT -> {
            val newYaw = (state.lightYaw + deltaX * 0.5f) % 360f
            val newPitch = (state.lightPitch - deltaY * 0.5f).coerceIn(-89f, 89f)
            val updated = state.copy(lightYaw = newYaw, lightPitch = newPitch)
            update3DState(updated)
        }
    }
}

/**
 * 更新 3D 状态并即时刷新预览位图
 */
internal fun PaintViewModel.update3DState(updated: Model3DState) {
    active3DLayers = active3DLayers + (updated.layerId to updated)
    // 异步快速离屏渲染预览 (保持 120Hz 手势流畅度)
    val mesh = getMeshForState(updated)
    val w = docWidth.coerceIn(256, 2048)
    val h = docHeight.coerceIn(256, 2048)
    live3DPreviewBitmap = gl3dRenderer.renderToBitmap(mesh, updated, w, h, live3DPreviewBitmap)
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
    commit3DLayerPixels(reset.layerId)
}

/**
 * 切换 3D 图层渲染风格 (素模 / 光影 / 线框)
 */
internal fun PaintViewModel.set3DRenderMode(mode: RenderMode3D) {
    val state = currentLayer3DState ?: return
    val updated = state.copy(renderMode = mode)
    update3DState(updated)
    commit3DLayerPixels(updated.layerId)
}

/**
 * 将 3D 离屏渲染烘焙结果写回底层 Krita 颜料图层 (持久化位图保证无缝展示与导出)
 */
internal fun PaintViewModel.commit3DLayerPixels(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val layerIdx = layers.indexOfFirst { it.id == layerId }
    if (layerIdx < 0) return

    val mesh = getMeshForState(state)
    val w = docWidth.coerceAtLeast(1)
    val h = docHeight.coerceAtLeast(1)

    val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h) ?: return
    val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

    runCore(render = true, after = {
        notifyLayerChanged()
    }) {
        ReverieCoreBridge.clearLayer(layerIdx)
        ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
    }
}

/**
 * 栅格化当前 3D 图层 (将其转化为普通光栅像素图层，解除 3D 绑定)
 */
internal fun PaintViewModel.rasterize3DLayer(layerId: Long) {
    active3DLayers = active3DLayers - layerId
    live3DPreviewBitmap = null
    showActionToast(R.string.toast_3d_rasterized, R.drawable.ic_layers)
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
    }
}
