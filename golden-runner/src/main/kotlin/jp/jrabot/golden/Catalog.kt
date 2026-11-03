package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * ゴールデンの対象一覧と Kotlin 移植の対応表（`tools/golden/catalog.json`。旧 tools/golden/catalog.py・constants.py のデータを JSON に移した: D-258）。
 * 『登録しただけ』の項目を実装済みと数えない。Kotlin の申告（coverage）と [supported]・[unsupported] が食い違ったら比較を失敗にする。
 */
class Catalog(
    val pinned: Pinned,
    val validStatus: List<String>,
    val entries: List<Entry>,
    val supported: List<String>,
    val unsupported: Map<String, String>,
) {
    class Pinned(
        val sourceRepository: String,
        val sourceCommit: String,
        val schemaVersions: Set<Int>,
        val seed: Long,
        val timezone: String,
    )

    class Entry(
        val id: String,
        val status: String,
        val caseIds: List<String>,
        val remaining: String,
    )

    /** カタログ自体の整合と、フィクスチャ（[byCase]）との整合。旧 verify.py の『カタログ整合』と同じ。 */
    fun check(byCase: Map<String, JsonNode>): List<String> {
        val errs = ArrayList<String>()
        val seen = HashSet<String>()
        for (e in entries) {
            if (!seen.add(e.id)) errs += "catalog: id 重複 ${e.id}"
            if (e.status !in validStatus) errs += "catalog: ${e.id} の status が不正"
            if (e.status == "planned" && e.caseIds.isNotEmpty()) errs += "catalog: planned の ${e.id} に case_ids がある"
            if (e.status != "planned" && e.caseIds.isEmpty()) errs += "catalog: ${e.id} は ${e.status} だが case_ids が空"
            if (e.remaining.isEmpty()) errs += "catalog: ${e.id} に残作業/未実装理由がない"
            for (cid in e.caseIds) if (cid !in byCase) errs += "catalog: ${e.id} が存在しないフィクスチャ $cid を参照"
        }
        if (supported.toSet().size != supported.size) errs += "catalog: KOTLIN_PORT.supported に重複がある"
        for (cid in (supported.toSet() intersect unsupported.keys).sorted()) errs += "catalog: KOTLIN_PORT の対応と未対応の両方に $cid"
        for (cid in ((supported.toSet() + unsupported.keys) - byCase.keys).sorted()) errs += "catalog: KOTLIN_PORT が存在しないフィクスチャ $cid を参照"
        for (cid in (byCase.keys - supported.toSet() - unsupported.keys).sorted()) errs += "catalog: KOTLIN_PORT に分類されていないフィクスチャ $cid"
        for ((cid, reason) in unsupported) if (reason.isEmpty()) errs += "catalog: KOTLIN_PORT の未対応 $cid に理由がない"
        for ((cid, fx) in byCase) {
            for (sid in fx.get("scenario_ids")?.values().orEmpty()) if (sid.stringValue() !in seen) errs += "$cid: scenario_ids に未登録の ${sid.stringValue()}"
        }
        return errs
    }

    fun statusCounts(): Map<String, Int> = validStatus.associateWith { s -> entries.count { it.status == s } }

    companion object {
        private val mapper: JsonMapper = JsonMapper.builder().build()

        /** 形式が不正なら [IllegalArgumentException]。 */
        fun load(path: Path): Catalog {
            val j = mapper.readTree(Files.readAllBytes(path))

            fun str(
                n: JsonNode?,
                what: String,
            ): String = n?.takeIf { it.isString }?.stringValue() ?: throw IllegalArgumentException("catalog: $what が文字列でない")
            val p = j.get("pinned") ?: throw IllegalArgumentException("catalog: pinned が無い")
            val pinned =
                Pinned(
                    str(p.get("source_repository"), "pinned.source_repository"),
                    str(p.get("source_commit"), "pinned.source_commit"),
                    p.get("schema_versions")?.values()?.map { it.intValue() }?.toSet() ?: throw IllegalArgumentException("catalog: pinned.schema_versions が無い"),
                    p.get("seed")?.takeIf { it.isIntegralNumber }?.longValue() ?: throw IllegalArgumentException("catalog: pinned.seed が整数でない"),
                    str(p.get("timezone"), "pinned.timezone"),
                )
            val entries =
                j.get("catalog")?.values()?.map {
                    Entry(
                        str(it.get("id"), "catalog.id"),
                        str(it.get("status"), "catalog.status"),
                        it.get("case_ids")?.values()?.map { c -> str(c, "catalog.case_ids") } ?: throw IllegalArgumentException("catalog: case_ids が無い"),
                        it.get("remaining")?.takeIf { r -> r.isString }?.stringValue() ?: "",
                    )
                } ?: throw IllegalArgumentException("catalog: catalog が無い")
            val port = j.get("kotlin_port") ?: throw IllegalArgumentException("catalog: kotlin_port が無い")
            val sup = port.get("supported")?.values()?.map { str(it, "kotlin_port.supported") } ?: throw IllegalArgumentException("catalog: supported が無い")
            val uns = port.get("unsupported")?.properties()?.associate { (k, v) -> k to (v.takeIf { it.isString }?.stringValue() ?: "") } ?: emptyMap()
            val status = j.get("valid_status")?.values()?.map { str(it, "valid_status") } ?: throw IllegalArgumentException("catalog: valid_status が無い")
            return Catalog(pinned, status, entries, sup, uns)
        }
    }
}
