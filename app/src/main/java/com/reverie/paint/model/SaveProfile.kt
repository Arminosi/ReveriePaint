/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * .revp 保存阶段耗时细分与产物体积模型 (对应 C++ RevpStat 枚举)。
 * 纯 Kotlin 模型，无 Android 依赖，可直接运行 JVM 单测。
 */
data class SaveProfile(
    val totalMs: Long = 0L,
    val snapshotMs: Long = 0L,
    val encodeMs: Long = 0L,
    val writeMs: Long = 0L,
    val pngCount: Long = 0L,
    val pngBytes: Long = 0L,
    val fileBytes: Long = 0L,
    val wasAsync: Boolean = false,
    // 阶段细分指标
    val compositeMs: Long = 0L,
    val layerCloneMs: Long = 0L,
    val selMaskMs: Long = 0L,
    val convertMs: Long = 0L,
    val pngDeflateMs: Long = 0L,
    val zipWriteMs: Long = 0L,
    val closeMs: Long = 0L,
    val reusedEntries: Long = 0L,
    val reusedBytes: Long = 0L,
) {
    companion object {
        const val STAT_TOTAL = 0
        const val STAT_SNAPSHOT = 1
        const val STAT_ENCODE = 2
        const val STAT_WRITE = 3
        const val STAT_PNG_COUNT = 4
        const val STAT_PNG_BYTES = 5
        const val STAT_FILE_BYTES = 6
        const val STAT_ASYNC = 7
        const val STAT_COMPOSITE = 8
        const val STAT_LAYER_CLONE = 9
        const val STAT_SEL_MASK = 10
        const val STAT_CONVERT = 11
        const val STAT_PNG_DEFLATE = 12
        const val STAT_ZIP_WRITE = 13
        const val STAT_CLOSE = 14
        const val STAT_REUSED_ENTRIES = 15
        const val STAT_REUSED_BYTES = 16
        const val STAT_COUNT = 17

        /**
         * 从 C++ revpSaveStats 返回的长整型数组中解码 SaveProfile。
         * 若 stats 为 null、数组长度小于 8 或 totalMs <= 0，返回 null。
         * 支持向前兼容（长度为 8 的旧版数组，细分指标默认填 0）与向后兼容（长度 > 17 时忽略多余字段）。
         */
        fun fromStats(stats: LongArray?): SaveProfile? {
            if (stats == null || stats.size < 8) return null
            val total = stats[STAT_TOTAL]
            if (total <= 0L) return null

            fun getOrZero(index: Int): Long {
                return if (index < stats.size) stats[index].coerceAtLeast(0L) else 0L
            }

            return SaveProfile(
                totalMs = total,
                snapshotMs = getOrZero(STAT_SNAPSHOT),
                encodeMs = getOrZero(STAT_ENCODE),
                writeMs = getOrZero(STAT_WRITE),
                pngCount = getOrZero(STAT_PNG_COUNT),
                pngBytes = getOrZero(STAT_PNG_BYTES),
                fileBytes = getOrZero(STAT_FILE_BYTES),
                wasAsync = stats[STAT_ASYNC] != 0L,
                compositeMs = getOrZero(STAT_COMPOSITE),
                layerCloneMs = getOrZero(STAT_LAYER_CLONE),
                selMaskMs = getOrZero(STAT_SEL_MASK),
                convertMs = getOrZero(STAT_CONVERT),
                pngDeflateMs = getOrZero(STAT_PNG_DEFLATE),
                zipWriteMs = getOrZero(STAT_ZIP_WRITE),
                closeMs = getOrZero(STAT_CLOSE),
                reusedEntries = getOrZero(STAT_REUSED_ENTRIES),
                reusedBytes = getOrZero(STAT_REUSED_BYTES),
            )
        }
    }
}
