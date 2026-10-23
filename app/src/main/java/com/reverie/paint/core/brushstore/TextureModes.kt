/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/**
 * Krita 纹理模式枚举（KisTextureOptionData.h:29-46 `TexturingMode`），按整数下标排列（W-03 / D-3）。
 *
 * 旧版 UI 用 hard_light/soft_light 等 Krita 不存在的名字，[toInt] 把旧名按旧写入器的整数
 * 映射兼容过来（旧写入器 KppHelper:953-965 / 原生 RCB:1735-1742 的同一张表），并记日志。
 */
object TextureModes {
    val NAMES: List<String> = listOf(
        "multiply", "subtract", "lightness", "gradient", "darken", "overlay",
        "color_dodge", "color_burn", "linear_dodge", "linear_burn",
        "hard_mix_photoshop", "hard_mix_softer_photoshop",
        "height", "linear_height", "height_photoshop", "linear_height_photoshop",
    )

    /** 旧名 → 旧写入器实际写下的整数（保持已存数据的效果不变） */
    private val LEGACY_ALIASES = mapOf(
        "dodge" to 6, "burn" to 7, "hard_light" to 10, "soft_light" to 11, "screen" to 0,
    )

    fun toInt(name: String?): Int {
        val n = name?.trim()?.lowercase().orEmpty()
        val idx = NAMES.indexOf(n)
        if (idx >= 0) return idx
        n.toIntOrNull()?.let { if (it in NAMES.indices) return it }
        LEGACY_ALIASES[n]?.let {
            BrushDiagnostics.log("texture_mode_alias", "from" to n, "to" to it)
            return it
        }
        return 0
    }

    fun nameOf(code: Int): String = NAMES.getOrElse(code) { NAMES[0] }

    /**
     * 预编译 .so 的 setBrushTexture 只认这几个字符串（RCB:1735-1742），其余一律写 0。
     * 返回 null 表示该模式无法经原生 setter 实时表达，只能靠重载 kpp 文件生效。
     */
    private val NATIVE_ARG = mapOf(
        0 to "multiply", 1 to "subtract", 4 to "darken", 5 to "overlay",
        6 to "dodge", 7 to "burn", 10 to "hard_light", 11 to "soft_light",
    )

    fun nativeArg(name: String?): String? = NATIVE_ARG[toInt(name)]
}

/**
 * 纹理参数的实时下发（W-03）。原生 setBrushTexture 会无条件覆盖 TexturingMode，
 * 对原生不认识的模式（如 linear_dodge）调用它会把模式静默改成 multiply；此时跳过实时下发，
 * 由调用方已有的 reloadEngine 路径从 kpp 文件重载生效。
 */
object BrushTextureLive {
    fun apply(enabled: Boolean, scale: Double, strength: Double, mode: String, pattern: String): Boolean {
        val arg = TextureModes.nativeArg(mode)
        if (arg == null) {
            BrushDiagnostics.log("texture_live_skipped", "mode" to mode)
            return false
        }
        com.reverie.paint.core.ReverieCoreBridge.setBrushTexture(enabled, scale, strength, arg, pattern)
        return true
    }
}
