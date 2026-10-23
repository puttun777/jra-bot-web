package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.bot.TransitionResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 多重起動防止の並行テスト（D-026）。最終的な排他は DB（UNIQUE・行ロック・版）で、アプリ内の mutex は使っていない。
 * 各ラウンドの後に「実行中（active_slot = 1）の行は 1 つだけ」の不変条件を DB に直接確かめる。
 */
@Tag("integration")
class BotRunLeaseConcurrencyIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "bot", poolMax = 16)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val now = Fixtures.NOW
    private val five = Duration.ofMinutes(5)

    @BeforeEach
    fun reset() = db.resetData()

    private fun activeRows(date: LocalDate = Fixtures.DATE) =
        db.count("SELECT COUNT(*) FROM bot_run WHERE tenant_id = 1 AND run_date = '$date' AND active_slot = 1")

    @Test
    fun `2 本が同時に取得しても勝者は 1 つで、敗者は勝者の ID の AlreadyActive`() {
        repeat(10) { round ->
            db.resetData()
            val results = runConcurrently(2) { i -> h.botRuns.acquire(Fixtures.lease(owner = "o-$i", now = now)) }.map { it.getOrThrow() }
            val winners = results.filterIsInstance<AcquireResult.Acquired>()
            assertEquals(1, winners.size, "round $round: $results")
            val losers = results.filterIsInstance<AcquireResult.AlreadyActive>()
            assertEquals(1, losers.size, "round $round: $results")
            assertEquals(winners.single().run.runId, losers.single().activeRunId)
            assertEquals(1, activeRows())
            assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
        }
    }

    @Test
    fun `8 本が同時に取得しても勝者は 1 つ（同時 5 本以上）`() {
        repeat(10) { round ->
            db.resetData()
            val results = runConcurrently(8) { i -> h.botRuns.acquire(Fixtures.lease(owner = "o-$i", now = now)) }.map { it.getOrThrow() }
            val winners = results.filterIsInstance<AcquireResult.Acquired>()
            assertEquals(1, winners.size, "round $round: $results")
            assertEquals(7, results.count { it is AcquireResult.AlreadyActive && it.activeRunId == winners.single().run.runId }, "round $round: $results")
            assertEquals(1, activeRows())
        }
    }

    @Test
    fun `期限切れのリースに 8 本が同時に奪取しても勝者は 1 つで、旧実行は ABORTED になる`() {
        repeat(10) { round ->
            db.resetData()
            val old = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "old", now = now))).run
            val at = now.plus(five) // 期限ちょうど
            val results = runConcurrently(8) { i -> h.botRuns.acquire(Fixtures.lease(owner = "o-$i", now = at)) }.map { it.getOrThrow() }
            val winners = results.filterIsInstance<AcquireResult.Acquired>()
            assertEquals(1, winners.size, "round $round: $results")
            assertEquals(old.runId, winners.single().takenOver!!.runId)
            assertEquals(7, results.count { it is AcquireResult.AlreadyActive && it.activeRunId == winners.single().run.runId }, "round $round: $results")
            assertEquals(1, activeRows())
            assertEquals(2, db.count("SELECT COUNT(*) FROM bot_run"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE state = 'ABORTED'"))
        }
    }

    @Test
    fun `異なるテナント・日付の取得は互いに妨げない（並行）`() {
        val results =
            runConcurrently(8) { i ->
                val tenant = if (i % 2 == 0) T1 else T2
                val date = LocalDate.of(2026, 10, 24).plusDays((i / 2).toLong())
                h.botRuns.acquire(Fixtures.lease(tenant, date, owner = "o-$i"))
            }.map { it.getOrThrow() }
        assertTrue(results.all { it is AcquireResult.Acquired }, "$results")
        assertEquals(8, db.count("SELECT COUNT(*) FROM bot_run WHERE active_slot = 1"))
    }

    @Test
    fun `heartbeat と奪取が同時でも、勝つのはどちらか一方だけ（二重の所有者にならない）`() {
        repeat(40) { round ->
            db.resetData()
            val old = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "old", now = now))).run
            val expiry = now.plus(five)
            val results =
                runConcurrently(2) { i ->
                    if (i == 0) {
                        // 旧所有者の heartbeat（期限の直前の時刻）
                        h.botRuns.heartbeat(T1, old.runId, "old", expiry.minusNanos(1000), five)
                    } else {
                        // 新しい起動（期限ちょうどの時刻）
                        h.botRuns.acquire(Fixtures.lease(owner = "new", now = expiry))
                    }
                }.map { it.getOrThrow() }
            val hb = results[0]
            val acq = results[1]
            val renewed = hb is HeartbeatResult.Renewed
            val acquired = acq is AcquireResult.Acquired
            assertTrue(renewed != acquired, "round $round: heartbeat=$hb acquire=$acq")
            assertEquals(1, activeRows(), "round $round")
            if (renewed) {
                assertIs<AcquireResult.AlreadyActive>(acq, "round $round")
                assertEquals(old.runId, h.botRuns.findActive(T1, Fixtures.DATE)!!.runId)
            } else {
                assertIs<HeartbeatResult.Lost>(hb, "round $round")
                assertEquals(BotRunState.ABORTED, h.botRuns.find(T1, old.runId)!!.state)
            }
        }
    }

    @Test
    fun `終了と新規取得が同時でも、実行中の行は常に 1 つ以下`() {
        repeat(40) { round ->
            db.resetData()
            val old = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "old", now = now))).run
            h.botRuns.transition(T1, old.runId, "old", BotRunState.RUNNING, now.plusSeconds(1))
            val results =
                runConcurrently(2) { i ->
                    if (i == 0) {
                        h.botRuns.transition(T1, old.runId, "old", BotRunState.FAILED, now.plusSeconds(2), "END")
                    } else {
                        h.botRuns.acquire(Fixtures.lease(owner = "new", now = now.plusSeconds(2)))
                    }
                }.map { it.getOrThrow() }
            assertIs<TransitionResult.Done>(results[0], "round $round")
            // 先に終了が入れば取得は成功し、先に取得が来れば AlreadyActive（その場合は終了後に再度取得すれば成功する）
            val acq = results[1]
            assertTrue(acq is AcquireResult.Acquired || acq is AcquireResult.AlreadyActive, "round $round: $acq")
            assertTrue(activeRows() <= 1, "round $round")
            if (acq is AcquireResult.AlreadyActive) {
                assertEquals(0, activeRows())
                assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "new", now = now.plusSeconds(3))))
            }
            assertEquals(1, activeRows())
        }
    }

    @Test
    fun `同じ実行への heartbeat と状態遷移と checkpoint を同時に行っても、行は壊れず版は単調に進む`() {
        val run = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "o", now = now))).run
        h.botRuns.transition(T1, run.runId, "o", BotRunState.RUNNING, now.plusSeconds(1))
        val results =
            runConcurrently(8) { i ->
                when (i % 3) {
                    0 -> h.botRuns.heartbeat(T1, run.runId, "o", now.plusSeconds(2L + i), five)
                    1 -> h.botRuns.saveCheckpoint(T1, run.runId, "o", 1, """{"w":$i}""", now.plusSeconds(2L + i))
                    else -> h.botRuns.heartbeat(T1, run.runId, "o", now.plusSeconds(2L + i), five)
                }
            }.map { it.getOrThrow() }
        assertTrue(results.none { it is HeartbeatResult.Lost || it is HeartbeatResult.Expired }, "$results")
        val row = h.botRuns.find(T1, run.runId)!!
        assertTrue(row.version >= 2, "${row.version}")
        assertEquals(BotRunState.RUNNING, row.state)
        assertEquals(1, activeRows())
    }

    @Test
    fun `DB に接続できないときは例外になり、取得できたことにしない（復旧後は取得できる）`() {
        val (host, port) = MySqlSupport.hostAndPort()
        TcpProxy(host, port).start().use { proxy ->
            RepositoryHarness(db, user = "bot", poolMax = 2, url = "jdbc:mysql://${proxy.jdbcHost}:${proxy.port}/${db.name}").use { viaProxy ->
                assertIs<AcquireResult.Acquired>(viaProxy.botRuns.acquire(Fixtures.lease(owner = "o1", now = now)))
                proxy.stop()
                assertFailsWith<RuntimeException> { viaProxy.botRuns.acquire(Fixtures.lease(owner = "o2", now = now)) }
                assertFailsWith<RuntimeException> { viaProxy.botRuns.heartbeat(T1, RunId.of(UUID.randomUUID()), "o1", now, five) }
                assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"), "接続できないのに行が増えた")
                proxy.start()
                assertIs<AcquireResult.AlreadyActive>(viaProxy.botRuns.acquire(Fixtures.lease(owner = "o2", now = now)))
            }
        }
    }
}
