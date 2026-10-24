package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealInkHoldPolicyTest {
    @Test
    fun `reject while held releases, accept does not`() {
        assertTrue(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = false))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = true, wantsHold = true))
        assertFalse(RealInkHoldPolicy.shouldReleaseOnReject(held = false, wantsHold = false))
    }

    @Test
    fun `epoch bumps only after the overlay accepts the frame`() {
        assertTrue(RealInkHoldPolicy.shouldBumpEpoch(shown = true))
        assertFalse(RealInkHoldPolicy.shouldBumpEpoch(shown = false))
    }

    @Test
    fun `drawing guide panel chrome alone does not block real-ink hold`() {
        // 辅助面板是悬浮设置窗, 不是全屏模态: 开着时仍应能按住前缓冲 (靠 UiPanelSlot 挖孔露面板)。
        assertFalse(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = true,
            ),
        )
    }

    @Test
    fun `unpinned brush panel still blocks real-ink hold`() {
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = true,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = false,
                drawingGuidePanelOpen = false,
            ),
        )
    }

    @Test
    fun `settings or more-tools modal blocks real-ink hold`() {
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = true,
                moreToolsOpen = false,
                drawingGuidePanelOpen = false,
            ),
        )
        assertTrue(
            RealInkHoldPolicy.overlayPanelsBlockHold(
                brushPanelBlocking = false,
                layerPanelBlocking = false,
                colorPanelBlocking = false,
                settingsPanelOpen = false,
                moreToolsOpen = true,
                drawingGuidePanelOpen = false,
            ),
        )
    }
}
