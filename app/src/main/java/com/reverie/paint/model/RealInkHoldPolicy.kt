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
 *
 * 判据 (Round 7): 只有"开着时画布根本收不到落笔"的面板才挡按住 —— 它们的全屏透明遮罩
 * `noRippleClickable(onClose)` 吃掉画布上的每个按下, 这一笔不存在, 挡不挡按住对墨无影响,
 * 而遮罩是全屏的, 无法登记成有限孔径。画布可落笔的面板一律走 [UiPanelSlot] 孔径, 不挡按住。
 */
internal object RealInkHoldPolicy {
    fun shouldReleaseOnReject(held: Boolean, wantsHold: Boolean): Boolean = held && !wantsHold

    fun shouldBumpEpoch(shown: Boolean): Boolean = shown

    /** 笔刷面板: 未钉住 = 全屏遮罩 (BrushPanel.kt 未钉住分支 `noRippleClickable(onClose)`); 钉住 = PINNED_BRUSH_PANEL 孔径。 */
    fun brushPanelBlocksHold(open: Boolean, pinned: Boolean): Boolean = open && !pinned

    /**
     * 图层面板: 未钉住且不在滤镜调节页 = 全屏遮罩; 钉住 = PINNED_LAYER_PANEL 孔径;
     * 未钉住的滤镜调节页没有遮罩 (画布可落笔), 卡片登记 LAYER_FILTER_ADJUST_CARD 孔径, 不挡按住。
     */
    fun layerPanelBlocksHold(open: Boolean, pinned: Boolean, filterAdjustPassThrough: Boolean): Boolean =
        open && !pinned && !filterAdjustPassThrough

    /** 色板: 未钉住 = 全屏遮罩 (ColorPanel.kt 未钉住分支); 钉住 = PINNED_COLOR_PANEL 孔径。 */
    fun colorPanelBlocksHold(open: Boolean, pinned: Boolean): Boolean = open && !pinned

    /**
     * @param brushPanelBlocking 笔刷面板开着且未钉住
     * @param layerPanelBlocking 见 [layerPanelBlocksHold]
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
