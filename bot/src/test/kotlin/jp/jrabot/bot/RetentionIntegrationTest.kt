package jp.jrabot.bot

import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TcpProxy
import jp.jrabot.persistence.integration.TestDb
import jp.jrabot.persistence.integration.resetData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * retention の通し（本物の MySQL・`bot` ユーザー・合成データ）。ドライランが既定で何も消さないこと、`RETENTION_EXECUTE=true` のときだけ保持期間を過ぎた行を消すこと、
 * 解析待ちの生レコード・実行中の bot-run・新しい行・別のテナントを消さないこと（4 表それぞれ）、再実行しても同じであることを確かめる。
 */
@Tag("integration")
class RetentionIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val proxy = MySqlSupport.hostAndPort().let { (h, p) -> TcpProxy(h, p).start() }
        private val dir: Path = Files.createTempDirectory("retention-it")

        @JvmStatic
        @AfterAll
        fun stop() = proxy.close()
    }

    private fun run(execute: Boolean): Int {
        val env =
            baseEnv(
                writeOpsState(Files.createTempDirectory(dir, "ops"), "NORMAL").toString(),
                "DB_URL" to MySqlSupport.jdbcUrl(db.name),
                "DB_PASSWORD" to MySqlSupport.PASSWORDS.getValue("bot"),
                "RETENTION_EXECUTE" to if (execute) "true" else null,
            )
        return BotLauncher().run(args("retention", "1"), env)
    }

    private fun n(table: String) = db.count("SELECT COUNT(*) FROM $table")

    @BeforeEach
    fun seed() {
        db.resetData()
        // 組オッズ: 古い（20 日前）2 行と新しい（3 日前）1 行、別テナントの古い 1 行
        db.exec(
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202610240501', '2026-10-24', '05', 1, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (2, '202610240501', '2026-10-24', '05', 1, NOW(6), NOW(6))",
        )
        for ((tenant, days, combo) in listOf(Triple(1, 20, "01-02"), Triple(1, 20, "01-03"), Triple(1, 3, "02-03"), Triple(2, 20, "01-02"))) {
            db.exec(
                "INSERT INTO combo_odds_snapshots (tenant_id, race_id, market, combo_key, collected_at, odds, created_at) " +
                    "VALUES ($tenant, '202610240501', 'wide', '$combo', DATE_SUB(NOW(6), INTERVAL $days DAY), 3.5, NOW(6))",
            )
        }
    }

    @Test
    fun `既定はドライラン 何も消さない`() {
        assertEquals(0, run(execute = false))
        assertEquals(4, n("combo_odds_snapshots"))
    }

    @Test
    fun `execute のときだけ 保持期間を過ぎた自分のテナントの行を消し 新しい行と別テナントは残し 再実行しても同じ`() {
        assertEquals(0, run(execute = true))
        assertEquals(2, n("combo_odds_snapshots"), "自テナントの古い 2 行だけ消える")
        assertEquals(1, db.count("SELECT COUNT(*) FROM combo_odds_snapshots WHERE tenant_id = 2"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM combo_odds_snapshots WHERE tenant_id = 1 AND combo_key = '02-03'"))
        assertEquals(0, run(execute = true))
        assertEquals(2, n("combo_odds_snapshots"))
    }

    /** 外部キーを一時的に無効にして行を直接入れる（取込側の親行を作らずに、削除の SQL の条件だけを確かめるため）。 */
    private fun seedRaw(vararg sql: String) {
        db.root().use { c ->
            c.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS = 0")
                sql.forEach { st.executeUpdate(it) }
                st.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    private fun raw(
        tenant: Int,
        hash: String,
        days: Int,
        status: String,
    ) = "INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at, parse_status) " +
        "VALUES ($tenant, '${hash.padEnd(
            64,
            '0',
        )}', '00000000-0000-0000-0000-000000000001', 1, 'RACE', 'RA', 'x', DATE_SUB(NOW(6), INTERVAL $days DAY), '$status')"

    @Test
    fun `生レコードは解析待ち(PENDING)を古くても残し 解析済み・失敗の古いものだけを消す`() {
        seedRaw(raw(1, "a", 40, "PARSED"), raw(1, "b", 40, "FAILED"), raw(1, "c", 40, "PENDING"), raw(1, "d", 5, "PARSED"), raw(2, "e", 40, "PARSED"))
        assertEquals(0, run(execute = true))
        assertEquals(
            0,
            db.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 1 AND parse_status <> 'PENDING' AND received_at < DATE_SUB(NOW(6), INTERVAL 30 DAY)"),
        )
        assertEquals(1, db.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 1 AND parse_status = 'PENDING'"), "PENDING は保護")
        assertEquals(1, db.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 1 AND parse_status = 'PARSED'"), "新しい行は残る")
        assertEquals(1, db.count("SELECT COUNT(*) FROM ingest_raw WHERE tenant_id = 2"), "別テナントは触らない")
    }

    private fun botRun(
        tenant: Int,
        id: Int,
        days: Int,
        state: String,
        slot: String,
    ) = "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, active_slot, started_at) " +
        "VALUES ($tenant, '00000000-0000-0000-0000-0000000000${"%02d".format(
            id,
        )}', '2026-01-01', 'SCHEDULER', '$state', $slot, DATE_SUB(NOW(6), INTERVAL $days DAY))"

    @Test
    fun `bot-run の記録は終了したものだけ消し 実行中(枠を持つ)は古くても残す`() {
        seedRaw(
            botRun(1, 1, 400, "SUCCEEDED", "NULL"),
            botRun(1, 2, 400, "FAILED", "NULL"),
            botRun(1, 3, 400, "RUNNING", "1"),
            botRun(1, 4, 10, "SUCCEEDED", "NULL"),
            botRun(2, 5, 400, "SUCCEEDED", "NULL"),
        )
        assertEquals(0, run(execute = true))
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE tenant_id = 1 AND state = 'RUNNING'"), "実行中は保護")
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE tenant_id = 1 AND state = 'SUCCEEDED'"), "新しい終了済みは残る")
        assertEquals(0, db.count("SELECT COUNT(*) FROM bot_run WHERE tenant_id = 1 AND state = 'FAILED'"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE tenant_id = 2"), "別テナントは触らない")
    }

    private fun event(
        tenant: Int,
        n: Int,
        days: Int,
    ): String {
        val hash = n.toString().padStart(64, '0')
        return "INSERT INTO race_schedule_event " +
            "(tenant_id, race_id, race_date, event_type, old_value, new_value, source, observed_at, received_at, batch_id, dedup_hash) " +
            "VALUES ($tenant, NULL, '2026-01-01', 'DAY_STATUS', NULL, NULL, 'SYSTEM', DATE_SUB(NOW(6), INTERVAL $days DAY), NOW(6), NULL, '$hash')"
    }

    @Test
    fun `予定履歴は 365 日より古いものだけ消す`() {
        seedRaw(event(1, 1, 400), event(1, 2, 10), event(2, 3, 400))
        assertEquals(0, run(execute = true))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE tenant_id = 1"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE tenant_id = 2"))
    }
}
