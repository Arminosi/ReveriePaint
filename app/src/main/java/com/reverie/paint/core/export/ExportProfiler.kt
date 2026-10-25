/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.os.SystemClock
import android.util.Log

/**
 * 回放与动画导出轻量性能分桶统计分析器。
 * 零开销度量导出链路各阶段（重放分发、原生渲染、画布合成、编码器处理、封包写盘），
 * 导出结束时输出格式化性能报告至 Logcat (Tag: `ReverieExport`)。
 */
internal class ExportProfiler(
    private val tag: String = "ReverieExport",
) {
    @Volatile
    var enabled: Boolean = true

    private var startNs: Long = 0L
    private var totalNs: Long = 0L

    var dispatchNs: Long = 0L
    var renderNs: Long = 0L
    var compositeNs: Long = 0L
    var encodeNs: Long = 0L
    var finishNs: Long = 0L

    var renderCalls: Int = 0
    var renderSkips: Int = 0
    var renderIncremental: Int = 0
    var renderFull: Int = 0

    var outputFrames: Int = 0
    var fileSizeBytes: Long = 0L

    fun start() {
        if (!enabled) return
        startNs = SystemClock.elapsedRealtimeNanos()
        dispatchNs = 0L
        renderNs = 0L
        compositeNs = 0L
        encodeNs = 0L
        finishNs = 0L
        renderCalls = 0
        renderSkips = 0
        renderIncremental = 0
        renderFull = 0
        outputFrames = 0
        fileSizeBytes = 0L
    }

    inline fun <T> measureDispatch(block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            dispatchNs += SystemClock.elapsedRealtimeNanos() - t0
        }
    }

    inline fun <T> measureRender(block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            renderNs += SystemClock.elapsedRealtimeNanos() - t0
        }
    }

    inline fun <T> measureComposite(block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            compositeNs += SystemClock.elapsedRealtimeNanos() - t0
        }
    }

    inline fun <T> measureEncode(block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            encodeNs += SystemClock.elapsedRealtimeNanos() - t0
        }
    }

    inline fun <T> measureFinish(block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            finishNs += SystemClock.elapsedRealtimeNanos() - t0
        }
    }

    fun finish(
        format: String,
        width: Int,
        height: Int,
        frames: Int,
        sizeBytes: Long,
    ) {
        if (!enabled) return
        totalNs = SystemClock.elapsedRealtimeNanos() - startNs
        outputFrames = frames
        fileSizeBytes = sizeBytes

        val totalMs = totalNs / 1_000_000.0
        val dispatchMs = dispatchNs / 1_000_000.0
        val renderMs = renderNs / 1_000_000.0
        val compositeMs = compositeNs / 1_000_000.0
        val encodeMs = encodeNs / 1_000_000.0
        val finishMs = finishNs / 1_000_000.0

        fun pct(ms: Double): String =
            if (totalMs > 0.0) String.format(java.util.Locale.US, "%.1f%%", (ms / totalMs) * 100.0) else "0.0%"

        val sizeMb = fileSizeBytes / (1024.0 * 1024.0)
        val fpsOut = if (totalMs > 0.0) (outputFrames / (totalMs / 1000.0)) else 0.0

        val sb = StringBuilder()
        sb.appendLine("========== [ReveriePaint Export Performance Report] ==========")
        sb.appendLine(String.format(java.util.Locale.US, "Target: %s (%dx%d) | Frames: %d | Size: %.2f MB | WallTime: %.2f ms (%.1f fps)",
            format.uppercase(), width, height, outputFrames, sizeMb, totalMs, fpsOut))
        sb.appendLine(String.format(java.util.Locale.US, "  - Dispatch   : %8.2f ms (%s)", dispatchMs, pct(dispatchMs)))
        sb.appendLine(String.format(java.util.Locale.US, "  - Render     : %8.2f ms (%s) [Calls: %d, Incr: %d, Full: %d, Skip: %d]",
            renderMs, pct(renderMs), renderCalls, renderIncremental, renderFull, renderSkips))
        sb.appendLine(String.format(java.util.Locale.US, "  - Composite  : %8.2f ms (%s)", compositeMs, pct(compositeMs)))
        sb.appendLine(String.format(java.util.Locale.US, "  - Encode     : %8.2f ms (%s)", encodeMs, pct(encodeMs)))
        sb.appendLine(String.format(java.util.Locale.US, "  - Finish/Mux : %8.2f ms (%s)", finishMs, pct(finishMs)))
        sb.appendLine("==============================================================")

        Log.i(tag, sb.toString())
    }
}
