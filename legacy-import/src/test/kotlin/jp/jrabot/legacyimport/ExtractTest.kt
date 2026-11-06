package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.legacyimport.LegacyDbs.exec
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** extract（P1。D-273）を合成の旧 SQLite で確かめる（実データは使わない）。 */
class ExtractTest {
    @TempDir
    lateinit var dir: Path

    private fun seeded(seed: (Connection) -> Unit = ::seed): Path = LegacyDbs.create(dir.resolve("jraev.sqlite"), seed)

    private fun seed(c: Connection) {
        c.exec("INSERT INTO races (race_id, date, track, race_no, post_time) VALUES ('202610240502', '20261024', '東京', 2, '10:30')")
        c.exec("INSERT INTO races (race_id, date, track, race_no, post_time) VALUES ('202610240501', '20261024', '東京', 1, '10:05')")
        c.exec("INSERT INTO races (race_id, date, track, race_no, post_time) VALUES ('202610240503', '20261024', '東京', 3, '11:00')")
        // 主キー（race_id, ts, market, umaban）の順と挿入の順を変える
        for ((ts, umaban) in listOf(
            "2026-10-24T09:56:00" to 2,
            "2026-10-24T09:55:00" to 3,
            "2026-10-24T09:55:00" to 1,
            "2026-10-24T09:57:00" to 1,
            "2026-10-24T09:56:00" to 1,
        )) {
            c.exec("INSERT INTO odds_snapshots (race_id, ts, umaban, odds) VALUES ('202610240501', '$ts', $umaban, 3.5)")
        }
        c.exec(
            "INSERT INTO signals (race_id, ts, umaban, odds, prob, ev, reason) VALUES ('202610240501', '2026-10-24T09:55:00', 1, 3.5, 0.30000000000000004, 1e999, NULL)",
        )
        c.exec("INSERT INTO signals (race_id, ts, umaban, odds, prob, ev, reason) VALUES ('202610240501', '2026-10-24T09:56:00', 2, 4.0, 0.1, -1e999, x'00ff')")
    }

    private fun lines(p: Path): List<JsonArray> =
        GZIPInputStream(Files.newInputStream(p)).bufferedReader(Charsets.UTF_8).readLines().map { StrictJson.parse(it) as JsonArray }

    private fun manifestText(out: Path) = Files.readString(out.resolve(Extract.MANIFEST))

    @Test
    fun `主キーの順に チャンクへ分けて書き、manifest の行数・範囲・sha256 が読み直した内容と一致する`() {
        val db = seeded()
        val before = LegacyDbs.sha(db)
        val out = dir.resolve("out")
        val m = Extract.run(db, out, chunkRows = 2)
        assertEquals(before, LegacyDbs.sha(db), "入力を変えない")
        assertEquals(15, m.tables.size)
        assertEquals(before, m.inputSha256)

        val odds = m.tables.single { it.table == "odds_snapshots" }
        assertEquals(listOf("race_id", "ts", "market", "umaban"), odds.primaryKey)
        assertEquals(5L, odds.rows)
        assertEquals(listOf(2L, 2L, 1L), odds.chunks.map { it.rows })
        assertEquals(listOf("odds_snapshots/00000.ndjson.gz", "odds_snapshots/00001.ndjson.gz", "odds_snapshots/00002.ndjson.gz"), odds.chunks.map { it.file })
        val keys =
            odds.chunks.flatMap { ch ->
                lines(out.resolve(ch.file)).map { r ->
                    listOf(1, 3).map {
                        (r.items[it] as? JsonString)?.value
                            ?: (r.items[it] as JsonInt).value.toString()
                    }
                }
            }
        assertEquals(
            listOf(
                listOf("2026-10-24T09:55:00", "1"),
                listOf("2026-10-24T09:55:00", "3"),
                listOf("2026-10-24T09:56:00", "1"),
                listOf("2026-10-24T09:56:00", "2"),
                listOf("2026-10-24T09:57:00", "1"),
            ),
            keys,
        )
        val first = odds.chunks.first().keyFrom as JsonArray
        assertEquals(JsonString("2026-10-24T09:55:00"), first.items[1])
        assertEquals(JsonInt(1), first.items[3])

        val races = m.tables.single { it.table == "races" }
        assertEquals(listOf("202610240501", "202610240502"), lines(out.resolve(races.chunks[0].file)).map { (it.items[0] as JsonString).value })
        assertTrue(
            m.tables
                .single { it.table == "bets" }
                .chunks
                .isEmpty(),
            "空の表はチャンクなし",
        )
        assertEquals(emptyList(), Extract.verify(out))
        assertEquals(m, Extract.fromJson(StrictJson.parse(manifestText(out))))
    }

    @Test
    fun `値は保存の型のまま — 実数は往復で同じ値、無限大と BLOB は印付き、NULL は null`() {
        val out = dir.resolve("out")
        val m = Extract.run(seeded(), out)
        val sig = m.tables.single { it.table == "signals" }
        val rows = lines(out.resolve(sig.chunks.single().file))

        fun col(
            row: JsonArray,
            name: String,
        ): JsonValue = row.items[sig.columns.indexOf(name)]
        assertEquals(JsonFloat(0.30000000000000004), col(rows[0], "prob"))
        assertEquals(JsonObject.of("\$real" to JsonString("inf")), col(rows[0], "ev"))
        assertEquals(JsonObject.of("\$real" to JsonString("-inf")), col(rows[1], "ev"))
        assertEquals(JsonNull, col(rows[0], "reason"))
        assertEquals(JsonObject.of("\$blob" to JsonString("AP8=")), col(rows[1], "reason"))
        assertEquals(JsonInt(1), col(rows[0], "umaban"))
        assertEquals(JsonFloat(1.0), col(rows[0], "stake"), "DEFAULT 1.0 は実数")
    }

    @Test
    fun `同じ入力からは同じ manifest（決定的）。起動時の列が無い旧 DB はその列を出さず、余分な列・表は出さない`() {
        val db = seeded()
        val a = dir.resolve("a")
        val b = dir.resolve("b")
        Extract.run(db, a, 2)
        Extract.run(db, b, 2)
        assertEquals(manifestText(a), manifestText(b))

        val old =
            LegacyDbs.create(dir.resolve("old.sqlite")) { c ->
                c.exec("ALTER TABLE races DROP COLUMN tenko_cd")
                c.exec("ALTER TABLE races ADD COLUMN custom_note TEXT")
                c.exec("CREATE TABLE local_notes (id INTEGER)")
                c.exec("INSERT INTO races (race_id, date, track, race_no, post_time, custom_note) VALUES ('202610240501', '20261024', '東京', 1, '10:05', 'x')")
            }
        val m = Extract.run(old, dir.resolve("old-out"))
        val races = m.tables.single { it.table == "races" }
        assertFalse("tenko_cd" in races.columns)
        assertFalse("custom_note" in races.columns)
        assertEquals(races.columns.size, lines(dir.resolve("old-out").resolve(races.chunks.single().file)).single().items.size)
        assertFalse(m.tables.any { it.table == "local_notes" })
        assertEquals("WARN", m.preflightVerdict)
    }

    @Test
    fun `出力先が空でなければ 2、preflight が NG なら 1 で抽出しない、チャンク行数の範囲外は 2`() {
        val err = ByteArrayOutputStream()
        val sink = PrintStream(err, true, Charsets.UTF_8)
        val db = seeded()
        val busy = Files.createDirectories(dir.resolve("busy"))
        Files.writeString(busy.resolve("keep.txt"), "keep")
        assertEquals(2, LegacyImport.run(arrayOf("extract", "--sqlite", db.toString(), "--out-dir", busy.toString()), sink, sink))
        assertEquals("keep", Files.readString(busy.resolve("keep.txt")))
        assertEquals(2, LegacyImport.run(arrayOf("extract", "--sqlite", db.toString(), "--out-dir", db.toString()), sink, sink), "入力のファイル")
        assertEquals(2, LegacyImport.run(arrayOf("extract", "--sqlite", db.toString(), "--out-dir", dir.toString()), sink, sink), "入力のあるディレクトリ")
        assertEquals(
            2,
            LegacyImport.run(arrayOf("extract", "--sqlite", db.toString(), "--out-dir", dir.resolve("x").toString(), "--chunk-rows", "0"), sink, sink),
        )
        assertEquals(2, LegacyImport.run(arrayOf("extract", "--sqlite", db.toString()), sink, sink))
        assertFailsWith<IllegalArgumentException> { Extract.run(db, dir.resolve("y"), 0) }

        val ng = LegacyDbs.create(dir.resolve("ng.sqlite")) { it.exec("DROP TABLE bet_legs") }
        val ngOut = dir.resolve("ng-out")
        assertEquals(1, LegacyImport.run(arrayOf("extract", "--sqlite", ng.toString(), "--out-dir", ngOut.toString()), sink, sink))
        assertFalse(Files.exists(ngOut.resolve(Extract.MANIFEST)))

        err.reset()
        val ok = dir.resolve("ok")
        assertEquals(0, LegacyImport.run(arrayOf("extract", "--sqlite", db.toString(), "--out-dir", ok.toString(), "--chunk-rows", "2"), sink, sink))
        assertTrue(err.toString(Charsets.UTF_8).contains("extract: OK"))
        assertFalse(err.toString(Charsets.UTF_8).contains("202610240501"), "値を出さない")
        assertEquals(0, LegacyImport.run(arrayOf("verify-extract", "--dir", ok.toString()), sink, sink))
    }

    @Test
    fun `verify-extract は 書き換え・欠落・manifest の無い出力先を検出する`() {
        val sink = PrintStream(ByteArrayOutputStream())
        val out = dir.resolve("out")
        val m = Extract.run(seeded(), out, 2)
        val ch = m.tables.single { it.table == "odds_snapshots" }.chunks[0]
        val p = out.resolve(ch.file)
        val tampered = lines(p).map { JsonArray(it.items.dropLast(1) + JsonFloat(9.9)) }
        GZIPOutputStream(Files.newOutputStream(p)).bufferedWriter(Charsets.UTF_8).use { w ->
            for (r in tampered) {
                w.write(
                    jp.jrabot.domain.ml.json.CanonicalJson
                        .encode(r) + "\n",
                )
            }
        }
        assertTrue(Extract.verify(out).any { it.contains("sha256") })
        assertEquals(1, LegacyImport.run(arrayOf("verify-extract", "--dir", out.toString()), sink, sink))

        Files.delete(
            out.resolve(
                m.tables
                    .single { it.table == "races" }
                    .chunks[0]
                    .file,
            ),
        )
        assertTrue(Extract.verify(out).any { it.contains("ファイルが無い") })

        Files.delete(out.resolve(Extract.MANIFEST))
        assertEquals(1, LegacyImport.run(arrayOf("verify-extract", "--dir", out.toString()), sink, sink))
    }
}
