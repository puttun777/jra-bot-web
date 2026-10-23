package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 制約の検証: テナント越境の拒否（複合 FK）、UNIQUE、CHECK、列挙、精度。合成データだけを使う。
 * DB は 1 クラスで 1 つ共有し、各テストは他と衝突しないキーを使う。
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaConstraintsIntegrationTest {
    private lateinit var db: TestDb

    private val now = "UTC_TIMESTAMP(6)"
    private val race1 = "202611010501" // テナント 1 のレース（2026-11-01 東京 1R）
    private val race2 = "202611010502" // テナント 2 のレース
    private val batch1 = "00000000-0000-7000-8000-000000000001"
    private val batch2 = "00000000-0000-7000-8000-000000000002"

    private fun hash(n: Int) = n.toString().padStart(64, '0')

    private fun uuid(n: Int) = "00000000-0000-7000-8000-" + n.toString().padStart(12, '0')

    private fun raceSql(
        tenant: Int,
        id: String,
    ): String {
        val date = "${id.substring(0, 4)}-${id.substring(4, 6)}-${id.substring(6, 8)}"
        return "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) " +
            "VALUES ($tenant, '$id', '$date', '${id.substring(8, 10)}', ${id.substring(10).toInt()}, $now, $now)"
    }

    private fun modelSql(
        tenant: Int,
        id: String,
        kind: String = "alpha",
        status: String = "CANDIDATE",
        flag: String = "NULL",
        previous: String? = null,
        sha: String = hash(1),
        activatedAt: String = "NULL",
    ) = "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, " +
        "active_flag, created_at, activated_at, previous_model_id) VALUES ($tenant, '$id', '$kind', 1, 'sha256:${hash(
            2,
        )}', 'gs://test/$id.json', '$sha', '${hash(3)}', " +
        "'$status', $flag, $now, $activatedAt, ${previous?.let { "'$it'" } ?: "NULL"})"

    private fun agentSql(
        tenant: Int,
        id: String,
    ) = "INSERT INTO agent (tenant_id, agent_id, name, token_hash, token_expires_at, status, created_at) " +
        "VALUES ($tenant, '$id', 'pc-$id', '${hash(9)}', DATE_ADD($now, INTERVAL 90 DAY), 'ACTIVE', $now)"

    private fun batchSql(
        tenant: Int,
        batch: String,
        agent: String,
    ) = "INSERT INTO ingest_batch (tenant_id, batch_id, agent_id, batch_class, dataspec, observed_at, received_at, payload_sha256, record_count) " +
        "VALUES ($tenant, '$batch', '$agent', 'S', 'RACE', $now, $now, '${hash(7)}', 1)"

    private fun scheduleSql(
        tenant: Int,
        id: String,
    ): String {
        val date = "${id.substring(0, 4)}-${id.substring(4, 6)}-${id.substring(6, 8)}"
        return "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, status, data_kubun, source_version, last_changed_at) " +
            "VALUES ($tenant, '$id', '$date', '${id.substring(8, 10)}', ${id.substring(10).toInt()}, 'ANNOUNCED', '1', '202610221', $now)"
    }

    private fun botRunSql(
        tenant: Int,
        runId: String,
        date: String,
        state: String,
        slot: String,
    ) = "INSERT INTO bot_run (tenant_id, run_id, run_date, trigger_type, state, active_slot, started_at) VALUES ($tenant, '$runId', '$date', 'SCHEDULER', '$state', $slot, $now)"

    @BeforeAll
    fun setUp() {
        db = CliHarness.migratedDatabase()
        db.root().use { c ->
            c.run("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1, 'tenant-one', $now), (2, 'tenant-two', $now)")
            c.run(raceSql(1, race1))
            c.run(raceSql(2, race2))
            c.run(scheduleSql(1, race1))
            c.run(scheduleSql(2, race2))
            c.run(agentSql(1, "a1"))
            c.run(agentSql(2, "a2"))
            c.run(batchSql(1, batch1, "a1"))
            c.run(batchSql(2, batch2, "a2"))
            c.run(modelSql(1, "m1"))
            c.run(modelSql(2, "m2"))
        }
    }

    private fun root(block: (Connection) -> Unit) = db.root().use(block)

    private fun expectFk(sql: String) = root { c -> assertSqlError(MySqlError.FK_CHILD, message = sql) { c.run(sql) } }

    private fun ok(sql: String) = root { c -> c.run(sql) }

    // ------------------------------------------------------------------ テナント越境（複合 FK）
    @Test
    fun `別テナントのレースを参照する行は、どの子表でも拒否され、同じテナントなら入る`() {
        val pairs =
            listOf(
                "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (%d, '$race1', 'win', '2026-11-01 01:00:00.000001', 1, 3.5)",
                "INSERT INTO race_horses (tenant_id, race_id, umaban, created_at, updated_at) VALUES (%d, '$race1', 1, $now, $now)",
                "INSERT INTO results (tenant_id, race_id, umaban, created_at, updated_at) VALUES (%d, '$race1', 1, $now, $now)",
                "INSERT INTO payouts (tenant_id, race_id, market, combo_key, payout, created_at, updated_at) VALUES (%d, '$race1', 'wide', '1-2', 150, $now, $now)",
                "INSERT INTO combo_odds_snapshots (tenant_id, race_id, market, collected_at, combo_key, odds, created_at) VALUES (%d, '$race1', 'wide', $now, '1-2', 5.5, $now)",
                "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (%d, '$race1', 'win', 1, '2026-11-01 01:00:00', 3.5, 0.3, 0.05, $now)",
                "INSERT INTO bets (tenant_id, race_id, placed_at, stake, created_at) VALUES (%d, '$race1', $now, 1, $now)",
                "INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (%d, '$race1', $now, $now)",
            )
        for (template in pairs) {
            expectFk(template.format(2))
            ok(template.format(1))
        }
    }

    @Test
    fun `別テナントの signals bets race_evaluations を参照する子行は拒否される`() {
        root { c ->
            c.run(
                "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 7, '2026-11-01 02:00:00', 2.0, 0.5, 0.0, $now)",
            )
            val signalId = c.long("SELECT id FROM signals WHERE tenant_id = 1 AND umaban = 7")
            // bets.signal_id: テナント 2 の bet がテナント 1 の signal を指せない
            assertSqlError(MySqlError.FK_CHILD) {
                c.run("INSERT INTO bets (tenant_id, race_id, placed_at, stake, signal_id, created_at) VALUES (2, '$race2', $now, 1, $signalId, $now)")
            }
            c.run("INSERT INTO bets (tenant_id, race_id, placed_at, stake, signal_id, created_at) VALUES (1, '$race1', $now, 1, $signalId, $now)")
            val betId = c.long("SELECT id FROM bets WHERE tenant_id = 1 AND signal_id = $signalId")
            assertSqlError(MySqlError.FK_CHILD) {
                c.run("INSERT INTO bet_legs (tenant_id, bet_id, umaban, odds_at_bet, created_at) VALUES (2, $betId, 7, 2.0, $now)")
            }
            c.run("INSERT INTO bet_legs (tenant_id, bet_id, umaban, odds_at_bet, created_at) VALUES (1, $betId, 7, 2.0, $now)")

            c.run("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, created_at) VALUES (1, '$race1', '2026-11-01 03:00:00', $now)")
            val evalId = c.long("SELECT id FROM race_evaluations WHERE tenant_id = 1 AND snapshot_at = '2026-11-01 03:00:00'")
            assertSqlError(MySqlError.FK_CHILD) {
                c.run(
                    "INSERT INTO ticket_evaluations (tenant_id, race_evaluation_id, race_id, snapshot_at, bet_type, odds, created_at) VALUES (2, $evalId, '$race2', $now, '単勝', 2.0, $now)",
                )
            }
            c.run(
                "INSERT INTO ticket_evaluations (tenant_id, race_evaluation_id, race_id, snapshot_at, bet_type, odds, created_at) VALUES (1, $evalId, '$race1', $now, '単勝', 2.0, $now)",
            )

            // 親の削除は子へ CASCADE する（設計どおり bet_legs と ticket_evaluations だけ）
            assertEquals(1, c.update("DELETE FROM bets WHERE tenant_id = 1 AND id = $betId"))
            assertEquals(0L, c.long("SELECT COUNT(*) FROM bet_legs WHERE tenant_id = 1 AND bet_id = $betId"))
            assertEquals(1, c.update("DELETE FROM race_evaluations WHERE tenant_id = 1 AND id = $evalId"))
            assertEquals(0L, c.long("SELECT COUNT(*) FROM ticket_evaluations WHERE tenant_id = 1 AND race_evaluation_id = $evalId"))
        }
    }

    @Test
    fun `モデルは同じテナントのものだけ参照できる（race_evaluations と previous_model_id）`() {
        expectFk("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, model_id, created_at) VALUES (2, '$race2', '2026-11-01 04:00:00', 'm1', $now)")
        ok("INSERT INTO race_evaluations (tenant_id, race_id, snapshot_at, model_id, created_at) VALUES (2, '$race2', '2026-11-01 04:00:00', 'm2', $now)")
        expectFk(modelSql(2, "m3", previous = "m1"))
        expectFk(modelSql(2, "m4", previous = "no-such-model")) // 存在しない親（MySQL 9.7.4 の自己参照 FK の未検査に対する回帰テスト: D-069）
        ok(modelSql(2, "m3", previous = "m2"))
    }

    @Test
    fun `エージェント・バッチ・イベント・補正も別テナントの親を参照できない`() {
        expectFk(batchSql(2, uuid(10), "a1"))
        ok(batchSql(2, uuid(10), "a2"))
        expectFk("INSERT INTO agent_heartbeat (tenant_id, agent_id, last_seen_at) VALUES (2, 'a1', $now)")
        ok("INSERT INTO agent_heartbeat (tenant_id, agent_id, last_seen_at) VALUES (2, 'a2', $now)")
        expectFk("INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (2, 'a1', 'n-1', $now)")
        ok("INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (2, 'a2', 'n-1', $now)")
        // 生レコードのバッチ
        val raw = "INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at) VALUES (%d, '%s', '%s', 1, 'RACE', 'RA', 'x', $now)"
        expectFk(raw.format(2, hash(100), batch1))
        ok(raw.format(2, hash(100), batch2))
        // 開催予定のイベント・補正・登録コード・取りこぼし
        val event = "INSERT INTO race_schedule_event (tenant_id, race_id, race_date, event_type, source, observed_at, received_at, batch_id, dedup_hash) VALUES (%d, %s, '2026-11-01', 'ADDED', 'YSCH', $now, $now, %s, '%s')"
        expectFk(event.format(2, "'$race1'", "NULL", hash(101)))
        expectFk(event.format(2, "NULL", "'$batch1'", hash(102)))
        ok(event.format(2, "'$race2'", "'$batch2'", hash(103)))
        expectFk(
            "INSERT INTO calendar_override (tenant_id, scope, race_date, race_id, action, reason, created_by, created_at) VALUES (2, 'RACE', '2026-11-01', '$race1', 'CANCEL', 'r', 'u@example.com', $now)",
        )
        ok(
            "INSERT INTO calendar_override (tenant_id, scope, race_date, race_id, action, reason, created_by, created_at) VALUES (2, 'RACE', '2026-11-01', '$race2', 'CANCEL', 'r', 'u@example.com', $now)",
        )
        expectFk(
            "INSERT INTO agent_registration_code (tenant_id, code_hash, expires_at, created_at, used_at, used_by_agent_id) VALUES (2, '${hash(
                110,
            )}', $now, $now, $now, 'a1')",
        )
        ok(
            "INSERT INTO agent_registration_code (tenant_id, code_hash, expires_at, created_at, used_at, used_by_agent_id) VALUES (2, '${hash(
                110,
            )}', $now, $now, $now, 'a2')",
        )
        expectFk(
            "INSERT INTO ingest_gap (tenant_id, agent_id, reason, gap_from, detected_at) VALUES (2, 'a1', 'AGENT_UNREACHABLE', '2026-11-01 00:00:00', $now)",
        )
        ok("INSERT INTO ingest_gap (tenant_id, agent_id, reason, gap_from, detected_at) VALUES (2, 'a2', 'AGENT_UNREACHABLE', '2026-11-01 00:00:00', $now)")
    }

    @Test
    fun `存在しないテナントの行は入らず、親のある行は削除できない`() {
        expectFk(raceSql(99, "202611010601"))
        root { c ->
            c.run(
                "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, '$race1', 'win', '2026-11-01 05:00:00', 2, 4.0)",
            )
            assertSqlError(MySqlError.FK_PARENT) { c.run("DELETE FROM races WHERE tenant_id = 1 AND race_id = '$race1'") }
            assertSqlError(MySqlError.FK_PARENT) { c.run("DELETE FROM tenant WHERE tenant_id = 1") }
        }
    }

    // ------------------------------------------------------------------ app_user（メール正規化と findByEmail の契約）
    @Test
    fun `app_user のメールは全体で一意で、小文字の正規化形だけを保存できる`() {
        val insert = "INSERT INTO app_user (tenant_id, email, role, created_at, updated_at) VALUES (%d, '%s', '%s', $now, $now)"
        ok(insert.format(1, "alice@example.com", "ADMIN"))
        // 同じメールを別テナントに登録できない: findByEmail(email) が常に 1 件に定まる（D-063）
        root { c -> assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(insert.format(2, "alice@example.com", "VIEWER")) } }
        // 大文字を含む形は保存できない（アプリが小文字に正規化する: D-062。DB は最後の防壁）
        root { c -> assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(insert.format(2, "Bob@Example.com", "VIEWER")) } }
        // ロールは VIEWER / ADMIN だけ
        root { c -> assertSqlError(MySqlError.DATA_TRUNCATED) { c.run(insert.format(2, "carol@example.com", "OWNER")) } }
        ok(insert.format(2, "dave@example.com", "VIEWER"))
        // 検索は完全一致（大文字小文字を同一視しない）。1 件に定まり、enabled の既定は 1
        assertEquals(listOf("1"), db.query("SELECT tenant_id FROM app_user WHERE email = 'alice@example.com'") { it.getString(1) })
        assertEquals(emptyList(), db.query("SELECT tenant_id FROM app_user WHERE email = 'ALICE@EXAMPLE.COM'") { it.getString(1) })
        assertEquals("1", db.scalar("SELECT enabled FROM app_user WHERE email = 'dave@example.com'"))
        // 形式の最小検査（@ が先頭）
        root { c -> assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(insert.format(2, "@example.com", "VIEWER")) } }
    }

    // ------------------------------------------------------------------ races / 値の範囲
    @Test
    fun `race_id は日付・場・レース番号と一致し、場コードは 01 から 10`() {
        root { c ->
            val bad = "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, created_at, updated_at) VALUES (1, '%s', '%s', '%s', %d, $now, $now)"
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "日付の不一致") { c.run(bad.format("202611010701", "2026-11-02", "07", 1)) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "場の不一致") { c.run(bad.format("202611010701", "2026-11-01", "08", 1)) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "R の不一致") { c.run(bad.format("202611010701", "2026-11-01", "07", 2)) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "レース番号 0") { c.run(bad.format("202611010700", "2026-11-01", "07", 0)) }
            assertSqlError(MySqlError.FK_CHILD, message = "場コード 11") { c.run(bad.format("202611011101", "2026-11-01", "11", 1)) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "12 桁でない") { c.run(bad.format("20261101070", "2026-11-01", "07", 1)) }
            c.run(bad.format("202611010701", "2026-11-01", "07", 1))
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(bad.format("202611010701", "2026-11-01", "07", 1)) }
            // 同じ race_id でもテナントが違えば別の行
            c.run(raceSql(2, "202611010701"))
            // 12 桁のうち 99R まで（D-060 の暫定上限）
            c.run(bad.format("202611011099", "2026-11-01", "10", 99))
        }
    }

    @Test
    fun `オッズは正の値、確率は 0 から 1、馬番は 1 から 99`() {
        root { c ->
            val odds = "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, '$race1', 'win', '2026-11-01 06:00:00', %d, %s)"
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(odds.format(1, "0")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(odds.format(1, "-1.5")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(odds.format(0, "2.0")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(odds.format(100, "2.0")) }
            c.run(odds.format(99, "1.0"))
            val signal = "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, created_at) VALUES (1, '$race1', 'win', 20, '2026-11-01 06:00:00', %s, %s, 0, $now)"
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(signal.format("2.0", "1.5")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(signal.format("2.0", "-0.1")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) { c.run(signal.format("0", "0.5")) }
            c.run(signal.format("2.0", "1.0"))
        }
    }

    // ------------------------------------------------------------------ signals（自然キーと通知状態）
    @Test
    fun `signals の自然キーは一意で、通知状態の 4 値を保存でき、claim は原子的`() {
        root { c ->
            val insert = "INSERT INTO signals (tenant_id, race_id, market, umaban, observed_at, odds, prob, ev, notify_state, created_at) VALUES (%d, '%s', 'win', 11, '%s', 3.0, 0.4, 0.2, '%s', $now)"
            c.run(insert.format(1, race1, "2026-11-01 07:00:00.000001", "UNSENT"))
            // 同じ (tenant, race, market, umaban, observed_at) は二重に入らない
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(insert.format(1, race1, "2026-11-01 07:00:00.000001", "UNSENT")) }
            // マイクロ秒が違えば別のサイクルの行
            c.run(insert.format(1, race1, "2026-11-01 07:00:00.000002", "CLAIMED"))
            c.run(insert.format(1, race1, "2026-11-01 07:00:00.000003", "SENT"))
            c.run(insert.format(1, race1, "2026-11-01 07:00:00.000004", "SEND_UNKNOWN"))
            // 同じ自然キーでもテナントが違えば別の行
            c.run(insert.format(2, race2, "2026-11-01 07:00:00.000001", "UNSENT"))
            assertSqlError(MySqlError.DATA_TRUNCATED) { c.run(insert.format(1, race1, "2026-11-01 07:00:00.000005", "FAILED")) }
            val states = db.query("SELECT notify_state FROM signals WHERE tenant_id = 1 AND umaban = 11 ORDER BY observed_at") { it.getString(1) }
            assertEquals(listOf("UNSENT", "CLAIMED", "SENT", "SEND_UNKNOWN"), states)
            // claim: UNSENT のときだけ更新できる（2 回目は 0 行）
            val id = c.long("SELECT id FROM signals WHERE tenant_id = 1 AND umaban = 11 AND notify_state = 'UNSENT'")
            val claim = "UPDATE signals SET notify_state = 'CLAIMED' WHERE tenant_id = 1 AND id = $id AND notify_state = 'UNSENT'"
            assertEquals(1, c.update(claim))
            assertEquals(0, c.update(claim))
            // legacy_id は NULL なら何度でも、値があればテナント内で一意
            c.run("UPDATE signals SET legacy_id = 900 WHERE tenant_id = 1 AND id = $id")
            val other = c.long("SELECT id FROM signals WHERE tenant_id = 1 AND umaban = 11 AND notify_state = 'SENT'")
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run("UPDATE signals SET legacy_id = 900 WHERE tenant_id = 1 AND id = $other") }
        }
    }

    // ------------------------------------------------------------------ bot_run
    @Test
    fun `bot_run は 8 状態をすべて保存でき、同じ日の実行中は 1 つだけ`() {
        val states = listOf("STARTING", "RUNNING", "DRAINING", "SUCCEEDED", "FAILED", "ABORTED", "STOPPED_BY_MODE", "INTERRUPTED")
        val active = setOf("STARTING", "RUNNING", "DRAINING")
        root { c ->
            states.forEachIndexed { i, state ->
                val slot = if (state in active) "1" else "NULL"
                c.run(botRunSql(1, uuid(200 + i), "2026-12-%02d".format(i + 1), state, slot))
            }
            assertEquals(
                states.toSet(),
                db
                    .query("SELECT state FROM bot_run WHERE tenant_id = 1 AND run_id LIKE '00000000-0000-7000-8000-0000000002%'") {
                        it.getString(1)
                    }.toSet(),
            )
            // 同じ (tenant, run_date) で 2 つ目の実行中は DB が拒否する（二重実行防止）
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(botRunSql(1, uuid(300), "2026-12-01", "RUNNING", "1")) }
            // 日が違う・テナントが違うなら実行中を持てる
            c.run(botRunSql(1, uuid(301), "2026-12-20", "RUNNING", "1"))
            c.run(botRunSql(2, uuid(302), "2026-12-01", "RUNNING", "1"))
            // 実行中でない行（slot = NULL）は同じ日に何件あってもよい
            c.run(botRunSql(1, uuid(303), "2026-12-01", "SUCCEEDED", "NULL"))
            c.run(botRunSql(1, uuid(304), "2026-12-01", "INTERRUPTED", "NULL"))
            // リースを手放す（INTERRUPTED + slot NULL）と、同じ日に新しい実行を始められる
            c.run(
                "UPDATE bot_run SET state = 'INTERRUPTED', active_slot = NULL, finished_at = $now, version = version + 1 WHERE tenant_id = 1 AND run_id = '${uuid(
                    200,
                )}'",
            )
            c.run(botRunSql(1, uuid(305), "2026-12-01", "STARTING", "1"))
        }
    }

    @Test
    fun `bot_run の状態と active_slot の食い違い・不正な値は CHECK が拒否する`() {
        root { c ->
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "実行中なのに slot なし") { c.run(botRunSql(1, uuid(400), "2027-01-01", "RUNNING", "NULL")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "INTERRUPTED が slot を持つ") { c.run(botRunSql(1, uuid(401), "2027-01-02", "INTERRUPTED", "1")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "SUCCEEDED が slot を持つ") { c.run(botRunSql(1, uuid(402), "2027-01-03", "SUCCEEDED", "1")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "slot は 1 だけ") { c.run(botRunSql(1, uuid(403), "2027-01-04", "RUNNING", "2")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "run_id の形式") { c.run(botRunSql(1, "not-a-uuid", "2027-01-05", "SUCCEEDED", "NULL")) }
            assertSqlError(MySqlError.DATA_TRUNCATED, message = "未知の状態") { c.run(botRunSql(1, uuid(404), "2027-01-06", "PAUSED", "NULL")) }
            c.run(botRunSql(1, uuid(405), "2027-01-07", "SUCCEEDED", "NULL"))
            assertEquals("0", db.scalar("SELECT version FROM bot_run WHERE tenant_id = 1 AND run_id = '${uuid(405)}'"))
            // 楽観ロック: version が一致するときだけ更新できる
            val update = "UPDATE bot_run SET exit_reason = 'x', version = version + 1 WHERE tenant_id = 1 AND run_id = '${uuid(405)}' AND version = %d"
            assertEquals(1, c.update(update.format(0)))
            assertEquals(0, c.update(update.format(0)))
            assertEquals("1", db.scalar("SELECT version FROM bot_run WHERE tenant_id = 1 AND run_id = '${uuid(405)}'"))
        }
    }

    // ------------------------------------------------------------------ race_schedule / ml_model
    @Test
    fun `race_schedule の version と発走時刻の整合`() {
        root { c ->
            c.run(scheduleSql(1, "202611010901"))
            assertEquals("0", db.scalar("SELECT version FROM race_schedule WHERE tenant_id = 1 AND race_id = '202611010901'"))
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(scheduleSql(1, "202611010901")) }
            assertSqlError(MySqlError.CHECK_VIOLATED) {
                c.run("UPDATE race_schedule SET post_time_unknown = 1, scheduled_post_at = $now WHERE tenant_id = 1 AND race_id = '202611010901'")
            }
            c.run("UPDATE race_schedule SET post_time_unknown = 1 WHERE tenant_id = 1 AND race_id = '202611010901'")
            val update = "UPDATE race_schedule SET status = 'MONITORING', version = version + 1 WHERE tenant_id = 1 AND race_id = '202611010901' AND version = %d"
            assertEquals(1, c.update(update.format(0)))
            assertEquals(0, c.update(update.format(0)))
        }
    }

    @Test
    fun `ml_model は kind ごとに ACTIVE が 1 つだけ、署名とスキーマ版を保持する`() {
        root { c ->
            c.run(modelSql(1, "act-alpha-1", kind = "alpha", status = "ACTIVE", flag = "1", activatedAt = now))
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(modelSql(1, "act-alpha-2", kind = "alpha", status = "ACTIVE", flag = "1", activatedAt = now)) }
            c.run(modelSql(1, "act-calib-1", kind = "calibration", status = "ACTIVE", flag = "1", activatedAt = now))
            c.run(modelSql(2, "act-alpha-1", kind = "alpha", status = "ACTIVE", flag = "1", activatedAt = now))
            // 退役・候補・却下は何件でも持てる
            c.run(modelSql(1, "ret-1", kind = "alpha", status = "RETIRED"))
            c.run(modelSql(1, "ret-2", kind = "alpha", status = "RETIRED", previous = "ret-1"))
            c.run(modelSql(1, "rej-1", kind = "alpha", status = "REJECTED"))
            assertSqlError(
                MySqlError.CHECK_VIOLATED,
                message = "ACTIVE なのに flag なし",
            ) { c.run(modelSql(1, "bad-1", status = "ACTIVE", flag = "NULL", activatedAt = now)) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "ACTIVE なのに activated_at なし") {
                c.run(modelSql(1, "bad-2", kind = "legacy_compat", status = "ACTIVE", flag = "1"))
            }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "CANDIDATE が flag を持つ") { c.run(modelSql(1, "bad-3", status = "CANDIDATE", flag = "1")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "model_id に / は使えない") { c.run(modelSql(1, "a/b")) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "sha256 は小文字 16 進 64 桁") { c.run(modelSql(1, "bad-4", sha = "A".repeat(64))) }
            assertSqlError(MySqlError.DATA_TRUNCATED, message = "kind") { c.run(modelSql(1, "bad-5", kind = "xgboost")) }
            assertEquals("1", db.scalar("SELECT schema_version FROM ml_model WHERE tenant_id = 1 AND model_id = 'act-alpha-1'"))
            assertEquals(hash(3), db.scalar("SELECT signature_sha256 FROM ml_model WHERE tenant_id = 1 AND model_id = 'act-alpha-1'"))
            // 昇格: 旧 ACTIVE を退役させてから新しい候補を ACTIVE にできる
            c.run("UPDATE ml_model SET status = 'RETIRED', active_flag = NULL WHERE tenant_id = 1 AND model_id = 'act-alpha-1'")
            c.run(
                "UPDATE ml_model SET status = 'ACTIVE', active_flag = 1, activated_at = $now, previous_model_id = 'act-alpha-1' WHERE tenant_id = 1 AND model_id = 'rej-1'",
            )
        }
    }

    // ------------------------------------------------------------------ 取込の冪等性
    @Test
    fun `取込は batch_id と record_hash と nonce と dedup_hash で重複を拒否する`() {
        root { c ->
            assertSqlError(MySqlError.DUPLICATE_ENTRY, message = "同じ batch_id の再送") { c.run(batchSql(1, batch1, "a1")) }
            val raw = "INSERT INTO ingest_raw (tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at) VALUES (%d, '%s', '%s', %d, 'RACE', 'RA', 'x', $now)"
            c.run(raw.format(1, hash(500), batch1, 1))
            assertSqlError(MySqlError.DUPLICATE_ENTRY, message = "同じレコードの再送") { c.run(raw.format(1, hash(500), batch1, 2)) }
            c.run(raw.format(2, hash(500), batch2, 1)) // 別テナントなら別の行
            assertEquals("pending", db.scalar("SELECT LOWER(parse_status) FROM ingest_raw WHERE tenant_id = 1 AND record_hash = '${hash(500)}'"))
            c.run("INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (1, 'a1', 'nonce-1', DATE_ADD($now, INTERVAL 10 MINUTE))")
            assertSqlError(MySqlError.DUPLICATE_ENTRY, message = "nonce の再利用（リプレイ）") {
                c.run("INSERT INTO agent_nonce (tenant_id, agent_id, nonce, expires_at) VALUES (1, 'a1', 'nonce-1', DATE_ADD($now, INTERVAL 10 MINUTE))")
            }
            val event = "INSERT INTO race_schedule_event (tenant_id, race_id, race_date, event_type, source, observed_at, received_at, dedup_hash) VALUES (1, NULL, '2026-11-01', 'DAY_STATUS', 'SYSTEM', $now, $now, '%s')"
            c.run(event.format(hash(600)))
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(event.format(hash(600))) }
            c.run(event.format(hash(601)))
            assertSqlError(MySqlError.CHECK_VIOLATED) {
                c.run("UPDATE ingest_batch SET accepted_count = 5, duplicate_count = 5 WHERE tenant_id = 1 AND batch_id = '$batch1'")
            }
        }
    }

    @Test
    fun `登録コードと取りこぼし区間の一意性`() {
        root { c ->
            val code = "INSERT INTO agent_registration_code (tenant_id, code_hash, expires_at, created_at) VALUES (%d, '%s', $now, $now)"
            c.run(code.format(1, hash(700)))
            // コードからテナントを引くので、別テナントにも同じコードは作れない
            assertSqlError(MySqlError.DUPLICATE_ENTRY) { c.run(code.format(2, hash(700))) }
            assertSqlError(MySqlError.CHECK_VIOLATED, message = "used_at と used_by は同時に入る") {
                c.run(
                    "INSERT INTO agent_registration_code (tenant_id, code_hash, expires_at, created_at, used_at) VALUES (1, '${hash(701)}', $now, $now, $now)",
                )
            }
            val gap = "INSERT INTO ingest_gap (tenant_id, agent_id, reason, gap_from, detected_at) VALUES (1, %s, '%s', '2026-11-01 00:00:00', $now)"
            c.run(gap.format("NULL", "STOPPED"))
            assertSqlError(MySqlError.DUPLICATE_ENTRY, message = "agent_id が NULL でも同じ区間は重複しない") { c.run(gap.format("NULL", "STOPPED")) }
            c.run(gap.format("'a1'", "STOPPED"))
            c.run(gap.format("NULL", "FETCH_FAILURE"))
            assertSqlError(MySqlError.CHECK_VIOLATED) {
                c.run(
                    "INSERT INTO ingest_gap (tenant_id, reason, gap_from, gap_to, detected_at) VALUES (1, 'OTHER', '2026-11-02 00:00:00', '2026-11-01 00:00:00', $now)",
                )
            }
        }
    }

    // ------------------------------------------------------------------ 精度
    @Test
    fun `時刻はマイクロ秒まで、オッズは DOUBLE のまま往復する`() {
        val observed = LocalDateTime.of(2026, 11, 1, 1, 23, 45, 678_901_000)
        root { c ->
            c.prepareStatement("INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, ?, 'win', ?, 3, ?)").use { ps ->
                ps.setString(1, race1)
                ps.setObject(2, observed)
                ps.setDouble(3, 12.345678901234567)
                ps.executeUpdate()
            }
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT observed_at, odds, DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s.%f') FROM odds_snapshots WHERE tenant_id = 1 AND race_id = '$race1' AND umaban = 3",
                    ).use { rs ->
                        assertTrue(rs.next())
                        assertEquals(observed, rs.getObject(1, LocalDateTime::class.java))
                        assertEquals(12.345678901234567, rs.getDouble(2))
                        assertEquals("2026-11-01 01:23:45.678901", rs.getString(3))
                    }
            }
            // 7 桁目以降は DATETIME(6) が丸める（アプリは Instant をマイクロ秒に切り捨ててから保存する: STAGE2_HANDOFF §14.1）
            c.run(
                "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (1, '$race1', 'win', '2026-11-01 01:23:45.1234564', 4, 2.0)",
            )
            assertEquals(
                "2026-11-01 01:23:45.123456",
                db.scalar(
                    "SELECT DATE_FORMAT(observed_at, '%Y-%m-%d %H:%i:%s.%f') FROM odds_snapshots WHERE tenant_id = 1 AND race_id = '$race1' AND umaban = 4",
                ),
            )
        }
    }
}
