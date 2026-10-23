package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R__grants.sql の検証（CI の一時コンテナのテスト用ユーザー）。許可された操作は通り、足りない権限の操作は拒否される。
 * **これは MySQL コンテナでの確認であり、Cloud SQL での検証（G-DB-6）ではない。**
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrantsIntegrationTest {
    private lateinit var db: TestDb

    private val now = "UTC_TIMESTAMP(6)"
    private val race1 = "202611010501"
    private val denied = intArrayOf(MySqlError.TABLE_DENIED, MySqlError.COLUMN_DENIED, MySqlError.DB_DENIED)

    private fun hash(n: Int) = n.toString().padStart(64, '0')

    @BeforeAll
    fun setUp() {
        db = CliHarness.migratedDatabase()
        db.root().use { c ->
            c.run("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1, 'tenant-one', $now)")
            c.run(
                "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '$race1', '2026-11-01', '05', 1, $now, $now)",
            )
            c.run(
                "INSERT INTO agent (tenant_id, agent_id, name, token_hash, token_expires_at, status, created_at) VALUES (1, 'a1', 'pc', '${hash(
                    1,
                )}', DATE_ADD($now, INTERVAL 1 DAY), 'ACTIVE', $now)",
            )
            c.run(
                "INSERT INTO ingest_batch (tenant_id, batch_id, agent_id, batch_class, dataspec, observed_at, received_at, payload_sha256, record_count) VALUES (1, '00000000-0000-7000-8000-000000000001', 'a1', 'S', 'RACE', $now, $now, '${hash(
                    2,
                )}', 1)",
            )
            c.run(
                "INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at) VALUES (1, '${hash(
                    3,
                )}', '00000000-0000-7000-8000-000000000001', 1, 'RACE', 'RA', 'x', $now)",
            )
            c.run(
                "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at) VALUES (1, 'm1', 'alpha', 1, 'sha256:${hash(
                    4,
                )}', 'gs://t/m1.json', '${hash(5)}', '${hash(6)}', 'CANDIDATE', $now)",
            )
            c.run("INSERT INTO app_user (tenant_id, email, role, created_at, updated_at) VALUES (1, 'alice@example.com', 'ADMIN', $now, $now)")
            c.run(
                "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 1, '2026-11-01 01:00:00', 3.0, 0.4, 0.2, $now)",
            )
            c.run(
                "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, '$race1', 'win', '2026-11-01 01:00:00', 1, 3.0)",
            )
            c.run("INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (1, 'a1', 'old', DATE_SUB($now, INTERVAL 1 DAY))")
            c.run(
                "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, status, data_kubun, source_version, last_changed_at) VALUES (1, '$race1', '2026-11-01', '05', 1, 'ANNOUNCED', '1', 'v1', $now)",
            )
        }
    }

    private fun allow(
        user: String,
        vararg statements: String,
    ) {
        db.connect(user).use { c ->
            for (sql in statements) {
                try {
                    c.run(sql)
                } catch (e: java.sql.SQLException) {
                    throw AssertionError("$user は許可されるはずの操作に失敗: $sql (${e.errorCode} ${e.message})", e)
                }
            }
        }
    }

    private fun deny(
        user: String,
        vararg statements: String,
    ) {
        db.connect(user).use { c ->
            for (sql in statements) assertSqlError(*denied, message = "$user: $sql") { c.run(sql) }
        }
    }

    @Test
    fun `4 つの用途別ユーザーにスキーマ全体の権限・DDL・GRANT OPTION が無い`() {
        for (user in MySqlSupport.RESTRICTED_USERS) {
            val grantee = "'$user'@'%'"
            assertEquals(
                emptyList(),
                db.query(
                    "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = \"$grantee\" AND TABLE_SCHEMA = '${db.name}'",
                ) {
                    it.getString(1)
                },
                "$user にスキーマ全体の権限がある",
            )
            assertEquals(
                listOf("USAGE"),
                db.query("SELECT DISTINCT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = \"$grantee\"") {
                    it.getString(1)
                },
                "$user にグローバル権限がある",
            )
            val tablePrivileges =
                db
                    .query(
                        "SELECT DISTINCT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = \"$grantee\" AND TABLE_SCHEMA = '${db.name}'",
                    ) {
                        it.getString(1)
                    }.toSet()
            assertTrue(
                tablePrivileges.isNotEmpty() && tablePrivileges.all { it in setOf("SELECT", "INSERT", "UPDATE", "DELETE") },
                "$user の表の権限: $tablePrivileges",
            )
            val grantOption =
                db.query(
                    "SELECT IS_GRANTABLE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = \"$grantee\" AND TABLE_SCHEMA = '${db.name}' AND IS_GRANTABLE = 'YES'",
                ) {
                    it.getString(1)
                }
            assertEquals(emptyList(), grantOption, "$user に GRANT OPTION がある")
            val columnPrivileges =
                db
                    .query(
                        "SELECT DISTINCT PRIVILEGE_TYPE FROM information_schema.COLUMN_PRIVILEGES WHERE GRANTEE = \"$grantee\" AND TABLE_SCHEMA = '${db.name}'",
                    ) {
                        it.getString(1)
                    }.toSet()
            assertTrue(columnPrivileges.all { it in setOf("SELECT", "UPDATE") }, "$user の列の権限: $columnPrivileges")
        }
    }

    @Test
    fun `全ユーザーが DDL とデータベース操作と GRANT を拒否される`() {
        for (user in MySqlSupport.RESTRICTED_USERS) {
            deny(
                user,
                "CREATE TABLE intruder (id INT)",
                "DROP TABLE races",
                "ALTER TABLE races ADD COLUMN x INT",
                "CREATE INDEX ix_intruder ON races (name)",
                "TRUNCATE TABLE races",
                "DROP DATABASE `${db.name}`",
                "GRANT SELECT ON `${db.name}`.races TO '$user'@'%'",
                "DROP TABLE flyway_schema_history",
                "DELETE FROM flyway_schema_history",
            )
        }
    }

    @Test
    fun `app（dashboard）は読取りと管理操作の表だけ書ける`() {
        allow(
            "app",
            "SELECT COUNT(*) FROM races",
            "SELECT email, role, enabled FROM app_user",
            "SELECT COUNT(*) FROM signals",
            "SELECT agent_id, name, status, token_expires_at FROM agent",
            "SELECT id, tenant_id, expires_at, used_at FROM agent_registration_code",
            "SELECT MAX(version) FROM flyway_schema_history",
            "INSERT INTO calendar_override (tenant_id, scope, race_date, action, reason, created_by, created_at) VALUES (1, 'DAY', '2026-11-01', 'MARK_NO_RACE', 'test', 'alice@example.com', $now)",
            "UPDATE calendar_override SET revoked_at = $now, revoked_by = 'alice@example.com' WHERE tenant_id = 1",
            "INSERT INTO race_schedule_event (tenant_id, race_id, race_date, event_type, source, observed_at, received_at, dedup_hash) VALUES (1, NULL, '2026-11-01', 'MANUAL_OVERRIDE', 'MANUAL', $now, $now, '${hash(
                40,
            )}')",
            "INSERT INTO agent_registration_code (tenant_id, code_hash, expires_at, created_at) VALUES (1, '${hash(
                10,
            )}', DATE_ADD($now, INTERVAL 1 DAY), $now)",
            "UPDATE agent SET status = 'REVOKED', revoked_at = $now, revoked_by = 'alice@example.com' WHERE tenant_id = 1 AND agent_id = 'a1'",
            "INSERT INTO audit_log (tenant_id, occurred_at, actor_type, actor, action, target_type) VALUES (1, $now, 'USER', 'alice@example.com', 'AGENT_REVOKE', 'agent')",
            "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, active_slot, started_at) VALUES (1, '00000000-0000-7000-8000-000000000a01', '2026-11-01', 'MANUAL', 'STARTING', 1, $now)",
            "UPDATE ml_model SET status = 'REJECTED' WHERE tenant_id = 1 AND model_id = 'm1'",
        )
        deny(
            "app",
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202611010601', '2026-11-01', '06', 1, $now, $now)",
            "UPDATE races SET name = 'x'",
            "DELETE FROM calendar_override",
            "UPDATE race_schedule_event SET source = 'SYSTEM'",
            "DELETE FROM race_schedule_event",
            "UPDATE race_schedule SET status = 'CARD'",
            "INSERT INTO race_day (tenant_id, race_date, venue_code, kai, nichime, status, first_seen_at) VALUES (1, '2026-11-01', '06', 1, 1, 'PLANNED', $now)",
            "UPDATE audit_log SET reason = 'tamper'",
            "DELETE FROM audit_log",
            "INSERT INTO app_user (tenant_id, email, role, created_at, updated_at) VALUES (1, 'x@example.com', 'ADMIN', $now, $now)",
            "UPDATE app_user SET role = 'ADMIN'",
            "SELECT token_hash FROM agent",
            "SELECT * FROM agent",
            "SELECT code_hash FROM agent_registration_code",
            "SELECT payload FROM ingest_raw",
            "SELECT nonce FROM agent_nonce",
            "SELECT * FROM import_quarantine",
            "UPDATE ml_model SET sha256 = '${hash(99)}'",
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at) VALUES (1, 'x', 'alpha', 1, 'h', 'u', '${hash(
                5,
            )}', '${hash(6)}', 'CANDIDATE', $now)",
            "UPDATE bot_run SET state = 'FAILED'",
            "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 9, '2026-11-01 09:00:00', 3.0, 0.4, 0.2, $now)",
        )
    }

    @Test
    fun `ingest は取込先の表だけ書け、判定・利用者・ML には触れない`() {
        allow(
            "ingest",
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202611010601', '2026-11-01', '06', 1, $now, $now)",
            "UPDATE races SET name = 'R' WHERE tenant_id = 1 AND race_id = '202611010601'",
            "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, '$race1', 'win', '2026-11-01 02:00:00', 2, 4.0)",
            "UPDATE odds_snapshots SET odds = 5.0 WHERE tenant_id = 1 AND umaban = 2",
            "INSERT INTO results (tenant_id, race_id, umaban, created_at, updated_at) VALUES (1, '$race1', 1, $now, $now)",
            "INSERT INTO agent_heartbeat (tenant_id, agent_id, last_seen_at) VALUES (1, 'a1', $now)",
            "INSERT INTO ingest_batch (tenant_id, batch_id, agent_id, batch_class, dataspec, observed_at, received_at, payload_sha256, record_count) VALUES (1, '00000000-0000-7000-8000-000000000b01', 'a1', 'S', 'RACE', $now, $now, '${hash(
                20,
            )}', 1)",
            "INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at) VALUES (1, '${hash(
                21,
            )}', '00000000-0000-7000-8000-000000000b01', 1, 'RACE', 'RA', 'x', $now)",
            "SELECT payload FROM ingest_raw",
            "INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (1, 'a1', 'n1', $now)",
            "DELETE FROM agent_nonce WHERE expires_at < $now",
            "INSERT INTO race_schedule_event (tenant_id, race_id, race_date, event_type, source, observed_at, received_at, dedup_hash) VALUES (1, NULL, '2026-11-01', 'DAY_STATUS', 'SYSTEM', $now, $now, '${hash(
                22,
            )}')",
            "UPDATE race_schedule SET status = 'CARD' WHERE tenant_id = 1",
            "SELECT agent_id, token_hash FROM agent",
            "UPDATE agent_registration_code SET used_at = $now, used_by_agent_id = 'a1' WHERE code_hash = '${hash(10)}'",
            "SELECT MAX(version) FROM flyway_schema_history",
        )
        deny(
            "ingest",
            "DELETE FROM races",
            "DELETE FROM odds_snapshots",
            "DELETE FROM ingest_raw",
            "UPDATE race_schedule_event SET source = 'MANUAL'",
            "DELETE FROM race_schedule_event",
            "SELECT * FROM signals",
            "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 9, '2026-11-01 09:00:00', 3.0, 0.4, 0.2, $now)",
            "SELECT * FROM bets",
            "SELECT * FROM app_user",
            "SELECT * FROM ml_model",
            "SELECT * FROM bot_run",
            "SELECT * FROM audit_log",
            "SELECT * FROM calendar_override",
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at) VALUES (1, 'x', 'alpha', 1, 'h', 'u', '${hash(
                5,
            )}', '${hash(6)}', 'CANDIDATE', $now)",
        )
    }

    @Test
    fun `bot は判定・成績・実行管理の表を書け、保持ポリシー対象の表だけ削除できる`() {
        allow(
            "bot",
            "SELECT COUNT(*) FROM races",
            "SELECT COUNT(*) FROM ml_model",
            "SELECT COUNT(*) FROM calendar_override",
            "SELECT tenant_id, record_hash, batch_id, seq, received_at FROM ingest_raw",
            "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 9, '2026-11-01 09:00:00', 3.0, 0.4, 0.2, $now)",
            "UPDATE signals SET notify_state = 'CLAIMED' WHERE tenant_id = 1 AND umaban = 9 AND notify_state = 'UNSENT'",
            "INSERT INTO bets (tenant_id, race_id, placed_at, stake, created_at) VALUES (1, '$race1', $now, 1, $now)",
            "INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race1', $now, $now)",
            "INSERT INTO model_metrics (tenant_id, sample_count, created_at) VALUES (1, 10, $now)",
            "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, active_slot, started_at) VALUES (1, '00000000-0000-7000-8000-000000000c01', '2026-11-02', 'SCHEDULER', 'STARTING', 1, $now)",
            "UPDATE bot_run SET state = 'RUNNING', version = version + 1 WHERE tenant_id = 1 AND run_id = '00000000-0000-7000-8000-000000000c01'",
            "UPDATE race_schedule SET status = 'MONITORING' WHERE tenant_id = 1",
            "INSERT INTO race_schedule_event (tenant_id, race_id, race_date, event_type, source, observed_at, received_at, dedup_hash) VALUES (1, NULL, '2026-11-01', 'DAY_STATUS', 'SYSTEM', $now, $now, '${hash(
                30,
            )}')",
            "INSERT INTO ingest_gap (tenant_id, reason, gap_from, detected_at) VALUES (1, 'STOPPED', '2026-11-01 00:00:00', $now)",
            "DELETE FROM odds_snapshots WHERE tenant_id = 1 AND observed_at < '2020-01-01'",
            "DELETE FROM ingest_raw WHERE tenant_id = 1 AND received_at < '2020-01-01'",
            "DELETE FROM race_schedule_event WHERE tenant_id = 1 AND observed_at < '2020-01-01'",
            "DELETE FROM bot_run WHERE tenant_id = 1 AND started_at < '2020-01-01'",
        )
        deny(
            "bot",
            "SELECT payload FROM ingest_raw",
            "SELECT * FROM ingest_raw",
            "SELECT * FROM app_user",
            "SELECT * FROM agent",
            "SELECT * FROM agent_nonce",
            "SELECT * FROM audit_log",
            "DELETE FROM signals",
            "DELETE FROM bets",
            "DELETE FROM races",
            "DELETE FROM results",
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202611010701', '2026-11-01', '07', 1, $now, $now)",
            "INSERT INTO calendar_override (tenant_id, scope, race_date, action, reason, created_by, created_at) VALUES (1, 'DAY', '2026-11-01', 'DENY_RUN', 'x', 'bot', $now)",
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at) VALUES (1, 'x', 'alpha', 1, 'h', 'u', '${hash(
                5,
            )}', '${hash(6)}', 'CANDIDATE', $now)",
            "UPDATE ml_model SET status = 'ACTIVE'",
            "INSERT INTO audit_log (tenant_id, occurred_at, actor_type, actor, action, target_type) VALUES (1, $now, 'SYSTEM', 'bot', 'X', 'x')",
        )
    }

    @Test
    fun `mltrain は ml_model の SELECT と INSERT だけ`() {
        allow(
            "mltrain",
            "SELECT model_id, status FROM ml_model",
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at) VALUES (1, 'trained-1', 'alpha', 1, 'sha256:${hash(
                4,
            )}', 'gs://t/trained-1.json', '${hash(7)}', '${hash(8)}', 'CANDIDATE', $now)",
        )
        deny(
            "mltrain",
            "UPDATE ml_model SET status = 'ACTIVE'",
            "UPDATE ml_model SET sha256 = '${hash(9)}'",
            "DELETE FROM ml_model",
            "SELECT * FROM races",
            "SELECT * FROM signals",
            "SELECT * FROM app_user",
            "SELECT * FROM tenant",
            "SELECT * FROM flyway_schema_history",
            "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '202611010701', '2026-11-01', '07', 1, $now, $now)",
            "INSERT INTO audit_log (tenant_id, occurred_at, actor_type, actor, action, target_type) VALUES (1, $now, 'SYSTEM', 'mltrain', 'X', 'x')",
        )
    }

    @Test
    fun `権限の再適用は冪等で、手で付けた余分な権限を取り除く`() {
        // 誤って強い権限が付いていても、次の R__grants.sql（内容が変わったとき）のリセットで外れる。ここでは同じ手順（リセット → 付与）を直接実行して確かめる。
        db.exec("GRANT ALL PRIVILEGES ON `${db.name}`.* TO 'mltrain'@'%'")
        db.exec("GRANT DELETE ON `${db.name}`.`races` TO 'mltrain'@'%'")
        val grants = checkNotNull(javaClass.classLoader.getResourceAsStream("db/migration/R__grants.sql")).readBytes().toString(Charsets.UTF_8)
        val sql =
            grants
                .lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .replace("\${flyway:defaultSchema}", db.name)
        db.root().use { c ->
            for (statement in sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }) c.run(statement)
        }
        deny("mltrain", "DELETE FROM races", "SELECT * FROM races", "CREATE TABLE intruder (id INT)")
        allow("mltrain", "SELECT COUNT(*) FROM ml_model")
        assertEquals(
            listOf("INSERT", "SELECT"),
            db.query(
                "SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE = \"'mltrain'@'%'\" AND TABLE_SCHEMA = '${db.name}' ORDER BY PRIVILEGE_TYPE",
            ) {
                it.getString(1)
            },
        )
    }
}
