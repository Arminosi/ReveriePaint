/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.ConflictResolution
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.core.backup.RestoreOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalBackupEngineTest {

    @Test
    fun `estimateCategory correctly calculates counts and sizes`() {
        val root = Files.createTempDirectory("backup-est").toFile()
        try {
            val projDir = File(root, "projects").apply { mkdirs() }
            File(projDir, "art1.revp").writeText("content-1")
            File(projDir, "art2.revp").writeText("content-2")
            File(projDir, "draft.txt").writeText("ignore")

            val prefs = mapOf(
                "userPalettes" to "[{\"id\":\"p1\",\"name\":\"Test\",\"colors\":[\"#FFFFFF\"]}]",
                "shortcut_bindings" to "{\"undo\":\"LeftCtrl + Z\",\"redo\":\"LeftCtrl + Y\"}",
                "somePref" to 123,
            )

            val projEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.PROJECTS)
            assertEquals(2, projEst.count)
            assertEquals(18L, projEst.totalBytes)

            val palEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.PALETTES)
            assertEquals(1, palEst.count)

            val scEst = LocalBackupEngine.estimateCategory(root, prefs, BackupCategory.SHORTCUTS_STYLUS)
            assertEquals(2, scEst.count)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `createBackup and inspectBackup round trip`() {
        val root = Files.createTempDirectory("backup-roundtrip").toFile()
        try {
            val projDir = File(root, "projects").apply { mkdirs() }
            File(projDir, "test.revp").writeText("drawing data")
            val brushDir = File(root, "paintoppresets").apply { mkdirs() }
            File(brushDir, "ink.kpp").writeText("preset data")

            val prefs = mapOf(
                "sync_password_enc" to "super-secret-encrypted-password",
                "userPalettes" to "[{\"id\":\"pal1\",\"name\":\"Pal\",\"colors\":[\"#FF0000\"]}]",
                "uiOpacity" to 0.85f,
            )

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = prefs,
                selectedCategories = setOf(BackupCategory.PROJECTS, BackupCategory.BRUSHES, BackupCategory.SETTINGS_LAYOUT),
                outputStream = out,
                appVersion = "1.3.4",
            )

            val zipBytes = out.toByteArray()
            assertTrue("Zip package should not be empty", zipBytes.isNotEmpty())

            val inspection = LocalBackupEngine.inspectBackup(root, ByteArrayInputStream(zipBytes))
            assertEquals(1, inspection.manifest.version)
            assertEquals("1.3.4", inspection.manifest.appVersion)
            assertEquals(1, inspection.manifest.projects.size)
            assertEquals("test.revp", inspection.manifest.projects.first().relativePath)
            assertEquals(1, inspection.manifest.brushPresets.size)
            assertEquals("ink.kpp", inspection.manifest.brushPresets.first())

            // Because the files exist in root, they should be identified as existing conflicts
            assertEquals(listOf("test.revp"), inspection.existingProjectConflicts)
            assertEquals(listOf("ink.kpp"), inspection.existingBrushConflicts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restoreBackup preserves both copies on KEEP_BOTH_COPY conflict`() {
        val srcRoot = Files.createTempDirectory("backup-src").toFile()
        val destRoot = Files.createTempDirectory("backup-dest").toFile()
        try {
            val srcProj = File(srcRoot, "projects").apply { mkdirs() }
            File(srcProj, "painting.revp").writeText("source-drawing")

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = srcRoot,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.PROJECTS),
                outputStream = out,
            )

            // In dest, pre-create the same file with different content
            val destProj = File(destRoot, "projects").apply { mkdirs() }
            val existing = File(destProj, "painting.revp").apply { writeText("local-modified") }

            var appliedPrefs: String? = null
            val outcome = LocalBackupEngine.restoreBackup(
                filesDir = destRoot,
                prefApplier = { prefs, _, _ -> appliedPrefs = prefs },
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(
                    selectedCategories = setOf(BackupCategory.PROJECTS),
                    projectConflictResolution = ConflictResolution.KEEP_BOTH_COPY,
                ),
            )

            assertEquals(1, outcome.restoredProjects)
            assertEquals("local-modified", existing.readText())

            val copyFile = File(destProj, "painting (备份恢复).revp")
            assertTrue("Should create copy file", copyFile.exists())
            assertEquals("source-drawing", copyFile.readText())
        } finally {
            srcRoot.deleteRecursively()
            destRoot.deleteRecursively()
        }
    }

    @Test
    fun `restoreBackup overwrites existing file when OVERWRITE is selected`() {
        val srcRoot = Files.createTempDirectory("backup-src-ow").toFile()
        val destRoot = Files.createTempDirectory("backup-dest-ow").toFile()
        try {
            val srcProj = File(srcRoot, "projects").apply { mkdirs() }
            File(srcProj, "painting.revp").writeText("new-backup-content")

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = srcRoot,
                prefsMap = emptyMap<String, Any>(),
                selectedCategories = setOf(BackupCategory.PROJECTS),
                outputStream = out,
            )

            val destProj = File(destRoot, "projects").apply { mkdirs() }
            val target = File(destProj, "painting.revp").apply { writeText("old-content") }

            val outcome = LocalBackupEngine.restoreBackup(
                filesDir = destRoot,
                prefApplier = null,
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(
                    selectedCategories = setOf(BackupCategory.PROJECTS),
                    projectConflictResolution = ConflictResolution.OVERWRITE,
                ),
            )

            assertEquals(1, outcome.restoredProjects)
            assertEquals("new-backup-content", target.readText())
        } finally {
            srcRoot.deleteRecursively()
            destRoot.deleteRecursively()
        }
    }

    @Test
    fun `safeValidatePath rejects zip slip traversal`() {
        val root = Files.createTempDirectory("slip-test").toFile()
        try {
            val safeFile = File(root, "projects/art.revp")
            LocalBackupEngine.safeValidatePath(root, safeFile)

            val maliciousFile = File(root, "../outside.txt")
            try {
                LocalBackupEngine.safeValidatePath(root, maliciousFile)
                fail("应当拦截 Zip Slip 目录穿越")
            } catch (e: SecurityException) {
                // Expected
                assertTrue(e.message?.contains("非法的备份路径") == true)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sensitive credentials are never included in exported preferences`() {
        val root = Files.createTempDirectory("sensitive-test").toFile()
        try {
            val prefs = mapOf(
                "sync_password_enc" to "ENCRYPTED_SECRET",
                "sync_last_backup" to 123456789L,
                "themeName" to "Morandi",
            )

            val out = ByteArrayOutputStream()
            LocalBackupEngine.createBackup(
                filesDir = root,
                prefsMap = prefs,
                selectedCategories = setOf(BackupCategory.SETTINGS_LAYOUT),
                outputStream = out,
            )

            var restoredPrefsJson: String? = null
            LocalBackupEngine.restoreBackup(
                filesDir = root,
                prefApplier = { prefsJson, _, _ -> restoredPrefsJson = prefsJson },
                inputStream = ByteArrayInputStream(out.toByteArray()),
                options = RestoreOptions(selectedCategories = setOf(BackupCategory.SETTINGS_LAYOUT)),
            )

            assertNotNull(restoredPrefsJson)
            assertFalse("不应导出 sync_password_enc", restoredPrefsJson!!.contains("sync_password_enc"))
            assertFalse("不应导出 sync_last_backup", restoredPrefsJson!!.contains("sync_last_backup"))
            assertTrue("应保留常规偏好", restoredPrefsJson!!.contains("themeName"))
        } finally {
            root.deleteRecursively()
        }
    }
}
