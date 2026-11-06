package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.io.BufferedReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 1 チャンク（1 ファイル）の記録。キーは主キーの値（JSON の配列）。 */
data class ChunkEntry(
    val no: Int,
    val file: String,
    val rows: Long,
    val keyFrom: JsonValue,
    val keyTo: JsonValue,
    /** 展開した NDJSON（各行 + 改行）の sha256。gzip の実装に依らない。 */
    val sha256: String,
)

data class TableEntry(
    val table: String,
    /** 出力した列（期待するスキーマの順。旧 DB に無い起動時の列は含まない）。 */
    val columns: List<String>,
    val primaryKey: List<String>,
    val rows: Long,
    val chunks: List<ChunkEntry>,
)

data class ExtractManifest(
    val inputSha256: String,
    val inputSizeBytes: Long,
    val chunkRows: Int,
    val preflightVerdict: String,
    val tables: List<TableEntry>,
)

/**
 * 抽出（DATA_MIGRATION_DESIGN §5.2 P1。D-273）: 旧 SQLite のコピーを読取り専用で開き、期待するスキーマの表・列だけを主キーの順に読み、
 * [chunkRows] 行ごとの `<表>/<番号>.ndjson.gz`（1 行 = 列の値の JSON 配列）と `manifest.json`（行数・主キーの範囲・内容の sha256）に書く。
 *
 * - 値は SQLite の保存の型のまま: 整数 → 整数、実数 → 小数（往復で同じ値になる最短の表記）、文字列 → 文字列、NULL → null。
 *   JSON で表せない値は印付きの object: BLOB → `{"$blob": base64}`、無限大 → `{"$real": "inf" | "-inf"}`（変換の段階で扱いを決める）。
 * - 期待に無い表・列は出さない（preflight が WARN で知らせる）。期待するスキーマの列が無ければ（起動時の列）その列を出さない。
 * - 出力先は空でなければ受け付けない。途中で失敗したら出力先を消して再実行する（抽出は決定的: 同じ入力から同じ内容）。
 *   `manifest.json` は最後に書く（これが無い出力先は不完全）。チャンク単位の再開は load の段階（`import_chunk`）で持つ。
 * - 抽出の前後で入力の sha256 が変わったら失敗（2）。
 */
object Extract {
    const val MANIFEST = "manifest.json"
    const val FORMAT = "legacy-import extract v1"
    const val DEFAULT_CHUNK_ROWS = 10_000
    val CHUNK_ROWS_RANGE = 1..1_000_000

    fun run(
        sqlite: Path,
        outDir: Path,
        chunkRows: Int = DEFAULT_CHUNK_ROWS,
    ): ExtractManifest {
        require(chunkRows in CHUNK_ROWS_RANGE) { "チャンクの行数は ${CHUNK_ROWS_RANGE.first}〜${CHUNK_ROWS_RANGE.last}" }
        checkOutDir(outDir)
        val report = Preflight.run(sqlite)
        if (report.verdict == "NG") throw PreflightFailed(report)
        checkOutDir(outDir)
        Files.createDirectories(outDir)
        val tables =
            Preflight.open(sqlite).use { c ->
                val byLower = LegacySchema.tables(c).associateBy { it.lowercase() }
                LegacySchema.expected.keys
                    .sorted()
                    .map { t -> extractTable(c, t, checkNotNull(byLower[t.lowercase()]), outDir, chunkRows) }
            }
        if (Preflight.sha256(sqlite) != report.sha256) throw InputRejected("抽出の間に入力が変わった。旧アプリを止めたコピーで再実行する")
        val manifest = ExtractManifest(report.sha256, report.sizeBytes, chunkRows, report.verdict, tables)
        Files.writeString(outDir.resolve(MANIFEST), CanonicalJson.encode(toJson(manifest)) + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return manifest
    }

    /** 出力先は無いか、空のディレクトリだけ受け付ける（前回の出力・入力を上書きしない）。 */
    private fun checkOutDir(outDir: Path) {
        if (!Files.exists(outDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(outDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw InputRejected("出力先がディレクトリではない")
        Files.list(outDir).use { if (it.findFirst().isPresent) throw InputRejected("出力先が空ではない（前回の出力を上書きしない）") }
    }

    private fun extractTable(
        c: Connection,
        table: String,
        actualTable: String,
        outDir: Path,
        chunkRows: Int,
    ): TableEntry {
        require(LegacySchema.IDENT.matches(actualTable)) { "表名の形式が不正" }
        val have = LegacySchema.columns(c, actualTable).associateBy { it.lowercase() }
        val columns = LegacySchema.expected.getValue(table).filter { it.lowercase() in have }
        val pk = LegacySchema.primaryKeys.getValue(table)
        require(pk.all { it.lowercase() in have }) { "主キーの列が無い: $table" }
        val select = columns.joinToString(", ") { "\"${have.getValue(it.lowercase())}\"" }
        // 主キーの順。主キーに NULL が入り得る（旧 SQLite の TEXT 主キー）ので rowid で順を確定させる
        val order = (pk.map { "\"${have.getValue(it.lowercase())}\"" } + "rowid").joinToString(", ")
        val pkIdx = pk.map { p -> columns.indexOfFirst { it.equals(p, true) } }
        val dir = outDir.resolve(table)
        Files.createDirectories(dir)
        val chunks = mutableListOf<ChunkEntry>()
        var total = 0L
        c.createStatement().use { s ->
            s.fetchSize = 1000
            s.executeQuery("SELECT $select FROM \"$actualTable\" ORDER BY $order").use { rs ->
                var writer: ChunkWriter? = null
                while (rs.next()) {
                    val row = (1..columns.size).map { value(rs, it) }
                    val w = writer ?: ChunkWriter(dir, table, chunks.size).also { writer = it }
                    w.write(row, JsonArray(pkIdx.map { row[it] }))
                    total++
                    if (w.rows == chunkRows.toLong()) {
                        chunks += w.close()
                        writer = null
                    }
                }
                writer?.let { chunks += it.close() }
            }
        }
        return TableEntry(table, columns, pk, total, chunks)
    }

    /** SQLite の保存の型のまま JSON の値にする。 */
    internal fun value(
        rs: ResultSet,
        i: Int,
    ): JsonValue =
        when (val v = rs.getObject(i)) {
            null -> JsonNull
            is Int -> JsonInt(v.toLong())
            is Long -> JsonInt(v)
            is Double -> if (v.isFinite()) JsonFloat(v) else JsonObject.of("\$real" to JsonString(if (v > 0) "inf" else "-inf"))
            is Float -> JsonFloat(v.toDouble())
            is String -> JsonString(v)
            is ByteArray -> JsonObject.of("\$blob" to JsonString(Base64.getEncoder().encodeToString(v)))
            else -> throw IllegalStateException("想定外の値の型: ${v.javaClass.simpleName}")
        }

    private class ChunkWriter(
        dir: Path,
        table: String,
        val no: Int,
    ) {
        val name = "%05d.ndjson.gz".format(no)
        val file = "$table/$name"
        private val md = MessageDigest.getInstance("SHA-256")
        private val gz = GZIPOutputStream(Files.newOutputStream(dir.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        private val out: OutputStream = DigestOutputStream(gz, md)
        var rows = 0L
        private var first: JsonValue = JsonNull
        private var last: JsonValue = JsonNull

        fun write(
            row: List<JsonValue>,
            key: JsonValue,
        ) {
            out.write((CanonicalJson.encode(JsonArray(row)) + "\n").toByteArray(Charsets.UTF_8))
            if (rows == 0L) first = key
            last = key
            rows++
        }

        fun close(): ChunkEntry {
            out.close()
            return ChunkEntry(no, file, rows, first, last, CanonicalJson.hex(md.digest()))
        }
    }

    /** 出力先の各チャンクを読み直し、行数・sha256・列の数を manifest と照らす。食い違いを返す（空なら一致）。 */
    fun verify(outDir: Path): List<String> {
        val manifestFile = outDir.resolve(MANIFEST)
        if (!Files.isRegularFile(manifestFile)) return listOf("manifest.json が無い（抽出が完了していない）")
        val m = fromJson(StrictJson.parse(Files.readString(manifestFile)))
        val problems = mutableListOf<String>()
        for (t in m.tables) {
            var rows = 0L
            for (ch in t.chunks) {
                val p = outDir.resolve(ch.file).normalize()
                if (!p.startsWith(outDir.normalize()) || !Files.isRegularFile(p)) {
                    problems += "${ch.file}: ファイルが無い"
                    continue
                }
                val md = MessageDigest.getInstance("SHA-256")
                var n = 0L
                var badWidth = false
                BufferedReader(DigestInputStream(GZIPInputStream(Files.newInputStream(p)), md).reader(Charsets.UTF_8)).use { r ->
                    r.lineSequence().forEach { line ->
                        val v = runCatching { StrictJson.parse(line) }.getOrNull()
                        if (v !is JsonArray || v.items.size != t.columns.size) badWidth = true
                        n++
                    }
                }
                if (CanonicalJson.hex(md.digest()) != ch.sha256) problems += "${ch.file}: sha256 が違う"
                if (n != ch.rows) problems += "${ch.file}: 行数が違う（manifest ${ch.rows}、実際 $n）"
                if (badWidth) problems += "${ch.file}: 読めない行・列の数が違う行がある"
                rows += n
            }
            if (rows != t.rows) problems += "${t.table}: 行数の合計が違う（manifest ${t.rows}、実際 $rows）"
        }
        return problems
    }

    fun toJson(m: ExtractManifest): JsonObject =
        JsonObject.of(
            "format" to JsonString(FORMAT),
            "input" to JsonObject.of("sha256" to JsonString(m.inputSha256), "size_bytes" to JsonInt(m.inputSizeBytes)),
            "chunk_rows" to JsonInt(m.chunkRows.toLong()),
            "preflight_verdict" to JsonString(m.preflightVerdict),
            "tables" to
                JsonArray(
                    m.tables.map { t ->
                        JsonObject.of(
                            "table" to JsonString(t.table),
                            "columns" to JsonArray(t.columns.map { JsonString(it) }),
                            "primary_key" to JsonArray(t.primaryKey.map { JsonString(it) }),
                            "rows" to JsonInt(t.rows),
                            "chunks" to
                                JsonArray(
                                    t.chunks.map { ch ->
                                        JsonObject.of(
                                            "no" to JsonInt(ch.no.toLong()),
                                            "file" to JsonString(ch.file),
                                            "rows" to JsonInt(ch.rows),
                                            "key_from" to ch.keyFrom,
                                            "key_to" to ch.keyTo,
                                            "sha256" to JsonString(ch.sha256),
                                        )
                                    },
                                ),
                        )
                    },
                ),
        )

    fun fromJson(v: JsonValue): ExtractManifest {
        fun JsonValue?.obj() = this as? JsonObject ?: throw IllegalArgumentException("manifest の形式が不正（object）")

        fun JsonValue?.str() = (this as? JsonString)?.value ?: throw IllegalArgumentException("manifest の形式が不正（文字列）")

        fun JsonValue?.long() = (this as? JsonInt)?.value ?: throw IllegalArgumentException("manifest の形式が不正（整数）")

        fun JsonValue?.arr() = (this as? JsonArray)?.items ?: throw IllegalArgumentException("manifest の形式が不正（配列）")
        val o = v.obj()
        require(o["format"].str() == FORMAT) { "manifest の版が違う" }
        val input = o["input"].obj()
        return ExtractManifest(
            inputSha256 = input["sha256"].str(),
            inputSizeBytes = input["size_bytes"].long(),
            chunkRows = o["chunk_rows"].long().toInt(),
            preflightVerdict = o["preflight_verdict"].str(),
            tables =
                o["tables"].arr().map { tv ->
                    val t = tv.obj()
                    TableEntry(
                        table = t["table"].str(),
                        columns = t["columns"].arr().map { it.str() },
                        primaryKey = t["primary_key"].arr().map { it.str() },
                        rows = t["rows"].long(),
                        chunks =
                            t["chunks"].arr().map { cv ->
                                val ch = cv.obj()
                                ChunkEntry(
                                    no = ch["no"].long().toInt(),
                                    file = ch["file"].str(),
                                    rows = ch["rows"].long(),
                                    keyFrom = ch["key_from"] ?: JsonNull,
                                    keyTo = ch["key_to"] ?: JsonNull,
                                    sha256 = ch["sha256"].str(),
                                )
                            },
                    )
                },
        )
    }
}

/** preflight が NG のため抽出しない（終了コード 1）。 */
class PreflightFailed(
    val report: PreflightReport,
) : RuntimeException("preflight が NG")
