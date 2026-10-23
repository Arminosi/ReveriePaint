/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 显示瓦片 COW 的"增量拷贝"账本 (只在渲染线程使用, 零分配热路径)。
 *
 * 背景: onDraw 交给过 HWUI 的瓦片位图不能就地写, 写之前要换一张后备位图并把旧像素整块拷过去
 * (512x512x4 = 1 MiB/块)。后备位图本身就是这块瓦片几帧前退役的旧版本时, 两者只在
 * "那之后写过的区域" 上不同 —— 只拷这些区域即可, 结果逐像素相同。
 *
 * 每块瓦片记一个单调版本号与最近 [HISTORY] 次写入的外接框 (瓦片局部, 半开区间 l,t,r,b)。
 * 版本 v 的内容 = 第 1..v 次写入后的像素。[deltaSince] 给出版本 since -> 当前之间
 * 所有写入框的并集; 历史不够或参数异常一律返回 [DELTA_FULL] (退回整块拷贝, 保守正确)。
 *
 * 退役位图与 (瓦片号, 版本) 的对应关系记在 [tagBitmap]/[takeTag] 的定长身份表里;
 * 表满时覆盖最老条目 —— 丢标签只会退回整块拷贝, 不会用错。
 */
class TileDeltaLedger(private val tagCapacity: Int = 128) {
    private var tileCount = 0
    private var versions = LongArray(0)
    private var boxes = IntArray(0) // tileCount * HISTORY * 4
    private var boxVer = LongArray(0) // tileCount * HISTORY

    private val tagKeys = arrayOfNulls<Any>(tagCapacity)
    private val tagIdx = IntArray(tagCapacity)
    private val tagVer = LongArray(tagCapacity)
    private var tagCursor = 0

    /** 换网格: 清空全部版本与标签 (旧标签的版本号会与新网格混淆, 必须一起丢掉)。 */
    fun reset(count: Int) {
        tileCount = count
        versions = LongArray(count)
        boxes = IntArray(count * HISTORY * 4)
        boxVer = LongArray(count * HISTORY) { -1L }
        clearTags()
    }

    fun clearTags() {
        tagKeys.fill(null)
        tagCursor = 0
    }

    fun version(idx: Int): Long = if (idx in 0 until tileCount) versions[idx] else -1L

    /** 记一次对瓦片 [idx] 的写入 (改过的像素 ⊆ [l,r)x[t,b)), 返回新版本号。 */
    fun recordWrite(idx: Int, l: Int, t: Int, r: Int, b: Int): Long {
        if (idx !in 0 until tileCount) return -1L
        val v = versions[idx] + 1
        versions[idx] = v
        val slot = idx * HISTORY + (v % HISTORY).toInt()
        boxVer[slot] = v
        val o = slot * 4
        boxes[o] = l
        boxes[o + 1] = t
        boxes[o + 2] = r
        boxes[o + 3] = b
        return v
    }

    /**
     * 版本 [since] 到当前之间改过的区域并集写进 [out] (l,t,r,b), 返回 [DELTA_NONE] / [DELTA_RECT] /
     * [DELTA_FULL]。DELTA_FULL 时 [out] 无意义, 调用方整块拷贝。
     */
    fun deltaSince(idx: Int, since: Long, out: IntArray): Int {
        if (idx !in 0 until tileCount || since < 0) return DELTA_FULL
        val cur = versions[idx]
        if (since > cur) return DELTA_FULL
        if (since == cur) return DELTA_NONE
        if (cur - since > HISTORY) return DELTA_FULL
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        var v = since + 1
        while (v <= cur) {
            val slot = idx * HISTORY + (v % HISTORY).toInt()
            if (boxVer[slot] != v) return DELTA_FULL
            val o = slot * 4
            if (boxes[o] < l) l = boxes[o]
            if (boxes[o + 1] < t) t = boxes[o + 1]
            if (boxes[o + 2] > r) r = boxes[o + 2]
            if (boxes[o + 3] > b) b = boxes[o + 3]
            v++
        }
        if (l >= r || t >= b) return DELTA_NONE
        out[0] = l
        out[1] = t
        out[2] = r
        out[3] = b
        return DELTA_RECT
    }

    /** 退役位图 [key] 当前持有瓦片 [idx] 的版本 [ver] 内容。 */
    fun tagBitmap(key: Any, idx: Int, ver: Long) {
        removeTag(key)
        if (ver < 0) return
        val slot = tagCursor
        tagCursor = (tagCursor + 1) % tagCapacity
        tagKeys[slot] = key
        tagIdx[slot] = idx
        tagVer[slot] = ver
    }

    /** [key] 是否标着瓦片 [idx] 的某个版本 (只查询, 不移除)。 */
    fun hasTagFor(key: Any, idx: Int): Boolean {
        for (i in 0 until tagCapacity) {
            if (tagKeys[i] === key) return tagIdx[i] == idx
        }
        return false
    }

    /** 取走 [key] 的标签: 若标的是瓦片 [idx] 返回其版本, 否则 -1。无论如何标签都被移除。 */
    fun takeTag(key: Any, idx: Int): Long {
        for (i in 0 until tagCapacity) {
            if (tagKeys[i] === key) {
                tagKeys[i] = null
                return if (tagIdx[i] == idx) tagVer[i] else -1L
            }
        }
        return -1L
    }

    fun removeTag(key: Any) {
        for (i in 0 until tagCapacity) {
            if (tagKeys[i] === key) tagKeys[i] = null
        }
    }

    companion object {
        const val HISTORY = 8
        const val DELTA_NONE = 0
        const val DELTA_RECT = 1
        const val DELTA_FULL = 2
    }
}
