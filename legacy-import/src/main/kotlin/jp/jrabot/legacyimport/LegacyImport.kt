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
 * legacy-import extract --sqlite <コピー> --out-dir <空のディレクトリ> [--chunk-rows <n>]   # P1（D-273）
 * legacy-import verify-extract --dir <extract の出力先>
 * legacy-import transform --extract <extract の出力先> --out-dir <空のディレクトリ> --tenant-id <n> [--naive-zone Asia/Tokyo] [--lenient-json]   # P2（D-274）
 * ```
 * | 終了コード | 意味 |
 * |---|---|
 * | 0 | レポートを出した（判定 OK または WARN。WARN の内容は本人が確認して方針を決める） |
 * | 1 | 判定 NG（整合性の検査の失敗・期待する表が無い・抽出に要る列が無い） |
 * | 2 | 引数・入力の誤り・ツールの失敗（ファイルが無い・開けない・SQLite ではない・横に未反映の -wal がある・出力先が入力と同じか既にある・読み書きの失敗）。レポートは出さない |
 *
 * extract: 0 = 抽出した、1 = preflight が NG（抽出しない）、2 = 引数・入力の誤り・出力先が空でない・抽出中に入力が変わった・ツールの失敗。
 * verify-extract: 0 = manifest と一致、1 = 食い違いがある、2 = 検証できない。
 * transform: 0 = 変換した（隔離した行は quarantine/ と manifest の件数）、2 = 引数・入力の誤り・extract の食い違い・ツールの失敗。
 *
 * transform・load・verify・promote は後続（実データのクラウド投入は COMP-4 の回答待ち）。
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
        val rest = args.drop(1)
        return when (args.firstOrNull()) {
            "preflight" -> parse(rest, setOf("sqlite", "out"))?.let { preflight(it, out, err) } ?: usage(err)
            "extract" -> parse(rest, setOf("sqlite", "out-dir", "chunk-rows"))?.let { extract(it, err) } ?: usage(err)
            "verify-extract" -> parse(rest, setOf("dir"))?.let { verifyExtract(it, err) } ?: usage(err)
            "transform" -> parse(rest, setOf("extract", "out-dir", "tenant-id", "naive-zone"), setOf("lenient-json"))?.let { transform(it, err) } ?: usage(err)
            else -> usage(err)
        }
    }

    private fun preflight(
        opts: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
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

    private fun extract(
        opts: Map<String, String>,
        err: PrintStream,
    ): Int {
        val sqlite = opts["sqlite"] ?: return usage(err)
        val outDir = opts["out-dir"] ?: return usage(err)
        val chunkRows =
            opts["chunk-rows"]?.let { v -> v.toIntOrNull()?.takeIf { it in Extract.CHUNK_ROWS_RANGE } ?: return usage(err) } ?: Extract.DEFAULT_CHUNK_ROWS
        return try {
            // 出力先は無いか空のディレクトリだけ（入力のファイル・入力のあるディレクトリは受け付けない）
            val m = Extract.run(Path.of(sqlite), Path.of(outDir), chunkRows)
            err.println(
                "extract: OK (tables=${m.tables.size}, rows=${m.tables.sumOf {
                    it.rows
                }}, chunks=${m.tables.sumOf { it.chunks.size }}, preflight=${m.preflightVerdict})",
            )
            0
        } catch (e: PreflightFailed) {
            err.println(summary(e.report))
            err.println("preflight が NG のため抽出しない")
            1
        } catch (e: InputRejected) {
            err.println("入力を受け付けない: ${e.message}")
            2
        } catch (e: Exception) {
            // パス・値は出さない。途中の出力は不完全（manifest.json が無い）なので、出力先を消して再実行する
            err.println("抽出に失敗した（出力先を消して再実行する）: ${e.javaClass.simpleName}")
            2
        }
    }

    private fun verifyExtract(
        opts: Map<String, String>,
        err: PrintStream,
    ): Int {
        val dir = opts["dir"] ?: return usage(err)
        return try {
            val problems = Extract.verify(Path.of(dir))
            for (p in problems) err.println("  $p")
            err.println(if (problems.isEmpty()) "verify-extract: OK" else "verify-extract: NG (${problems.size})")
            if (problems.isEmpty()) 0 else 1
        } catch (e: Exception) {
            err.println("検証できない: ${e.javaClass.simpleName}")
            2
        }
    }

    private const val USAGE =
        "使い方: legacy-import preflight --sqlite <path> [--out <report.json>]\n" +
            "       legacy-import extract --sqlite <path> --out-dir <dir> [--chunk-rows <n>]\n" +
            "       legacy-import verify-extract --dir <dir>\n" +
            "       legacy-import transform --extract <dir> --out-dir <dir> --tenant-id <n> [--naive-zone Asia/Tokyo] [--lenient-json]"

    private fun usage(err: PrintStream): Int {
        err.println(USAGE)
        return 2
    }

    /** `--name value` の組と、値を取らない `--flag`（値は "true"）。未知の名前・重複・値の欠落は null。 */
    private fun parse(
        rest: List<String>,
        names: Set<String>,
        flags: Set<String> = emptySet(),
    ): Map<String, String>? {
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < rest.size) {
            val name = rest[i].removePrefix("--")
            if (!rest[i].startsWith("--") || name in m) return null
            when (name) {
                in flags -> {
                    m[name] = "true"
                    i += 1
                }

                in names -> {
                    if (i + 1 >= rest.size) return null
                    m[name] = rest[i + 1]
                    i += 2
                }

                else -> {
                    return null
                }
            }
        }
        return m
    }

    private fun transform(
        opts: Map<String, String>,
        err: PrintStream,
    ): Int {
        val extract = opts["extract"] ?: return usage(err)
        val outDir = opts["out-dir"] ?: return usage(err)
        val tenant = opts["tenant-id"]?.toLongOrNull()?.takeIf { it > 0 } ?: return usage(err)
        val zone =
            try {
                java.time.ZoneId.of(opts["naive-zone"] ?: "Asia/Tokyo")
            } catch (e: java.time.DateTimeException) {
                return usage(err)
            }
        return try {
            val m = Transform(tenant, zone, opts["lenient-json"] == "true").run(Path.of(extract), Path.of(outDir))
            for (t in m.tables) {
                val q = t.quarantined.values.sum()
                if (q > 0 || t.adjusted.isNotEmpty()) {
                    err.println("  ${t.source} → ${t.target}: ${t.rows} 行、隔離 $q ${t.quarantined}、扱い ${t.adjusted}")
                }
            }
            val q = m.tables.sumOf { t -> t.quarantined.values.sum() }
            err.println("transform: OK (rows=${m.tables.sumOf { it.rows }}, quarantined=$q, naive_zone=${m.naiveZone})")
            0
        } catch (e: InputRejected) {
            err.println("入力を受け付けない: ${e.message}")
            2
        } catch (e: Exception) {
            err.println("変換に失敗した（出力先を消して再実行する）: ${e.javaClass.simpleName}")
            2
        }
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
