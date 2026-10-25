/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.backup

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import com.reverie.paint.model.BackupPreferenceKeys

object LocalBackupEngine {
    const val PREFS_NAME = "paint_prefs"
    const val MANIFEST_ENTRY = "manifest.json"
    const val PREFERENCES_ENTRY = "data/preferences.json"
    const val PALETTES_ENTRY = "data/palettes.json"
    const val SHORTCUTS_ENTRY = "data/shortcuts_stylus.json"

    // 排除 Android Keystore 硬件绑定的加密密码及动态缓存
    val EXCLUDED_PREF_KEYS = setOf(
        "sync_password_enc",
        "sync_last_backup",
    )

    data class CategoryEstimate(
        val count: Int,
        val totalBytes: Long,
        val description: String,
    )

    fun estimateCategory(context: Context, category: BackupCategory): CategoryEstimate {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return estimateCategory(context.filesDir, prefs.all, category)
    }

    fun estimateCategory(filesDir: File, prefsMap: Map<String, *>, category: BackupCategory): CategoryEstimate {
        return when (category) {
            BackupCategory.PROJECTS -> {
                val dir = File(filesDir, "projects")
                val files = scanFiles(dir) { it.name.endsWith(".revp", ignoreCase = true) }
                CategoryEstimate(files.size, files.sumOf { it.length() }, "${files.size} 个画作工程")
            }
            BackupCategory.AUTOSAVE -> {
                val dir = File(filesDir, "autosave")
                val files = scanFiles(dir) { it.name.endsWith(".revp", ignoreCase = true) }
                CategoryEstimate(files.size, files.sumOf { it.length() }, "${files.size} 个自动保存草稿")
            }
            BackupCategory.BRUSHES -> {
                val presets = scanFiles(File(filesDir, "paintoppresets")) { it.name.endsWith(".kpp", ignoreCase = true) }
                val tips = scanFiles(File(filesDir, "brushes"))
                val patterns = scanFiles(File(filesDir, "patterns"))
                val totalBytes = presets.sumOf { it.length() } + tips.sumOf { it.length() } + patterns.sumOf { it.length() }
                CategoryEstimate(presets.size + tips.size + patterns.size, totalBytes, "${presets.size} 个预设, ${tips.size + patterns.size} 个笔尖纹理")
            }
            BackupCategory.PALETTES -> {
                val json = (prefsMap["userPalettes"] as? String) ?: "[]"
                val count = runCatching { JSONArray(json).length() }.getOrDefault(0)
                CategoryEstimate(count, 0L, "$count 个自定义色板")
            }
            BackupCategory.SETTINGS_LAYOUT -> {
                val size = prefsMap.size
                CategoryEstimate(size, 0L, "通用偏好、UI布局与主题设置")
            }
            BackupCategory.SHORTCUTS_STYLUS -> {
                val shortcuts = (prefsMap["shortcut_bindings"] as? String) ?: "{}"
                val count = runCatching { JSONObject(shortcuts).length() }.getOrDefault(0) +
                    prefsMap.keys.count(BackupPreferenceKeys::isStylus)
                CategoryEstimate(count, 0L, "$count 组快捷键与按键绑定")
            }
            BackupCategory.REFERENCES_FONTS -> {
                val refs = scanFiles(File(filesDir, "project_references")) + scanFiles(File(filesDir, "ref_images"))
                val fonts = scanFiles(File(filesDir, "fonts"))
                val totalBytes = refs.sumOf { it.length() } + fonts.sumOf { it.length() }
                CategoryEstimate(refs.size + fonts.size, totalBytes, "${refs.size} 个参考图, ${fonts.size} 个字体")
            }
        }
    }

    fun createBackup(
        context: Context,
        selectedCategories: Set<BackupCategory>,
        outputStream: OutputStream,
        appVersion: String = "",
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createBackup(
            filesDir = context.filesDir,
            prefsMap = prefs.all,
            selectedCategories = selectedCategories,
            outputStream = outputStream,
            appVersion = appVersion,
            onProgress = onProgress,
        )
    }

    fun createBackup(
        filesDir: File,
        prefsMap: Map<String, *>,
        selectedCategories: Set<BackupCategory>,
        outputStream: OutputStream,
        appVersion: String = "",
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ) {
        val projectFiles = if (selectedCategories.contains(BackupCategory.PROJECTS)) {
            scanFiles(File(filesDir, "projects")) { it.name.endsWith(".revp", ignoreCase = true) }
        } else emptyList()

        val autosaveFiles = if (selectedCategories.contains(BackupCategory.AUTOSAVE)) {
            scanFiles(File(filesDir, "autosave")) { it.name.endsWith(".revp", ignoreCase = true) }
        } else emptyList()

        val brushPresets = if (selectedCategories.contains(BackupCategory.BRUSHES)) {
            scanFiles(File(filesDir, "paintoppresets")) { it.name.endsWith(".kpp", ignoreCase = true) }
        } else emptyList()

        val brushTips = if (selectedCategories.contains(BackupCategory.BRUSHES)) {
            scanFiles(File(filesDir, "brushes"))
        } else emptyList()

        val brushPatterns = if (selectedCategories.contains(BackupCategory.BRUSHES)) {
            scanFiles(File(filesDir, "patterns"))
        } else emptyList()

        val referenceFiles = if (selectedCategories.contains(BackupCategory.REFERENCES_FONTS)) {
            scanFiles(File(filesDir, "project_references")) + scanFiles(File(filesDir, "ref_images"))
        } else emptyList()

        val fontFiles = if (selectedCategories.contains(BackupCategory.REFERENCES_FONTS)) {
            scanFiles(File(filesDir, "fonts"))
        } else emptyList()

        val totalItems = projectFiles.size + autosaveFiles.size + brushPresets.size +
                brushTips.size + brushPatterns.size + referenceFiles.size + fontFiles.size + 4
        var processed = 0

        val manifest = BackupManifest(
            version = 1,
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            categories = selectedCategories.map { it.id },
            projects = projectFiles.map { BackupFileEntry(it.relativeTo(File(filesDir, "projects")).path, it.length(), sha256(it)) },
            autosaves = autosaveFiles.map { BackupFileEntry(it.relativeTo(File(filesDir, "autosave")).path, it.length(), sha256(it)) },
            brushPresets = brushPresets.map { it.name },
            brushTips = brushTips.map { it.name },
            brushPatterns = brushPatterns.map { it.name },
            palettesCount = if (selectedCategories.contains(BackupCategory.PALETTES)) {
                val json = (prefsMap["userPalettes"] as? String) ?: "[]"
                runCatching { JSONArray(json).length() }.getOrDefault(0)
            } else 0,
            shortcutsCount = if (selectedCategories.contains(BackupCategory.SHORTCUTS_STYLUS)) {
                val shortcuts = (prefsMap["shortcut_bindings"] as? String) ?: "{}"
                runCatching { JSONObject(shortcuts).length() }.getOrDefault(0) +
                    prefsMap.keys.count(BackupPreferenceKeys::isStylus)
            } else 0,
        )

        ZipOutputStream(outputStream.buffered()).use { zip ->
            // 1. 写入 manifest.json
            val manifestJson = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestJson)
            zip.closeEntry()
            processed++
            onProgress(processed, totalItems, "清单与配置")

            // 2. 写入偏好设置 (SETTINGS_LAYOUT)
            if (selectedCategories.contains(BackupCategory.SETTINGS_LAYOUT)) {
                val prefObj = JSONObject()
                for ((k, v) in prefsMap) {
                    if (k in EXCLUDED_PREF_KEYS) continue
                    if (k == "userPalettes" || k == "recentColors") continue
                    if (BackupPreferenceKeys.isShortcutOrStylus(k)) continue
                    val item = JSONObject()
                    when (v) {
                        is Boolean -> { item.put("t", "bool"); item.put("v", v) }
                        is Int -> { item.put("t", "int"); item.put("v", v) }
                        is Long -> { item.put("t", "long"); item.put("v", v) }
                        is Float -> { item.put("t", "float"); item.put("v", v.toDouble()) }
                        is Double -> { item.put("t", "float"); item.put("v", v) }
                        is String -> { item.put("t", "str"); item.put("v", v) }
                        else -> continue
                    }
                    prefObj.put(k, item)
                }
                zip.putNextEntry(ZipEntry(PREFERENCES_ENTRY))
                zip.write(prefObj.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            processed++

            // 3. 写入色板 (PALETTES)
            if (selectedCategories.contains(BackupCategory.PALETTES)) {
                val palObj = JSONObject()
                palObj.put("userPalettes", (prefsMap["userPalettes"] as? String) ?: "[]")
                palObj.put("recentColors", (prefsMap["recentColors"] as? String) ?: "")
                zip.putNextEntry(ZipEntry(PALETTES_ENTRY))
                zip.write(palObj.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            processed++

            // 4. 写入快捷键与数位笔 (SHORTCUTS_STYLUS)
            if (selectedCategories.contains(BackupCategory.SHORTCUTS_STYLUS)) {
                val scObj = JSONObject()
                scObj.put("shortcut_bindings", (prefsMap["shortcut_bindings"] as? String) ?: "{}")
                for ((k, v) in prefsMap) {
                    if (BackupPreferenceKeys.isStylus(k)) {
                        val item = JSONObject()
                        when (v) {
                            is Boolean -> { item.put("t", "bool"); item.put("v", v) }
                            is Int -> { item.put("t", "int"); item.put("v", v) }
                            is Long -> { item.put("t", "long"); item.put("v", v) }
                            is Float -> { item.put("t", "float"); item.put("v", v.toDouble()) }
                            is Double -> { item.put("t", "float"); item.put("v", v) }
                            is String -> { item.put("t", "str"); item.put("v", v) }
                            else -> continue
                        }
                        scObj.put(k, item)
                    }
                }
                zip.putNextEntry(ZipEntry(SHORTCUTS_ENTRY))
                zip.write(scObj.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            processed++

            // 5. 写入工程文件
            val projRoot = File(filesDir, "projects")
            for (f in projectFiles) {
                val rel = f.relativeTo(projRoot).path.replace('\\', '/')
                writeFileToZip(zip, "data/projects/$rel", f)
                processed++
                onProgress(processed, totalItems, f.name)
            }

            // 6. 写入自动保存草稿
            val autoRoot = File(filesDir, "autosave")
            for (f in autosaveFiles) {
                val rel = f.relativeTo(autoRoot).path.replace('\\', '/')
                writeFileToZip(zip, "data/autosave/$rel", f)
                processed++
                onProgress(processed, totalItems, f.name)
            }

            // 7. 写入笔刷与材质
            val presetRoot = File(filesDir, "paintoppresets")
            for (f in brushPresets) {
                writeFileToZip(zip, "data/brushes/presets/${f.name}", f)
                processed++
                onProgress(processed, totalItems, f.name)
            }
            for (f in brushTips) {
                writeFileToZip(zip, "data/brushes/tips/${f.name}", f)
                processed++
                onProgress(processed, totalItems, f.name)
            }
            for (f in brushPatterns) {
                writeFileToZip(zip, "data/brushes/patterns/${f.name}", f)
                processed++
                onProgress(processed, totalItems, f.name)
            }

            // 8. 写入参考图与字体
            val refRoot1 = File(filesDir, "project_references")
            val refRoot2 = File(filesDir, "ref_images")
            for (f in referenceFiles) {
                val rel = if (f.absolutePath.startsWith(refRoot1.absolutePath)) {
                    "data/references/project_references/" + f.relativeTo(refRoot1).path.replace('\\', '/')
                } else {
                    "data/references/ref_images/" + f.relativeTo(refRoot2).path.replace('\\', '/')
                }
                writeFileToZip(zip, rel, f)
                processed++
                onProgress(processed, totalItems, f.name)
            }

            val fontRoot = File(filesDir, "fonts")
            for (f in fontFiles) {
                writeFileToZip(zip, "data/fonts/" + f.name, f)
                processed++
                onProgress(processed, totalItems, f.name)
            }

            zip.flush()
        }
    }

    fun inspectBackup(context: Context, inputStream: InputStream): BackupInspectionResult =
        inspectBackup(context.filesDir, inputStream)

    fun inspectBackup(filesDir: File, inputStream: InputStream): BackupInspectionResult {
        var manifest: BackupManifest? = null

        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) {
                    val bytes = readEntryBytes(zip)
                    manifest = decodeManifest(String(bytes, Charsets.UTF_8))
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val parsedManifest = manifest ?: throw IllegalArgumentException("非法的 ReveriePaint 备份文件: 缺少 manifest.json")

        // 检查本地同名画作冲突
        val localProjectsDir = File(filesDir, "projects")
        val projectConflicts = parsedManifest.projects
            .map { it.relativePath }
            .filter { File(localProjectsDir, it).exists() }

        // 检查本地同名笔刷预设冲突
        val localPresetsDir = File(filesDir, "paintoppresets")
        val brushConflicts = parsedManifest.brushPresets
            .filter { File(localPresetsDir, it).exists() }

        return BackupInspectionResult(
            manifest = parsedManifest,
            existingProjectConflicts = projectConflicts,
            existingBrushConflicts = brushConflicts,
        )
    }

    fun restoreBackup(
        context: Context,
        inputStream: InputStream,
        options: RestoreOptions,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): RestoreOutcome {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return restoreBackup(
            filesDir = context.filesDir,
            prefApplier = { prefsJson, palJson, scJson ->
                prefsJson?.let { restorePreferences(prefs, it) }
                palJson?.let { restorePalettes(prefs, it) }
                scJson?.let { restoreShortcuts(prefs, it) }
            },
            inputStream = inputStream,
            options = options,
            onProgress = onProgress,
        )
    }

    fun restoreBackup(
        filesDir: File,
        prefApplier: ((preferencesJson: String?, palettesJson: String?, shortcutsJson: String?) -> Unit)?,
        inputStream: InputStream,
        options: RestoreOptions,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): RestoreOutcome {
        var restoredProjects = 0
        var restoredAutosaves = 0
        var restoredBrushes = 0
        var restoredPalettes = false
        var restoredPreferences = false
        var restoredShortcuts = false
        var restoredReferences = 0
        val errors = ArrayList<String>()

        var pendingPrefsJson: String? = null
        var pendingPalettesJson: String? = null
        var pendingShortcutsJson: String? = null

        ZipInputStream(inputStream.buffered()).use { zip ->
            var entry = zip.nextEntry
            var index = 0
            while (entry != null) {
                index++
                val name = entry.name
                when {
                    name == PREFERENCES_ENTRY && options.selectedCategories.contains(BackupCategory.SETTINGS_LAYOUT) -> {
                        onProgress(index, 0, "偏好设置")
                        runCatching {
                            pendingPrefsJson = String(readEntryBytes(zip), Charsets.UTF_8)
                            restoredPreferences = true
                        }.onFailure { errors.add("恢复偏好设置失败: ${it.message}") }
                    }

                    name == PALETTES_ENTRY && options.selectedCategories.contains(BackupCategory.PALETTES) -> {
                        onProgress(index, 0, "自定义色板")
                        runCatching {
                            pendingPalettesJson = String(readEntryBytes(zip), Charsets.UTF_8)
                            restoredPalettes = true
                        }.onFailure { errors.add("恢复色板失败: ${it.message}") }
                    }

                    name == SHORTCUTS_ENTRY && options.selectedCategories.contains(BackupCategory.SHORTCUTS_STYLUS) -> {
                        onProgress(index, 0, "快捷键与数位笔")
                        runCatching {
                            pendingShortcutsJson = String(readEntryBytes(zip), Charsets.UTF_8)
                            restoredShortcuts = true
                        }.onFailure { errors.add("恢复快捷键失败: ${it.message}") }
                    }

                    name.startsWith("data/projects/") && options.selectedCategories.contains(BackupCategory.PROJECTS) -> {
                        val rel = name.removePrefix("data/projects/")
                        if (rel.isNotEmpty()) {
                            onProgress(index, 0, rel)
                            val target = File(File(filesDir, "projects"), rel)
                            safeValidatePath(filesDir, target)
                            val dest = resolveTarget(target, options.projectConflictResolution)
                            if (dest != null) {
                                writeEntryToFile(zip, dest)
                                restoredProjects++
                            }
                        }
                    }

                    name.startsWith("data/autosave/") && options.selectedCategories.contains(BackupCategory.AUTOSAVE) -> {
                        val rel = name.removePrefix("data/autosave/")
                        if (rel.isNotEmpty()) {
                            onProgress(index, 0, rel)
                            val target = File(File(filesDir, "autosave"), rel)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                            restoredAutosaves++
                        }
                    }

                    name.startsWith("data/brushes/presets/") && options.selectedCategories.contains(BackupCategory.BRUSHES) -> {
                        val fileName = name.removePrefix("data/brushes/presets/")
                        if (fileName.isNotEmpty()) {
                            onProgress(index, 0, fileName)
                            val target = File(File(filesDir, "paintoppresets"), fileName)
                            safeValidatePath(filesDir, target)
                            val dest = resolveTarget(target, options.brushConflictResolution)
                            if (dest != null) {
                                writeEntryToFile(zip, dest)
                                restoredBrushes++
                            }
                        }
                    }

                    name.startsWith("data/brushes/tips/") && options.selectedCategories.contains(BackupCategory.BRUSHES) -> {
                        val fileName = name.removePrefix("data/brushes/tips/")
                        if (fileName.isNotEmpty()) {
                            val target = File(File(filesDir, "brushes"), fileName)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                        }
                    }

                    name.startsWith("data/brushes/patterns/") && options.selectedCategories.contains(BackupCategory.BRUSHES) -> {
                        val fileName = name.removePrefix("data/brushes/patterns/")
                        if (fileName.isNotEmpty()) {
                            val target = File(File(filesDir, "patterns"), fileName)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                        }
                    }

                    name.startsWith("data/references/project_references/") && options.selectedCategories.contains(BackupCategory.REFERENCES_FONTS) -> {
                        val rel = name.removePrefix("data/references/project_references/")
                        if (rel.isNotEmpty()) {
                            val target = File(File(filesDir, "project_references"), rel)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                            restoredReferences++
                        }
                    }

                    name.startsWith("data/references/ref_images/") && options.selectedCategories.contains(BackupCategory.REFERENCES_FONTS) -> {
                        val rel = name.removePrefix("data/references/ref_images/")
                        if (rel.isNotEmpty()) {
                            val target = File(File(filesDir, "ref_images"), rel)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                            restoredReferences++
                        }
                    }

                    name.startsWith("data/fonts/") && options.selectedCategories.contains(BackupCategory.REFERENCES_FONTS) -> {
                        val fileName = name.removePrefix("data/fonts/")
                        if (fileName.isNotEmpty()) {
                            val target = File(File(filesDir, "fonts"), fileName)
                            safeValidatePath(filesDir, target)
                            writeEntryToFile(zip, target)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        prefApplier?.invoke(pendingPrefsJson, pendingPalettesJson, pendingShortcutsJson)

        return RestoreOutcome(
            restoredProjects = restoredProjects,
            restoredAutosaves = restoredAutosaves,
            restoredBrushes = restoredBrushes,
            restoredPalettes = restoredPalettes,
            restoredPreferences = restoredPreferences,
            restoredShortcuts = restoredShortcuts,
            restoredReferences = restoredReferences,
            errors = errors,
        )
    }

    fun resolveTarget(target: File, resolution: ConflictResolution): File? {
        if (!target.exists()) return target
        return when (resolution) {
            ConflictResolution.OVERWRITE -> target
            ConflictResolution.SKIP_EXISTING -> null
            ConflictResolution.KEEP_BOTH_COPY -> {
                val dir = target.parentFile ?: return target
                val name = target.name
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var copy = File(dir, "$stem (备份恢复)$ext")
                var counter = 1
                while (copy.exists()) {
                    copy = File(dir, "$stem (备份恢复 $counter)$ext")
                    counter++
                }
                copy
            }
        }
    }

    fun safeValidatePath(baseDir: File, target: File) {
        val baseCanonical = baseDir.canonicalPath
        val targetCanonical = target.canonicalPath
        if (!targetCanonical.startsWith(baseCanonical + File.separator) && targetCanonical != baseCanonical) {
            throw SecurityException("非法的备份路径解压位置: $targetCanonical")
        }
    }

    private fun scanFiles(dir: File, filter: (File) -> Boolean = { true }): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && filter(it) }.toList()
    }

    private fun writeFileToZip(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip, 32 * 1024) }
        zip.closeEntry()
    }

    private fun writeEntryToFile(zip: ZipInputStream, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            zip.copyTo(output, 32 * 1024)
            output.flush()
            output.fd.sync()
        }
    }

    private fun readEntryBytes(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        zip.copyTo(out, 16 * 1024)
        return out.toByteArray()
    }

    fun restorePreferences(prefs: SharedPreferences, jsonStr: String) {
        val obj = JSONObject(jsonStr)
        val editor = prefs.edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = obj.getJSONObject(key)
            when (item.optString("t")) {
                "bool" -> editor.putBoolean(key, item.getBoolean("v"))
                "int" -> editor.putInt(key, item.getInt("v"))
                "long" -> editor.putLong(key, item.getLong("v"))
                "float" -> editor.putFloat(key, item.getDouble("v").toFloat())
                "str" -> editor.putString(key, item.getString("v"))
            }
        }
        editor.apply()
    }

    fun restorePalettes(prefs: SharedPreferences, jsonStr: String) {
        val obj = JSONObject(jsonStr)
        val editor = prefs.edit()
        if (obj.has("userPalettes")) {
            editor.putString("userPalettes", obj.getString("userPalettes"))
        }
        if (obj.has("recentColors")) {
            editor.putString("recentColors", obj.getString("recentColors"))
        }
        editor.apply()
    }

    fun restoreShortcuts(prefs: SharedPreferences, jsonStr: String) {
        val obj = JSONObject(jsonStr)
        val editor = prefs.edit()
        if (obj.has("shortcut_bindings")) {
            editor.putString("shortcut_bindings", obj.getString("shortcut_bindings"))
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "shortcut_bindings") continue
            val item = obj.optJSONObject(key) ?: continue
            when (item.optString("t")) {
                "bool" -> editor.putBoolean(key, item.getBoolean("v"))
                "int" -> editor.putInt(key, item.getInt("v"))
                "long" -> editor.putLong(key, item.getLong("v"))
                "float" -> editor.putFloat(key, item.getDouble("v").toFloat())
                "str" -> editor.putString(key, item.getString("v"))
            }
        }
        editor.apply()
    }

    private fun encodeManifest(m: BackupManifest): String {
        val root = JSONObject()
        root.put("version", m.version)
        root.put("createdAt", m.createdAt)
        root.put("appVersion", m.appVersion)

        val cats = JSONArray()
        m.categories.forEach { cats.put(it) }
        root.put("categories", cats)

        val projs = JSONArray()
        for (p in m.projects) {
            val po = JSONObject()
            po.put("path", p.relativePath)
            po.put("size", p.size)
            po.put("sha256", p.sha256)
            projs.put(po)
        }
        root.put("projects", projs)

        val autos = JSONArray()
        for (a in m.autosaves) {
            val ao = JSONObject()
            ao.put("path", a.relativePath)
            ao.put("size", a.size)
            ao.put("sha256", a.sha256)
            autos.put(ao)
        }
        root.put("autosaves", autos)

        val bp = JSONArray()
        m.brushPresets.forEach { bp.put(it) }
        root.put("brushPresets", bp)

        val bt = JSONArray()
        m.brushTips.forEach { bt.put(it) }
        root.put("brushTips", bt)

        val pat = JSONArray()
        m.brushPatterns.forEach { pat.put(it) }
        root.put("brushPatterns", pat)

        root.put("palettesCount", m.palettesCount)
        root.put("shortcutsCount", m.shortcutsCount)
        return root.toString(2)
    }

    private fun decodeManifest(jsonStr: String): BackupManifest {
        val root = JSONObject(jsonStr)
        val cats = ArrayList<String>()
        val catArr = root.optJSONArray("categories")
        if (catArr != null) {
            for (i in 0 until catArr.length()) cats.add(catArr.getString(i))
        }

        val projs = ArrayList<BackupFileEntry>()
        val projArr = root.optJSONArray("projects")
        if (projArr != null) {
            for (i in 0 until projArr.length()) {
                val o = projArr.getJSONObject(i)
                projs.add(BackupFileEntry(o.getString("path"), o.getLong("size"), o.optString("sha256", "")))
            }
        }

        val autos = ArrayList<BackupFileEntry>()
        val autoArr = root.optJSONArray("autosaves")
        if (autoArr != null) {
            for (i in 0 until autoArr.length()) {
                val o = autoArr.getJSONObject(i)
                autos.add(BackupFileEntry(o.getString("path"), o.getLong("size"), o.optString("sha256", "")))
            }
        }

        val bp = ArrayList<String>()
        val bpArr = root.optJSONArray("brushPresets")
        if (bpArr != null) {
            for (i in 0 until bpArr.length()) bp.add(bpArr.getString(i))
        }

        val bt = ArrayList<String>()
        val btArr = root.optJSONArray("brushTips")
        if (btArr != null) {
            for (i in 0 until btArr.length()) bt.add(btArr.getString(i))
        }

        val pat = ArrayList<String>()
        val patArr = root.optJSONArray("brushPatterns")
        if (patArr != null) {
            for (i in 0 until patArr.length()) pat.add(patArr.getString(i))
        }

        return BackupManifest(
            version = root.optInt("version", 1),
            createdAt = root.optLong("createdAt", System.currentTimeMillis()),
            appVersion = root.optString("appVersion", ""),
            categories = cats,
            projects = projs,
            autosaves = autos,
            brushPresets = bp,
            brushTips = bt,
            brushPatterns = pat,
            palettesCount = root.optInt("palettesCount", 0),
            shortcutsCount = root.optInt("shortcutsCount", 0),
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
