/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 统一导入管线（执行文档 W-08）：.kpp / .myb / .bundle / .zip（含根目录 kpp、嵌套 bundle）。
 *
 * - 每个导入预设分配 `imp:<uuid>`，显示名与 内置 ∪ 已有 ∪ 本批 撞名时改为 "名字 (包名)"、"名字 (包名) 2"…
 *   **绝不覆盖已有文件**（旧实现会直接覆盖同名内置笔刷）。
 * - 快照存原始字节；笔尖/纹理与已有同名但内容不同时改名为 `<包名>__<原名>`，
 *   并以 rawKeys 改写该批预设有效副本里的引用（快照不动，重置保留 rawKeys，见 [BrushEditSession.reset]）；内容相同则复用。
 * - v2 未激活时（开关关闭/启动失败）只按同样的规则落盘文件，不建快照。
 *
 * 纯 JVM 实现（不依赖 Android），zip 的 manifest/tag 解析留在 VM。
 */
object BrushImportPipeline {
    const val PROVENANCE_IMPORT = "import"
    const val MAX_DEPTH = 2
    const val SINGLE_FILE_PACK = "导入"

    data class Incoming(val wantedName: String, val ext: String, val bytes: ByteArray, val pack: String)

    data class Resource(val kind: String, val name: String, val bytes: ByteArray)

    data class Parsed(
        val presets: List<Incoming>,
        val resources: List<Resource>,
        /** 跳过的条目（嵌套 .abr 等），只记日志 */
        val skipped: List<String>,
    )

    data class Imported(val name: String, val ext: String, val id: String?, val wantedName: String, val pack: String)

    data class Result(val imported: List<Imported>, val resourceRenames: Map<String, String>)

    // ------------------------------------------------------------------ 解析

    private fun isPreset(n: String) = n.endsWith(".kpp", true) || n.endsWith(".myb", true)

    private fun isArchive(n: String) = n.endsWith(".bundle", true) || n.endsWith(".zip", true)

    /**
     * 解析 bundle/zip：`paintoppresets/` 子目录或根目录下的 .kpp/.myb、`brushes/`、`patterns/` 资源；
     * 嵌套 .bundle/.zip 递归（深度 ≤ [MAX_DEPTH]）。`_prev.png` 等其它条目忽略。
     */
    fun parseArchive(bytes: ByteArray, pack: String, depth: Int = 0): Parsed {
        val presets = ArrayList<Incoming>()
        val resources = ArrayList<Resource>()
        val skipped = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                val path = e.name.replace('\\', '/')
                if (!e.isDirectory && !path.contains("../") && !path.startsWith("__MACOSX/")) {
                    val file = path.substringAfterLast('/')
                    val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
                    when {
                        file.isBlank() || file.startsWith(".") -> Unit
                        isPreset(file) && (parent.isEmpty() || parent.equals("paintoppresets", true)) ->
                            presets += Incoming(file.substringBeforeLast('.'), file.substringAfterLast('.').lowercase(), zin.readBytes(), pack)
                        parent.equals("brushes", true) -> resources += Resource("brushes", file, zin.readBytes())
                        parent.equals("patterns", true) -> resources += Resource("patterns", file, zin.readBytes())
                        isArchive(file) && depth < MAX_DEPTH -> {
                            val nested = parseArchive(zin.readBytes(), pack, depth + 1)
                            presets += nested.presets
                            resources += nested.resources
                            skipped += nested.skipped
                        }
                        file.endsWith(".abr", true) || isArchive(file) -> skipped += path
                        else -> Unit
                    }
                }
                e = zin.nextEntry
            }
        }
        return Parsed(presets, resources, skipped)
    }

    // ------------------------------------------------------------------ 命名

    /** 内置 ∪ 已注册 ∪ 磁盘上已有的预设名 */
    fun takenNames(presetDir: File): MutableSet<String> {
        val out = HashSet<String>()
        BrushIdentity.all().forEach { out += it.name }
        presetDir.listFiles()?.forEach { f -> if (f.isFile && isPreset(f.name)) out += f.nameWithoutExtension }
        return out
    }

    /** 复制/新建用：撞名时 "名字 2"、"名字 3"… */
    fun uniqueCopyName(base: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (base.lowercase() !in lower) return base
        var n = 2
        while ("$base $n".lowercase() in lower) n++
        return "$base $n"
    }

    private fun safePack(pack: String): String =
        pack.substringBeforeLast('.').replace(Regex("""[^\w\u4e00-\u9fa5-]"""), "_").ifBlank { "import" }

    // ------------------------------------------------------------------ 资源

    /**
     * 写入笔尖/纹理。目标已存在且内容不同 → 改名 `<包名>__<原名>`（仍冲突则加序号）；内容相同 → 复用。
     * 返回 原名 → 新名（只含改了名的）。
     */
    fun installResources(resources: List<Resource>, brushDir: File, patternDir: File, pack: String): Map<String, String> {
        val renames = LinkedHashMap<String, String>()
        for (r in resources) {
            val dir = if (r.kind == "patterns") patternDir else brushDir
            dir.mkdirs()
            val target = File(dir, r.name)
            if (!target.exists()) {
                AtomicFiles.write(target, r.bytes)
                continue
            }
            if (target.readBytes().contentEquals(r.bytes)) {
                BrushDiagnostics.log("import_resource_reuse", "kind" to r.kind, "name" to r.name)
                continue
            }
            val base = "${safePack(pack)}__${r.name}"
            var name = base
            var n = 2
            while (true) {
                val f = File(dir, name)
                if (!f.exists()) {
                    AtomicFiles.write(f, r.bytes)
                    break
                }
                if (f.readBytes().contentEquals(r.bytes)) break
                name = "${base.substringBeforeLast('.')}_$n.${r.name.substringAfterLast('.', "")}".removeSuffix(".")
                n++
            }
            renames[r.name] = name
            BrushDiagnostics.log("import_resource_renamed", "kind" to r.kind, "from" to r.name, "to" to name)
        }
        return renames
    }

    /** 该预设引用了被改名资源时，返回改写引用所需的 rawKeys（不引用则为空） */
    fun rewriteRefs(xml: String, renames: Map<String, String>): Map<String, String> {
        if (renames.isEmpty()) return emptyMap()
        val raw = LinkedHashMap<String, String>()
        val tip = KppXml.brushAttr(xml, "filename")
        val newTip = tip?.let { renames[it] }
        if (newTip != null) {
            KppXml.mainBrushDef(KppXml.setBrushAttr(xml, "filename", newTip))?.let { raw[KppXml.BRUSH_DEF] = it }
            if (KppXml.get(xml, "requiredBrushFile") == tip) raw["requiredBrushFile"] = newTip
            KppXml.get(xml, "requiredBrushFilesList")?.let { l ->
                val parts = l.split(',', ';').map { it.trim() }
                if (tip in parts) raw["requiredBrushFilesList"] = l.replace(tip, newTip)
            }
        }
        val patFile = KppXml.get(xml, "Texture/Pattern/PatternFileName")
        val patName = KppXml.get(xml, "Texture/Pattern/Name")
        // 真实 kpp 的 PatternFileName 常是作者机器上的绝对路径（/home/…/patterns/x.png），按文件名匹配
        val newPat = (patFile?.substringAfterLast('/')?.substringAfterLast('\\')?.let { renames[it] }) ?: (patName?.let { renames[it] })
        if (newPat != null) {
            if (patFile != null) raw["Texture/Pattern/PatternFileName"] = newPat
            if (patName != null) raw["Texture/Pattern/Name"] = newPat
            // Krita 先按 md5 匹配纹理（KisEmbeddedTextureData.cpp:32-44）；rawKeys 只能写不能删，置空等价于"无 md5"
            if (KppXml.get(xml, "Texture/Pattern/PatternMD5") != null) raw["Texture/Pattern/PatternMD5"] = ""
            if (KppXml.get(xml, "Texture/Pattern/PatternMD5Sum") != null) raw["Texture/Pattern/PatternMD5Sum"] = ""
        }
        return raw
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 落盘一批预设。[v2] 为 true 时建快照 + identity +（需要时）rawKeys 覆盖并 materialize；
     * 否则只把（改写过引用的）字节写到唯一名字的文件。
     */
    fun commit(presets: List<Incoming>, renames: Map<String, String>, presetDir: File, v2: Boolean): List<Imported> {
        presetDir.mkdirs()
        val taken = takenNames(presetDir)
        val out = ArrayList<Imported>()
        for (p in presets) {
            val name = BrushIdentity.uniqueDisplayName(p.wantedName, p.pack, taken)
            taken += name
            val isKpp = p.ext.equals("kpp", true)
            val xml = if (isKpp) KppHelper.readPresetXml(p.bytes, stripEmbeddedResources = true) else null
            if (isKpp && xml == null) {
                BrushDiagnostics.log("import_skipped", "name" to p.wantedName, "reason" to "unreadable")
                continue
            }
            val raw = if (xml != null) rewriteRefs(xml, renames) else emptyMap()
            var id: String? = null
            if (v2 && isKpp) {
                id = BrushIdentity.newImportedId()
                PresetSnapshotStore.putImported(
                    id, p.ext, p.bytes,
                    JSONObject().put("provenance", PROVENANCE_IMPORT).put("pack", p.pack)
                        .put("file", "${p.wantedName}.${p.ext}").put("md5", BrushDiagnostics.md5(p.bytes)),
                )
                BrushIdentity.register(BrushIdentity.Entry(id, name, p.ext, BrushIdentity.Kind.IMPORTED, p.pack))
                if (raw.isNotEmpty()) BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(rawKeys = raw))
                EffectivePresetBuilder.materialize(id, presetDir)
            } else {
                val bytes = if (raw.isNotEmpty()) {
                    var x = KppHelper.readPresetXml(p.bytes, stripEmbeddedResources = false)!!
                    for ((k, v) in raw) x = KppXml.set(x, k, v)
                    KppHelper.replacePresetXml(p.bytes, x)
                } else {
                    p.bytes
                }
                AtomicFiles.write(File(presetDir, "$name.${p.ext}"), bytes)
            }
            out += Imported(name, p.ext, id, p.wantedName, p.pack)
            BrushDiagnostics.log(
                "import", "name" to name, "wanted" to p.wantedName, "pack" to p.pack, "id" to id,
                "renamed" to (name != p.wantedName), "rawKeys" to raw.size,
            )
        }
        return out
    }

    /** 单文件 .kpp/.myb */
    fun importFile(fileName: String, bytes: ByteArray, presetDir: File, v2: Boolean): Result {
        // 单文件没有"包"：撞名后缀用 "(导入)"；原文件名记在 source.json 的 file 里
        val inc = Incoming(fileName.substringBeforeLast('.'), fileName.substringAfterLast('.').lowercase(), bytes, SINGLE_FILE_PACK)
        return Result(commit(listOf(inc), emptyMap(), presetDir, v2), emptyMap())
    }

    /** .bundle/.zip */
    fun importArchive(fileName: String, bytes: ByteArray, presetDir: File, brushDir: File, patternDir: File, v2: Boolean): Result {
        val parsed = parseArchive(bytes, fileName)
        parsed.skipped.forEach { BrushDiagnostics.log("import_entry_skipped", "pack" to fileName, "entry" to it) }
        val renames = installResources(parsed.resources, brushDir, patternDir, fileName)
        return Result(commit(parsed.presets, renames, presetDir, v2), renames)
    }

    const val PROVENANCE_ABR = "abr"

    /**
     * ABR 转换结果入库（W-09）：快照 = 转换后的 kpp 字节；source.json 记 abrParams（AbrParser 原始结构）与
     * 转换器版本。覆盖为空，所以重置后字节 == 导入时。[name] 由调用方保证唯一。
     */
    fun importAbr(
        name: String,
        kppBytes: ByteArray,
        abrParams: JSONObject,
        converterVersion: Int,
        pack: String,
        presetDir: File,
        appBaseline: JSONObject = JSONObject(),
    ): String {
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, "kpp", kppBytes,
            JSONObject().put("provenance", PROVENANCE_ABR).put("pack", pack).put("file", "$name.kpp")
                .put("md5", BrushDiagnostics.md5(kppBytes))
                .put("abrConverterVersion", converterVersion).put("abrParams", abrParams)
                .put("appBaseline", appBaseline),
        )
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, pack))
        EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("import_abr", "name" to name, "id" to id, "pack" to pack, "converter" to converterVersion)
        return id
    }

    /**
     * App 自己落盘的新 kpp（新建 / ABR 转换 / 图片笔尖）纳入 v2：当前文件即快照（provenance=[provenance]）。
     * 已有 identity 或非 kpp 时不做事；返回 id。
     */
    fun adoptFile(name: String, presetDir: File, provenance: String, pack: String = ""): String? {
        if (BrushIdentity.entryForName(name) != null) return BrushIdentity.idForName(name)
        val f = File(presetDir, "$name.kpp")
        if (!f.isFile) return null
        val bytes = f.readBytes()
        if (KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) == null) return null
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, "kpp", bytes,
            JSONObject().put("provenance", provenance).put("pack", pack).put("file", f.name).put("md5", BrushDiagnostics.md5(bytes)),
        )
        BrushIdentity.register(BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, pack))
        BrushDiagnostics.log("adopt", "name" to name, "id" to id, "provenance" to provenance)
        return id
    }

    /** 复制：新快照 = 源快照，覆盖 = 源覆盖的拷贝；返回新 id */
    fun duplicate(srcId: String, newName: String, presetDir: File): String? {
        val src = BrushIdentity.entry(srcId) ?: return null
        val snap = PresetSnapshotStore.read(srcId) ?: return null
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(
            id, src.ext, snap,
            JSONObject().put("provenance", "duplicate").put("from", srcId).put("pack", src.pack).put("md5", BrushDiagnostics.md5(snap))
                .also { j -> PresetSnapshotStore.source(srcId)?.optJSONObject("appBaseline")?.let { j.put("appBaseline", it) } },
        )
        BrushIdentity.register(BrushIdentity.Entry(id, newName, src.ext, BrushIdentity.Kind.IMPORTED, src.pack))
        BrushOverrideStore.copy(srcId, id)
        EffectivePresetBuilder.materialize(id, presetDir)
        BrushDiagnostics.log("duplicate", "from" to srcId, "id" to id, "name" to newName)
        return id
    }

    /** 删除导入/复制的预设：identity、快照、覆盖一起删（否则下次启动 materializeAll 会把文件"复活"） */
    fun forget(name: String) {
        val e = BrushIdentity.entryForName(name) ?: return
        if (e.kind != BrushIdentity.Kind.IMPORTED) return
        BrushOverrideStore.clear(e.id)
        BrushIdentity.remove(e.id)
        PresetSnapshotStore.removeImported(e.id)
        PresetReadback.clearBaselineCache(e.id)
        BrushDiagnostics.log("forget", "name" to name, "id" to e.id)
    }
}
