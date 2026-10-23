package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 空の MySQL に V1・V2・R__grants を適用できること、できあがったスキーマの形（表・列・型・索引）。 */
@Tag("integration")
class MigrationApplyIntegrationTest {
    private val expectedTables =
        setOf(
            "tenant",
            "app_user",
            "code_venue",
            "races",
            "race_horses",
            "horses",
            "horse_past_runs",
            "odds_snapshots",
            "combo_odds_snapshots",
            "results",
            "payouts",
            "signals",
            "bets",
            "bet_legs",
            "race_evaluations",
            "ticket_evaluations",
            "model_metrics",
            "ingest_cursor",
            "import_quarantine",
            "audit_log",
            "race_day",
            "race_schedule",
            "race_schedule_event",
            "calendar_override",
            "bot_run",
            "agent",
            "agent_registration_code",
            "agent_heartbeat",
            "agent_nonce",
            "ingest_batch",
            "ingest_raw",
            "ingest_gap",
            "ml_model",
            "flyway_schema_history",
        )

    private fun columnType(
        db: TestDb,
        table: String,
        column: String,
    ): String =
        checkNotNull(
            db.scalar(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = '$table' AND COLUMN_NAME = '$column'",
            ),
        ) { "$table.$column が無い" }

    @Test
    fun `空のデータベースに V1 V2 R__grants を適用でき、サーバの版を記録する`() {
        val db = MySqlSupport.newDatabase()
        val result = CliHarness.run(db, "migrate")
        assertEquals(0, result.code, result.stderr)
        assertTrue(""""success":true""" in result.stdout, result.stdout)
        assertTrue(""""applied":["1","2","R__grants"]""" in result.stdout, result.stdout)
        assertTrue(""""current":"2"""" in result.stdout && """"latest":"2"""" in result.stdout && """"pending":[]""" in result.stdout, result.stdout)
        println("MySQL ${MySqlSupport.image} -> server version ${MySqlSupport.serverVersion()}")
    }

    @Test
    fun `期待した表がすべてあり、余計な表は無い`() {
        val db = CliHarness.migratedDatabase()
        val actual =
            db.query(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_TYPE = 'BASE TABLE'",
            ) { it.getString(1) }
        assertEquals(expectedTables, actual.toSet())
        assertEquals(expectedTables.size, actual.size)
    }

    @Test
    fun `履歴は V1 V2 と R__grants の 3 行で、すべて成功`() {
        val db = CliHarness.migratedDatabase()
        val rows =
            db.query("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank") {
                "${it.getString(1)}|${it.getString(2)}|${it.getInt(3)}"
            }
        assertEquals(listOf("1|baseline|1", "2|ops calendar ingest ml|1", "null|grants|1"), rows)
    }

    @Test
    fun `V1 は本番の行を作らず、場コードの定数だけを入れる`() {
        val db = CliHarness.migratedDatabase()
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM tenant"))
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM app_user"))
        assertEquals((1..10).map { "%02d".format(it) }, db.query("SELECT venue_code FROM code_venue ORDER BY venue_code") { it.getString(1) })
        for (table in expectedTables - setOf("code_venue", "flyway_schema_history")) {
            assertEquals("0", db.scalar("SELECT COUNT(*) FROM `$table`"), "$table は空のはず")
        }
    }

    @Test
    fun `migrate を再実行しても安全で、何も変わらない`() {
        val db = CliHarness.migratedDatabase()
        val before =
            db.query("SELECT installed_rank, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank") {
                "${it.getInt(1)}|${it.getString(2)}|${it.getString(3)}"
            }
        repeat(2) {
            val again = CliHarness.run(db, "migrate")
            assertEquals(0, again.code, again.stderr)
            assertTrue(""""applied":[]""" in again.stdout, again.stdout)
        }
        val after =
            db.query("SELECT installed_rank, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank") {
                "${it.getInt(1)}|${it.getString(2)}|${it.getString(3)}"
            }
        assertEquals(before, after)
    }

    @Test
    fun `楽観ロックの version 列と状態の列挙が domain の型と一致する`() {
        val db = CliHarness.migratedDatabase()
        for (table in listOf("bot_run", "race_schedule", "race_day")) {
            assertEquals("int", columnType(db, table, "version"), table)
            assertEquals(
                "0",
                db.scalar(
                    "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = '$table' AND COLUMN_NAME = 'version'",
                ),
            )
            assertEquals(
                "NO",
                db.scalar(
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = '$table' AND COLUMN_NAME = 'version'",
                ),
            )
        }
        // domain の BotRunState（INTERRUPTED を含む 8 状態）と同じ。:domain には依存できないので、persistence の契約テスト（T2.4）でも照合する。
        assertEquals("enum('STARTING','RUNNING','DRAINING','SUCCEEDED','FAILED','ABORTED','STOPPED_BY_MODE','INTERRUPTED')", columnType(db, "bot_run", "state"))
        assertEquals("enum('SCHEDULER','MANUAL','RECONCILE','BACKFILL')", columnType(db, "bot_run", "trigger_type"))
        assertEquals("enum('UNSENT','CLAIMED','SENT','SEND_UNKNOWN')", columnType(db, "signals", "notify_state"))
        assertEquals("enum('VIEWER','ADMIN')", columnType(db, "app_user", "role"))
        assertEquals("enum('win','wide','sanrenpuku')", columnType(db, "odds_snapshots", "market"))
        assertEquals(
            "enum('ANNOUNCED','CARD','MONITORING','OFF','RESULT_PENDING','RESULT_PARTIAL','SETTLED','FINALIZED','CANCELLED','DELETED')",
            columnType(db, "race_schedule", "status"),
        )
    }

    @Test
    fun `コード列は ascii_bin、race_id は CHAR(12)`() {
        val db = CliHarness.migratedDatabase()
        val rows =
            db.query(
                "SELECT TABLE_NAME, COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = '${db.name}' AND COLUMN_NAME = 'race_id'",
            ) { "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}|${it.getString(4)}" }
        assertTrue(rows.size >= 14, "race_id を持つ表が少なすぎる: ${rows.size}")
        for (row in rows) assertTrue(row.endsWith("|char(12)|ascii|ascii_bin"), row)
        assertEquals(
            "ascii_bin",
            db.scalar(
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'code_venue' AND COLUMN_NAME = 'venue_code'",
            ),
        )
        assertEquals(
            "utf8mb4_0900_bin",
            db.scalar(
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'email'",
            ),
        )
    }

    @Test
    fun `時刻はすべて DATETIME(6)、TIMESTAMP は使わない`() {
        val db = CliHarness.migratedDatabase()
        val rows =
            db.query(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATETIME_PRECISION FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME <> 'flyway_schema_history' AND DATA_TYPE IN ('datetime', 'timestamp', 'time')" +
                    " AND COLUMN_NAME <> 'post_time'",
            ) { "${it.getString(1)}.${it.getString(2)}|${it.getString(3)}|${it.getString(4)}" }
        assertTrue(rows.size > 40, "時刻列が少なすぎる: ${rows.size}")
        for (row in rows) assertTrue(row.endsWith("|datetime|6"), "DATETIME(6) ではない: $row")
    }

    @Test
    fun `確率とオッズは DOUBLE`() {
        val db = CliHarness.migratedDatabase()
        for ((table, column) in listOf(
            "odds_snapshots" to "odds",
            "signals" to "odds",
            "signals" to "prob",
            "signals" to "ev",
            "results" to "payout",
            "payouts" to "payout",
        )) {
            assertEquals("double", columnType(db, table, column), "$table.$column")
        }
    }

    @Test
    fun `自然キーと冪等キーの UNIQUE 索引がある`() {
        val db = CliHarness.migratedDatabase()

        fun index(
            table: String,
            name: String,
        ): Pair<Boolean, List<String>> {
            val rows =
                db.query(
                    "SELECT NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = '$table' AND INDEX_NAME = '$name' ORDER BY SEQ_IN_INDEX",
                ) { it.getInt(1) to it.getString(2) }
            assertTrue(rows.isNotEmpty(), "$table.$name が無い")
            return (rows.first().first == 0) to rows.map { it.second }
        }
        assertEquals(true to listOf("tenant_id", "race_id", "market", "umaban", "observed_at"), index("signals", "uq_signals_natural"))
        assertEquals(true to listOf("email"), index("app_user", "uq_app_user_email"))
        assertEquals(true to listOf("tenant_id", "run_date", "active_slot"), index("bot_run", "uq_bot_run_active"))
        assertEquals(true to listOf("tenant_id", "kind", "active_flag"), index("ml_model", "uq_ml_model_active"))
        assertEquals(true to listOf("tenant_id", "record_hash"), index("ingest_raw", "PRIMARY"))
        assertEquals(true to listOf("tenant_id", "batch_id"), index("ingest_batch", "PRIMARY"))
        assertEquals(true to listOf("code_hash"), index("agent_registration_code", "uq_agent_registration_code_hash"))
        assertEquals(true to listOf("tenant_id", "dedup_hash"), index("race_schedule_event", "uq_race_schedule_event_dedup"))
        assertEquals(true to listOf("tenant_id", "reason", "gap_from", "agent_key"), index("ingest_gap", "uq_ingest_gap_natural"))
        assertEquals(true to listOf("tenant_id", "race_id", "market", "observed_at", "umaban"), index("odds_snapshots", "PRIMARY"))
        assertEquals(true to listOf("tenant_id", "agent_id", "nonce"), index("agent_nonce", "PRIMARY"))
    }

    @Test
    fun `外部キーはすべて InnoDB で有効、テナント表の FK は複合`() {
        val db = CliHarness.migratedDatabase()
        val tenantScopedFkWithoutTenant =
            db.query(
                "SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = '${db.name}' AND ORDINAL_POSITION = 1 " +
                    "AND REFERENCED_TABLE_NAME IS NOT NULL AND REFERENCED_TABLE_NAME NOT IN ('tenant', 'code_venue') AND COLUMN_NAME <> 'tenant_id' AND CONSTRAINT_NAME <> 'fk_ml_model_previous'",
            ) { it.getString(1) }
        assertEquals(emptyList(), tenantScopedFkWithoutTenant)
        assertTrue(db.scalar("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = '${db.name}'")!!.toInt() >= 40)
    }
}
