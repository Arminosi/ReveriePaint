package com.reverie.paint.core.brushstore

import android.content.Context

/**
 * 笔刷存储 v2（快照 + 覆盖）的功能开关。
 *
 * - `brush_store_v2_enabled`：默认 true；关闭时 VM 走旧的 brushParams 全量路径（P0 期间旧代码完整保留）。
 * - `brush_store_v2_migrated_schema`：[LegacyBrushParamsMigrator] 完成迁移后写入的 schema 号。
 *
 * 存放在与其余 VM 偏好相同的 `paint_prefs` 文件中。
 */
object BrushStoreFlags {
    const val PREFS = "paint_prefs"
    const val KEY_ENABLED = "brush_store_v2_enabled"
    const val KEY_MIGRATED_SCHEMA = "brush_store_v2_migrated_schema"
    const val CURRENT_SCHEMA = 2

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun migratedSchema(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MIGRATED_SCHEMA, 0)

    fun setMigratedSchema(context: Context, schema: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MIGRATED_SCHEMA, schema).commit()
    }

    fun needsMigration(context: Context): Boolean = migratedSchema(context) < CURRENT_SCHEMA
}
