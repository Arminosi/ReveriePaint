/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import androidx.core.content.edit
import com.reverie.paint.R
import com.reverie.paint.model.GradientColorStop
import com.reverie.paint.model.GradientPreset
import org.json.JSONArray
import org.json.JSONObject

object GradientPresetManager {
    private const val PREFS_KEY_USER_PRESETS = "custom_gradient_presets_json"

    val BUILTIN_PRESETS = listOf(
        GradientPreset(
            id = "sunset_gold",
            nameRes = R.string.gradient_preset_sunset_gold,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF2C0B38.toInt()),
                GradientColorStop(0.35f, 0xFFB82E55.toInt()),
                GradientColorStop(0.7f, 0xFFE88A35.toInt()),
                GradientColorStop(1.0f, 0xFFFFF6A5.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "cyber_neon",
            nameRes = R.string.gradient_preset_cyber_neon,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF0F052A.toInt()),
                GradientColorStop(0.4f, 0xFF8A148D.toInt()),
                GradientColorStop(0.8f, 0xFF00E5FF.toInt()),
                GradientColorStop(1.0f, 0xFFFFFFFF.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "deep_sea_blue",
            nameRes = R.string.gradient_preset_deep_sea_blue,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF021B38.toInt()),
                GradientColorStop(0.35f, 0xFF084B83.toInt()),
                GradientColorStop(0.7f, 0xFF429EA6.toInt()),
                GradientColorStop(1.0f, 0xFFC7EDE8.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "vintage",
            nameRes = R.string.gradient_preset_vintage,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF2B1D0C.toInt()),
                GradientColorStop(0.4f, 0xFF8A5A36.toInt()),
                GradientColorStop(0.75f, 0xFFD4A373.toInt()),
                GradientColorStop(1.0f, 0xFFFAEDCD.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "lava",
            nameRes = R.string.gradient_preset_lava,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF1A0000.toInt()),
                GradientColorStop(0.3f, 0xFF800000.toInt()),
                GradientColorStop(0.6f, 0xFFFF3300.toInt()),
                GradientColorStop(0.85f, 0xFFFFCC00.toInt()),
                GradientColorStop(1.0f, 0xFFFFFFFF.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "dreamy_purple",
            nameRes = R.string.gradient_preset_dreamy_purple,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF3D1635.toInt()),
                GradientColorStop(0.35f, 0xFF8E3B68.toInt()),
                GradientColorStop(0.7f, 0xFFD8829D.toInt()),
                GradientColorStop(1.0f, 0xFFF9EAE1.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "forest_green",
            nameRes = R.string.gradient_preset_forest_green,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF0A2312.toInt()),
                GradientColorStop(0.35f, 0xFF1E522E.toInt()),
                GradientColorStop(0.7f, 0xFF4E9F3D.toInt()),
                GradientColorStop(1.0f, 0xFFD8EBB5.toInt()),
            ),
            isBuiltIn = true,
        ),
        GradientPreset(
            id = "bw_film",
            nameRes = R.string.gradient_preset_bw_film,
            stops = listOf(
                GradientColorStop(0.0f, 0xFF0D0D0D.toInt()),
                GradientColorStop(0.5f, 0xFF7A7A7A.toInt()),
                GradientColorStop(1.0f, 0xFFF0F0F0.toInt()),
            ),
            isBuiltIn = true,
        ),
    )

    fun loadUserPresets(context: Context): List<GradientPreset> {
        val prefs = context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(PREFS_KEY_USER_PRESETS, null) ?: return emptyList()
        val result = mutableListOf<GradientPreset>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val stopsArr = obj.getJSONArray("stops")
                val stops = mutableListOf<GradientColorStop>()
                for (j in 0 until stopsArr.length()) {
                    val sObj = stopsArr.getJSONObject(j)
                    stops.add(
                        GradientColorStop(
                            position = sObj.getDouble("pos").toFloat(),
                            colorArgb = sObj.getInt("color"),
                        ),
                    )
                }
                result.add(GradientPreset(id = id, nameStr = name, stops = stops, isBuiltIn = false))
            }
        } catch (_: Exception) {}
        return result
    }

    fun saveUserPresets(context: Context, presets: List<GradientPreset>) {
        val prefs = context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (preset in presets) {
            val obj = JSONObject()
            obj.put("id", preset.id)
            obj.put("name", preset.nameStr ?: "Custom")
            val stopsArr = JSONArray()
            for (stop in preset.stops) {
                val sObj = JSONObject()
                sObj.put("pos", stop.position.toDouble())
                sObj.put("color", stop.colorArgb)
                stopsArr.put(sObj)
            }
            obj.put("stops", stopsArr)
            arr.put(obj)
        }
        prefs.edit { putString(PREFS_KEY_USER_PRESETS, arr.toString()) }
    }
}
