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
    viewW: Int = 0,
    viewH: Int = 0,
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
