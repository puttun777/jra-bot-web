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
 * 解析待ちの生レコード・実行中の bot-run・新しい行・別のテナントを消さないこと、再実行しても同じであることを確かめる。
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
}
