/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferSurfaceGateTest {
    private fun match(
        valid: Boolean = true,
        sw: Int = 2560,
        sh: Int = 1600,
        ow: Int = 2560,
        oh: Int = 1600,
        ox: Int = 0,
        oy: Int = 0,
        tw: Int = 2560,
        th: Int = 1600,
        tx: Int = 0,
        ty: Int = 0,
    ) = FrontBufferSurfaceGate.geometryMatches(valid, sw, sh, ow, oh, ox, oy, tw, th, tx, ty)

    private fun pose(cx: Float = 1280f, scale: Float = 0.5f, bmpW: Int = 3840) =
        CanvasPose(cx, 800f, scale, 0f, 1f, 1f, bmpW / 2f, 1080f, bmpW, 2160)

    @Test fun settledSurfaceMatches() = assertTrue(match())

    @Test fun noSurfaceYetRejects() {
        // 渲染器在 View 构造时就建好, 表面要等 surfaceChanged: 之前不能按住。
        assertFalse(match(valid = false))
        assertFalse(match(sw = 0, sh = 0))
    }

    @Test fun transientSurfaceSizeRejects() {
        // 进画布过渡期表面仍是旧尺寸, View 已是最终尺寸: 缓冲坐标与画布坐标不一致。
        assertFalse(match(sw = 2252, sh = 1408))
    }

    @Test fun overlayAndCanvasViewMustCoincide() {
        assertFalse(match(tw = 2400))
        assertFalse(match(th = 1500))
        assertFalse(match(tx = 12))
        assertFalse(match(oy = 40))
    }

    @Test fun offsetButCoincidentIsFine() = assertTrue(match(ox = 0, oy = 80, tx = 0, ty = 80))

    @Test fun stillValidTracksDestroyAndResize() {
        assertTrue(FrontBufferSurfaceGate.stillValid(true, 2560, 1600, 2560, 1600))
        assertFalse(FrontBufferSurfaceGate.stillValid(false, 2560, 1600, 2560, 1600))
        assertFalse(FrontBufferSurfaceGate.stillValid(true, 2560, 1600, 1600, 2560))
        assertFalse(FrontBufferSurfaceGate.stillValid(true, 0, 0, 0, 0))
    }

    @Test fun surfaceEventReleasesOnlyHeldStrokeOnRebuild() {
        assertFalse(FrontBufferSurfaceGate.surfaceEventReleases(false, 0, 0, false, 0, 0))
        assertFalse(FrontBufferSurfaceGate.surfaceEventReleases(false, 100, 100, true, 200, 200))
        assertTrue(FrontBufferSurfaceGate.surfaceEventReleases(true, 2560, 1600, false, 0, 0))
        assertTrue(FrontBufferSurfaceGate.surfaceEventReleases(true, 2252, 1408, true, 2560, 1600))
        // 同尺寸的 surfaceChanged (androidx 不重建缓冲) 不打断按住。
        assertFalse(FrontBufferSurfaceGate.surfaceEventReleases(true, 2560, 1600, true, 2560, 1600))
    }

    @Test fun poseChangeWhileHeldReleases() {
        val a = pose()
        assertFalse(FrontBufferSurfaceGate.poseChangeReleases(true, a, pose()))
        assertTrue(FrontBufferSurfaceGate.poseChangeReleases(true, a, pose(cx = 1290f)))
        assertTrue(FrontBufferSurfaceGate.poseChangeReleases(true, a, pose(scale = 0.6f)))
        assertTrue(FrontBufferSurfaceGate.poseChangeReleases(true, a, pose(bmpW = 1920)))
    }

    @Test fun poseChangeWhenNotHeldOrFirstPublishKeepsGoing() {
        assertFalse(FrontBufferSurfaceGate.poseChangeReleases(false, pose(), pose(cx = 0f)))
        assertFalse(FrontBufferSurfaceGate.poseChangeReleases(true, null, pose()))
    }
}
