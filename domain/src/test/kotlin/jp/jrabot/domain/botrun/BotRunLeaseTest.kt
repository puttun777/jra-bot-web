package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** リース・二重起動の防止・奪取・古い実行の更新禁止・DB 障害（メモリ上の偽物。DB の UNIQUE・行ロックそのものは MySQL の統合試験の担当）。 */
class BotRunLeaseTest {
    private fun lease(
        k: Kit,
        owner: String,
        at: java.time.Instant = k.clock.now(),
        tenant: jp.jrabot.domain.tenant.TenantId = k.tenant,
    ) = LeaseRequest(tenant, RunId.of(UUID.randomUUID()), Cal.DATE, BotRunTrigger.SCHEDULER, owner, at, Duration.ofMinutes(5))

    @Test
    fun `実行中にもう 1 つ起動しても拒否される 同じ日に実行中の行は 1 つだけ`() {
        val k = Kit()
        var second: RunOutcome? = null
        k.onPause = {
            if (second == null && k.clock.now() >= k.at(8, 0)) {
                second = k.orchestrator(pacerOverride = Pacer { _, _ -> error("2 つ目は待機に入らない") }).run(k.request(owner = "owner-B"))
                assertEquals(1, k.runs.all().count { it.state.holdsActiveSlot })
            }
        }
        val first = k.run()
        assertEquals("ALL_RACES_OFF", first.reason)
        val s = second ?: error("2 つ目が走っていない")
        assertEquals(RunExit.OK, s.exitCode)
        assertEquals("ALREADY_ACTIVE", s.reason)
        assertNull(s.runId)
        assertEquals(1, k.runs.all().size) // 2 つ目は行を作らない
        assertEquals(3, k.notifier.signals.size) // 通知は 1 つの run だけ
    }

    @Test
    fun `期限が切れたリースは奪取され 前の run は ABORTED になり checkpoint を引き継ぐ`() {
        val k = Kit(start = Cal.jst(Cal.DATE, 7, 55))
        // 前の run（owner-X）が 07:46 に始まり、R1 の 5 分前の評価を発火済みにして落ちた（リースは 07:51 に切れている）
        val old = k.runs.acquire(lease(k, "owner-X", at = k.at(7, 46)))
        val oldRun = (old as jp.jrabot.domain.bot.AcquireResult.Acquired).run
        val cp = RunCheckpoint(4, k.at(7, 50), mapOf(k.raceIds[0] to RaceCheckpoint(RaceStage.MONITORING, k.at(9, 50), setOf(5.0))))
        k.runs.saveCheckpoint(k.tenant, oldRun.runId, "owner-X", oldRun.version, cp.toJson(), k.at(7, 46))
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        val aborted = k.runs.find(k.tenant, oldRun.runId)!!
        assertEquals(BotRunState.ABORTED, aborted.state)
        assertEquals("LEASE_EXPIRED_TAKEN_OVER", aborted.exitReason)
        assertTrue(k.events.any { it == "LEASE_ACQUIRED takenOver=true" })
        assertTrue(k.events.any { it.startsWith("CHECKPOINT_RESTORED from=ABORTED cycle=4") })
        // R1 は前の run が発火済み: 再通知しない（二重通知より取りこぼし）。R2・R3 だけ
        assertEquals(2, k.notifier.signals.size)
        assertTrue(k.signals.all().none { it.raceId == k.raceIds[0] })
        // 次の cycle 番号は前から続く
        assertTrue(
            RunCheckpoint
                .parse(
                    k.runs
                        .all()
                        .first { it.state == BotRunState.SUCCEEDED }
                        .checkpoint,
                )!!
                .cycle > 4,
        )
    }

    @Test
    fun `有効なリースは奪取できない`() {
        val k = Kit(start = Cal.jst(Cal.DATE, 7, 55))
        k.runs.acquire(lease(k, "owner-X", at = k.at(7, 54)))
        val out = k.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("ALREADY_ACTIVE", out.reason)
        assertEquals(1, k.runs.all().size)
    }

    @Test
    fun `奪取された古い実行は heartbeat も遷移も checkpoint も拒否される`() {
        val k = Kit(start = Cal.jst(Cal.DATE, 7, 55))
        val a = (k.runs.acquire(lease(k, "owner-A", at = k.at(7, 50))) as jp.jrabot.domain.bot.AcquireResult.Acquired).run
        k.clock.advance(Duration.ofMinutes(6)) // 07:50 + 5 分 = 07:55 で切れる。さらに過ぎた
        val b = (k.runs.acquire(lease(k, "owner-B")) as jp.jrabot.domain.bot.AcquireResult.Acquired)
        assertEquals(a.runId, b.takenOver!!.runId)
        val keeperA = LeaseKeeper(k.runs, "owner-A", k.clock, k.config.lease, a)
        assertIs<LeaseStatus.Lost>(keeperA.heartbeat())
        assertIs<LeaseStatus.Lost>(keeperA.saveCheckpoint("{}"))
        assertIs<LeaseStatus.Lost>(keeperA.transition(BotRunState.RUNNING, null, null))
        assertIs<LeaseStatus.Lost>(keeperA.transition(BotRunState.SUCCEEDED, "STALE", null))
        // 新しい実行の行は変更されていない
        val current = k.runs.find(k.tenant, b.run.runId)!!
        assertEquals(b.run, current)
        assertEquals(BotRunState.STARTING, current.state)
        // 別の所有者の heartbeat も拒否される
        assertIs<HeartbeatResult.Lost>(k.runs.heartbeat(k.tenant, b.run.runId, "owner-A", k.clock.now(), Duration.ofMinutes(5)))
    }

    @Test
    fun `実行中に自分のリースが奪われたら 以後は何も書かず 75 で止まる`() {
        val k = Kit()
        var stolen = false
        k.onPause = {
            if (!stolen && k.clock.now() >= k.at(8, 30)) {
                stolen = true
                k.clock.advance(Duration.ofMinutes(10)) // プロセスが止まっていて heartbeat できなかった
                val r = k.runs.acquire(lease(k, "owner-B"))
                assertIs<jp.jrabot.domain.bot.AcquireResult.Acquired>(r)
            }
        }
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertTrue(out.reason.startsWith("LEASE_"))
        assertNull(out.state)
        val rows = k.runs.all()
        assertEquals(2, rows.size)
        // 奪った側（owner-B）の行は STARTING のまま。古い run が書き換えていない
        assertEquals(BotRunState.STARTING, rows.first { it.leaseOwner == "owner-B" }.state)
        assertEquals(BotRunState.ABORTED, rows.first { it.leaseOwner == "owner-A" }.state)
        assertTrue(k.notifier.errors.any { it.subject.contains("LEASE_LOST") })
        assertTrue(k.notifier.signals.isEmpty())
    }

    @Test
    fun `heartbeat は間隔ごとに更新され リースが延びる`() {
        val k = Kit()
        var observed = false
        k.onPause = {
            if (!observed && k.clock.now() >= k.at(8, 30)) {
                observed = true
                val row = k.runs.findActive(k.tenant, Cal.DATE)!!
                assertTrue(row.heartbeatAt!! >= k.at(8, 29))
                assertTrue(row.leaseExpiresAt!! > k.clock.now())
                assertEquals(BotRunState.RUNNING, row.state)
            }
        }
        k.run()
        assertTrue(observed)
    }

    @Test
    fun `別テナントの実行は互いのリースに影響しない`() {
        val k = Kit()
        k.runs.acquire(lease(k, "owner-A"))
        val other = k.runs.acquire(lease(k, "owner-A2", tenant = Cal.T2))
        assertIs<jp.jrabot.domain.bot.AcquireResult.Acquired>(other)
        assertEquals(2, k.runs.all().count { it.state.holdsActiveSlot })
    }

    @Test
    fun `別テナントの依頼には他テナントのデータを使わない 予定が無ければ起動しない`() {
        val k = Kit()
        val out = k.run(k.request(tenantId = Cal.T2))
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertEquals("SCHEDULE_UNKNOWN", out.reason)
        assertTrue(k.signals.all().isEmpty() && k.notifier.sent.isEmpty() && k.runs.all().isEmpty())
    }

    @Test
    fun `リース取得前の DB 障害は 75 で 行を作らない 想定外の例外は 1`() {
        val transient = Kit()
        transient.runs.failNext = TransientTestFailure()
        val t = transient.run()
        assertEquals(RunExit.TEMPFAIL, t.exitCode)
        assertEquals("DB_UNAVAILABLE", t.reason)
        val bug = Kit()
        bug.runs.failNext = IllegalStateException("bug")
        val b = bug.run()
        assertEquals(RunExit.FAILURE, b.exitCode)
        assertEquals("RUN_FAILED_BEFORE_LEASE", b.reason)
        assertTrue(transient.runs.all().isEmpty() && bug.runs.all().isEmpty())
    }

    @Test
    fun `サイクルの一時的な DB 障害は次のサイクルで回復する`() {
        val k = Kit()
        var injected = false
        k.onPause = {
            if (!injected && k.clock.now() >= k.at(9, 49)) {
                injected = true
                k.odds.failNext = TransientTestFailure()
            }
        }
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertTrue(k.events.any { it.startsWith("CYCLE_FAILED TransientTestFailure consecutive=1") })
        assertEquals(3, k.notifier.signals.size) // 失敗したサイクルの分も次のサイクルで評価された
    }

    @Test
    fun `DB 障害が続くと上限で FAILED 75 になる リースは解放される`() {
        val k = Kit()
        k.onPause = { if (k.clock.now() >= k.at(9, 49)) k.odds.failAlways = TransientTestFailure() }
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertEquals("DB_UNAVAILABLE", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
        assertNull(k.runs.findActive(k.tenant, Cal.DATE))
        assertTrue(k.notifier.errors.any { it.subject.contains("DB_UNAVAILABLE") })
    }

    @Test
    fun `1 つのレースの失敗は他のレースを止めない`() {
        val k = Kit()
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) throw IllegalStateException("boom") }
        val out = k.run()
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertEquals(2, k.notifier.signals.size)
        assertTrue(k.notifier.errors.any { it.subject.contains("RACE_FAILED") })
        assertNotEquals(0, k.runs.all().size)
    }

    @Test
    fun `claim したまま記録できなかった古い Signal は起動時に SEND_UNKNOWN にされ 再送されない`() {
        val k = Kit()
        // 前の実行が 07:00 に claim したまま落ちた Signal
        k.clock.instant = k.at(7, 0)
        val saved =
            k.signals
                .insertIfAbsent(
                    jp.jrabot.domain.signal.Signal(
                        k.tenant,
                        null,
                        k.raceIds[0],
                        jp.jrabot.domain.odds.Market.WIN,
                        jp.jrabot.domain.race.HorseNumber
                            .of(2),
                        k.at(6, 59),
                        jp.jrabot.domain.odds.Odds
                            .ofOrNull(8.0)!!,
                        jp.jrabot.domain.value.Probability
                            .of(0.2),
                        0.6,
                        true,
                        3.0,
                        "market",
                    ),
                ).signal
        val savedId = checkNotNull(saved.id)
        assertTrue(k.signals.claim(k.tenant, savedId))
        k.clock.instant = k.at(7, 50)
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(NotificationState.SEND_UNKNOWN, k.signals.find(k.tenant, savedId)!!.state)
        assertTrue(k.notifier.errors.any { it.subject.contains("STALE_CLAIMS") })
        // 回収した Signal は通知本文に含まれない（再送しない）: R1 の通知は新しい Signal だけ
        assertTrue(k.notifier.signals.none { it.at < k.at(9, 50) })
    }
}
