/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 前缓冲真墨的"缓冲几何"门控 (纯 JVM、零分配, 可单测)。
 *
 * 背景: androidx `CanvasFrontBufferedRenderer` 在构造时 (FrontBufferPreviewOverlay.init) 表面往往
 * 还不存在, 真正的 HardwareBuffer 要等 `surfaceChanged(w, h)` 才按那一刻的表面尺寸/旋转提示建好;
 * 之后每次尺寸或旋转提示变化都会整套拆掉重建, `surfaceDestroyed` 则直接销毁。在此之前/之间提交的
 * 前缓冲包被静默丢弃, 而 compose 真墨包是**增量**的 (每包只贴本帧脏区), 按住期间窗口又不画这一笔:
 * 只要按住时缓冲不存在、尺寸与画布 View 不一致、或中途被重建/销毁, 就会出现断段; 抬笔后窗口按正确
 * 位置画整笔、前缓冲残留按旧几何, 两者叠在一起就是错位的平行双线。
 *
 * 规则:
 *  - 落笔时只有 [geometryMatches] (表面有效且与覆盖层 View、画布 View 同尺寸同屏幕原点) 才允许按住;
 *  - 按住期间 [stillValid] 失败 (表面销毁/尺寸变化) 立即松开, 窗口接管剩余部分;
 *  - 按住期间画布 pose 变化 ([poseChangeReleases]) 立即松开并登记追赶清屏 —— 已上屏的墨按旧 pose 烤,
 *    新包按新 pose 烤, 不能混在同一层里。
 * 松开后这一笔与关低延迟时逐像素相同 (窗口画), 不引入任何等待。
 */
internal object FrontBufferSurfaceGate {

    /**
     * 落笔判定: 前缓冲表面 (androidx 据其尺寸分配 HardwareBuffer) 必须已就绪, 且与覆盖层 View、
     * 画布 View 完全重合 —— 真墨包里的屏幕坐标是画布 View 坐标, 直接贴进缓冲坐标。
     */
    @JvmStatic
    fun geometryMatches(
        surfaceValid: Boolean,
        surfaceW: Int,
        surfaceH: Int,
        overlayW: Int,
        overlayH: Int,
        overlayX: Int,
        overlayY: Int,
        targetW: Int,
        targetH: Int,
        targetX: Int,
        targetY: Int,
    ): Boolean {
        if (!surfaceValid) return false
        if (surfaceW <= 0 || surfaceH <= 0) return false
        if (surfaceW != overlayW || surfaceH != overlayH) return false
        if (overlayW != targetW || overlayH != targetH) return false
        return overlayX == targetX && overlayY == targetY
    }

    /** 按住期间 (渲染线程逐帧): 表面仍有效且尺寸仍等于覆盖层 View 尺寸。 */
    @JvmStatic
    fun stillValid(surfaceValid: Boolean, surfaceW: Int, surfaceH: Int, overlayW: Int, overlayH: Int): Boolean =
        surfaceValid && surfaceW > 0 && surfaceH > 0 && surfaceW == overlayW && surfaceH == overlayH

    /**
     * 表面回调 (surfaceChanged/surfaceDestroyed) 到来时是否必须松开当前按住的笔:
     * 销毁或尺寸变化都意味着 androidx 丢弃了旧缓冲 (连同已上屏的增量墨)。
     */
    @JvmStatic
    fun surfaceEventReleases(held: Boolean, oldW: Int, oldH: Int, newValid: Boolean, newW: Int, newH: Int): Boolean {
        if (!held) return false
        if (!newValid) return true
        return oldW != newW || oldH != newH
    }

    /** 按住期间画布 pose 变化: 必须松开 (首次发布 pose 不算变化)。 */
    @JvmStatic
    fun poseChangeReleases(held: Boolean, oldPose: CanvasPose?, newPose: CanvasPose): Boolean =
        held && oldPose != null && oldPose != newPose
}
