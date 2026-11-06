package jp.jrabot.legacyimport

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** preflight（D-271）を合成の旧 SQLite で確かめる（実データは使わない）。 */
class PreflightTest {
    @TempDir
    lateinit var dir: Path

    /** 旧スキーマ（期待値と同じリソース）で合成の DB を作る。 */
    private fun legacyDb(
        name: String = "jraev.sqlite",
        seed: (Connection) -> Unit = {},
    ): Path {
        val p = dir.resolve(name)
        DriverManager.getConnection("jdbc:sqlite:$p").use { c ->
            for (file in listOf("schema.sql", "migration_add_bets.sql")) {
                val sql = checkNotNull(javaClass.getResource("/legacy-schema/$file")).readText()
                for (stmt in LegacySchema.statements(sql)) c.createStatement().use { it.executeUpdate(stmt) }
            }
            for (line in checkNotNull(javaClass.getResource("/legacy-schema/dynamic-columns.tsv")).readText().lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (t, col, type) = line.split('\t')
                if (col !in LegacySchema.columns(c, t)) c.createStatement().use { it.executeUpdate("ALTER TABLE $t ADD COLUMN $col $type") }
            }
            seed(c)
        }
        return p
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun sha(p: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }

    @Test
    fun `期待するスキーマは旧の 15 表と起動時に足される列を含む`() {
        val e = LegacySchema.expected
        assertEquals(15, e.size, e.keys.toString())
        assertTrue("tenko_cd" in e.getValue("races") && "prob_source" in e.getValue("signals") && "skip_reason" in e.getValue("bets"))
        assertTrue("is_provisional" in e.getValue("payouts") && "best_win_hint_json" in e.getValue("race_evaluations"))
    }

    @Test
    fun `空の旧 DB は OK で 表の差も問題も無い`() {
        val r = Preflight.run(legacyDb())
        assertEquals("OK", r.verdict, LegacyImport.summary(r))
        assertEquals("ok", r.integrity)
        assertTrue(r.missingTables.isEmpty() && r.extraTables.isEmpty() && r.missingColumns.isEmpty())
        assertEquals(15, r.rowCounts.size)
        assertTrue(r.rowCounts.values.all { it == 0L })
    }

    @Test
    fun `時刻の形式（JST の T 区切り・UTC の空白区切り・判別不能）と 日付・レース ID・JSON・孤立を件数で数える`() {
        val p =
            legacyDb { c ->
                c.exec(
                    "INSERT INTO races (race_id, date, track, race_no, post_time, created_at) VALUES ('202610240501', '20261024', '東京', 1, '10:05', '2026-10-24 01:00:00')",
                )
                c.exec("INSERT INTO races (race_id, date, track, race_no, post_time) VALUES ('BADID', '2026/10/24', '東京', 2, '10:30')")
                c.exec("INSERT INTO odds_snapshots (race_id, ts, umaban, odds) VALUES ('202610240501', '2026-10-24T09:55:00.123456', 1, 3.5)")
                c.exec("INSERT INTO odds_snapshots (race_id, ts, umaban, odds) VALUES ('202610240501', '2026-10-24T09:56:00', 2, 4.5)")
                c.exec("INSERT INTO odds_snapshots (race_id, ts, umaban, odds) VALUES ('202610240501', '24/10/2026 09:57', 3, 5.5)")
                c.exec("INSERT INTO odds_snapshots (race_id, ts, umaban, odds) VALUES ('202610249999', '2026-10-24T09:58:00', 1, 2.0)") // 親が無い
                c.exec(
                    "INSERT INTO horses (ketto_num, bamei, birth_date, pedigree_json, style_tendency_json) VALUES ('2020100001', 'テスト馬', '20200401', '[1,2]', '{broken')",
                )
                c.exec("INSERT INTO bets (race_id, ts, stake) VALUES ('202610240501', '2026-10-24T09:59:00', 1.0)")
                c.exec("INSERT INTO bet_legs (bet_id, umaban, odds_at_bet) VALUES (999, 1, 3.0)") // 親が無い
            }
        val r = Preflight.run(p)
        assertEquals("WARN", r.verdict)
        val odds = r.times.single { it.table == "odds_snapshots" && it.column == "ts" }.counts
        assertEquals(3L, odds["iso_t_naive"])
        assertEquals(1L, odds["other"])
        val racesCreated = r.times.single { it.table == "races" && it.column == "created_at" }.counts
        assertEquals(2L, racesCreated["space_utc"], "明示した UTC と DEFAULT の CURRENT_TIMESTAMP")
        val raceDate = r.dates.single { it.table == "races" && it.column == "date" }.counts
        assertEquals(1L, raceDate["yyyymmdd"])
        assertEquals(1L, raceDate["invalid"])
        assertEquals(1L, r.raceIds.single { it.table == "races" }.counts["invalid"])
        assertEquals(1L, r.orphans.single { it.table == "odds_snapshots" }.count)
        assertEquals(1L, r.orphans.single { it.table == "bet_legs" }.count)
        assertEquals(0L, r.orphans.single { it.table == "bets" }.count)
        assertEquals(1L, r.json.single { it.column == "pedigree_json" }.counts["valid"])
        assertEquals(1L, r.json.single { it.column == "style_tendency_json" }.counts["invalid"])
        assertEquals(4L, r.rowCounts["odds_snapshots"])
    }

    @Test
    fun `レポートは件数だけで 値（馬名・レース ID・JSON の中身）を含まず 元のファイルを変えない`() {
        val p =
            legacyDb { c ->
                c.exec("INSERT INTO races (race_id, date, track, race_no, post_time) VALUES ('202610240501', '20261024', '東京', 1, '10:05')")
                c.exec("INSERT INTO horses (ketto_num, bamei, pedigree_json) VALUES ('2020100001', 'テスト馬', '[\"secret-sire\"]')")
            }
        val before = sha(p)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = LegacyImport.run(arrayOf("preflight", "--sqlite", p.toString()), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        assertEquals(0, code)
        val json = out.toString("UTF-8")
        for (v in listOf("テスト馬", "202610240501", "secret-sire", "2020100001")) assertFalse(json.contains(v) || err.toString("UTF-8").contains(v), v)
        assertTrue(json.contains("\"verdict\":\"OK\"") && json.contains("\"sha256\":\"$before\""), json.take(300))
        assertEquals(before, sha(p), "読取り専用で開き、書き換えない")
        assertFalse(Files.exists(dir.resolve("jraev.sqlite-wal")), "WAL を作らない")
    }

    @Test
    fun `期待する表が無ければ NG（終了コード 1）、列が無ければ WARN、余分な表・列は報告だけ`() {
        val noTable = legacyDb("a.sqlite") { it.exec("DROP TABLE bet_legs") }
        val r1 = Preflight.run(noTable)
        assertEquals(listOf("bet_legs"), r1.missingTables)
        assertEquals("NG", r1.verdict)
        val sink = PrintStream(ByteArrayOutputStream())
        assertEquals(1, LegacyImport.run(arrayOf("preflight", "--sqlite", noTable.toString()), sink, sink))

        val noColumn =
            legacyDb("b.sqlite") { c ->
                c.exec("ALTER TABLE races DROP COLUMN tenko_cd")
                c.exec("CREATE TABLE local_notes (id INTEGER)")
                c.exec("ALTER TABLE signals ADD COLUMN custom_flag INTEGER")
            }
        val r2 = Preflight.run(noColumn)
        assertEquals(mapOf("races" to listOf("tenko_cd")), r2.missingColumns)
        assertEquals(listOf("local_notes"), r2.extraTables)
        assertEquals(mapOf("signals" to listOf("custom_flag")), r2.extraColumns)
        assertEquals("WARN", r2.verdict)
    }

    @Test
    fun `出力先を指定すれば ファイルに書く`() {
        val p = legacyDb()
        val out = dir.resolve("report.json")
        val sink = PrintStream(ByteArrayOutputStream())
        assertEquals(0, LegacyImport.run(arrayOf("preflight", "--sqlite", p.toString(), "--out", out.toString()), sink, sink))
        assertTrue(Files.readString(out).contains("\"tool\":\"legacy-import preflight\""))
    }

    @Test
    fun `引数の誤り・無いファイル・SQLite ではないファイルは 2`() {
        val sink = PrintStream(ByteArrayOutputStream())
        assertEquals(2, LegacyImport.run(arrayOf(), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("extract"), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("preflight"), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("preflight", "--sqlite"), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("preflight", "--sqlite", "a", "--sqlite", "b"), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("preflight", "--bogus", "x"), sink, sink))
        assertEquals(2, LegacyImport.run(arrayOf("preflight", "--sqlite", dir.resolve("none.sqlite").toString()), sink, sink))
        val junk = dir.resolve("junk.sqlite")
        Files.write(junk, ByteArray(4096) { 7 })
        assertEquals(2, LegacyImport.run(arrayOf("preflight", "--sqlite", junk.toString()), sink, sink))
    }
}
