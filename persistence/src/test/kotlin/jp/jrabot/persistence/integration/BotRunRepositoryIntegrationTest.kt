package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.CheckpointResult
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.bot.TransitionResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bot-run の DB リース（取得・heartbeat・状態遷移・checkpoint）。並行・競合は [BotRunLeaseConcurrencyIntegrationTest]。 */
@Tag("integration")
class BotRunRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "bot", poolMax = 8)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val now = Fixtures.NOW
    private val five = Duration.ofMinutes(5)

    @BeforeEach
    fun reset() = db.resetData()

    private fun acquired(r: AcquireResult): BotRun = assertIs<AcquireResult.Acquired>(r).run

    private fun start(
        owner: String = "owner-1",
        at: Instant = now,
        date: LocalDate = Fixtures.DATE,
        runId: RunId = RunId.of(UUID.randomUUID()),
    ): BotRun = acquired(h.botRuns.acquire(Fixtures.lease(owner = owner, now = at, runDate = date, runId = runId)))

    @Test
    fun `取得すると排他を持つ STARTING の実行として読み戻せる`() {
        val request = Fixtures.lease()
        val r = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(request))
        assertNull(r.takenOver)
        val run = r.run
        assertEquals(BotRunState.STARTING, run.state)
        assertEquals("owner-1", run.leaseOwner)
        assertEquals(now.plus(five), run.leaseExpiresAt)
        assertEquals(now, run.heartbeatAt)
        assertEquals(now, run.startedAt)
        assertEquals(0, run.version)
        assertNull(run.finishedAt)
        assertEquals(run, h.botRuns.find(T1, request.runId))
        assertEquals(run, h.botRuns.findActive(T1, Fixtures.DATE))
        assertEquals(listOf(run), h.botRuns.listByDate(T1, Fixtures.DATE))
        assertEquals("1", db.scalar("SELECT active_slot FROM bot_run"))
    }

    @Test
    fun `同じ日の 2 つ目の取得は AlreadyActive で、先の実行の ID と期限を返し、行も増えない`() {
        val first = start()
        val second = h.botRuns.acquire(Fixtures.lease(owner = "owner-2", now = now.plusSeconds(10)))
        assertEquals(AcquireResult.AlreadyActive(first.runId, now.plus(five)), second)
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `日付が違う・テナントが違えば同時に取得できる`() {
        start()
        start(date = LocalDate.of(2026, 10, 25))
        acquired(h.botRuns.acquire(Fixtures.lease(T2)))
        assertEquals(3, db.count("SELECT COUNT(*) FROM bot_run"))
        assertEquals(3, db.count("SELECT COUNT(*) FROM bot_run WHERE active_slot = 1"))
    }

    @Test
    fun `期限切れの境界（直前は AlreadyActive、ちょうど・直後は奪取）`() {
        val expiry = now.plus(five)
        for (
        (label, at, takeover) in
        listOf(
            Triple("期限の 1 マイクロ秒前", expiry.minusNanos(1000), false),
            Triple("期限ちょうど", expiry, true),
            Triple("期限の 1 マイクロ秒後", expiry.plusNanos(1000), true),
        )
        ) {
            db.resetData()
            val old = start()
            val r = h.botRuns.acquire(Fixtures.lease(owner = "owner-2", now = at))
            if (takeover) {
                val won = assertIs<AcquireResult.Acquired>(r, label)
                val prev = assertNotNull(won.takenOver, label)
                assertEquals(old.runId, prev.runId, label)
                assertEquals(BotRunState.ABORTED, prev.state, label)
                assertEquals("LEASE_EXPIRED_TAKEN_OVER", prev.exitReason, label)
                assertEquals(at, prev.finishedAt, label)
                assertEquals(1, prev.version, label)
                assertEquals(won.run, h.botRuns.findActive(T1, Fixtures.DATE), label)
                assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE active_slot = 1"), label)
            } else {
                assertEquals(AcquireResult.AlreadyActive(old.runId, expiry), r, label)
                assertEquals(old, h.botRuns.findActive(T1, Fixtures.DATE), label)
            }
        }
    }

    @Test
    fun `奪取された旧実行の checkpoint は前の実行の行に残り、新しい実行が引き継げる`() {
        val old = start()
        val saved =
            assertIs<CheckpointResult.Saved>(
                h.botRuns.saveCheckpoint(T1, old.runId, "owner-1", old.version, """{"stage":"MONITORING","cycle":7}""", now.plusSeconds(30)),
            )
        val r = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "owner-2", now = now.plus(five).plusSeconds(1))))
        assertEquals(saved.run.checkpoint, r.takenOver!!.checkpoint)
        assertTrue(r.takenOver!!.checkpoint!!.contains("\"cycle\": 7"))
        assertNull(r.run.checkpoint)
        assertEquals(listOf(r.run.runId, old.runId), h.botRuns.listByDate(T1, Fixtures.DATE).map { it.runId })
    }

    @Test
    fun `heartbeat はリースを延長し、期限を短くしない`() {
        val run = start()
        val renewed = assertIs<HeartbeatResult.Renewed>(h.botRuns.heartbeat(T1, run.runId, "owner-1", now.plusSeconds(60), five)).run
        assertEquals(now.plusSeconds(60).plus(five), renewed.leaseExpiresAt)
        assertEquals(now.plusSeconds(60), renewed.heartbeatAt)
        assertEquals(1, renewed.version)
        // 短い期間での heartbeat は期限を縮めない
        val shorter = assertIs<HeartbeatResult.Renewed>(h.botRuns.heartbeat(T1, run.runId, "owner-1", now.plusSeconds(61), Duration.ofSeconds(10))).run
        assertEquals(renewed.leaseExpiresAt, shorter.leaseExpiresAt)
        assertEquals(2, shorter.version)
    }

    @Test
    fun `heartbeat の期限の境界（直前は更新、ちょうどは Expired）`() {
        val run = start()
        val expiry = now.plus(five)
        assertIs<HeartbeatResult.Renewed>(h.botRuns.heartbeat(T1, run.runId, "owner-1", expiry.minusNanos(1000), five))
        db.resetData()
        val run2 = start()
        val r = assertIs<HeartbeatResult.Expired>(h.botRuns.heartbeat(T1, run2.runId, "owner-1", expiry, five))
        assertEquals(run2, r.run)
        assertEquals(run2, h.botRuns.find(T1, run2.runId), "期限切れの heartbeat が行を変えた")
    }

    @Test
    fun `別の所有者・存在しない run・他テナントの heartbeat は拒否される`() {
        val run = start()
        assertIs<HeartbeatResult.Lost>(h.botRuns.heartbeat(T1, run.runId, "someone-else", now.plusSeconds(1), five))
        assertEquals(HeartbeatResult.NotFound, h.botRuns.heartbeat(T1, RunId.of(UUID.randomUUID()), "owner-1", now.plusSeconds(1), five))
        assertEquals(HeartbeatResult.NotFound, h.botRuns.heartbeat(T2, run.runId, "owner-1", now.plusSeconds(1), five))
        assertEquals(run, h.botRuns.find(T1, run.runId))
    }

    @Test
    fun `奪取された旧実行は heartbeat・遷移・checkpoint のどれも拒否され、新しい実行のリースに触れない`() {
        val old = start()
        val taken = assertIs<AcquireResult.Acquired>(h.botRuns.acquire(Fixtures.lease(owner = "owner-2", now = now.plus(five)))).run
        val later = now.plus(five).plusSeconds(10)
        assertIs<HeartbeatResult.Lost>(h.botRuns.heartbeat(T1, old.runId, "owner-1", later, five))
        assertIs<TransitionResult.Lost>(h.botRuns.transition(T1, old.runId, "owner-1", BotRunState.RUNNING, later))
        assertIs<TransitionResult.Lost>(h.botRuns.transition(T1, old.runId, "owner-1", BotRunState.SUCCEEDED, later))
        assertIs<CheckpointResult.Lost>(h.botRuns.saveCheckpoint(T1, old.runId, "owner-1", 1, """{"a":1}""", later))
        // 旧所有者の名前で新しい実行の行を操作しても拒否（所有者が違う）
        assertIs<HeartbeatResult.Lost>(h.botRuns.heartbeat(T1, taken.runId, "owner-1", later, five))
        assertIs<TransitionResult.Lost>(h.botRuns.transition(T1, taken.runId, "owner-1", BotRunState.ABORTED, later))
        assertEquals(taken, h.botRuns.findActive(T1, Fixtures.DATE))
        assertEquals(BotRunState.ABORTED, h.botRuns.find(T1, old.runId)!!.state)
    }

    @Test
    fun `状態遷移は表どおりで、終了の状態では排他を解放して終了時刻を記録し、新規の取得ができる`() {
        val run = start()
        val running = assertIs<TransitionResult.Done>(h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.RUNNING, now.plusSeconds(1))).run
        assertEquals(BotRunState.RUNNING, running.state)
        assertNull(running.finishedAt)
        val draining = assertIs<TransitionResult.Done>(h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.DRAINING, now.plusSeconds(2))).run
        assertEquals("1", db.scalar("SELECT active_slot FROM bot_run"))
        val done =
            assertIs<TransitionResult.Done>(
                h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.SUCCEEDED, now.plusSeconds(3), exitReason = "ALL_SETTLED"),
            ).run
        assertEquals(3, done.version)
        assertEquals(now.plusSeconds(3), done.finishedAt)
        assertEquals("ALL_SETTLED", done.exitReason)
        assertNull(db.scalar("SELECT active_slot FROM bot_run"))
        assertNull(h.botRuns.findActive(T1, Fixtures.DATE))
        // 終了後は同じ日に新しい実行を開始できる
        val next = start(owner = "owner-2", at = now.plusSeconds(10))
        assertNotEquals(run.runId, next.runId)
        assertEquals(draining.runId, run.runId)
    }

    @Test
    fun `遷移表に無い変更と逆方向は拒否し、何も変更しない`() {
        val run = start()
        assertEquals(
            TransitionResult.InvalidTransition(BotRunState.STARTING, BotRunState.SUCCEEDED),
            h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.SUCCEEDED, now),
        )
        assertEquals(
            TransitionResult.InvalidTransition(BotRunState.STARTING, BotRunState.DRAINING),
            h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.DRAINING, now),
        )
        assertEquals(
            TransitionResult.InvalidTransition(BotRunState.STARTING, BotRunState.STARTING),
            h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.STARTING, now),
        )
        h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.RUNNING, now)
        assertEquals(
            TransitionResult.InvalidTransition(BotRunState.RUNNING, BotRunState.STARTING),
            h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.STARTING, now),
        )
        assertEquals(1, h.botRuns.find(T1, run.runId)!!.version)
    }

    @Test
    fun `終了済みの実行は何をしても Lost（終端から戻れない）`() {
        val run = start()
        h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.FAILED, now, exitReason = "BOOM")
        for (to in BotRunState.entries) assertIs<TransitionResult.Lost>(h.botRuns.transition(T1, run.runId, "owner-1", to, now.plusSeconds(1)), "$to")
        assertIs<HeartbeatResult.Lost>(h.botRuns.heartbeat(T1, run.runId, "owner-1", now.plusSeconds(1), five))
        assertEquals(BotRunState.FAILED, h.botRuns.find(T1, run.runId)!!.state)
    }

    @Test
    fun `期限切れの実行は実行中の状態へ進めないが、終了の状態への遷移（後始末）はできる`() {
        val run = start()
        val expired = now.plus(five)
        assertIs<TransitionResult.Lost>(h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.RUNNING, expired))
        val done =
            assertIs<TransitionResult.Done>(
                h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.INTERRUPTED, expired, "SIGTERM", """{"stage":"OFF"}"""),
            ).run
        assertEquals(BotRunState.INTERRUPTED, done.state)
        assertTrue(done.checkpoint!!.contains("OFF"))
        assertNull(h.botRuns.findActive(T1, Fixtures.DATE))
    }

    @Test
    fun `SIGTERM 相当の遷移は checkpoint と INTERRUPTED を同時に保存する`() {
        val run = start()
        h.botRuns.transition(T1, run.runId, "owner-1", BotRunState.RUNNING, now.plusSeconds(1))
        val r =
            assertIs<TransitionResult.Done>(
                h.botRuns.transition(
                    T1,
                    run.runId,
                    "owner-1",
                    BotRunState.INTERRUPTED,
                    now.plusSeconds(2),
                    "SIGTERM",
                    """{"races":{"202610240511":"MONITORING"}}""",
                ),
            ).run
        assertEquals(BotRunState.INTERRUPTED, r.state)
        assertEquals("SIGTERM", r.exitReason)
        assertEquals(r, h.botRuns.find(T1, run.runId), "DB から読み直して状態を復元できる")
        assertTrue(r.checkpoint!!.contains("202610240511"))
    }

    @Test
    fun `checkpoint の保存は version が一致するときだけ、heartbeat との競合は Conflict で保存されない`() {
        val run = start()
        val saved = assertIs<CheckpointResult.Saved>(h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", run.version, """{"cycle":1}""", now.plusSeconds(1))).run
        assertEquals(1, saved.version)
        // heartbeat が先に入って version が進んだ後に、古い version で保存しようとする
        h.botRuns.heartbeat(T1, run.runId, "owner-1", now.plusSeconds(2), five)
        val stale =
            assertIs<CheckpointResult.Conflict>(h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", saved.version, """{"cycle":2}""", now.plusSeconds(3)))
        assertTrue(stale.run.checkpoint!!.contains("1"), "競合したのに checkpoint が書き換わった")
        // 読み直した version で再試行すると成功する
        val retry =
            assertIs<CheckpointResult.Saved>(h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", stale.run.version, """{"cycle":2}""", now.plusSeconds(4)))
        assertTrue(retry.run.checkpoint!!.contains("2"))
    }

    @Test
    fun `checkpoint の保存は期限切れ・他の所有者・存在しない run を拒否する`() {
        val run = start()
        assertIs<CheckpointResult.Expired>(h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", 0, """{"a":1}""", now.plus(five)))
        assertIs<CheckpointResult.Lost>(h.botRuns.saveCheckpoint(T1, run.runId, "other", 0, """{"a":1}""", now.plusSeconds(1)))
        assertEquals(CheckpointResult.NotFound, h.botRuns.saveCheckpoint(T1, RunId.of(UUID.randomUUID()), "owner-1", 0, """{"a":1}""", now))
        assertEquals(CheckpointResult.NotFound, h.botRuns.saveCheckpoint(T2, run.runId, "owner-1", 0, """{"a":1}""", now))
        assertNull(h.botRuns.find(T1, run.runId)!!.checkpoint)
    }

    @Test
    fun `不正な checkpoint は保存せず失敗する（無視しない）`() {
        val run = start()
        assertFailsWith<IllegalArgumentException> { h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", 0, "not json", now) }
        assertFailsWith<IllegalArgumentException> { h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", 0, "[1]", now) }
        assertFailsWith<IllegalArgumentException> { h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", 0, "{" + "x".repeat(70_000) + "}", now) }
        // 形は JSON オブジェクトに見えるが JSON として不正 → MySQL が拒否する（3140）。version も checkpoint も変わらない
        assertSqlError(3140) { h.botRuns.saveCheckpoint(T1, run.runId, "owner-1", 0, "{bad}", now) }
        assertEquals(run, h.botRuns.find(T1, run.runId))
    }

    @Test
    fun `リースの時刻はマイクロ秒まで UTC で往復する`() {
        val at = Instant.parse("2026-10-24T02:03:04.123456Z")
        val run = start(at = at)
        assertEquals(at.plus(five), run.leaseExpiresAt)
        assertEquals("2026-10-24 02:08:04.123456", db.scalar("SELECT DATE_FORMAT(lease_expires_at, '%Y-%m-%d %H:%i:%s.%f') FROM bot_run"))
        assertEquals("2026-10-24 02:03:04.123456", db.scalar("SELECT DATE_FORMAT(started_at, '%Y-%m-%d %H:%i:%s.%f') FROM bot_run"))
    }

    @Test
    fun `run_id の重複（UNIQUE 以外の制約違反）は AlreadyActive にせず失敗する`() {
        val id = RunId.of(UUID.randomUUID())
        start(runId = id)
        assertSqlError(MySqlError.DUPLICATE_ENTRY) { h.botRuns.acquire(Fixtures.lease(runId = id, runDate = LocalDate.of(2026, 10, 25), owner = "owner-2")) }
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `一覧の件数制限と順序`() {
        start(at = now)
        start(date = LocalDate.of(2026, 10, 25), at = now.plusSeconds(5))
        assertEquals(listOf(LocalDate.of(2026, 10, 25), Fixtures.DATE), h.botRuns.listRecent(T1, 10).map { it.runDate })
        assertEquals(1, h.botRuns.listRecent(T1, 1).size)
        assertFailsWith<IllegalArgumentException> { h.botRuns.listRecent(T1, 0) }
        assertFailsWith<IllegalArgumentException> { h.botRuns.listRecent(T1, 201) }
    }
}
