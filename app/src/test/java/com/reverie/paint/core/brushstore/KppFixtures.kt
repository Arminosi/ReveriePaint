/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import java.io.File

/** 真实内置资产（app/src/main/assets/paintoppresets 下的 .kpp）作为测试夹具 */
object KppFixtures {
    val dir: File by lazy {
        listOf(File("src/main/assets/paintoppresets"), File("app/src/main/assets/paintoppresets"))
            .first { it.isDirectory }
    }

    val all: List<Pair<String, String>> by lazy {
        dir.listFiles { f -> f.name.endsWith(".kpp") }!!.sortedBy { it.name }.map { f ->
            // 去掉 <resource> 内嵌图片的 base64（映射表不碰这些元素），否则 12 MB 的 XML 让逐控件往返测试慢一个量级
            f.nameWithoutExtension to requireNotNull(KppHelper.readPresetXml(f.readBytes(), stripEmbeddedResources = true)) { "无法解析 ${f.name}" }
        }
    }

    fun xml(stem: String): String = all.first { it.first == stem }.second

    fun bytes(stem: String): ByteArray = File(dir, "$stem.kpp").readBytes()
}
