/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

/**
 * 真墨按住策略 (纯决策, 无 Android 依赖)。
 *
 * [overlayPanelsBlockHold] 对应 CanvasTouchView.overlayPanelsOpen: 全屏/半模态设置类面板开着时
 * RealInkController.wantsHold 必须返回 false (前缓冲置顶会盖住它们且无法靠孔径裁剪露出来)。
 * 绘图辅助面板是悬浮设置窗, 靠 [UiPanelSlot] + clipOutUi 露面板, 不得挡按住。
 */
internal object RealInkHoldPolicy {
    fun shouldReleaseOnReject(held: Boolean, wantsHold: Boolean): Boolean = held && !wantsHold

    fun shouldBumpEpoch(shown: Boolean): Boolean = shown

    /**
     * @param brushPanelBlocking 笔刷面板开着且未钉住
     * @param layerPanelBlocking 图层面板开着且未钉住
     * @param colorPanelBlocking 色板开着且未钉住
     * @param settingsPanelOpen 设置半模态
     * @param moreToolsOpen 更多工具半模态
     * @param drawingGuidePanelOpen 绘图辅助悬浮窗 (不挡按住; 参数保留以免接线处漏改)
     */
    fun overlayPanelsBlockHold(
        brushPanelBlocking: Boolean,
        layerPanelBlocking: Boolean,
        colorPanelBlocking: Boolean,
        settingsPanelOpen: Boolean,
        moreToolsOpen: Boolean,
        drawingGuidePanelOpen: Boolean,
    ): Boolean {
        // drawingGuidePanelOpen 故意不参与: 见 KDoc。
        return brushPanelBlocking || layerPanelBlocking || colorPanelBlocking ||
            settingsPanelOpen || moreToolsOpen
    }
}
