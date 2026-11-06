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
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** transform（P2。D-274）を合成の旧 SQLite → extract → transform で確かめる（実データは使わない）。 */
class TransformTest {
    @TempDir
    lateinit var dir: Path

    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun seed(c: Connection) {
        val r = "INSERT INTO races (race_id, date, track, track_cd, race_no, post_time, distance_m, created_at) VALUES "
        c.exec("$r('202610240501', '20261024', '東京', '05', 1, '10:05', 1600, '2026-10-20 03:00:00')")
        c.exec("$r('202610240502', '20261024', '東京', '05', 2, '00:00', 0, '2026-10-20 03:00:00')")
        c.exec("$r('202610240503', '20261025', '東京', '05', 3, '11:00', 1800, '2026-10-20 03:00:00')") // 日付が race_id と違う
        val o = "INSERT INTO odds_snapshots (race_id, ts, market, umaban, odds) VALUES "
        c.exec("$o('202610240501', '2026-10-24T09:55:00', 'win', 1, 3.5)")
        c.exec("$o('202610240501', '2026-10-24T09:56:00', 'win', 1, 0)") // 0 以下
        c.exec("$o('202610240503', '2026-10-24T09:55:00', 'win', 1, 2.0)") // 親が隔離された
        c.exec("$o('202610249999', '2026-10-24T09:55:00', 'win', 1, 2.0)") // 親が無い
        val sg = "INSERT INTO signals (id, race_id, ts, umaban, odds, prob, ev, sent, created_at) VALUES "
        c.exec("$sg(10, '202610240501', '2026-10-24T09:55:00', 1, 3.5, 0.3, 1.05, 1, '2026-10-24T09:55:01')")
        c.exec("$sg(11, '202610240501', '2026-10-24T09:56:00', 2, 4.0, 0.25, 1.0, 0, '2026-10-24T09:56:01')")
        c.exec("$sg(12, '202610240501', '2026-10-24T09:57:00', 3, 4.0, 1.5, 6.0, 0, '2026-10-24T09:57:01')") // prob が範囲外
        val b = "INSERT INTO bets (id, race_id, ts, stake, source, note, notes, signal_id, created_at) VALUES "
        c.exec("$b(100, '202610240501', '2026-10-24T09:55:00', 1.0, 'paper', 'a', 'b', 12, '2026-10-24T09:55:02')")
        c.exec("$b(101, '202610240501', '2026-10-24T09:56:00', 1.0, 'bogus', NULL, NULL, 10, '2026-10-24T09:56:02')") // source が未知
        c.exec("INSERT INTO bet_legs (id, bet_id, umaban, odds_at_bet, created_at) VALUES (1000, 100, 1, 3.5, '2026-10-24T09:55:03')")
        c.exec("INSERT INTO bet_legs (id, bet_id, umaban, odds_at_bet, created_at) VALUES (1001, 101, 1, 3.5, '2026-10-24T09:56:03')") // 親が隔離
        c.exec(
            "INSERT INTO race_evaluations (id, race_id, snapshot_at, bet_types, best_candidate_json, created_at) " +
                "VALUES (50, '202610240501', '2026-10-24T09:55:00', '[\"ワイド\"]', '{broken', '2026-10-24T09:55:00')",
        )
        c.exec(
            "INSERT INTO ticket_evaluations (id, race_evaluation_id, race_id, snapshot_at, bet_type, odds, rank, created_at) " +
                "VALUES (500, 50, '202610240501', '2026-10-24T09:55:00', 'ワイド', 5.0, 1, '2026-10-24T09:55:00')",
        )
        c.exec(
            "INSERT INTO horses (ketto_num, bamei, birth_date, pedigree_json, updated_at) VALUES ('2020100001', 'テスト馬', '20200231', '[1, 2]', '2026-10-20 03:00:00')",
        )
        c.exec("INSERT INTO jvlink_timestamps (data_type, lastfiletimestamp, updated_at) VALUES ('RACE', '20261024120000', '2026-10-24 03:00:00')")
    }

    private fun extracted(): Path {
        val db = LegacyDbs.create(dir.resolve("jraev.sqlite"), ::seed)
        val out = dir.resolve("extract")
        Extract.run(db, out, 2)
        return out
    }

    private fun rows(
        out: Path,
        t: TransformedTable,
    ): List<Map<String, JsonValue>> =
        t.chunks.flatMap { ch ->
            GZIPInputStream(Files.newInputStream(out.resolve(ch.file))).bufferedReader().readLines().map { line ->
                val items = (StrictJson.parse(line) as JsonArray).items
                t.columns.zip(items).toMap()
            }
        }

    private fun quarantine(
        out: Path,
        source: String,
    ): List<JsonObject> =
        GZIPInputStream(
            Files.newInputStream(out.resolve("quarantine/$source.ndjson.gz")),
        ).bufferedReader().readLines().map { StrictJson.parse(it) as JsonObject }

    @Test
    fun `各表で 元の行数 = 変換 + 隔離。親が無い・隔離された子は orphan で隔離する`() {
        val out = dir.resolve("t")
        val m = Transform(7, tokyo, lenientJson = false).run(extracted(), out)
        for (t in m.tables) assertEquals(t.sourceRows, t.rows + t.quarantined.values.sum(), t.source)
        val byS = m.tables.associateBy { it.source }
        assertEquals(mapOf("race_id_mismatch" to 1L), byS.getValue("races").quarantined)
        assertEquals(mapOf("out_of_range" to 1L, "orphan" to 2L), byS.getValue("odds_snapshots").quarantined)
        assertEquals(mapOf("out_of_range" to 1L), byS.getValue("signals").quarantined)
        assertEquals(mapOf("enum" to 1L), byS.getValue("bets").quarantined)
        assertEquals(mapOf("orphan" to 1L), byS.getValue("bet_legs").quarantined)
        assertEquals(mapOf("json" to 1L), byS.getValue("race_evaluations").quarantined)
        assertEquals(mapOf("orphan" to 1L), byS.getValue("ticket_evaluations").quarantined, "親の評価が隔離された")
        assertEquals("ingest_cursor", byS.getValue("jvlink_timestamps").target)

        val q = quarantine(out, "odds_snapshots").map { it["reason_code"] }
        assertEquals(listOf("out_of_range", "orphan", "orphan").map(::JsonString), q)
        val qr = quarantine(out, "races").single()
        assertEquals(JsonArray(JsonString("202610240503")), qr["source_key"])
        assertEquals(JsonString("20261025"), (qr["payload"] as JsonObject)["date"], "隔離は元の値を持つ")
        assertEquals(m.quarantineFiles.sumOf { it.rows }, m.tables.sumOf { it.quarantined.values.sum() })
    }

    @Test
    fun `値の変換 — 日付・場・発走時刻・時刻の UTC 化・tenant_id・通知の状態・note の結合・参照の NULL 化`() {
        val out = dir.resolve("t")
        val m = Transform(7, tokyo, lenientJson = false).run(extracted(), out)
        val byS = m.tables.associateBy { it.source }
        val races = rows(out, byS.getValue("races")).associateBy { (it.getValue("race_id") as JsonString).value }
        val r1 = races.getValue("202610240501")
        assertEquals(JsonInt(7), r1["tenant_id"])
        assertEquals(JsonString("2026-10-24"), r1["race_date"])
        assertEquals(JsonString("05"), r1["venue_code"])
        assertEquals(JsonInt(1), r1["race_no"])
        assertEquals(JsonString("10:05:00"), r1["post_time"])
        assertEquals(JsonInt(0), r1["post_time_unknown"])
        assertEquals(JsonString("2026-10-20 03:00:00.000000"), r1["created_at"])
        val r2 = races.getValue("202610240502")
        assertEquals(JsonNull, r2["post_time"])
        assertEquals(JsonInt(1), r2["post_time_unknown"])
        assertEquals(JsonNull, r2["distance_m"])
        assertEquals(mapOf("post_time: 不明（00:00・範囲外・形式外）" to 1L, "distance_m: 0 を NULL" to 1L), byS.getValue("races").adjusted)

        val odds = rows(out, byS.getValue("odds_snapshots")).single()
        assertEquals(JsonString("2026-10-24 00:55:00.000000"), odds["observed_at"], "JST の naive → UTC")
        assertEquals(JsonFloat(3.5), odds["odds"])

        val sig = rows(out, byS.getValue("signals")).associateBy { (it.getValue("legacy_id") as JsonInt).value }
        assertEquals(JsonString("SENT"), sig.getValue(10)["notify_state"])
        assertEquals(JsonString("SEND_UNKNOWN"), sig.getValue(11)["notify_state"], "UNSENT にしない（古い通知を送らない）")
        assertFalse(sig.values.any { it["notify_state"] == JsonString("UNSENT") })

        val bet = rows(out, byS.getValue("bets")).single()
        assertEquals(JsonString("a\nb"), bet["note"])
        assertEquals(JsonNull, bet["signal_legacy_id"], "参照先の signal 12 は隔離された")
        assertEquals(JsonInt(100), rows(out, byS.getValue("bet_legs")).single()["bet_legacy_id"])

        val horse = rows(out, byS.getValue("horses")).single()
        assertEquals(JsonNull, horse["birth_date"], "暦にない生年月日は NULL")
        assertEquals(JsonString("[1,2]"), horse["pedigree_json"], "JSON は正準化")
        val cursor = rows(out, byS.getValue("jvlink_timestamps")).single()
        assertEquals(JsonString("20261024120000"), cursor["last_file_timestamp"])
    }

    @Test
    fun `lenient-json では読めない JSON の列だけ NULL にして行を残す。同じ入力からは同じ manifest`() {
        val ex = extracted()
        val a = Transform(7, tokyo, lenientJson = true).run(ex, dir.resolve("a"))
        val ev = a.tables.single { it.source == "race_evaluations" }
        assertEquals(emptyMap(), ev.quarantined)
        assertEquals(mapOf("best_candidate_json: 読めない JSON を NULL" to 1L), ev.adjusted)
        assertEquals(1L, a.tables.single { it.source == "ticket_evaluations" }.rows, "親の評価が残るので子も残る")
        Transform(7, tokyo, lenientJson = true).run(ex, dir.resolve("b"))
        assertEquals(Files.readString(dir.resolve("a").resolve(Transform.MANIFEST)), Files.readString(dir.resolve("b").resolve(Transform.MANIFEST)))
    }

    @Test
    fun `CLI — 成功は 0、引数の誤り・空でない出力先・書き換えられた extract は 2`() {
        val err = ByteArrayOutputStream()
        val sink = PrintStream(err, true, Charsets.UTF_8)
        val ex = extracted()
        assertEquals(
            0,
            LegacyImport.run(arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("ok").toString(), "--tenant-id", "7"), sink, sink),
        )
        val msg = err.toString(Charsets.UTF_8)
        assertTrue(msg.contains("transform: OK"), msg)
        assertFalse(msg.contains("202610240503"), "値を出さない")
        assertEquals(
            2,
            LegacyImport.run(arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("x").toString(), "--tenant-id", "0"), sink, sink),
        )
        assertEquals(
            2,
            LegacyImport.run(
                arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("x").toString(), "--tenant-id", "7", "--naive-zone", "Nowhere/X"),
                sink,
                sink,
            ),
        )
        assertEquals(
            2,
            LegacyImport.run(arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("ok").toString(), "--tenant-id", "7"), sink, sink),
        )
        assertEquals(
            0,
            LegacyImport.run(
                arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("lenient").toString(), "--tenant-id", "7", "--lenient-json"),
                sink,
                sink,
            ),
        )

        val races = Extract.fromJson(StrictJson.parse(Files.readString(ex.resolve(Extract.MANIFEST)))).tables.single { it.table == "races" }
        val p = ex.resolve(races.chunks[0].file)
        java.util.zip
            .GZIPOutputStream(Files.newOutputStream(p))
            .use { it.write("[]\n".toByteArray()) }
        assertEquals(
            2,
            LegacyImport.run(arrayOf("transform", "--extract", ex.toString(), "--out-dir", dir.resolve("bad").toString(), "--tenant-id", "7"), sink, sink),
        )
        assertFalse(Files.exists(dir.resolve("bad").resolve(Transform.MANIFEST)))
    }
}
