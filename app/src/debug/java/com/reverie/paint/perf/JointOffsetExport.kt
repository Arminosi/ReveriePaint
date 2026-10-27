/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.content.Context
import com.reverie.paint.model.JointOffsetSpec
import java.io.File

/**
 * 关节微调的导出 (仅 debug 包存在)。
 *
 * 用户在设计器页面里拖动关节后点「导出」, 这里把当前 jointAngles 落到
 * `files/joint_edit.json`, 开发侧用
 * `adb shell run-as com.reverie.paint cat files/joint_edit.json` 拉回,
 * 再烘焙进 GLB 资产 (见 build/rigless/reseat_y2.py 的后续版本)。
 *
 * 正式包用 [com.reverie.paint.perf.JointOffsetExport] 的同签名空实现,
 * 与 PerfHud 一致: 走变体源集而不是 BuildConfig.DEBUG 分支, 保证逻辑不进正式包。
 */
internal object JointOffsetExport {

    val available: Boolean = true

    /** 写入 JSON, 返回落盘路径 (失败返回 null) */
    fun write(context: Context, modelType: String, preset: String, jointAngles: Map<String, Float>): String? {
        return try {
            val file = File(context.filesDir, "joint_edit.json")
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"modelType\": \"").append(modelType).append("\",\n")
            sb.append("  \"posePreset\": \"").append(preset).append("\",\n")
            sb.append("  \"exportedAt\": ").append(System.currentTimeMillis()).append(",\n")
            sb.append("  \"jointAngles\": {\n")
            val entries = jointAngles.entries.sortedBy { it.key }
            entries.forEachIndexed { index, e ->
                sb.append("    \"").append(e.key).append("\": ").append(e.value)
                if (index != entries.lastIndex) sb.append(',')
                sb.append('\n')
            }
            sb.append("  },\n")
            sb.append("  \"readable\": \"")
            sb.append(JointOffsetSpec.describe(jointAngles).replace("\n", " | "))
            sb.append("\"\n")
            sb.append("}\n")
            file.writeText(sb.toString())
            file.absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}
