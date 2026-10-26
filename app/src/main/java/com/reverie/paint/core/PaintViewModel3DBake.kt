/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 3D 参考图层的"烘焙上位"
 *
 * 3D 图层在引擎里是一张被清空的光栅层, 视口每帧叠加绘制它 —— 因此它永远盖在所有图层之上。
 * 这里在交互静止后把当前姿态烘焙进该图层 (和栅格化同样的盖章路径), 之后它由引擎按正常图层顺序合成,
 * 于是**会被上层图层遮挡**; 一旦开始新的 3D 交互 (旋转/打光/姿态), 立即撤下烘焙改回实时叠加预览,
 * 避免出现"旧姿态残影 + 新姿态"的重影。
 */
internal fun PaintViewModel.is3DLayerBaked(layerId: Long): Boolean = layerId in baked3DLayers

/**
 * 交互开始: 撤下烘焙, 交回实时预览。挂起中的烘焙任务立即作废 (代次 +1)。
 */
internal fun PaintViewModel.begin3DLivePreview(layerId: Long) {
    bake3DGeneration++
    bake3DJob?.cancel()
    bake3DJob = null
    if (is3DLayerBaked(layerId)) {
        baked3DLayers = baked3DLayers - layerId
        ensure3DLayerClearedInCore(layerId)
        com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.postInvalidate()
    }
}

/**
 * 去抖烘焙: 静止 ~260ms 后把姿态写进图层并标记为"已上位"。
 * 交互中 (isInteracting3D) 直接放弃, 等抬手后的最后一次状态变更再来。
 */
internal fun PaintViewModel.schedule3DBake(layerId: Long) {
    if (isInteracting3D) return
    bake3DJob?.cancel()
    val generation = ++bake3DGeneration
    bake3DJob = viewModelScope.launch {
        delay(260)
        if (isInteracting3D) return@launch
        if (active3DLayers[layerId] == null) return@launch
        commit3DLayerPixels(layerId) {
            if (generation != bake3DGeneration) return@commit3DLayerPixels
            baked3DLayers = baked3DLayers + layerId
            com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.postInvalidate()
        }
    }
}
