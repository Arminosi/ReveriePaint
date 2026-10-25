/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.core.backup.RestoreOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupStylusTest {

    @Test
    fun `stylus category includes actual device bindings and pressure without unrelated preferences`() {
        val root = Files.createTempDirectory("backup-stylus").toFile()
        try {
            val bindings = mapOf<String, Any>(
                "oppoDoubleTapAction" to "undo",
                "huaweiSqueezeAction" to "tool_color",
                "honorSingleClickAction" to "redo",
                "xiaomiPrimaryButtonAction" to "undo",
                "vivoSecondaryClickAction" to "redo",
                "samsungSideButtonErase" to false,
                "genericPrimaryButtonAction" to "undo",
                "genericSecondaryButtonAction" to "redo",
                "genericStylusEnabled" to true,
                "genericSideButtonErase" to false,
                "stylusHapticsIntensity" to 0.25f,
                "frontBufferPredictionEnabled" to true,
                "pressureCurvePreset" to 4,
                "pressureControlPoints" to "[{\"x\":0,\"y\":0},{\"x\":1,\"y\":1}]",
                "stylus_legacy_option" to true,
                "pressure_curve" to "legacy-curve",
            )
            val prefs = bindings + mapOf(
                "shortcut_bindings" to "{\"undo\":\"LeftCtrl + Z\"}",
                "themeName" to "Morandi",
                "sync_password_enc" to "not-for-export",
            )
            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                root, prefs, setOf(BackupCategory.SHORTCUTS_STYLUS), out,
            )
            var restored: String? = null
            var unrelated: String? = null
            LocalBackupEngine.restoreBackup(
                root, { settings, _, shortcuts -> unrelated = settings; restored = shortcuts },
                ByteArrayInputStream(out.toByteArray()),
                RestoreOptions(selectedCategories = setOf(BackupCategory.SHORTCUTS_STYLUS)),
            )
            val result = JSONObject(requireNotNull(restored))
            for ((key, expected) in bindings) {
                assertTrue("Missing stylus setting: $key", result.has(key))
                val value = result.getJSONObject(key).get("v")
                if (expected is Number) assertEquals(expected.toDouble(), (value as Number).toDouble(), 0.00001)
                else assertEquals(expected, value)
            }
            assertEquals(prefs["shortcut_bindings"], result.getString("shortcut_bindings"))
            assertFalse(result.has("themeName"))
            assertFalse(result.has("sync_password_enc"))
            assertEquals(null, unrelated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `settings-only backup does not change stylus bindings on restore`() {
        val root = Files.createTempDirectory("backup-settings-only").toFile()
        try {
            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                root, mapOf("themeName" to "Morandi", "genericPrimaryButtonAction" to "undo"),
                setOf(BackupCategory.SETTINGS_LAYOUT), out,
            )
            var settings: String? = null
            LocalBackupEngine.restoreBackup(
                root, { prefs, _, _ -> settings = prefs }, ByteArrayInputStream(out.toByteArray()),
                RestoreOptions(selectedCategories = setOf(BackupCategory.SETTINGS_LAYOUT)),
            )
            val result = JSONObject(requireNotNull(settings))
            assertTrue(result.has("themeName"))
            assertFalse(result.has("genericPrimaryButtonAction"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `old backups with stylus settings in the preferences entry remain readable`() {
        val root = Files.createTempDirectory("backup-legacy-stylus").toFile()
        try {
            val oldSettings = """{"genericPrimaryButtonAction":{"t":"str","v":"undo"}}"""
            val oldShortcuts = """{"shortcut_bindings":"{}","stylus_legacy_option":{"t":"bool","v":true}}"""
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, body) in listOf(
                    LocalBackupEngine.PREFERENCES_ENTRY to oldSettings,
                    LocalBackupEngine.SHORTCUTS_ENTRY to oldShortcuts,
                )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray())
                    zip.closeEntry()
                }
            }
            var restoredSettings: String? = null
            var restoredShortcuts: String? = null
            LocalBackupEngine.restoreBackup(
                root, { settings, _, shortcuts -> restoredSettings = settings; restoredShortcuts = shortcuts },
                ByteArrayInputStream(out.toByteArray()),
                RestoreOptions(setOf(BackupCategory.SETTINGS_LAYOUT, BackupCategory.SHORTCUTS_STYLUS)),
            )
            assertEquals(oldSettings, restoredSettings)
            assertEquals(oldShortcuts, restoredShortcuts)
        } finally {
            root.deleteRecursively()
        }
    }

}
