package com.reverie.paint.core.brushstore

import org.json.JSONObject
import java.io.File

/**
 * 不可变预设快照（W-02）。
 *
 * ```
 * brush_store/snapshots/
 *   factory/<manifestMd5>/<name>.kpp|.myb      内置：assets 原样字节
 *   factory/CURRENT                            当前出厂版本号（manifest md5）
 *   imported/<id 目录名>/preset.<ext>          导入：转换完成那一刻的字节
 *   imported/<id 目录名>/source.json           来源（包名、原始文件名、ABR 源与转换参数…）
 * ```
 *
 * 快照写入后设为只读；同一 id 再次写入会被拒绝（[SnapshotExistsException]），
 * 只有 [BuiltinSnapshotMigrator] 换出厂版本时会整体换目录。
 */
object PresetSnapshotStore {
    const val DIR = "snapshots"
    const val FACTORY = "factory"
    const val IMPORTED = "imported"
    const val CURRENT_FILE = "CURRENT"

    class SnapshotExistsException(id: String) : IllegalStateException("snapshot already exists: $id")

    private val lock = Any()
    private var root: File? = null

    fun open(storeRoot: File) {
        synchronized(lock) {
            root = storeRoot
            File(storeRoot, "$DIR/$FACTORY").mkdirs()
            File(storeRoot, "$DIR/$IMPORTED").mkdirs()
        }
    }

    private fun requireRoot(): File = root ?: throw IllegalStateException("PresetSnapshotStore not opened")

    fun factoryRoot(): File = File(requireRoot(), "$DIR/$FACTORY")

    fun currentFactoryVersion(): String? =
        File(factoryRoot(), CURRENT_FILE).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun factoryDir(version: String): File = File(factoryRoot(), version)

    /** 写入一个出厂版本的全部快照（目录先在 .partial 里写好再 rename，保证要么完整要么不存在） */
    fun installFactory(version: String, files: Map<String, ByteArray>) {
        synchronized(lock) {
            val dest = factoryDir(version)
            if (dest.exists()) return
            val partial = File(factoryRoot(), "$version.partial")
            partial.deleteRecursively()
            partial.mkdirs()
            for ((fileName, bytes) in files) {
                val f = File(partial, fileName)
                f.writeBytes(bytes)
                f.setReadOnly()
            }
            if (!partial.renameTo(dest)) throw java.io.IOException("factory install rename failed")
        }
    }

    fun setCurrentFactoryVersion(version: String) {
        synchronized(lock) { AtomicFiles.writeText(File(factoryRoot(), CURRENT_FILE), version) }
    }

    /** 删除不再引用的旧出厂版本 */
    fun pruneFactory(keep: Set<String>) {
        synchronized(lock) {
            factoryRoot().listFiles()?.forEach { f ->
                if (f.isDirectory && f.name !in keep) {
                    f.walkBottomUp().forEach { it.setWritable(true) }
                    f.deleteRecursively()
                }
            }
        }
    }

    fun factoryFile(name: String, version: String? = currentFactoryVersion()): File? {
        val v = version ?: return null
        val dir = factoryDir(v)
        for (ext in listOf("kpp", "myb")) {
            val f = File(dir, "$name.$ext")
            if (f.exists()) return f
        }
        return null
    }

    fun importedDir(id: String): File = File(requireRoot(), "$DIR/$IMPORTED/${BrushIdentity.dirNameFor(id)}")

    fun putImported(id: String, ext: String, bytes: ByteArray, source: JSONObject) {
        require(BrushIdentity.isImported(id)) { "not an imported id: $id" }
        synchronized(lock) {
            val dir = importedDir(id)
            if (dir.exists() && dir.listFiles()?.any { it.name.startsWith("preset.") } == true) {
                throw SnapshotExistsException(id)
            }
            dir.mkdirs()
            val f = File(dir, "preset.$ext")
            AtomicFiles.write(f, bytes)
            f.setReadOnly()
            val s = File(dir, "source.json")
            AtomicFiles.writeText(s, source.toString(1))
            s.setReadOnly()
        }
    }

    fun removeImported(id: String) {
        synchronized(lock) {
            val dir = importedDir(id)
            dir.walkBottomUp().forEach { it.setWritable(true) }
            dir.deleteRecursively()
        }
    }

    fun importedFile(id: String): File? =
        importedDir(id).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("preset.") }

    fun source(id: String): JSONObject? {
        if (!BrushIdentity.isImported(id)) return null
        val f = File(importedDir(id), "source.json")
        return if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null
    }

    /** id → 快照文件；内置按 `builtin:<name>` 在当前出厂版本里找 */
    fun snapshotFile(id: String): File? = when {
        BrushIdentity.isBuiltin(id) -> factoryFile(id.removePrefix(BrushIdentity.BUILTIN_PREFIX))
        BrushIdentity.isImported(id) -> importedFile(id)
        else -> null
    }

    fun read(id: String): ByteArray? = snapshotFile(id)?.readBytes()

    fun has(id: String): Boolean = snapshotFile(id) != null
}
