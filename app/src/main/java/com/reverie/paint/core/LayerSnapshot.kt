/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 图层快照探针 (P0 验收用, 见 docs/plans/CORE-SPLIT.md)
 *
 * `setprop debug.reverie.snapshot 1` -> 每次同步图层时用旧路径 + 快照路径各算一遍,
 *   逐字段比对并把差异与时序打到 logcat (tag: ReverieSnapshot)
 * `setprop debug.reverie.snapshot 2` -> 除比对之外, 面板数据改用快照结果 (等价性验证)
 *
 * 关闭 (0) 时唯一开销是一次返回 0 的 JNI 调用。
 */
package com.reverie.paint.core

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 与 `rp_layer_snapshot` 一一对应的解析结果 (字段偏移见 reverie_core.h) */
internal data class SnapshotLayer(
    val index: Int,
    val id: Long,
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
    val alphaLocked: Boolean,
    val isGroup: Boolean,
    val nodeType: Int,
    val depth: Int,
    val colorLabel: Int,
    val clipped: Boolean,
    val alphaInherited: Boolean,
    val isBackground: Boolean,
    val soloed: Boolean,
    val opacity: Double,
    val blendMode: String,
    val isStrokeLayer: Boolean,
    val strokeSize: Int,
    val strokeColor: Int,
    val strokePosition: Int,
    val strokeOpacity: Int,
    val fillColor: Int,
    val passThrough: Boolean,
)

internal data class SnapshotPage(
    val rev: Int,
    val currentLayer: Int,
    val soloActive: Boolean,
    val layers: List<SnapshotLayer>,
)

internal object LayerSnapshotProbe {

    private const val TAG = "ReverieSnapshot"
    private const val MAGIC = 0x43535052 // "RPSC" little-endian
    private const val HEADER_BYTES = 32

    /** 0 = 关闭, 1 = 只比对, 2 = 用快照结果 (由 native 读系统属性) */
    val mode: Int by lazy { ReverieCoreBridge.nativeSnapshotMode() }

    fun read(): SnapshotPage? {
        val size = ReverieCoreBridge.nativeSnapshotSize()
        if (size <= HEADER_BYTES) return null
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        val rev = ReverieCoreBridge.nativeSnapshotFill(buffer)
        if (rev < 0) return null
        buffer.position(0)
        if (buffer.int != MAGIC) {
            Log.w(TAG, "bad magic from native snapshot")
            return null
        }
        buffer.int // abi
        val snapRev = buffer.int
        val count = buffer.int
        val currentLayer = buffer.int
        val soloActive = buffer.int != 0
        val stride = buffer.int
        buffer.position(HEADER_BYTES)

        val layers = ArrayList<SnapshotLayer>(count)
        for (i in 0 until count) {
            val base = buffer.position()
            val id = buffer.getLong(base)
            val opacity = buffer.getDouble(base + 8)
            val index = buffer.getInt(base + 16)
            val nodeType = buffer.getInt(base + 20)
            val depth = buffer.getInt(base + 24)
            val colorLabel = buffer.getInt(base + 28)
            val strokeSize = buffer.getInt(base + 32)
            val strokePosition = buffer.getInt(base + 36)
            val strokeOpacity = buffer.getInt(base + 40)
            val strokeColor = buffer.getInt(base + 44)
            val fillColor = buffer.getInt(base + 48)
            val flags = buffer.getInt(base + 52)
            val name = readCString(buffer, base + 56, 64)
            val blend = readCString(buffer, base + 120, 32)
            buffer.position(base + stride)
            layers.add(
                SnapshotLayer(
                    index = index,
                    id = id,
                    name = name,
                    // solo 生效时面板显示由 soloKeep 决定, 与旧路径保持一致
                    visible = if (soloActive) (flags and FLAG_SOLO_SHADOWED) == 0
                    else (flags and FLAG_VISIBLE) != 0,
                    locked = (flags and FLAG_LOCKED) != 0,
                    alphaLocked = (flags and FLAG_ALPHA_LOCKED) != 0,
                    isGroup = (flags and FLAG_GROUP) != 0,
                    nodeType = nodeType,
                    depth = depth,
                    colorLabel = colorLabel,
                    clipped = (flags and FLAG_CLIPPED) != 0,
                    alphaInherited = (flags and FLAG_ALPHA_INHERITED) != 0,
                    isBackground = (flags and FLAG_BACKGROUND) != 0,
                    soloed = (flags and FLAG_SOLOED) != 0,
                    opacity = opacity,
                    blendMode = blend,
                    isStrokeLayer = (flags and FLAG_STROKE) != 0,
                    strokeSize = strokeSize,
                    strokeColor = strokeColor,
                    strokePosition = strokePosition,
                    strokeOpacity = strokeOpacity,
                    fillColor = fillColor,
                    passThrough = (flags and FLAG_PASS_THROUGH) != 0,
                ),
            )
        }
        return SnapshotPage(rev = snapRev, currentLayer = currentLayer, soloActive = soloActive, layers = layers)
    }

    /** 与旧路径逐字段比对, 返回差异描述 (最多 [limit] 条) */
    fun diff(legacy: List<PaintViewModel.LayerUiState>, snap: SnapshotPage, limit: Int = 8): List<String> {
        val out = ArrayList<String>()
        if (legacy.size != snap.layers.size) {
            out.add("count legacy=${legacy.size} snapshot=${snap.layers.size}")
            return out
        }
        for (i in legacy.indices) {
            val a = legacy[i]
            val b = snap.layers[i]
            fun check(name: String, l: Any?, s: Any?) {
                if (out.size >= limit) return
                if (l != s) out.add("layer[$i].$name legacy=$l snapshot=$s")
            }
            check("index", a.index, b.index)
            check("id", a.id, b.id)
            check("name", a.name, b.name)
            check("visible", a.visible, b.visible)
            check("locked", a.locked, b.locked)
            check("alphaLocked", a.alphaLocked, b.alphaLocked)
            check("isGroup", a.isGroup, b.isGroup)
            check("nodeType", a.nodeType, b.nodeType)
            check("depth", a.depth, b.depth)
            check("colorLabel", a.colorLabel, b.colorLabel)
            check("clipped", a.clipped, b.clipped)
            check("alphaInherited", a.alphaInherited, b.alphaInherited)
            check("isBackground", a.isBackground, b.isBackground)
            check("soloed", a.soloed, b.soloed)
            check("opacity", a.opacity, b.opacity)
            check("blendMode", a.blendMode, b.blendMode)
            check("isStrokeLayer", a.isStrokeLayer, b.isStrokeLayer)
            check("strokeSize", a.strokeSize, b.strokeSize)
            check("strokeColor", a.strokeColor, b.strokeColor)
            check("strokePosition", a.strokePosition, b.strokePosition)
            check("strokeOpacity", a.strokeOpacity, b.strokeOpacity)
            check("fillColor", a.fillColor, b.fillColor)
            check("passThrough", a.passThrough, b.passThrough)
        }
        return out
    }

    fun toUiState(layer: SnapshotLayer): PaintViewModel.LayerUiState = PaintViewModel.LayerUiState(
        index = layer.index,
        id = layer.id,
        name = layer.name,
        visible = layer.visible,
        locked = layer.locked,
        alphaLocked = layer.alphaLocked,
        isGroup = layer.isGroup,
        nodeType = layer.nodeType,
        depth = layer.depth,
        colorLabel = layer.colorLabel,
        clipped = layer.clipped,
        alphaInherited = layer.alphaInherited,
        isBackground = layer.isBackground,
        soloed = layer.soloed,
        opacity = layer.opacity,
        blendMode = layer.blendMode,
        isStrokeLayer = layer.isStrokeLayer,
        strokeSize = layer.strokeSize,
        strokeColor = layer.strokeColor,
        strokePosition = layer.strokePosition,
        strokeOpacity = layer.strokeOpacity,
        fillColor = layer.fillColor,
        passThrough = layer.passThrough,
    )

    fun reportDiff(rev: Int, legacyUs: Long, snapshotUs: Long, diffs: List<String>) {
        if (diffs.isEmpty()) {
            Log.i(TAG, "rev=$rev equal (legacy=${legacyUs}us snapshot=${snapshotUs}us)")
        } else {
            Log.w(TAG, "rev=$rev DIFF ${diffs.size}+ (legacy=${legacyUs}us snapshot=${snapshotUs}us)")
            diffs.forEach { Log.w(TAG, "  $it") }
        }
    }

    private fun readCString(buffer: ByteBuffer, offset: Int, cap: Int): String {
        var end = offset
        while (end < offset + cap && buffer.get(end).toInt() != 0) end++
        val bytes = ByteArray(end - offset)
        for (i in bytes.indices) bytes[i] = buffer.get(offset + i)
        return String(bytes, Charsets.UTF_8)
    }

    private const val FLAG_VISIBLE = 1 shl 0
    private const val FLAG_LOCKED = 1 shl 1
    private const val FLAG_ALPHA_LOCKED = 1 shl 2
    private const val FLAG_GROUP = 1 shl 3
    private const val FLAG_STROKE = 1 shl 4
    private const val FLAG_BACKGROUND = 1 shl 5
    private const val FLAG_SOLOED = 1 shl 6
    private const val FLAG_CLIPPED = 1 shl 7
    private const val FLAG_ALPHA_INHERITED = 1 shl 8
    private const val FLAG_PASS_THROUGH = 1 shl 9
    private const val FLAG_SOLO_SHADOWED = 1 shl 10
}
