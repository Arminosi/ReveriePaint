/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrParser
import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * T2/T3 共用夹具：把 53 支真实 ABR 设备快照（`app/src/test/resources/abr53/`，T-batch1 拷入）
 * 装进 JVM 临时 `brush_store`，并按既有 [AbrImportResetTest] 的模式初始化 [BrushStoreBootstrap]。
 *
 * 装载步骤：临时目录 → Bootstrap.run（真实出厂 kpp 造出厂版本）→ 逐目录拷贝
 * `source.json`/`preset.kpp` 到 `snapshots/imported/<目录名>/` → [BrushIdentity.registerAll]
 * 注册 `imp:<uuid>` 身份。此后 [OfficialBp.forId] 第三级 fallback 会把 sidecar
 * `official_bp.json` 写进**临时**目录（预期副作用）；资源目录本身只读不写回。
 *
 * tsv 行按名字原样（保留尾随空格）与 `abrParams.name` 精确对齐。
 */
internal object Abr53Fixture {

    const val TSV_NAME = "abr_preset_table.tsv"
    const val TSV_COLUMNS = 18

    class Store(val root: File, val presetDir: File) {
        fun importedSidecar(dirName: String): File =
            File(root, "brush_store/snapshots/imported/$dirName/official_bp.json")

        fun tearDown() {
            BrushStoreBootstrap.resetForTest()
            root.deleteRecursively()
        }
    }

    class Sample(
        val id: String, // imp:<uuid>
        val dirName: String, // 资源目录名 == BrushIdentity.dirNameFor(id)
        val name: String, // abrParams.name 原样（可能带尾随空格）
        val pack: String,
        val source: JSONObject, // source.json
        val abrParams: JSONObject,
        val info: AbrParser.AbrPresetInfo, // AbrParamsDecoder.decode(abrParams)
        val tsvRow: List<String>, // tsv 原始行（18 列，右侧缺列补空串，不 trim）
        val snapshotKpp: File, // 临时目录里的快照 kpp
    ) {
        /** 回填 bp（OfficialBp.forId 结果；首次调用后走临时目录 sidecar 缓存） */
        val bp: BrushParams by lazy { requireNotNull(OfficialBp.forId(id)) { "forId 返回 null: $id" } }

        val parsedKpp by lazy { KppHelper.parseKppFile(snapshotKpp) }

        /** tsv 第 [col] 列（0 起）trim 后的值 */
        fun tsv(col: Int): String = tsvRow[col].trim()
    }

    fun setUp(): Pair<Store, List<Sample>> {
        val resDir = File("src/test/resources/abr53")
        check(resDir.isDirectory) { "缺少 abr53 资源目录: ${resDir.absolutePath}" }
        val dirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("imp_") }!!
            .sortedBy { it.name }
        check(dirs.size == 53) { "abr53 目录数应为 53，实际 ${dirs.size}" }

        val root = Files.createTempDirectory("abr53store").toFile()
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        // 与 AbrImportResetTest 相同的 Bootstrap 初始化（一个真实出厂 kpp 造出厂版本）
        check(
            BrushStoreBootstrap.run(
                root, presetDir, true, "1",
                { mapOf("b)_Basic-1.kpp" to KppFixtures.bytes("b)_Basic-1")) },
                migrate = { true },
            ),
        ) { "BrushStoreBootstrap.run 失败" }

        val tsvRows = readTsv(File(resDir, TSV_NAME))
        check(tsvRows.size == 53) { "tsv 行数应为 53，实际 ${tsvRows.size}" }
        val byName = HashMap<String, List<String>>() // tsv 名字（原样）-> 行
        for (row in tsvRows) {
            check(byName.put(row[1], row) == null) { "tsv 名字重复: [${row[1]}]" }
        }

        val importedRoot = File(root, "brush_store/snapshots/imported")
        check(importedRoot.isDirectory) { "PresetSnapshotStore.imported 目录不存在" }
        val entries = ArrayList<BrushIdentity.Entry>(dirs.size)
        val samples = ArrayList<Sample>(dirs.size)
        for (d in dirs) {
            val srcJson = File(d, "source.json")
            val srcKpp = File(d, "preset.kpp")
            check(srcJson.isFile && srcKpp.isFile) { "样本不完整: ${d.name}" }
            val dest = File(importedRoot, d.name)
            check(dest.mkdir() || dest.isDirectory)
            File(dest, "source.json").writeBytes(srcJson.readBytes())
            File(dest, "preset.kpp").writeBytes(srcKpp.readBytes())
            // forId 第三级 sidecar 属临时目录副作用；这里确保从干净状态开始
            File(dest, "official_bp.json").delete()

            val source = JSONObject(srcJson.readText())
            val abrParams = source.getJSONObject("abrParams")
            val name = abrParams.getString("name")
            val id = "imp:" + d.name.removePrefix("imp_")
            check(BrushIdentity.dirNameFor(id) == d.name) { "目录名与 dirNameFor 不一致: ${d.name}" }
            entries += BrushIdentity.Entry(id, name, "kpp", BrushIdentity.Kind.IMPORTED, source.optString("pack"))
            val row = requireNotNull(byName[name]) { "tsv 中找不到名字: [$name]" }
            samples += Sample(
                id = id,
                dirName = d.name,
                name = name,
                pack = source.optString("pack"),
                source = source,
                abrParams = abrParams,
                info = AbrParamsDecoder.decode(abrParams),
                tsvRow = row,
                snapshotKpp = File(dest, "preset.kpp"),
            )
        }
        BrushIdentity.registerAll(entries)
        check(dirs.none { File(it, "official_bp.json").exists() }) { "资源目录被写入 sidecar（资源不得写回）" }
        return Store(root, presetDir) to samples
    }

    private fun readTsv(f: File): List<List<String>> {
        check(f.isFile) { "缺少 $TSV_NAME" }
        return f.readLines().filter { it.isNotBlank() }.map { line ->
            val cols = line.split('\t')
            (0 until TSV_COLUMNS).map { cols.getOrNull(it) ?: "" }
        }
    }
}
