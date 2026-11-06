package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * 旧 SQLite → MySQL の移行ツール（D-271。DATA_MIGRATION_DESIGN §5・§12）。利用者の PC で、旧ファイルの**コピー**に対して実行する。
 *
 * ```
 * legacy-import preflight --sqlite <コピーした jraev.sqlite> [--out <report.json>]
 * ```
 * | 終了コード | 意味 |
 * |---|---|
 * | 0 | レポートを出した（判定 OK または WARN。WARN の内容は本人が確認して方針を決める） |
 * | 1 | 判定 NG（整合性の検査の失敗・期待する表が無い・抽出に要る列が無い） |
 * | 2 | 引数・入力の誤り・ツールの失敗（ファイルが無い・開けない・SQLite ではない・横に未反映の -wal がある・出力先が入力と同じか既にある・読み書きの失敗）。レポートは出さない |
 *
 * extract・transform・load・verify・promote は後続（今は preflight だけ。実データのクラウド投入は COMP-4 の回答待ち）。
 */
fun main(args: Array<String>) {
    exitProcess(LegacyImport.run(args, System.out, System.err))
}

object LegacyImport {
    fun run(
        args: Array<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        if (args.firstOrNull() != "preflight") {
            err.println("使い方: legacy-import preflight --sqlite <path> [--out <report.json>]")
            return 2
        }
        val opts = parse(args.drop(1)) ?: return usage(err)
        val sqlite = opts["sqlite"] ?: return usage(err)
        val (input, outPath) =
            try {
                Path.of(sqlite) to opts["out"]?.let { Path.of(it) }
            } catch (e: java.nio.file.InvalidPathException) {
                err.println("パスの形式が不正: ${e.javaClass.simpleName}")
                return 2
            }
        if (outPath != null) {
            val bad =
                try {
                    when {
                        isSameOrSidecar(outPath, input) -> "出力先が入力（またはその -wal・-shm・-journal）と同じ。別のパスを指定する"
                        Files.exists(outPath, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "出力先が既にある（上書きしない）。別のパスを指定する"
                        else -> null
                    }
                } catch (e: RuntimeException) {
                    "出力先を確かめられない: ${e.javaClass.simpleName}"
                }
            if (bad != null) {
                err.println(bad)
                return 2
            }
        }
        val report =
            try {
                Preflight.run(input)
            } catch (e: InputRejected) {
                err.println("入力を受け付けない: ${e.message}")
                return 2
            } catch (e: java.sql.SQLException) {
                err.println("SQLite を開けない・読めない: ${e.javaClass.simpleName}") // パス・中身は出さない
                return 2
            } catch (e: java.io.IOException) {
                err.println("入力を読めない: ${e.javaClass.simpleName}")
                return 2
            } catch (e: RuntimeException) {
                // 判定 NG（1）と区別する: ツールの失敗は 2（レポートは無い）
                err.println("ツールの失敗: ${e.javaClass.simpleName}")
                return 2
            }
        val json = CanonicalJson.encode(toJson(report))
        if (outPath == null) {
            out.println(json)
        } else {
            try {
                // 既存のファイルを上書きしない（入力の取り違え・前回のレポートの消失を防ぐ）
                Files.writeString(outPath, json + "\n", java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
            } catch (e: java.io.IOException) {
                err.println("レポートを書けない（既にある・書込み不可）: ${e.javaClass.simpleName}")
                return 2
            }
        }
        err.println(summary(report))
        return if (report.verdict == "NG") 1 else 0
    }

    /** 出力先が入力そのもの・リンク・横の -wal・-shm・-journal と同じか。 */
    private fun isSameOrSidecar(
        out: Path,
        input: Path,
    ): Boolean {
        val o = out.toAbsolutePath().normalize()
        val i = input.toAbsolutePath().normalize()
        if (o == i || listOf("-wal", "-shm", "-journal").any { o == i.resolveSibling(i.fileName.toString() + it) }) return true
        return Files.exists(o) && Files.exists(i) && runCatching { Files.isSameFile(o, i) }.getOrDefault(true)
    }

    private fun usage(err: PrintStream): Int {
        err.println("使い方: legacy-import preflight --sqlite <path> [--out <report.json>]")
        return 2
    }

    /** `--name value` の組（未知の名前・重複・値の欠落は null）。 */
    private fun parse(rest: List<String>): Map<String, String>? {
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < rest.size) {
            val name = rest[i].removePrefix("--")
            if (!rest[i].startsWith("--") || name !in setOf("sqlite", "out") || name in m || i + 1 >= rest.size) return null
            m[name] = rest[i + 1]
            i += 2
        }
        return m
    }

    /** 人が読む要約（件数と表・列の名前だけ）。 */
    fun summary(r: PreflightReport): String =
        buildString {
            append("preflight: ${r.verdict} (integrity=${r.integrity}, tables=${r.rowCounts.size}, rows=${r.rowCounts.values.sum()}, wal=${r.walMode})")
            if (r.missingTables.isNotEmpty()) append("\n  期待する表が無い: ${r.missingTables.joinToString()}")
            for ((t, cols) in r.missingRequiredColumns) append("\n  抽出に要る列が無い: $t.${cols.joinToString()}")
            for (w in r.warnings) append("\n  $w")
        }

    fun toJson(r: PreflightReport): JsonObject {
        fun list(xs: List<String>): JsonValue = JsonArray(xs.map { JsonString(it) })

        fun cols(m: Map<String, List<String>>): JsonValue = JsonObject(m.mapValues { list(it.value) })

        fun formats(xs: List<FormatCounts>): JsonValue =
            JsonArray(
                xs.map { f ->
                    JsonObject.of(
                        "table" to JsonString(f.table),
                        "column" to JsonString(f.column),
                        "counts" to JsonObject(f.counts.mapValues { JsonInt(it.value) }),
                    )
                },
            )
        return JsonObject.of(
            "tool" to JsonString("legacy-import preflight"),
            "schema_source" to JsonString("puttun777/jra-bot@f7dbf69"),
            "verdict" to JsonString(r.verdict),
            "sha256" to JsonString(r.sha256),
            "size_bytes" to JsonInt(r.sizeBytes),
            "wal_mode" to JsonBool(r.walMode),
            "warnings" to list(r.warnings),
            "missing_required_columns" to cols(r.missingRequiredColumns),
            "integrity" to JsonString(if (r.integrity == "ok") "ok" else "failed"), // integrity_check の詳細（ページ番号など）は出さない
            "missing_tables" to list(r.missingTables),
            "extra_tables" to list(r.extraTables),
            "missing_columns" to cols(r.missingColumns),
            "extra_columns" to cols(r.extraColumns),
            "row_counts" to JsonObject(r.rowCounts.mapValues { JsonInt(it.value) }),
            "time_formats" to formats(r.times),
            "date_formats" to formats(r.dates),
            "race_id_formats" to formats(r.raceIds),
            "json_validity" to formats(r.json),
            "orphans" to
                JsonArray(
                    r.orphans.map {
                        JsonObject.of(
                            "table" to JsonString(it.table),
                            "column" to JsonString(it.column),
                            "parent" to JsonString(it.parent),
                            "count" to JsonInt(it.count),
                        )
                    },
                ),
        )
    }
}
