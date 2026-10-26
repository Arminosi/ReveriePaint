/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealInkCatchUpTest {
    @Test
    fun `shouldClearAfterDraw decision table`() {
        // held=true: 只要笔还在按住，绝不清墨
        assertFalse(RealInkHoldPolicy.shouldClearAfterDraw(held = true, armed = true, drawnRev = 10L, targetRev = 10L))
        // armed=false: 没有触发追赶门控，不清
        assertFalse(RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = false, drawnRev = 10L, targetRev = 10L))
        // target=MAX_VALUE: 渲染线程尚未落定目标版本号，不清
        assertFalse(RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = 10L, targetRev = Long.MAX_VALUE))
        // drawnRev < targetRev: 窗口画的仍是旧瓦片，前缓冲不能清
        assertFalse(RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = 9L, targetRev = 10L))
        // drawnRev == targetRev: 窗口已画出追赶的目标版本瓦片，放行
        assertTrue(RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = 10L, targetRev = 10L))
        // drawnRev > targetRev: 窗口画出的版本甚至更新，放行
        assertTrue(RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = 11L, targetRev = 10L))
    }

    @Test
    fun `read revision before drawable tiles regression test`() {
        // 模拟「先读修订号再读瓦片」与「后读修订号」的时序竞争
        class TileState(var rev: Long, var tileVer: Long)

        val state = TileState(rev = 5L, tileVer = 5L)

        // 1. 正确时序: 先读修订号
        val drawnRev = state.rev // = 5

        // 在读取瓦片引用之前或期间，渲染线程发布了新瓦片并自增修订号，同时更新追赶目标
        state.rev = 6L
        state.tileVer = 6L
        val catchUpTarget = 6L

        // UI 线程拿到的是旧瓦片 (ver 5)
        // 此时如果用 drawnRev 判断:
        assertFalse(
            "先读修订号时 drawnRev 为 5，目标为 6，绝不会误清前缓冲",
            RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = drawnRev, targetRev = catchUpTarget)
        )

        // 2. 错误时序 (对比): 若在瓦片更新后才读 revision
        val badDrawnRev = state.rev // = 6
        assertTrue(
            "若在渲染发布后才读 revision，则会误判为已追平",
            RealInkHoldPolicy.shouldClearAfterDraw(held = false, armed = true, drawnRev = badDrawnRev, targetRev = catchUpTarget)
        )
    }
}
