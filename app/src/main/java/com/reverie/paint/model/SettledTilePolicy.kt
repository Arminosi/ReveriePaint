/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.max

/**
 * 运笔中渐进刷新已落定瓦片的判定策略 (P5 / D3) 与图层缩略图选择逻辑。
 * 纯 Kotlin 实现, 无 Android 依赖, 可 JVM 单测。
 */
object SettledTilePolicy {
    /** 瓦片落定要求的最小无新写帧数。 */
    const val SETTLED_FRAME_THRESHOLD = 3

    /** 瓦片与笔尖瓦片的切比雪夫距离必须严格大于此值 (离笔尖 > 2 个瓦片)。 */
    const val SETTLED_DISTANCE_THRESHOLD = 2

    /**
     * 单个瓦片是否已落定:
     * - 至少被写入过一次 (`lastWriteFrame > 0`)
     * - 本笔自上次写入后尚未刷新 (`lastWriteFrame > refreshedAtFrame`)
     * - 连续至少 3 帧无新写入 (`currentFrame - lastWriteFrame >= 3`)
     * - 切比雪夫距离严格大于 2 (`max(abs(tx - tipCol), abs(ty - tipRow)) > 2`)
     */
    fun isTileSettled(
        tx: Int,
        ty: Int,
        tipCol: Int,
        tipRow: Int,
        lastWriteFrame: Int,
        currentFrame: Int,
        refreshedAtFrame: Int,
    ): Boolean {
        if (lastWriteFrame <= 0) return false
        if (lastWriteFrame <= refreshedAtFrame) return false
        if (currentFrame - lastWriteFrame < SETTLED_FRAME_THRESHOLD) return false
        val dx = abs(tx - tipCol)
        val dy = abs(ty - tipRow)
        return max(dx, dy) > SETTLED_DISTANCE_THRESHOLD
    }

    /**
     * 找出所有已落定的瓦片索引 (无分配热路径版):
     * 写入 [out] 数组并返回落定瓦片数量。
     */
    fun findSettledTiles(
        lastWrite: IntArray,
        refreshedAt: IntArray,
        frameNo: Int,
        tipCol: Int,
        tipRow: Int,
        cols: Int,
        out: IntArray,
    ): Int {
        if (cols <= 0) return 0
        val count = minOf(lastWrite.size, refreshedAt.size, out.size)
        var n = 0
        for (i in 0 until count) {
            val tx = i % cols
            val ty = i / cols
            if (isTileSettled(tx, ty, tipCol, tipRow, lastWrite[i], frameNo, refreshedAt[i])) {
                out[n++] = i
            }
        }
        return n
    }

    /**
     * 找出所有已落定的瓦片索引 (分配版, 供测试与便捷调用)。
     */
    fun settledTiles(
        lastWrite: IntArray,
        refreshedAt: IntArray,
        frameNo: Int,
        tipCol: Int,
        tipRow: Int,
        cols: Int,
    ): List<Int> {
        if (cols <= 0 || lastWrite.isEmpty()) return emptyList()
        val count = minOf(lastWrite.size, refreshedAt.size)
        val result = ArrayList<Int>()
        for (i in 0 until count) {
            val tx = i % cols
            val ty = i / cols
            if (isTileSettled(tx, ty, tipCol, tipRow, lastWrite[i], frameNo, refreshedAt[i])) {
                result.add(i)
            }
        }
        return result
    }

    /**
     * 带节流检查的落定瓦片查询: 节流时间内返回空列表。
     */
    fun settledTiles(
        lastWrite: IntArray,
        refreshedAt: IntArray,
        frameNo: Int,
        tipCol: Int,
        tipRow: Int,
        cols: Int,
        nowMs: Long,
        lastRefreshMs: Long,
        minIntervalMs: Long,
    ): List<Int> {
        if (minIntervalMs <= 0L) return emptyList()
        if (nowMs - lastRefreshMs < minIntervalMs) return emptyList()
        return settledTiles(lastWrite, refreshedAt, frameNo, tipCol, tipRow, cols)
    }

    /**
     * 从深度遍历展开的图层列表中找出目标图层及其所有祖先组图层的序号:
     * 从 targetIndex 向前扫描, 逐级寻找 `depth == currentDepth - 1 && isGroup` 的祖先。
     */
    fun findLayerAndAncestorIndices(
        targetIndex: Int,
        layerCount: Int,
        depth: (Int) -> Int,
        isGroup: (Int) -> Boolean,
    ): Set<Int> {
        if (targetIndex !in 0 until layerCount) return emptySet()
        val targetDepth = depth(targetIndex)
        val result = LinkedHashSet<Int>()
        result.add(targetIndex)
        if (targetDepth > 0) {
            var currentDepth = targetDepth
            for (i in targetIndex - 1 downTo 0) {
                val d = depth(i)
                val group = isGroup(i)
                if (group && d == currentDepth - 1) {
                    result.add(i)
                    currentDepth = d
                    if (currentDepth <= 0) break
                }
            }
        }
        return result
    }
}
