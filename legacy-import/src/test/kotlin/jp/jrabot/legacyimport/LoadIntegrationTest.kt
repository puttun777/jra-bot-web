package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.legacyimport.LegacyDbs.exec
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.mysql.MySQLContainer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * load（P3。D-275）を、Testcontainers の MySQL（MYSQL_IMAGE）に本物の db-migration CLI で V1〜V5 を適用した DB に対して確かめる。
 * 合成の旧 SQLite → extract → transform → load の往復で、新表の制約（CHECK・外部キー・型）を満たすことも確かめる（transform のレビュー 低）。
 */
@Tag("integration")
class LoadIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private fun seed(c: Connection) {
        val r = "INSERT INTO races (race_id, date, track, track_cd, race_no, post_time, name, distance_m, tenko_cd, created_at) VALUES "
        c.exec("$r('202610240501', '20261024', '東京', '05', 1, '10:05', '2歳未勝利', 1600, '1', '2026-10-20 03:00:00')")
        c.exec("$r('202610240511', '20261024', '東京', '05', 11, '00:00', NULL, 0, NULL, '2026-10-20 03:00:00')")
        c.exec("$r('202610240512', '20261025', '東京', '05', 12, '16:00', NULL, 1200, NULL, '2026-10-20 03:00:00')") // 隔離
        c.exec(
            "INSERT INTO horses (ketto_num, bamei, birth_date, pedigree_json, race_count, updated_at)" +
                " VALUES ('2020100001', 'テスト馬', '20200401', '[1, 2]', 5, '2026-10-20 03:00:00')",
        )
        c.exec(
            "INSERT INTO race_horses (race_id, umaban, ketto_num, bamei, created_at) VALUES ('202610240501', 1, '2020100001', 'テスト馬', '2026-10-20 03:00:00')",
        )
        c.exec(
            "INSERT INTO horse_past_runs (ketto_num, race_id, date, track_cd, race_no, umaban, finish, odds, popularity, corner1, time_str, created_at) " +
                "VALUES ('2020100001', '202609010101', '20260901', '01', 1, 3, 2, 4.5, 2, 3, '1:35.2', '2026-10-20 03:00:00')",
        )
        for (u in 1..3) {
            c.exec(
                "INSERT INTO odds_snapshots (race_id, ts, market, umaban, odds, popularity)" +
                    " VALUES ('202610240501', '2026-10-24T09:55:00.123456', 'win', $u, ${u + 1.5}, $u)",
            )
        }
        c.exec("INSERT INTO odds_snapshots (race_id, ts, market, umaban, odds) VALUES ('202610240512', '2026-10-24T09:55:00', 'win', 1, 2.0)") // 親が隔離
        c.exec(
            "INSERT INTO combo_odds_snapshots (race_id, collected_at, market, combo_key, odds, source, created_at)" +
                " VALUES ('202610240501', '2026-10-24T09:56:00', 'wide', '1-2', 5.5, '0B33', '2026-10-24 00:56:00')",
        )
        c.exec("INSERT INTO results (race_id, umaban, finish, payout, created_at) VALUES ('202610240501', 1, 1, 3.5, '2026-10-24 06:00:00')")
        c.exec(
            "INSERT INTO payouts (race_id, market, key, payout, is_provisional, created_at)" +
                " VALUES ('202610240501', 'wide', '1-2', 250, 0, '2026-10-24 06:00:00')",
        )
        val sg = "INSERT INTO signals (id, race_id, ts, market, umaban, odds, prob, ev, stake, sent, prob_source, created_at) VALUES "
        c.exec("$sg(10, '202610240501', '2026-10-24T09:55:00', 'win', 1, 3.5, 0.3, 1.05, 1.0, 1, 'model', '2026-10-24T09:55:01')")
        c.exec("$sg(11, '202610240501', '2026-10-24T09:56:00', 'win', 2, 4.0, 0.26, 1.04, 1.0, 0, NULL, '2026-10-24T09:56:01')")
        c.exec(
            "INSERT INTO race_evaluations (id, race_id, snapshot_at, bet_types, best_candidate_json, signal_sent, settled_at, hit, created_at) " +
                "VALUES (50, '202610240501', '2026-10-24T09:55:00', '[\"ワイド\"]', '{\"ev\": 1.2}', 1, '2026-10-24T16:00:00', 1, '2026-10-24T09:55:00')",
        )
        c.exec(
            "INSERT INTO ticket_evaluations (id, race_evaluation_id, race_id, snapshot_at, bet_type, combo_key, odds, rank, accepted, created_at) " +
                "VALUES (500, 50, '202610240501', '2026-10-24T09:55:00', 'ワイド', '1-2', 5.5, 1, 1, '2026-10-24T09:55:00')",
        )
        c.exec(
            "INSERT INTO bets (id, race_id, ts, stake, source, note, signal_id, status, created_at)" +
                " VALUES (100, '202610240501', '2026-10-24T09:55:00', 1.0, 'paper', 'n', 10, 'open', '2026-10-24T09:55:02')",
        )
        c.exec("INSERT INTO bet_legs (id, bet_id, umaban, odds_at_bet, created_at) VALUES (1000, 100, 1, 3.5, '2026-10-24T09:55:03')")
        c.exec(
            "INSERT INTO model_metrics (id, date_from, date_to, market, count, brier, calibration_json, created_at)" +
                " VALUES (1, '20260101', '2026-06-30', 'win', 120, 0.2, '{}', '2026-07-01 00:00:00')",
        )
        c.exec("INSERT INTO jvlink_timestamps (data_type, lastfiletimestamp, updated_at) VALUES ('RACE', '20261024120000', '2026-10-24 03:00:00')")
    }

    private fun transformed(chunkRows: Int = 2): Path {
        val db = LegacyDbs.create(dir.resolve("jraev.sqlite"), ::seed)
        Extract.run(db, dir.resolve("extract"), chunkRows)
        Transform(TENANT, ZoneId.of("Asia/Tokyo"), lenientJson = false).run(dir.resolve("extract"), dir.resolve("transform"))
        return dir.resolve("transform")
    }

    private fun run(
        vararg args: String,
        password: String? = Db.ROOT_PASSWORD,
    ): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val sink = PrintStream(err, true, Charsets.UTF_8)
        val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
        return LegacyImport.load(opts, password, sink) to err.toString(Charsets.UTF_8)
    }

    @Test
    fun `合成の旧 DB を extract → transform → load し、件数・値・親子の張り替え・隔離が新表に入る`() {
        val t = transformed()
        val db = Db.migrated()
        val (code, msg) = run("--transform", t.toString(), "--jdbc-url", db.url, "--user", "root", "--state", dir.resolve("state.json").toString())
        assertEquals(0, code, msg)
        assertTrue(msg.contains("load: OK"), msg)
        db.root().use { c ->
            fun one(sql: String): String? = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
            assertEquals("2", one("SELECT COUNT(*) FROM races WHERE tenant_id = $TENANT"))
            assertEquals(
                "2026-10-24|05|1|10:05:00|0|2026-10-20 03:00:00.000000",
                one(
                    "SELECT CONCAT_WS('|', race_date, venue_code, race_no, post_time, post_time_unknown, created_at) FROM races WHERE race_id = '202610240501'",
                ),
            )
            assertEquals("1", one("SELECT post_time_unknown FROM races WHERE race_id = '202610240511'"))
            assertEquals("2026-10-24 00:55:00.123456", one("SELECT MIN(observed_at) FROM odds_snapshots WHERE tenant_id = $TENANT"))
            assertEquals("SENT,SEND_UNKNOWN", one("SELECT GROUP_CONCAT(notify_state ORDER BY legacy_id) FROM signals WHERE tenant_id = $TENANT"))
            assertEquals("10", one("SELECT s.legacy_id FROM bets b JOIN signals s ON s.tenant_id = b.tenant_id AND s.id = b.signal_id WHERE b.legacy_id = 100"))
            assertEquals("100", one("SELECT b.legacy_id FROM bet_legs l JOIN bets b ON b.tenant_id = l.tenant_id AND b.id = l.bet_id WHERE l.legacy_id = 1000"))
            assertEquals(
                "50",
                one(
                    "SELECT e.legacy_id FROM ticket_evaluations te" +
                        " JOIN race_evaluations e ON e.tenant_id = te.tenant_id AND e.id = te.race_evaluation_id WHERE te.legacy_id = 500",
                ),
            )
            assertEquals("[\"ワイド\"]", one("SELECT bet_types FROM race_evaluations WHERE legacy_id = 50"))
            assertEquals("20261024120000", one("SELECT last_file_timestamp FROM ingest_cursor WHERE tenant_id = $TENANT AND data_type = 'RACE'"))
            assertEquals(
                "race_id_mismatch,orphan",
                one("SELECT GROUP_CONCAT(reason_code ORDER BY source_table DESC) FROM import_quarantine WHERE tenant_id = $TENANT"),
            )
        }
    }

    @Test
    fun `途中で止まったチャンクは入れ直し（冪等）、完了済みの再実行は何もしない、状態ファイルの無い再実行と空でない DB は拒否`() {
        val t = transformed(chunkRows = 1)
        val db = Db.migrated()
        val state = dir.resolve("state.json")
        val args = arrayOf("--transform", t.toString(), "--jdbc-url", db.url, "--user", "root", "--state", state.toString())
        assertEquals(0, run(*args).first)
        val counts = db.counts()

        // odds_snapshots の最後のチャンクを「書き込み中に停止」に戻す（DB には入っている）
        val s = StrictJson.parse(Files.readString(state)) as JsonObject
        val done = (s["done"] as JsonArray).items.map { (it as JsonString).value }
        val last = done.last { it.startsWith("odds_snapshots/") }
        Files.writeString(
            state,
            CanonicalJson.encode(
                JsonObject(
                    s.fields.toMutableMap().also {
                        it["done"] = JsonArray(done.filter { f -> f != last }.map(::JsonString))
                        it["in_progress"] = JsonString(last)
                    },
                ),
            ),
        )
        assertEquals(0, run(*args).first, "停止したチャンクは重複を無視して入れ直す")
        assertEquals(counts, db.counts())
        assertEquals(JsonNull, (StrictJson.parse(Files.readString(state)) as JsonObject)["in_progress"])
        assertEquals(0, run(*args).first, "完了済みは何もしない")
        assertEquals(counts, db.counts())

        val (code, msg) = run("--transform", t.toString(), "--jdbc-url", db.url, "--user", "root", "--state", dir.resolve("other.json").toString())
        assertEquals(2, code)
        assertTrue(msg.contains("既にある"), msg)
    }

    @Test
    fun `ループバック以外の接続先・パスワードの無い実行・テナントの無い DB は 2`() {
        val t = transformed()
        val st = dir.resolve("s.json").toString()
        assertEquals(2, run("--transform", t.toString(), "--jdbc-url", "jdbc:mysql://10.0.0.5:3306/jrabot", "--user", "root", "--state", st).first)
        assertEquals(2, run("--transform", t.toString(), "--jdbc-url", "jdbc:mysql://db.example.com/jrabot", "--user", "root", "--state", st).first)
        assertEquals(2, run("--transform", t.toString(), "--jdbc-url", Db.migrated().url, "--user", "root", "--state", st, password = null).first)
        val noTenant = Db.migrated(tenant = false)
        val (code, msg) = run("--transform", t.toString(), "--jdbc-url", noTenant.url, "--user", "root", "--state", st)
        assertEquals(2, code)
        assertTrue(msg.contains("テナントが無い"), msg)
        assertTrue(!Files.exists(Path.of(st)), "検査で止まったら状態ファイルを作らない")
    }

    private companion object {
        const val TENANT = 7L
    }

    /** Testcontainers の MySQL と、db-migration CLI（installDist）で V1〜V5・R__grants を適用した新しいデータベース。 */
    private class Db(
        val name: String,
    ) {
        val url: String get() = "jdbc:mysql://localhost:${container.getMappedPort(3306)}/$name"

        fun root(): Connection = DriverManager.getConnection(url, "root", ROOT_PASSWORD)

        fun counts(): Map<String, Long> =
            root().use { c ->
                listOf(
                    "races",
                    "horses",
                    "race_horses",
                    "horse_past_runs",
                    "odds_snapshots",
                    "combo_odds_snapshots",
                    "results",
                    "payouts",
                    "signals",
                    "race_evaluations",
                    "ticket_evaluations",
                    "bets",
                    "bet_legs",
                    "model_metrics",
                    "ingest_cursor",
                    "import_quarantine",
                ).associateWith { t ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(*) FROM $t").use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
                }
            }

        companion object {
            const val ROOT_PASSWORD = "root-pw-for-ci-container" // CI の一時コンテナ専用のテスト値（本番の秘密ではない）
            private val USERS =
                mapOf(
                    "migrate" to "migrate-pw-ci",
                    "app" to "app-pw-ci",
                    "ingest" to "ingest-pw-ci",
                    "bot" to "bot-pw-ci",
                    "mltrain" to "mltrain-pw-ci",
                )

            val container: MySQLContainer by lazy {
                MySQLContainer(System.getenv("MYSQL_IMAGE")?.takeIf { it.isNotBlank() } ?: "mysql:8.4")
                    .withDatabaseName("bootstrap")
                    .withUsername("bootstrap")
                    .withPassword(ROOT_PASSWORD)
                    .also { it.start() }
                    .also { c ->
                        DriverManager.getConnection("jdbc:mysql://localhost:${c.getMappedPort(3306)}/", "root", ROOT_PASSWORD).use { conn ->
                            for ((u, p) in USERS) conn.createStatement().use { it.execute("CREATE USER IF NOT EXISTS '$u'@'%' IDENTIFIED BY '$p'") }
                        }
                    }
            }

            fun migrated(tenant: Boolean = true): Db {
                val name =
                    "lit" +
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .take(12)
                DriverManager.getConnection("jdbc:mysql://localhost:${container.getMappedPort(3306)}/", "root", ROOT_PASSWORD).use { c ->
                    c.createStatement().use {
                        it.execute("CREATE DATABASE `$name` DEFAULT CHARACTER SET utf8mb4")
                        it.execute("GRANT ALL PRIVILEGES ON `$name`.* TO 'migrate'@'%' WITH GRANT OPTION")
                    }
                }
                val db = Db(name)
                migrate(db)
                if (tenant) {
                    db.root().use { c ->
                        c.createStatement().use { it.execute("INSERT INTO tenant (tenant_id, name, created_at) VALUES ($TENANT, 'legacy', NOW(6))") }
                    }
                }
                return db
            }

            private fun migrate(db: Db) {
                val installDir =
                    File(requireNotNull(System.getProperty("dbmigration.install.dir")) { "dbmigration.install.dir が未設定（integrationTest タスクで実行する）" })
                val lib = File(installDir, "lib")
                check(lib.isDirectory) { "db-migration の installDist が無い: $lib" }
                val java = File(System.getProperty("java.home"), "bin/java").path
                val builder = ProcessBuilder(java, "-cp", "${lib.path}/*", "jp.jrabot.dbmigration.DbMigrationMainKt", "--mode=migrate")
                builder.environment().apply {
                    clear()
                    put("PATH", System.getenv("PATH") ?: "")
                    put("LC_ALL", "C.UTF-8")
                    put("DB_URL", db.url)
                    put("DB_USER", "migrate")
                    put("DB_PASSWORD", USERS.getValue("migrate"))
                }
                val out = File.createTempFile("migrate-out", ".txt")
                try {
                    val p = builder.redirectErrorStream(true).redirectOutput(out).start()
                    check(p.waitFor(180, TimeUnit.SECONDS)) { "migrate が 180 秒で終わらない" }
                    check(p.exitValue() == 0) { "migrate が失敗: ${out.readText()}" }
                } finally {
                    out.delete()
                }
            }
        }
    }
}
