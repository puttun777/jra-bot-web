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
 * reconcile の通し（本物の MySQL・本番と同じ配線・`bot` ユーザー）。`bot` が `results` / `payouts` を読め、`race_day` を更新し、`race_schedule_event`（SYSTEM の DAY_STATUS）を
 * 追記できること、何度実行しても同じ結果（履歴が増えない）であることを確かめる。合成データのみ。
 */
@Tag("integration")
class ReconcileIntegrationTest {
    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val proxy = MySqlSupport.hostAndPort().let { (h, p) -> TcpProxy(h, p).start() }
        private val dir: Path = Files.createTempDirectory("reconcile-it")
        private const val DATE = "2026-10-24"

        @JvmStatic
        @AfterAll
        fun stop() = proxy.close()
    }

    private fun env(): Map<String, String> =
        baseEnv(
            writeOpsState(Files.createTempDirectory(dir, "ops"), "NORMAL").toString(),
            "DB_URL" to MySqlSupport.jdbcUrl(db.name),
            "DB_PASSWORD" to MySqlSupport.PASSWORDS.getValue("bot"),
        )

    private fun reconcile() = BotLauncher().run(args("reconcile", "1", "--date=$DATE"), env())

    private fun rid(no: Int) = "20261024" + "05" + "%02d".format(no)

    private fun status() = db.scalar("SELECT status FROM race_day WHERE race_date = '$DATE'")

    private fun events() = db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'DAY_STATUS' AND source = 'SYSTEM'")

    @BeforeEach
    fun seed() {
        db.resetData()
        db.exec(
            "INSERT INTO race_day (tenant_id, race_date, venue_code, kai, nichime, status, first_seen_at, last_confirmed_at) VALUES " +
                "(1, '$DATE', '05', 4, 8, 'INCOMPLETE', '2026-10-21 03:00:00.000000', '2026-10-24 00:00:00.000000')",
        )
        for (no in 1..3) {
            // 発走 10:00 / 10:25 / 10:50（JST）= UTC 01:00 / 01:25 / 01:50。締切（最終発走 + 90 分）は現在より十分に前
            val post = "2026-10-24 01:%02d:00.000000".format(25 * (no - 1))
            val id = rid(no)
            db.exec(
                "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) " +
                    "VALUES (1, '$id', '$DATE', '05', $no, NOW(6), NOW(6))",
            )
            db.exec(
                "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, scheduled_post_at, post_time_unknown, status, " +
                    "data_kubun, source_version, race_name, last_changed_at) " +
                    "VALUES (1, '$id', '$DATE', '05', $no, '$post', 0, 'OFF', '2', '202610232', 'R$no', '2026-10-23 03:00:00.000000')",
            )
        }
    }

    private fun settle(no: Int) {
        db.exec(
            "INSERT INTO results (tenant_id, race_id, umaban, finish, payout, created_at, updated_at) " +
                "VALUES (1, '${rid(no)}', 1, 1, 3.2, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO payouts (tenant_id, race_id, market, combo_key, payout, is_provisional, created_at, updated_at) VALUES (1, '${rid(
                no,
            )}', 'win', '01', 320, 0, NOW(6), NOW(6))",
        )
    }

    @Test
    fun `結果がそろっていれば INCOMPLETE の日を COMPLETED にして 履歴を残し 再実行しても同じ`() {
        (1..3).forEach(::settle)
        assertEquals(0, reconcile())
        assertEquals("COMPLETED", status())
        val first = events()
        assertEquals(true, first >= 1, "SYSTEM の DAY_STATUS が追記される")
        assertEquals(0, reconcile())
        assertEquals("COMPLETED", status())
        assertEquals(first, events(), "再実行で履歴が増えない（冪等）")
    }

    @Test
    fun `結果がそろっていなければ変更せず 履歴も増やさない`() {
        settle(1)
        settle(2) // 3 レース目の結果が無い
        repeat(2) { assertEquals(0, reconcile()) }
        assertEquals("INCOMPLETE", status())
        assertEquals(0, events())
    }

    @Test
    fun `暫定の払戻だけでは完了にしない`() {
        (1..3).forEach(::settle)
        db.exec("UPDATE payouts SET is_provisional = 1 WHERE race_id = '${rid(3)}'")
        assertEquals(0, reconcile())
        assertEquals("INCOMPLETE", status())
    }
}
