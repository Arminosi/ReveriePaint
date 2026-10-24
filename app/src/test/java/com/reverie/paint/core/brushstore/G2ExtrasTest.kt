/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrPresetConverter
import com.reverie.paint.core.BrushParams
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * G2 矩阵三个"特别断言"格子（任务书 task-G2x-brief.md）：
 * 1. W-09 之前导入（无 abrParams）的 ABR：[OfficialBp.forId] 四级 fallback 全落空 → 数据前提
 *    上必然进 S2.4 重导名单（[ParityRepair.runOnce] 的 vm 侧逻辑含 SharedPreferences/ViewModel，
 *    无法 JVM 直测，此处只验证其数据前提，判断依据写在各用例注释里）。
 * 2. duplicate 链：复制随 [BrushImportPipeline.duplicate] 落盘 officialBp（S1 路径）；
 *    手工摘掉键的"老 duplicate"由第 4 级沿 `from` 递归兜底，取回与源一致的 bp。
 * 3. .myb：[BrushStoreBootstrap.managedEntry] 要求 ext==kpp，.myb 条目即便有快照也返回 null，
 *    即不归 v2 管（FORMAT_MATRIX #7）。
 *
 * 夹具沿用 [AbrImportResetTest] 的临时 store 模式；ABR 样本取
 * `src/test/resources/abr53/` 的真实快照（T-batch1 拷入，只读不写回）。
 */
class G2ExtrasTest {

    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("g2x").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stem = KppFixtures.all.first().first
        assertTrue(
            BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------ 夹具

    /** 从 abr53 资源目录拷一个 imp_* 进临时 store（source.json + preset.kpp），返回样本信息 */
    private class SampleCopy(
        val id: String, // imp:<uuid>
        val dir: File, // 临时 store 里的快照目录
        val name: String, // abrParams.name 原样
        val abrParams: JSONObject,
        val kppBytes: ByteArray,
        val pack: String,
    )

    private fun copyAbr53Sample(): SampleCopy {
        val resDir = File("src/test/resources/abr53")
        val src = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("imp_") }!!
            .sortedBy { it.name }.first()
        val id = "imp:" + src.name.removePrefix("imp_")
        val dest = PresetSnapshotStore.importedDir(id)
        dest.mkdirs()
        File(dest, "source.json").writeBytes(File(src, "source.json").readBytes())
        File(dest, "preset.kpp").writeBytes(File(src, "preset.kpp").readBytes())
        assertFalse("资源目录不得被写入 sidecar", File(src, "official_bp.json").exists())
        val source = JSONObject(File(src, "source.json").readText())
        val abrParams = source.getJSONObject("abrParams")
        return SampleCopy(id, dest, abrParams.getString("name"), abrParams, File(src, "preset.kpp").readBytes(), source.optString("pack"))
    }

    private fun sidecar(dir: File) = File(dir, "official_bp.json")

    // ---- 用例 1：W-09 之前导入（无 abrParams）的 ABR ----

    @Test
    fun `W09 之前导入无 abrParams 的 ABR forId 四级 fallback 全落空`() {
        val s = copyAbr53Sample()

        // 前置 sanity：真实样本 provenance == "abr"，abrParams 存在且此刻能靠第 3 级重算命中
        val source = PresetSnapshotStore.source(s.id)!!
        assertEquals("abr", source.getString("provenance"))
        assertTrue("不应有 officialBp 键（W-09 前导入）", !source.has("officialBp"))
        assertFalse("不应有 sidecar", sidecar(s.dir).exists())
        BrushIdentity.register(BrushIdentity.Entry(s.id, s.name, "kpp", BrushIdentity.Kind.IMPORTED, s.pack))
        assertNotNull("对照组：带 abrParams 时第 3 级应能重算出 bp", OfficialBp.forId(s.id))

        // 模拟 W-09 之前导入：把 source.json 的 abrParams 键删掉、其余保留（临时目录可改）
        source.remove("abrParams")
        AtomicFiles.writeText(File(s.dir, "source.json"), source.toString(1))
        // 删键时若第 3 级已写过 sidecar，一并摘掉，保证四级全部落空
        sidecar(s.dir).delete()

        assertNull("officialBp(级1)/sidecar(级2)/abrParams(级3) 全无、provenance=abr 非 duplicate(级4) → 应返回 null", OfficialBp.forId(s.id))

        // S2.4 重导名单判断依据（ParityRepair.runOnce，vm 侧逻辑无法 JVM 直测，此处验证其数据前提）：
        //   isAbrFamily(id) == true（provenance=abr，isAbrFamily 沿 source.json 首个 "abr" 即 true）
        //   && managedEntry(name) != null（v2 激活 + ext=kpp + 有快照）
        //   && OfficialBp.forId(id) == null（上已断言）
        //   ⇒ runOnce 把该预设名记入 prefs StringSet `brush_parity_reimport`（kind="parity_no_bp"）。
        assertEquals("managedEntry 应命中（数据前提 1）", s.id, BrushStoreBootstrap.managedEntry(s.name)?.id)
        assertEquals("identity 已注册且名字可反查（数据前提 2）", s.id, BrushIdentity.entryForName(s.name)?.id)
    }

    // ---- 用例 2：duplicate 链 ----

    @Test
    fun `duplicate 链 officialBp 落盘且 forId 与源一致 老数据走 L4 递归兜底`() {
        val s = copyAbr53Sample()

        // 先建一支只带 abrParams 的导入，用第 3 级重算得到一份真实官方 bp（作为后续 officialBp 的值）
        val recName = s.name + " G2X重算"
        val recId = BrushImportPipeline.importAbr(
            recName, s.kppBytes, s.abrParams, AbrPresetConverter.CONVERTER_VERSION, s.pack, presetDir,
        )
        val bp: BrushParams = requireNotNull(OfficialBp.forId(recId)) { "第 3 级应能重算 bp" }

        // officialBp 编解码与 loadBrushParams 同键同愈合：spacingCustomized=false 时 spacing
        // 恒愈合成 0.1（LegacyBrushParamsCodec 的 healedSpacing 规则），不是数据漂移。
        // 因此后续对比基准取"记录态"= 编解码往返后的值，并钉死往返唯一差异就是这次愈合。
        val bpStored: BrushParams = OfficialBpJson.fromJson(OfficialBpJson.toJson(bp))
        assertEquals("officialBp 往返唯一差异应为 spacing 愈合", bp.copy(spacing = bpStored.spacing), bpStored)

        // 源：S1 v2 导入路径——importAbr 显式传 officialBp，验证其落盘进 source.json
        val srcName = s.name + " G2X源"
        val srcId = BrushImportPipeline.importAbr(
            srcName, s.kppBytes, s.abrParams, AbrPresetConverter.CONVERTER_VERSION, s.pack, presetDir,
            JSONObject(), OfficialBpJson.toJson(bp),
        )
        val srcSource = PresetSnapshotStore.source(srcId)!!
        assertTrue("S1：officialBp 应落盘进 source.json", srcSource.has("officialBp"))
        assertEquals("级 1 取回的 bp == 记录态基准", bpStored, OfficialBp.forId(srcId))

        // 复制：新 source.json 带 officialBp，forId(newId) == forId(srcId) == bpStored
        val dupName = s.name + " G2X副本"
        val dupId = BrushImportPipeline.duplicate(srcId, dupName, presetDir)!!
        val dupSource = PresetSnapshotStore.source(dupId)!!
        assertEquals("duplicate", dupSource.getString("provenance"))
        assertEquals(srcId, dupSource.getString("from"))
        assertTrue("复制应随源落盘 officialBp", dupSource.has("officialBp"))
        assertFalse("复制不应有自己的 sidecar", sidecar(PresetSnapshotStore.importedDir(dupId)).exists())
        assertEquals("级 1：forId(newId) == forId(srcId)", bpStored, OfficialBp.forId(dupId))
        assertEquals(bpStored, OfficialBp.forId(srcId))

        // 老 duplicate：复制后从新 source.json 摘掉 officialBp 键（模拟 S1 之前的老数据），
        // 四级仅剩第 4 级：provenance=duplicate → 沿 from 递归到 srcId → 级 1 命中 bp
        val oldName = s.name + " G2X老副本"
        val oldId = BrushImportPipeline.duplicate(srcId, oldName, presetDir)!!
        val oldDir = PresetSnapshotStore.importedDir(oldId)
        val oldSource = JSONObject(File(oldDir, "source.json").readText())
        oldSource.remove("officialBp")
        AtomicFiles.writeText(File(oldDir, "source.json"), oldSource.toString(1))
        assertFalse("摘键后不应有 sidecar（否则走的是级 2 而非 L4 递归）", sidecar(oldDir).exists())
        assertNull("老 duplicate 自身 source.json 已无 officialBp", PresetSnapshotStore.source(oldId)!!.optJSONObject("officialBp"))
        assertEquals("L4 递归兜底：forId(oldId) 仍 == 源 bp", bpStored, OfficialBp.forId(oldId))
    }

    // ---- 用例 3：.myb 不归 v2 管 ----

    @Test
    fun `myb 条目 managedEntry 返回 null 即不归 v2 管`() {
        // 构造 ext="myb" 的导入条目并落快照（PresetSnapshotStore.has(id)==true），
        // 从而隔离出 managedEntry 的 ext==kpp 检查这一条（排掉"快照缺失"的干扰解释）
        val mybId = BrushIdentity.newImportedId()
        val mybName = "G2X Myb 笔刷"
        val stem = KppFixtures.all.first().first
        PresetSnapshotStore.putImported(mybId, "myb", KppFixtures.bytes(stem), JSONObject().put("provenance", "import"))
        BrushIdentity.register(BrushIdentity.Entry(mybId, mybName, "myb", BrushIdentity.Kind.IMPORTED, ""))
        assertTrue("快照应存在（隔离变量）", PresetSnapshotStore.has(mybId))
        assertNull("managedEntry 要求 ext==kpp（BrushStoreBootstrap），.myb 即便有快照也返回 null", BrushStoreBootstrap.managedEntry(mybName))

        // 对照组：同样有快照、ext="kpp" 的条目命中，证明挡住 .myb 的正是 ext 检查
        val kppId = BrushIdentity.newImportedId()
        val kppName = "G2X Kpp 笔刷"
        PresetSnapshotStore.putImported(kppId, "kpp", KppFixtures.bytes(stem), JSONObject().put("provenance", "import"))
        BrushIdentity.register(BrushIdentity.Entry(kppId, kppName, "kpp", BrushIdentity.Kind.IMPORTED, ""))
        assertEquals(kppId, BrushStoreBootstrap.managedEntry(kppName)?.id)

        // 机制说明（FORMAT_MATRIX #7）：BrushStoreVm.prepareSelect/prepareRestore 对
        // managedEntry(name) == null 直接 return（no-op），因此 .myb 选笔/恢复不进 v2 落盘链，
        // 与导入前一样走旧路径（loadBrushPreset 直读文件）。
    }
}
