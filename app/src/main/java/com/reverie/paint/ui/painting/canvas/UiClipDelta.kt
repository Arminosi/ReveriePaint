/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.UiClipSnapshot
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 不可变浮点矩形（纯 JVM，零 Android 框架依赖，单测直接覆盖）。
 */
data class ClipRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom

    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean =
        right > l && left < r && bottom > t && top < b

    fun intersects(other: ClipRect): Boolean =
        intersects(other.left, other.top, other.right, other.bottom)

    fun intersect(other: ClipRect): ClipRect? {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        return if (l < r && t < b) ClipRect(l, t, r, b) else null
    }

    fun union(other: ClipRect): ClipRect =
        ClipRect(
            min(left, other.left),
            min(top, other.top),
            max(right, other.right),
            max(bottom, other.bottom),
        )
}

enum class ClipChangeAction {
    KEEP_UPDATE,
    REBAKE,
}

/**
 * 按槽位号对齐计算两个裁剪集合的对称差矩形列表（覆盖空槽、共享槽并集、阴影外扩变化、槽数变化）。
 */
fun clipDelta(
    oldBySlot: Map<Int, ClipRect>,
    newBySlot: Map<Int, ClipRect>,
): List<ClipRect> {
    val deltas = mutableListOf<ClipRect>()
    val allSlots = oldBySlot.keys + newBySlot.keys
    for (slot in allSlots) {
        val r1 = oldBySlot[slot]
        val r2 = newBySlot[slot]
        when {
            r1 == null && r2 != null -> {
                if (!r2.isEmpty) deltas.add(r2)
            }
            r1 != null && r2 == null -> {
                if (!r1.isEmpty) deltas.add(r1)
            }
            r1 != null && r2 != null -> {
                if (r1.left != r2.left || r1.top != r2.top || r1.right != r2.right || r1.bottom != r2.bottom) {
                    val inter = r1.intersect(r2)
                    if (inter == null || inter.isEmpty) {
                        if (!r1.isEmpty) deltas.add(r1)
                        if (!r2.isEmpty) deltas.add(r2)
                    } else {
                        // r1 \ inter
                        if (r1.top < inter.top) {
                            val r = ClipRect(r1.left, r1.top, r1.right, inter.top)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r1.bottom > inter.bottom) {
                            val r = ClipRect(r1.left, inter.bottom, r1.right, r1.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r1.left < inter.left) {
                            val r = ClipRect(r1.left, inter.top, inter.left, inter.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r1.right > inter.right) {
                            val r = ClipRect(inter.right, inter.top, r1.right, inter.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }

                        // r2 \ inter
                        if (r2.top < inter.top) {
                            val r = ClipRect(r2.left, r2.top, r2.right, inter.top)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r2.bottom > inter.bottom) {
                            val r = ClipRect(r2.left, inter.bottom, r2.right, r2.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r2.left < inter.left) {
                            val r = ClipRect(r2.left, inter.top, inter.left, inter.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }
                        if (r2.right > inter.right) {
                            val r = ClipRect(inter.right, inter.top, r2.right, inter.bottom)
                            if (!r.isEmpty) deltas.add(r)
                        }
                    }
                }
            }
        }
    }
    return deltas
}

fun clipDelta(old: UiClipSnapshot, new: UiClipSnapshot): List<ClipRect> {
    if (old === new || old == new) return emptyList()
    val oldMap = mutableMapOf<Int, ClipRect>()
    for (i in 0 until old.count) {
        val slot = old.slotOrdinal(i).takeIf { it >= 0 } ?: i
        oldMap[slot] = ClipRect(old.left(i), old.top(i), old.right(i), old.bottom(i))
    }
    val newMap = mutableMapOf<Int, ClipRect>()
    for (i in 0 until new.count) {
        val slot = new.slotOrdinal(i).takeIf { it >= 0 } ?: i
        newMap[slot] = ClipRect(new.left(i), new.top(i), new.right(i), new.bottom(i))
    }
    return clipDelta(oldMap, newMap)
}

/**
 * 判定裁剪集合变化后的动作。
 *
 * 铁律：永远不返回 release。
 * - 几何没变或差集与当前已画墨及笔尖均不相交 -> [ClipChangeAction.KEEP_UPDATE]
 * - 差集与已有墨或当前笔尖相交 -> [ClipChangeAction.REBAKE]
 */
fun clipChangeAction(
    delta: List<ClipRect>,
    inkBounds: ClipRect?,
    headRect: ClipRect? = null,
): ClipChangeAction {
    if (delta.isEmpty()) return ClipChangeAction.KEEP_UPDATE
    if (inkBounds == null && headRect == null) return ClipChangeAction.KEEP_UPDATE
    for (d in delta) {
        if (d.isEmpty) continue
        if (inkBounds != null && d.intersects(inkBounds)) return ClipChangeAction.REBAKE
        if (headRect != null && d.intersects(headRect)) return ClipChangeAction.REBAKE
    }
    return ClipChangeAction.KEEP_UPDATE
}

/**
 * 判定画布 pose 变化是否必须释放 hold：
 * 比较位图四角在屏幕上的最大位移，位移 >= 0.5 px 或 bmpW/bmpH 变化才释放。
 * 0.001 的抖动或微小位移不释放，1 px 平移必须释放。
 */
fun poseNeedsRelease(
    old: CanvasPose?,
    new: CanvasPose,
): Boolean {
    if (old == null) return false
    if (old.bmpW != new.bmpW || old.bmpH != new.bmpH) return true

    val w = new.bmpW.toFloat()
    val h = new.bmpH.toFloat()

    val corners = floatArrayOf(
        0f, 0f,
        w, 0f,
        w, h,
        0f, h,
    )

    val radOld = Math.toRadians(old.rotation.toDouble())
    val cosOld = cos(radOld).toFloat()
    val sinOld = sin(radOld).toFloat()

    val radNew = Math.toRadians(new.rotation.toDouble())
    val cosNew = cos(radNew).toFloat()
    val sinNew = sin(radNew).toFloat()

    var i = 0
    while (i < 8) {
        val bx = corners[i]
        val by = corners[i + 1]

        val dxOld = (bx - old.halfW) * old.signX * old.scale
        val dyOld = (by - old.halfH) * old.signY * old.scale
        val sxOld = old.centerX + (dxOld * cosOld - dyOld * sinOld)
        val syOld = old.centerY + (dxOld * sinOld + dyOld * cosOld)

        val dxNew = (bx - new.halfW) * new.signX * new.scale
        val dyNew = (by - new.halfH) * new.signY * new.scale
        val sxNew = new.centerX + (dxNew * cosNew - dyNew * sinNew)
        val syNew = new.centerY + (dxNew * sinNew + dyNew * cosNew)

        val dist = hypot((sxNew - sxOld).toDouble(), (syNew - syOld).toDouble()).toFloat()
        if (dist >= 0.5f) {
            return true
        }
        i += 2
    }
    return false
}

/**
 * #81 预览提交门控：按住期间或预览清除被阻挡期间均为 false。
 */
fun previewSubmitAllowed(held: Boolean, blockPreviewClear: Boolean): Boolean =
    !held && !blockPreviewClear

/**
 * 释放 hold 时决定是否置位 #81 预览清屏挡板：
 * 仅当前缓冲真正有墨时才置挡板 (防抹除真墨)；无墨时不阻挡 #81 (避免延迟回退)。
 */
fun shouldSetPreviewClearBarrier(existingBarrier: Boolean, hasInkContent: Boolean): Boolean =
    existingBarrier || hasInkContent

/**
 * 烘焙完成后的提交动作决策（纯函数）：
 * - 未能全部烘焙：重试（不提交当前帧）；
 * - 笔画序号已过期或已放开 hold：丢弃（清除 busy，不提交已烤包，防止幽灵墨残留）；
 * - 正常按住且序号匹配：提交。
 */
enum class PresentAction {
    SUBMIT,
    DROP,
    RETRY,
}

fun decidePresentAction(seq: Long, strokeSeq: Long, held: Boolean, allBaked: Boolean): PresentAction {
    if (!allBaked) return PresentAction.RETRY
    if (seq != strokeSeq) return PresentAction.DROP
    if (!held) return PresentAction.DROP
    return PresentAction.SUBMIT
}

/**
 * 抬笔收尾校验（纯函数）：
 * committedSeq > 0 且与当前 strokeSeq 不符时（上一笔的异步收尾在下一笔 arm 之后到达），
 * 绝不能释放当前新笔画。
 */
fun shouldCommitStroke(currentSeq: Long, committedSeq: Long): Boolean {
    if (committedSeq <= 0L) return true
    return committedSeq == currentSeq
}

/**
 * 计算重烤包中脏区矩形与当前 UI 裁剪矩形的交集孔洞列表（纯函数，零分配）。
 * [screenRects] 包含 [rectCount] 块矩形 (l, t, r, b)。
 * [uiClipRects] 包含 [uiClipCount] 个 UI 裁剪矩形 (l, t, r, b)。
 * 结果写入 [outHoles]，返回交集孔洞数。
 */
fun computeRebakeHoles(
    screenRects: IntArray,
    rectCount: Int,
    uiClipRects: FloatArray,
    uiClipCount: Int,
    outHoles: FloatArray,
): Int {
    if (rectCount <= 0 || uiClipCount <= 0) return 0
    var holeCount = 0
    val maxHoles = outHoles.size / 4
    for (i in 0 until rectCount) {
        val s = i * 4
        val pL = screenRects[s].toFloat()
        val pT = screenRects[s + 1].toFloat()
        val pR = screenRects[s + 2].toFloat()
        val pB = screenRects[s + 3].toFloat()
        if (pL >= pR || pT >= pB) continue

        for (j in 0 until uiClipCount) {
            val u = j * 4
            val uL = uiClipRects[u]
            val uT = uiClipRects[u + 1]
            val uR = uiClipRects[u + 2]
            val uB = uiClipRects[u + 3]
            if (uL >= uR || uT >= uB) continue

            val interL = max(pL, uL)
            val interT = max(pT, uT)
            val interR = min(pR, uR)
            val interB = min(pB, uB)

            if (interL < interR && interT < interB) {
                if (holeCount < maxHoles) {
                    val o = holeCount * 4
                    outHoles[o] = interL
                    outHoles[o + 1] = interT
                    outHoles[o + 2] = interR
                    outHoles[o + 3] = interB
                    holeCount++
                }
            }
        }
    }
    return holeCount
}

/**
 * 计算重烤包矩形与 UI 裁剪矩形的交集矩形列表（对象版本，单测与纯逻辑复用）。
 */
fun computeRebakeHoleRects(
    packetRects: List<ClipRect>,
    uiClips: List<ClipRect>,
): List<ClipRect> {
    if (packetRects.isEmpty() || uiClips.isEmpty()) return emptyList()
    val result = ArrayList<ClipRect>()
    for (p in packetRects) {
        if (p.isEmpty) continue
        for (u in uiClips) {
            if (u.isEmpty) continue
            val inter = p.intersect(u)
            if (inter != null && !inter.isEmpty) {
                result.add(inter)
            }
        }
    }
    return result
}

/**
 * 将 4 个 Int16 边界坐标 (left, top, right, bottom) 打包进 64 位 Long。
 * 坐标范围限制在 [-32768, 32767] 内（超限自动 clamp）。
 * 空矩形打包为 0L。
 */
fun packInkBounds(l: Int, t: Int, r: Int, b: Int): Long {
    if (l >= r || t >= b) return 0L
    val cl = l.coerceIn(-32768, 32767) and 0xFFFF
    val ct = t.coerceIn(-32768, 32767) and 0xFFFF
    val cr = r.coerceIn(-32768, 32767) and 0xFFFF
    val cb = b.coerceIn(-32768, 32767) and 0xFFFF
    return (cl.toLong() shl 48) or
        (ct.toLong() shl 32) or
        (cr.toLong() shl 16) or
        cb.toLong()
}

/**
 * 从 64 位 Long 解包为 [ClipRect]。0L 或无效坐标返回 null。
 */
fun unpackInkBounds(packed: Long): ClipRect? {
    if (packed == 0L) return null
    val l = (packed ushr 48).toShort().toFloat()
    val t = ((packed ushr 32) and 0xFFFFL).toShort().toFloat()
    val r = ((packed ushr 16) and 0xFFFFL).toShort().toFloat()
    val b = (packed and 0xFFFFL).toShort().toFloat()
    if (l >= r || t >= b) return null
    return ClipRect(l, t, r, b)
}

/**
 * 空闲时槽位缩容决策（纯函数）：
 * 笔画进行中（held）或槽位被占用（isBusy）时绝不缩容；
 * 仅当空闲且尺寸超过阈值（默认 2×SLOT_MIN_PX = 1024）时缩回。
 */
fun shouldShrinkSlot(
    held: Boolean,
    isBusy: Boolean,
    width: Int,
    height: Int,
    threshold: Int = 1024,
): Boolean {
    if (held || isBusy) return false
    return width > threshold || height > threshold
}

/**
 * 根据表面实际尺寸计算合理的槽位预分配尺寸（纯函数）。
 * 不超过 [maxThreshold]（默认 1024），不小于 [minPx]（默认 512），按 256 对齐。
 */
fun sensiblePreallocateSize(
    surfaceW: Int,
    surfaceH: Int,
    minPx: Int = 512,
    maxThreshold: Int = 1024,
): Pair<Int, Int> {
    val targetW = min(surfaceW, maxThreshold).coerceAtLeast(minPx)
    val targetH = min(surfaceH, maxThreshold).coerceAtLeast(minPx)
    val w = ((targetW + 255) and -256).coerceAtLeast(minPx)
    val h = ((targetH + 255) and -256).coerceAtLeast(minPx)
    return Pair(w, h)
}
