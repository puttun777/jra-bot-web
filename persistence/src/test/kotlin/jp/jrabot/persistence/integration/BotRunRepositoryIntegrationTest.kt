package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.StartResult
import jp.jrabot.domain.repository.UpdateResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
class BotRunRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 8)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() = db.resetData()

    private fun started(run: jp.jrabot.domain.bot.BotRun = Fixtures.run()): jp.jrabot.domain.bot.BotRun =
        assertIs<StartResult.Started>(h.botRuns.tryStart(run)).run

    @Test
    fun `開始すると排他を持つ実行として読み戻せる`() {
        val run = Fixtures.run()
        val result = assertIs<StartResult.Started>(h.botRuns.tryStart(run))
        assertEquals(run, result.run)
        assertEquals(run, h.botRuns.find(T1, run.runId))
        assertEquals(run, h.botRuns.findActive(T1, Fixtures.DATE))
        assertEquals("1", db.scalar("SELECT active_slot FROM bot_run"))
    }

    @Test
    fun `同じ日の 2 つ目の開始は AlreadyActive で、先に開始した実行の ID を返す`() {
        val first = started()
        val second = h.botRuns.tryStart(Fixtures.run())
        assertEquals(StartResult.AlreadyActive(first.runId), second)
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `日付が違う・テナントが違えば同時に開始できる`() {
        started()
        started(Fixtures.run(runDate = LocalDate.of(2026, 10, 25)))
        started(Fixtures.run(T2))
        assertEquals(3, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `主キーの重複(同じ run_id)は AlreadyActive にせず例外になる`() {
        val run = Fixtures.run()
        started(run)
        // 別の日にして排他の衝突ではなく主キー(tenant_id, run_id)の重複だけにする
        val ex = runCatching { h.botRuns.tryStart(run.copy(runDate = LocalDate.of(2026, 10, 25))) }.exceptionOrNull()
        assertNotNull(ex, "AlreadyActive を返してはいけない")
        assertEquals(MySqlError.DUPLICATE_ENTRY, ex.mysqlErrorCode())
    }

    @Test
    fun `実行中でない状態や version が 0 でない run は開始できない`() {
        assertFailsWith<IllegalArgumentException> { h.botRuns.tryStart(Fixtures.run(state = BotRunState.SUCCEEDED)) }
        assertFailsWith<IllegalArgumentException> { h.botRuns.tryStart(Fixtures.run().copy(version = 1)) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `更新は version が一致すれば成功して version が進み、古い version は Conflict`() {
        val run = started()
        val updated = assertIs<UpdateResult.Updated<*>>(h.botRuns.update(run.copy(state = BotRunState.RUNNING, checkpoint = "{\"stage\": \"A\"}"))).value
        updated as jp.jrabot.domain.bot.BotRun
        assertEquals(1, updated.version)
        assertEquals(BotRunState.RUNNING, updated.state)
        assertEquals("{\"stage\": \"A\"}", updated.checkpoint)

        // 古い version(0)での更新は競合。保存された内容は変わらない
        assertEquals(UpdateResult.Conflict, h.botRuns.update(run.copy(state = BotRunState.DRAINING)))
        assertEquals(updated, h.botRuns.find(T1, run.runId))

        val next = assertIs<UpdateResult.Updated<*>>(h.botRuns.update(updated.copy(heartbeatAt = Instant.parse("2026-10-24T00:01:00.000001Z")))).value
        assertEquals(2, (next as jp.jrabot.domain.bot.BotRun).version)
    }

    @Test
    fun `存在しない run と他テナントの run の更新は NotFound で、元の実行は変わらない`() {
        val run = started()
        assertEquals(UpdateResult.NotFound, h.botRuns.update(Fixtures.run().copy(tenantId = T1)))
        assertEquals(UpdateResult.NotFound, h.botRuns.update(run.copy(tenantId = T2, state = BotRunState.FAILED)))
        assertEquals(run, h.botRuns.find(T1, run.runId))
        assertNull(h.botRuns.find(T2, run.runId))
    }

    @Test
    fun `INTERRUPTED など実行中でない状態への更新で排他が解放され、同じ日に新しく開始できる`() {
        val run = started()
        val interrupted =
            assertIs<UpdateResult.Updated<*>>(
                h.botRuns.update(run.copy(state = BotRunState.INTERRUPTED, leaseOwner = null, leaseExpiresAt = null, exitReason = "SIGTERM")),
            ).value as jp.jrabot.domain.bot.BotRun
        assertEquals(BotRunState.INTERRUPTED, interrupted.state)
        assertNull(db.scalar("SELECT active_slot FROM bot_run"))
        assertNull(h.botRuns.findActive(T1, Fixtures.DATE))

        val next = started()
        assertEquals(next, h.botRuns.findActive(T1, Fixtures.DATE))
        assertEquals(2, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `終了では finishedAt を持てて、他の実行中の run がある日に実行中へ戻す更新は Conflict`() {
        val first = started()
        val done =
            assertIs<UpdateResult.Updated<*>>(
                h.botRuns.update(first.copy(state = BotRunState.SUCCEEDED, finishedAt = Instant.parse("2026-10-24T08:00:00.000001Z"))),
            ).value as jp.jrabot.domain.bot.BotRun
        assertEquals(Instant.parse("2026-10-24T08:00:00.000001Z"), done.finishedAt)

        val second = started()
        // 終わった first を RUNNING に戻そうとすると、second の排他と衝突する
        assertEquals(UpdateResult.Conflict, h.botRuns.update(done.copy(state = BotRunState.RUNNING, finishedAt = null)))
        assertEquals(second, h.botRuns.findActive(T1, Fixtures.DATE))
        assertEquals(BotRunState.SUCCEEDED, h.botRuns.find(T1, first.runId)?.state)
    }

    @Test
    fun `checkpoint は JSON 列。不正な JSON は拒否される`() {
        val run = started(Fixtures.run().copy(checkpoint = "{\"races\": {\"202610240511\": \"MONITORING\"}}"))
        assertEquals("{\"races\": {\"202610240511\": \"MONITORING\"}}", h.botRuns.find(T1, run.runId)?.checkpoint)
        assertSqlError(3140) { h.botRuns.update(run.copy(checkpoint = "not json")) }
        assertEquals(run, h.botRuns.find(T1, run.runId))
    }

    @Test
    fun `listRecent は開始の新しい順で件数を制限し、テナントで絞る`() {
        val dates = (20..24).map { LocalDate.of(2026, 10, it) }
        dates.forEach { d -> started(Fixtures.run(runDate = d, startedAt = d.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))) }
        started(Fixtures.run(T2))
        assertEquals(dates.reversed().take(3), h.botRuns.listRecent(T1, 3).map { it.runDate })
        assertEquals(1, h.botRuns.listRecent(T2, 10).size)
        assertFailsWith<IllegalArgumentException> { h.botRuns.listRecent(T1, 0) }
        assertFailsWith<IllegalArgumentException> { h.botRuns.listRecent(T1, 201) }
    }

    @Test
    fun `実際に競合する複数の接続で同時に開始しても、排他を取れるのは 1 つだけ`() {
        repeat(15) { round ->
            val date = LocalDate.of(2027, 1, 1).plusDays(round.toLong())
            val results = runConcurrently(if (round % 2 == 0) 2 else 6) { h.botRuns.tryStart(Fixtures.run(runDate = date)) }.map { it.getOrThrow() }
            val winners = results.filterIsInstance<StartResult.Started>()
            val losers = results.filterIsInstance<StartResult.AlreadyActive>()
            assertEquals(1, winners.size, "round=$round $results")
            assertEquals(results.size - 1, losers.size, "round=$round $results")
            assertTrue(losers.all { it.activeRunId == winners.single().run.runId }, "round=$round $results")
            assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE run_date = '$date'"))
        }
    }

    @Test
    fun `同じ version から同時に更新すると、成功するのは 1 つだけ（もう 1 つは Conflict）`() {
        repeat(10) {
            db.resetData()
            val run = started()
            val results = runConcurrently(2) { i -> h.botRuns.update(run.copy(exitReason = "writer-$i", state = BotRunState.RUNNING)) }.map { it.getOrThrow() }
            assertEquals(1, results.count { it is UpdateResult.Updated<*> }, "$results")
            assertEquals(1, results.count { it == UpdateResult.Conflict }, "$results")
            assertEquals(1, h.botRuns.find(T1, run.runId)?.version)
        }
    }
}
