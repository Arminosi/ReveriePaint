/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

internal object QuickShapeReplay {
    const val STEP_PX = 4f
    const val MAX_SAMPLES = 8192

    fun samples(
        original: FloatArray,
        edited: QuickShapeResult,
        perPointPressure: Boolean,
        recognized: QuickShapeResult,
    ): FloatArray {
        if (original.size < 12 || original.size % 6 != 0) return FloatArray(0)
        val outline = QuickShapeGeometry.outline(edited)
        if (outline.size < 2 || outline.any { !it.x.isFinite() || !it.y.isFinite() }) return FloatArray(0)

        // 1. 调用 QuickShapePressure.stroke 获得对齐识别形与编辑形周长的致密几何样本
        val baseSamples = QuickShapePressure.stroke(original, recognized, edited)
        if (baseSamples.size < 12) return FloatArray(0)

        // 2. 若关闭逐点压感，压力通道填充平均值；若开启则保留映射曲线
        val samplesWithPressure = if (perPointPressure) {
            baseSamples
        } else {
            val avg = QuickShapePressure.average(original)
            FloatArray(baseSamples.size).also { out ->
                baseSamples.copyInto(out)
                for (i in 0 until out.size / 6) {
                    out[i * 6 + 2] = avg
                }
            }
        }

        // 3. 超过 8192 点时的安全降采样：严格保留所有目标几何角点，仅加大常规插值段步长
        val sampleCount = samplesWithPressure.size / 6
        if (sampleCount <= MAX_SAMPLES) return samplesWithPressure

        return resamplePreservingCorners(samplesWithPressure, outline, MAX_SAMPLES)
    }

    private fun resamplePreservingCorners(samples: FloatArray, outline: List<Point2D>, maxCount: Int): FloatArray {
        val total = samples.size / 6
        val cornerSet = outline.map { Point2D(it.x, it.y) }.toSet()
        val stride = ceil(total.toDouble() / maxCount).toInt().coerceAtLeast(1)
        val result = mutableListOf<Int>()
        for (i in 0 until total) {
            val px = samples[i * 6]
            val py = samples[i * 6 + 1]
            val isCorner = cornerSet.any { abs(it.x - px) < 0.01f && abs(it.y - py) < 0.01f }
            if (isCorner || i % stride == 0 || i == total - 1) {
                result.add(i)
            }
        }
        val out = FloatArray(result.size * 6)
        result.forEachIndexed { idx, srcIdx ->
            samples.copyInto(out, idx * 6, srcIdx * 6, srcIdx * 6 + 6)
        }
        return out
    }
}
