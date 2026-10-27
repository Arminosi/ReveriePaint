/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

internal object FrontBufferQuiet {
    fun untilMs(alreadyPainting: Boolean, nowUptimeMs: Long, quietMs: Long, existingUntilMs: Long): Long {
        if (alreadyPainting) return existingUntilMs
        return maxOf(existingUntilMs, nowUptimeMs + quietMs)
    }

    /** 新文档或工程加载完成上屏时开启静默窗口 */
    fun onDocumentLoaded(nowUptimeMs: Long, quietMs: Long): Long = nowUptimeMs + quietMs
}
