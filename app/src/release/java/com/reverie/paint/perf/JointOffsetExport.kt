/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.content.Context

/**
 * 关节微调导出在**正式版里不存在**: 与 debug 版同签名的空实现。
 *
 * 与 [PerfHud] 同一套理由: release 未开启 R8, 常量分支不会被消除,
 * 因此把入口整体放进变体源集, 正式包既没有按钮也没有 IO 代码。
 */
internal object JointOffsetExport {

    val available: Boolean = false

    @Suppress("UNUSED_PARAMETER", "SameReturnValue")
    fun write(context: Context, modelType: String, preset: String, jointAngles: Map<String, Float>): String? = null
}
