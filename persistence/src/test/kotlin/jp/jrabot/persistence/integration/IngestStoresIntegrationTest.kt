package jp.jrabot.persistence.integration

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ingest.AgentCredentialIssuer
import jp.jrabot.domain.ingest.AgentCredentialStore
import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.ingest.BackfillRequestResult
import jp.jrabot.domain.ingest.BackfillService
import jp.jrabot.domain.ingest.BackfillStatus
import jp.jrabot.domain.ingest.BackfillStore
import jp.jrabot.domain.ingest.IngestCursorStore
import jp.jrabot.domain.ingest.IngestGapStore
import jp.jrabot.domain.ingest.KeyEncryptionKeys
import jp.jrabot.domain.ingest.NewBackfill
import jp.jrabot.domain.ingest.NonceStore
import jp.jrabot.domain.ingest.SigningKeyCipher
import jp.jrabot.domain.ingest.UnitOfWork
import jp.jrabot.persistence.repository.AgentRegistrar
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T3.4 の永続化（V3 の表と Repository）。本物の MySQL。DB ユーザーは本番と同じ ingest / bot。 */
@Tag("integration")
class IngestStoresIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val ingest = RepositoryHarness(db, user = "ingest", poolMax = 6)
        private val bot = RepositoryHarness(db, user = "bot", poolMax = 2)

        @JvmStatic
        @AfterAll
        fun close() {
            ingest.close()
            bot.close()
        }
    }

    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private val issuer = AgentCredentialIssuer(SigningKeyCipher(KeyEncryptionKeys(mapOf("k1" to ByteArray(32) { 3 }), "k1")), ByteArray(16) { 1 })

    @BeforeEach
    fun clean() {
        for (t in listOf("ingest_fetch", "ingest_backfill", "ingest_gap", "ingest_cursor", "agent_nonce", "agent_heartbeat", "agent")) db.exec("DELETE FROM $t")
    }

    private fun register(
        id: String,
        tenant: Long,
    ) = issuer.issue(tenantOf(tenant), AgentId.of(id)).also {
        ingest.bean(AgentRegistrar::class.java).register(tenantOf(tenant), "n", it, now.plus(Duration.ofDays(90)), "test", now)
    }

    private fun tenantOf(n: Long) = if (n == 1L) T1 else T2

    @Test
    fun `agent_id は全体で一意で、認証はエージェントの行からテナントと暗号化された署名鍵を引ける`() {
        val issued = register("ag_a", 1)
        val c = assertNotNull(ingest.bean(AgentCredentialStore::class.java).findByAgentId(AgentId.of("ag_a")))
        assertEquals(T1, c.tenantId)
        assertEquals(AgentStatus.ACTIVE, c.status)
        assertEquals(now.plus(Duration.ofDays(90)), c.tokenExpiresAt)
        assertNotNull(c.signingKey).also { k -> assertEquals("k1", k.kid) }
        // トークンも署名鍵もそのままは DB に無い（暗号文とペッパー付きハッシュ）
        val rows = db.query("SELECT HEX(sign_key_enc), token_hash FROM agent WHERE agent_id = 'ag_a'") { it.getString(1) to it.getString(2) }.single()
        assertFalse(rows.first.contains(issued.token.toByteArray().joinToString("") { b -> "%02X".format(b) }))
        assertTrue(rows.second.matches(Regex("[0-9a-f]{64}")))
        assertNull(ingest.bean(AgentCredentialStore::class.java).findByAgentId(AgentId.of("ag_unknown")))
        // 別テナントで同じ agent_id は登録できない（HTTP の X-Agent-Id からテナントを一意に決められる）
        assertSqlError(MySqlError.DUPLICATE_ENTRY) { register("ag_a", 2) }
        // 鍵の無い行は署名鍵なし（認証できない = fail closed）
        db.exec(
            "INSERT INTO agent (tenant_id, agent_id, name, token_hash, token_expires_at, created_at) VALUES (1, 'ag_nokey', 'x', '${"a".repeat(
                64,
            )}', '2030-01-01', NOW(6))",
        )
        assertNull(ingest.bean(AgentCredentialStore::class.java).findByAgentId(AgentId.of("ag_nokey"))?.signingKey)
        // 署名鍵の列は kid と対（片方だけは不可）
        assertSqlError(MySqlError.CHECK_VIOLATED) { db.exec("UPDATE agent SET sign_key_kid = NULL WHERE agent_id = 'ag_a'") }
    }

    @Test
    fun `nonce は1回だけ消費でき、期限切れは掃除される`() {
        register("ag_a", 1)
        val nonces = ingest.bean(NonceStore::class.java)
        assertTrue(nonces.consume(T1, AgentId.of("ag_a"), "nonce-aaaaaaaaaaaaaaaa", now.plusSeconds(600)))
        assertFalse(nonces.consume(T1, AgentId.of("ag_a"), "nonce-aaaaaaaaaaaaaaaa", now.plusSeconds(600)))
        assertTrue(nonces.consume(T1, AgentId.of("ag_a"), "nonce-bbbbbbbbbbbbbbbb", now.minusSeconds(1)))
        assertEquals(1, nonces.purgeExpired(now, 100))
        assertEquals(1, db.count("SELECT COUNT(*) FROM agent_nonce"))
    }

    @Test
    fun `カーソルは前進だけで、後戻りせず、テナントごとに独立`() {
        val c = ingest.bean(IngestCursorStore::class.java)
        assertTrue(c.advance(T1, "RACE:2", "20261031200000", now))
        assertFalse(c.advance(T1, "RACE:2", "20261031200000", now), "同じ値は進めたことにしない")
        assertFalse(c.advance(T1, "RACE:2", "20261001000000", now), "後戻りしない")
        assertTrue(c.advance(T1, "RACE:2", "20261101000000", now))
        assertEquals("20261101000000", c.find(T1, "RACE:2"))
        assertNull(c.find(T2, "RACE:2"))
        assertTrue(c.advance(T2, "RACE:2", "20260101000000", now))
        assertEquals("20261101000000", c.find(T1, "RACE:2"))
    }

    @Test
    fun `取りこぼし区間は同じ区間を重複させず、解消は1回だけ`() {
        register("ag_a", 1)
        val gaps = ingest.bean(IngestGapStore::class.java)
        val from = Instant.parse("2026-10-01T00:00:00Z")
        assertTrue(gaps.openFetchFailure(T1, AgentId.of("ag_a"), "RACE", from, now, "x"))
        assertFalse(gaps.openFetchFailure(T1, AgentId.of("ag_a"), "RACE", from, now, "x"), "同じ区間は重複させない")
        assertEquals(1, db.count("SELECT COUNT(*) FROM ingest_gap"))
        val id = db.scalar("SELECT id FROM ingest_gap")!!.toLong()
        assertFalse(gaps.resolve(T2, id, now), "別テナントの区間は解消できない")
        assertTrue(gaps.resolve(T1, id, now))
        assertFalse(gaps.resolve(T1, id, now))
        assertNotNull(db.scalar("SELECT gap_to FROM ingest_gap WHERE id = $id"))
    }

    @Test
    fun `バックフィル要求は実行中の同じ要求を重複登録せず、完了後は出し直せる（テナントごと）`() {
        val svc = BackfillService(bot.bean(BackfillStore::class.java), bot.clock)
        val store = bot.bean(BackfillStore::class.java)
        val req = NewBackfill("RACE", 2, "20261001000000", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, "me@example.com")
        val a = assertIs<BackfillRequestResult.Created>(svc.request(T1, req))
        assertEquals(a.id, assertIs<BackfillRequestResult.AlreadyActive>(svc.request(T1, req)).id)
        assertEquals(1, db.count("SELECT COUNT(*) FROM ingest_backfill"))
        val other = assertIs<BackfillRequestResult.Created>(svc.request(T2, req), "別テナントは別の要求")
        assertTrue(other.id != a.id)
        // option が違えば別の要求。option=null も一意性に参加する（NULL を重複させない）
        assertIs<BackfillRequestResult.Created>(svc.request(T1, NewBackfill("RACE", null, "20261001000000", null, null, null, "me")))
        assertIs<BackfillRequestResult.AlreadyActive>(svc.request(T1, NewBackfill("RACE", null, "20261001000000", null, null, null, "me")))
        assertEquals(2, store.listActive(T1).size)
        // 状態遷移: 紐づけ → 失敗で戻す → 紐づけ → 完了。別の取得セッションは引き継ぎ時間内は紐づけられない
        val f1 = "11111111-2222-4333-8444-555555555555"
        assertFalse(store.attachFetch(T2, a.id, f1, now, now.minusSeconds(3600)), "別テナントの要求には触れない")
        assertTrue(store.attachFetch(T1, a.id, f1, now, now.minusSeconds(3600)))
        assertTrue(store.attachFetch(T1, a.id, f1, now.plusSeconds(1), now.minusSeconds(3600)), "同じ取得セッションの再紐づけは冪等")
        assertFalse(store.attachFetch(T1, a.id, "22222222-2222-4333-8444-555555555555", now.plusSeconds(2), now.minusSeconds(3600)))
        assertTrue(store.attachFetch(T1, a.id, "22222222-2222-4333-8444-555555555555", now.plusSeconds(7200), now.plusSeconds(3600)), "無通信が長ければ引き継げる")
        assertFalse(store.complete(T1, a.id, f1, now), "引き継がれた古い取得セッションの完了は無効")
        assertTrue(store.releaseFetch(T1, a.id, "22222222-2222-4333-8444-555555555555", "UNPARSED_RECORDS", now))
        assertEquals(BackfillStatus.REQUESTED, store.find(T1, a.id)!!.status)
        assertEquals("UNPARSED_RECORDS", store.find(T1, a.id)!!.failReason)
        assertTrue(store.attachFetch(T1, a.id, f1, now.plusSeconds(10), now.minusSeconds(3600)))
        assertTrue(store.complete(T1, a.id, f1, now.plusSeconds(20)))
        assertFalse(store.complete(T1, a.id, f1, now.plusSeconds(30)), "完了は1回だけ")
        assertFalse(store.cancel(T1, a.id, now), "完了した要求は取り消せない")
        assertIs<BackfillRequestResult.Created>(svc.request(T1, req), "完了後は同じ要求を出し直せる")
    }

    @Test
    fun `バックフィルが別テナントの取りこぼし区間・取得セッションを参照することは DB が拒否する`() {
        register("ag_a", 1)
        db.exec("INSERT INTO ingest_gap (tenant_id, agent_id, reason, gap_from, detected_at) VALUES (1, 'ag_a', 'OTHER', '2026-10-01', NOW(6))")
        val gapId = db.scalar("SELECT id FROM ingest_gap")!!
        assertSqlError(MySqlError.FK_CHILD) {
            db.exec(
                "INSERT INTO ingest_backfill (tenant_id, dataspec, fromtime, gap_id, requested_at, requested_by, updated_at) VALUES (2, 'RACE', '20261001000000', $gapId, NOW(6), 'x', NOW(6))",
            )
        }
        db.exec(
            "INSERT INTO ingest_backfill (tenant_id, dataspec, fromtime, requested_at, requested_by, updated_at) VALUES (1, 'RACE', '20261001000000', NOW(6), 'x', NOW(6))",
        )
        val bf = db.scalar("SELECT id FROM ingest_backfill")!!
        assertSqlError(MySqlError.FK_CHILD) {
            db.exec(
                "INSERT INTO ingest_fetch (tenant_id, fetch_id, agent_id, dataspec, origin, backfill_id, started_at, last_batch_at) " +
                    "VALUES (1, '33333333-2222-4333-8444-555555555555', 'ag_a', 'RACE', 'BACKFILL', 999999, NOW(6), NOW(6))",
            )
        }
        db.exec(
            "INSERT INTO agent (tenant_id, agent_id, name, token_hash, token_expires_at, created_at) VALUES (2, 'ag_b', 'x', '${"b".repeat(
                64,
            )}', '2030-01-01', NOW(6))",
        )
        assertSqlError(MySqlError.FK_CHILD) {
            db.exec(
                "INSERT INTO ingest_fetch (tenant_id, fetch_id, agent_id, dataspec, origin, backfill_id, started_at, last_batch_at) " +
                    "VALUES (2, '33333333-2222-4333-8444-555555555556', 'ag_b', 'RACE', 'BACKFILL', $bf, NOW(6), NOW(6))",
            )
        }
    }

    @Test
    fun `取得の事実（ScheduleFetchFactReader）は成功した YSCH だけから、範囲内の日付に、最新の完了時刻で返る`() {
        register("ag_a", 1)
        val reader = bot.bean(ScheduleFetchFactReader::class.java)

        fun fetch(
            id: String,
            status: String,
            dataspec: String = "YSCH",
            from: String? = "2026-11-01",
            to: String? = "2026-12-27",
            at: String = "2026-10-23 03:00:00",
            tenant: Long = 1,
        ) = db.exec(
            "INSERT INTO ingest_fetch (tenant_id, fetch_id, agent_id, dataspec, status, fail_reason, covered_from, covered_to, " +
                "started_at, last_batch_at, completed_at) " +
                "VALUES " +
                "($tenant, '$id', 'ag_a', '$dataspec', '$status', ${if (status == "FAILED") "'x'" else "NULL"}, ${from?.let {
                    "'$it'"
                } ?: "NULL"}, ${to?.let { "'$it'" } ?: "NULL"}, '$at', '$at', ${if (status == "IN_PROGRESS") "NULL" else "'$at'"})",
        )
        val d = LocalDate.of(2026, 11, 10)
        assertNull(reader.latestSuccess(T1, d), "取得していない")
        fetch("00000000-0000-4000-8000-000000000001", "IN_PROGRESS", from = null, to = null)
        assertNull(reader.latestSuccess(T1, d), "途中")
        fetch("00000000-0000-4000-8000-000000000002", "FAILED", from = null, to = null)
        assertNull(reader.latestSuccess(T1, d), "失敗")
        fetch("00000000-0000-4000-8000-000000000003", "SUCCEEDED", dataspec = "RACE")
        assertNull(reader.latestSuccess(T1, d), "YSCH 以外は事実にしない")
        fetch("00000000-0000-4000-8000-000000000004", "SUCCEEDED", from = null, to = null)
        assertNull(reader.latestSuccess(T1, d), "範囲が空の成功は事実にならない")
        fetch("00000000-0000-4000-8000-000000000005", "SUCCEEDED", at = "2026-10-20 00:00:00")
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), reader.latestSuccess(T1, d)!!.fetchedAt)
        fetch("00000000-0000-4000-8000-000000000006", "SUCCEEDED", at = "2026-10-22 00:00:00", to = "2026-11-30")
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), reader.latestSuccess(T1, d)!!.fetchedAt, "新しい取得が優先")
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), reader.latestSuccess(T1, LocalDate.of(2026, 12, 20))!!.fetchedAt, "範囲が違えば別の取得の事実")
        assertNull(reader.latestSuccess(T1, LocalDate.of(2026, 12, 28)))
        assertNull(reader.latestSuccess(T1, LocalDate.of(2026, 10, 31)))
        assertNull(reader.latestSuccess(T2, d), "別テナントの事実は見えない")
    }

    @Test
    fun `UnitOfWork： READ COMMITTED で、例外なら全部巻き戻り、デッドロックは全体を再実行して両方が成功する`() {
        val uow = ingest.bean(UnitOfWork::class.java)
        val jdbc = JdbcTemplate(ingest.dataSource)
        assertEquals("READ-COMMITTED", uow.run { jdbc.queryForObject("SELECT @@transaction_isolation", String::class.java) })
        // 巻き戻り
        assertFailsWith<IllegalStateException> {
            uow.run {
                jdbc.update("INSERT INTO ingest_cursor (tenant_id, data_type, last_file_timestamp, updated_at) VALUES (1, 'X', '20260101000000', NOW(6))")
                error("途中で失敗")
            }
        }
        assertEquals(0, db.count("SELECT COUNT(*) FROM ingest_cursor"))
        // デッドロック
        db.exec(
            "INSERT INTO ingest_cursor (tenant_id, data_type, last_file_timestamp, updated_at) VALUES (1, 'A', '20260101000000', NOW(6)), (1, 'B', '20260101000000', NOW(6))",
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val attempts = AtomicInteger()

            fun cross(
                first: String,
                second: String,
            ) = pool.submit {
                uow.run {
                    val n = attempts.incrementAndGet()
                    jdbc.update("UPDATE ingest_cursor SET last_file_timestamp = '20260102000000' WHERE tenant_id = 1 AND data_type = ?", first)
                    if (n <= 2) barrier.await(10, TimeUnit.SECONDS)
                    jdbc.update("UPDATE ingest_cursor SET last_file_timestamp = '20260102000000' WHERE tenant_id = 1 AND data_type = ?", second)
                }
            }
            val f1 = cross("A", "B")
            val f2 = cross("B", "A")
            f1.get(60, TimeUnit.SECONDS)
            f2.get(60, TimeUnit.SECONDS)
            assertTrue(attempts.get() >= 3, "デッドロックで少なくとも 1 回は全体を再実行した: ${attempts.get()}")
            assertEquals(2, db.count("SELECT COUNT(*) FROM ingest_cursor WHERE last_file_timestamp = '20260102000000'"))
        } finally {
            pool.shutdownNow()
        }
    }
}
