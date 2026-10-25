/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.AbrPresetConverter
import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import java.io.File
import org.json.JSONObject

/**
 * 官方 brushParams（导入时的换算产物）的按需取回：为"已有 ABR 导入数据"提供官方
 * bp 的取得途径，按以下四级 fallback 依次尝试，全部落空返回 null（不抛异常）：
 *
 * 1. source.json 的 `officialBp` 键（v2 导入直接写入，首选）；键存在但解析失败时
 *    log 后继续走后续级别（坏数据可被第 3 级 abrParams 重算兜底）；
 * 2. 快照目录 sidecar `official_bp.json`（本类第三级成功后回写落盘的缓存）；
 * 3. source.json 的 `abrParams` 键：[AbrParamsDecoder] 还原 [com.reverie.paint.core.AbrParser.AbrPresetInfo]，
 *    走 [AbrPresetConverter.convert] 重算官方 bp（matchedTip 传 null 合法——tipScale 只
 *    影响 kppBytes 不影响 bp），成功后把结果写 sidecar（已存在则跳过，不覆盖）；
 *    sidecar 写入不改 source.json、不改 preset.kpp（source.json 写后只读且无事后修改 API）；
 * 4. provenance == "duplicate"：沿 source.json `from` 键向上递归，对源 id 重新走
 *    完整的 1→4 级解析（老数据链根是 ABR 导入时靠第 3 级重算命中；递归在源快照
 *    目录写 sidecar 是预期副作用）；深度上限 [MAX_DUPLICATE_DEPTH] 防环，from 为空/
 *    等于当前 id/读不到 source.json → 落空。正常复制路径已随 [BrushImportPipeline.duplicate]
 *    拷贝 officialBp，此级主要兜老数据。
 *
 * 快照 kpp 的 `tipAsset` 为 null 表示计算型笔尖，与导入时一致地映射为空串。
 * 本对象只被 S2b 迁移调用，不改任何调用方逻辑。
 */
object OfficialBp {

    private const val SIDECAR_NAME = "official_bp.json"
    private const val MAX_DUPLICATE_DEPTH = 10

    fun forId(id: String): BrushParams? = resolve(id, depth = 0)

    /** 四级 fallback 主体；[depth] 为当前 duplicate 链深度，仅第 4 级递归时递增 */
    private fun resolve(id: String, depth: Int): BrushParams? {
        val source = PresetSnapshotStore.source(id)

        // 1. source.json 的 officialBp 键（解析失败不终止，继续走后续级别）
        source?.optJSONObject("officialBp")?.let { j ->
            runCatching { OfficialBpJson.fromJson(j) }
                .onSuccess { return it }
                .onFailure {
                    BrushDiagnostics.log("official_bp_level1_failed", "id" to id, "err" to it.javaClass.simpleName)
                }
        }

        // 2. sidecar official_bp.json
        val sidecar = File(PresetSnapshotStore.importedDir(id), SIDECAR_NAME)
        if (sidecar.exists()) {
            runCatching { OfficialBpJson.fromJson(JSONObject(sidecar.readText())) }
                .onSuccess { return it }
                .onFailure { BrushDiagnostics.log("official_bp_level2_failed", "id" to id, "err" to it.javaClass.simpleName) }
            // 读失败继续走下一级，不抛
        }

        // 3. source.json 的 abrParams 键：重算官方 bp
        if (source != null && source.has("abrParams")) {
            convertFromAbrParams(id, source)?.let { return it }
        }

        // 4. duplicate 兜底：沿 from 递归，对源 id 重走完整 1→4 级
        if (source?.optString("provenance", "") == "duplicate") {
            val from = source.optString("from", "")
            if (from.isEmpty() || from == id) return null
            if (depth + 1 > MAX_DUPLICATE_DEPTH) {
                BrushDiagnostics.log("official_bp_duplicate_chain_too_deep", "id" to id, "from" to from)
                return null
            }
            resolve(from, depth + 1)?.let { return it }
        }

        // 5. 都没有
        return null
    }

    /** 第 3 级：abrParams 重算。任何一步异常（convert 抛、kpp 解析抛、sidecar 写失败）log 后返回 null */
    private fun convertFromAbrParams(id: String, source: JSONObject): BrushParams? {
        return try {
            val info = AbrParamsDecoder.decode(source.getJSONObject("abrParams"))
            val snapFile = PresetSnapshotStore.snapshotFile(id)
            val parsed = snapFile?.let { KppHelper.parseKppFile(it) }
            // 计算型笔尖 tipAsset 为 null → ""，与导入时一致
            val matchedTipFileName = parsed?.tipAsset ?: ""
            val tipUuidToFileName = if (info.dualBrush == null) {
                emptyMap()
            } else {
                val masking = parsed?.maskingTipAsset ?: ""
                val dualTipUuid = info.dualBrush.tipUuid ?: ""
                BrushDiagnostics.log(
                    "official_bp_dual_map",
                    "id" to id, "tipUuid" to dualTipUuid, "maskingTipAsset" to masking,
                )
                mapOf(dualTipUuid to masking)
            }
            val entry = BrushIdentity.entry(id)
            if (entry == null) {
                BrushDiagnostics.log("official_bp_no_entry", "id" to id)
                null
            } else {
                // 只取 converted.bp，丢弃 kppBytes（快照不可改）
                val converted = AbrPresetConverter.convert(
                    info, entry.name, null, matchedTipFileName, tipUuidToFileName, source.optString("pack"),
                )
                val sidecar = File(PresetSnapshotStore.importedDir(id), SIDECAR_NAME)
                if (!sidecar.exists()) {
                    AtomicFiles.writeText(sidecar, OfficialBpJson.toJson(converted.bp).toString())
                    sidecar.setReadOnly()
                }
                converted.bp
            }
        } catch (t: Throwable) {
            BrushDiagnostics.log(
                "official_bp_convert_failed",
                "id" to id, "err" to t.javaClass.simpleName, "msg" to (t.message ?: ""),
            )
            null
        }
    }
}
