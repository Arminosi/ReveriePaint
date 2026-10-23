/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.model.FrontBufferHolePolicy.ARM_NORMAL
import com.reverie.paint.model.FrontBufferHolePolicy.ARM_NO_HOLES
import com.reverie.paint.model.FrontBufferHolePolicy.ARM_WITH_MASK
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_E
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_HMIN
import com.reverie.paint.model.FrontBufferHolePolicy.MODE_OFF
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_HANDBACK
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_HOLE_REDRAW
import com.reverie.paint.model.FrontBufferHolePolicy.PACKET_PLAIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferHolePolicyTest {
    @Test
    fun parseMode() {
        assertEquals(MODE_OFF, FrontBufferHolePolicy.parseMode(0, "e"))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, null))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, "hmin"))
        assertEquals(MODE_HMIN, FrontBufferHolePolicy.parseMode(1, ""))
        assertEquals(MODE_E, FrontBufferHolePolicy.parseMode(1, " E "))
        assertEquals(MODE_OFF, FrontBufferHolePolicy.parseMode(1, "off"))
    }

    @Test
    fun armDecision() {
        fun d(mode: Int = MODE_HMIN, vis: Boolean = true, ready: Boolean = true, cov: Float = 0.1f, other: Boolean = false) =
            FrontBufferHolePolicy.decideArm(mode, vis, ready, cov, 0.35f, other)
        assertEquals(ARM_WITH_MASK, d())
        assertEquals(ARM_NO_HOLES, d(vis = false, ready = false))
        assertEquals(ARM_NORMAL, d(ready = false))
        assertEquals(ARM_NORMAL, d(cov = 0.9f)) // 4x 像素网格铺满: 降级常规
        assertEquals(ARM_NORMAL, d(cov = Float.NaN))
        assertEquals(ARM_WITH_MASK, d(cov = 0.35f))
        assertEquals(ARM_NORMAL, d(other = true))
        assertEquals(ARM_NORMAL, d(vis = false, other = true))
        assertEquals(ARM_NO_HOLES, d(mode = MODE_OFF, ready = false, other = true))
        assertEquals(ARM_WITH_MASK, d(mode = MODE_E))
    }

    @Test
    fun packetDecisionNonHitIsPlain() {
        for (m in intArrayOf(MODE_OFF, MODE_HMIN, MODE_E)) assertEquals(PACKET_PLAIN, FrontBufferHolePolicy.decidePacket(m, false))
        assertEquals(PACKET_PLAIN, FrontBufferHolePolicy.decidePacket(MODE_OFF, true))
        assertEquals(PACKET_HOLE_REDRAW, FrontBufferHolePolicy.decidePacket(MODE_HMIN, true))
        assertEquals(PACKET_HANDBACK, FrontBufferHolePolicy.decidePacket(MODE_E, true))
    }

    @Test
    fun clearDelay() {
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(1, 0))
        assertFalse(FrontBufferHolePolicy.clearAfterCommit(1, 2))
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(3, 2))
        assertTrue(FrontBufferHolePolicy.clearAfterCommit(1, -4))
    }

    @Test
    fun snapLinesOnlyWithAssistedSnapModes() {
        assertFalse(FrontBufferHolePolicy.snapLinesPossible(false, true, true))
        assertFalse(FrontBufferHolePolicy.snapLinesPossible(true, false, false))
        assertTrue(FrontBufferHolePolicy.snapLinesPossible(true, true, false))
        assertTrue(FrontBufferHolePolicy.snapLinesPossible(true, false, true))
    }

    @Test
    fun maskRebuildSkipsDebounceOnlyOnFirstChangeAfterQuiet() {
        // 手势抬起: 之前很久没有过期 -> 立即
        assertTrue(FrontBufferHolePolicy.buildImmediately(true, 10_000L, Long.MIN_VALUE / 2, 50L))
        assertTrue(FrontBufferHolePolicy.buildImmediately(true, 1_000L, 950L, 50L))
        // 连续变化中的一帧 (滑杆 / 动画) -> 防抖
        assertFalse(FrontBufferHolePolicy.buildImmediately(true, 1_000L, 984L, 50L))
        // 视图动画还在跑 -> 防抖
        assertFalse(FrontBufferHolePolicy.buildImmediately(false, 10_000L, 0L, 50L))
    }

    @Test
    fun snapHoldDefaultOffForcesNormalPath() {
        // 开关关 (默认): 可能出现吸附线 -> 常规路径, 不为导引线维护备份层
        assertTrue(FrontBufferHolePolicy.snapForcesNormal(snapPossible = true, snapHoldEnabled = false))
        assertFalse(FrontBufferHolePolicy.snapNeedsExtras(snapPossible = true, snapHoldEnabled = false))
        // 开关开 (A/B): 按住, 从首帧维护备份层
        assertFalse(FrontBufferHolePolicy.snapForcesNormal(snapPossible = true, snapHoldEnabled = true))
        assertTrue(FrontBufferHolePolicy.snapNeedsExtras(snapPossible = true, snapHoldEnabled = true))
        // 没有吸附线: 与开关无关
        for (on in listOf(false, true)) {
            assertFalse(FrontBufferHolePolicy.snapForcesNormal(false, on))
            assertFalse(FrontBufferHolePolicy.snapNeedsExtras(false, on))
        }
        // 经 decideArm: 吸附线算作"其他覆盖层" -> ARM_NORMAL
        val forced = FrontBufferHolePolicy.snapForcesNormal(true, false)
        assertEquals(ARM_NORMAL, FrontBufferHolePolicy.decideArm(MODE_HMIN, true, true, 0.1f, 0.35f, forced))
    }

    /**
     * 两个表面不能同一时刻在同一像素画导引线: 枚举 按住/松开 x 前缓冲导引线在屏/已擦 的全部时序,
     * 窗口画完整导引线 当且仅当 不按住且前缓冲已擦。
     */
    @Test
    fun guideNeverDrawnByBothSurfaces() {
        for (held in listOf(false, true)) {
            for (front in listOf(false, true)) {
                val holesOnly = FrontBufferHolePolicy.windowGuideHolesOnly(true, held, front)
                val windowFull = !holesOnly
                assertFalse("held=$held front=$front", windowFull && front)
                assertEquals(!held && !front, windowFull)
                // 开关关: 前缓冲不画导引线, 窗口永远画完整导引线 (1a 之前的行为)
                assertFalse(FrontBufferHolePolicy.windowGuideHolesOnly(false, held, front))
            }
        }
        assertFalse(FrontBufferHolePolicy.frontDrawsGuide(false))
        assertTrue(FrontBufferHolePolicy.frontDrawsGuide(true))
    }

    @Test
    fun frontGuideClearTriggersWindowRedrawOnlyAfterRelease() {
        // E 交还 / 抬笔后前缓冲擦掉导引线 -> 窗口补画完整导引线
        assertTrue(FrontBufferHolePolicy.guideClearedNeedsRedraw(wasOnScreen = true, nowOnScreen = false, held = false))
        // 按住期间每包擦旧画新: 窗口不必额外重画
        assertFalse(FrontBufferHolePolicy.guideClearedNeedsRedraw(true, false, held = true))
        assertFalse(FrontBufferHolePolicy.guideClearedNeedsRedraw(false, false, false))
        assertFalse(FrontBufferHolePolicy.guideClearedNeedsRedraw(true, true, false))
        assertFalse(FrontBufferHolePolicy.guideClearedNeedsRedraw(false, true, false))
    }
}
