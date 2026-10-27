package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.notify.DeliveryResult
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SIGTERM・再起動・checkpoint の復元・強制終了・運用モード（STOPPED / UNKNOWN）・通知の出どころ。 */
class BotRunRestartAndModeTest {
    // ---- SIGTERM ----

    @Test
    fun `SIGTERM で新しいサイクルを始めず checkpoint を保存して INTERRUPTED 75 でリースを解放する`() {
        val k = Kit()
        k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.stop = true } // R1 の通知（09:50）の後
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode) // NORMAL のままの cancel は 75
        assertEquals("SIGTERM", out.reason)
        assertEquals(BotRunState.INTERRUPTED, out.state)
        assertNull(k.runs.findActive(k.tenant, Cal.DATE))
        val row = k.runs.all().single()
        assertEquals(BotRunState.INTERRUPTED, row.state)
        val cp = RunCheckpoint.parse(row.checkpoint)!!
        assertEquals(setOf(5.0), cp.races.getValue(k.raceIds[0]).firedMinutes)
        assertEquals(1, k.notifier.signals.size) // 停止要求後は何も送っていない
    }

    @Test
    fun `SIGTERM が確定した非 NORMAL のモードの最中なら 0 で終わる`() {
        val k = Kit()
        k.onPause = {
            if (k.clock.now() >= k.at(9, 0)) {
                k.mode.mode = OpsMode.STOPPED
                k.stop = true
            }
        }
        val out = k.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals(BotRunState.INTERRUPTED, out.state)
    }

    @Test
    fun `モードが UNKNOWN のまま SIGTERM になったら安全側で 75`() {
        val k = Kit()
        k.onPause = {
            if (k.clock.now() >= k.at(9, 0)) {
                k.mode.mode = OpsMode.UNKNOWN
                k.stop = true
            }
        }
        assertEquals(RunExit.TEMPFAIL, k.run().exitCode)
    }

    @Test
    fun `判定の最中に SIGTERM が来ても通知は始めない claim せず 次のサイクルも始めない`() {
        val k = Kit()
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) k.stop = true }
        val out = k.run()
        assertEquals("SIGTERM", out.reason)
        assertTrue(k.notifier.signals.isEmpty())
        // 記録は済んでいるが通知はされていない（claim もしていない）
        assertTrue(k.signals.all().isNotEmpty() && k.signals.all().all { it.state == NotificationState.UNSENT })
        assertEquals(1, k.evaluations.rows.size) // 次のレースの処理に進まない
    }

    // ---- 再起動・強制終了 ----

    @Test
    fun `INTERRUPTED の後の再起動は checkpoint を引き継ぎ 通知済みのレースを再通知しない`() {
        val k = Kit()
        k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        k.stop = false
        k.onPause = {}
        val second = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", second.reason)
        assertEquals(3, k.notifier.signals.size) // R1（1 回目）+ R2・R3（2 回目）。R1 の再通知なし
        assertEquals(1, k.signals.all().count { it.raceId == k.raceIds[0] && it.market == jp.jrabot.domain.odds.Market.WIN && it.umaban.value == 2 })
        assertTrue(k.events.any { it.startsWith("CHECKPOINT_RESTORED from=INTERRUPTED") })
        assertEquals(
            listOf(BotRunState.SUCCEEDED, BotRunState.INTERRUPTED),
            k.runs
                .all()
                .map { it.state }
                .sortedBy { it.name }
                .reversed(),
        )
    }

    @Test
    fun `強制終了 その後リースが切れた run は奪取され 重複通知なしで続きから動く`() {
        val k = Kit()
        // R1 を通知した直後にプロセスが突然死する（例外ではなく Error。状態は RUNNING のまま・リースが残る）
        val killer =
            Pacer { d, _ ->
                k.clock.advance(d)
                k.feedOdds()
                if (k.clock.now() >= k.at(9, 52)) throw AssertionError("kill -9 を模擬")
            }
        try {
            k.orchestrator(pacerOverride = killer).run(k.request())
        } catch (_: AssertionError) {
            // プロセスは死んだ。後始末は何も書かれない
        }
        assertEquals(BotRunState.RUNNING, k.runs.findActive(k.tenant, Cal.DATE)!!.state)
        assertEquals(1, k.notifier.signals.size)
        // 有効なリースの間は新しい run が始まらない
        assertEquals("ALREADY_ACTIVE", k.run(k.request(owner = "owner-B")).reason)
        // リース（5 分）が切れた後は奪取できる
        k.clock.advance(Duration.ofMinutes(6))
        val out = k.run(k.request(owner = "owner-C"))
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertEquals(
            BotRunState.ABORTED,
            k.runs
                .all()
                .first { it.leaseOwner == "owner-A" }
                .state,
        )
        assertNull(k.runs.findActive(k.tenant, Cal.DATE))
    }

    @Test
    fun `完了後の再実行は何も重複させない 評価も通知も記録も増えない`() {
        val k = Kit()
        k.run()
        val evals = k.evaluations.rows.size
        val signals = k.signals.all().size
        val sent = k.notifier.sent.size
        k.autoOdds = false
        val again = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", again.reason)
        assertEquals(evals, k.evaluations.rows.size)
        assertEquals(signals, k.signals.all().size)
        assertEquals(sent, k.notifier.sent.size)
    }

    @Test
    fun `同じオッズの観測を 2 回評価しても行は増えず 通知は 1 回だけ`() {
        val k = Kit()
        k.autoOdds = false
        val judge = StubJudge()
        val processor =
            RaceCycleProcessor(
                k.config,
                k.tenant,
                NotificationOrigin.LIVE,
                k.odds,
                judge,
                k.evaluations,
                k.signals,
                jp.jrabot.domain.botrun.testing
                    .InMemoryEntries(),
                k.featureSource,
                jp.jrabot.domain.botrun.testing
                    .InMemoryRaces(),
                k.dispatcher,
            )
        val race = k.raceIds[0]
        k.clock.instant = k.at(9, 51)
        k.odds.add(Kit.snapshot(race, k.at(9, 50, 30)))
        val a = processor.process(race, k.at(9, 55), RaceCheckpoint(RaceStage.MONITORING), k.clock.now()) { true }
        val b = processor.process(race, k.at(9, 55), RaceCheckpoint(RaceStage.MONITORING), k.clock.now()) { true } // checkpoint が保存されていなかった再実行
        assertTrue(a is RaceStep.Evaluated && b is RaceStep.Evaluated)
        assertEquals(1, k.evaluations.rows.size)
        assertEquals(2, k.signals.all().size)
        assertEquals(1, k.notifier.signals.size)
        assertNull(b.dispatch) // 2 回目は UNSENT が無いので送るものが無い
    }

    @Test
    fun `checkpoint を保存できなければ通知しない 発火済みの記録が先`() {
        val k = Kit()
        k.autoOdds = false
        val processor =
            RaceCycleProcessor(
                k.config,
                k.tenant,
                NotificationOrigin.LIVE,
                k.odds,
                StubJudge(),
                k.evaluations,
                k.signals,
                jp.jrabot.domain.botrun.testing
                    .InMemoryEntries(),
                k.featureSource,
                jp.jrabot.domain.botrun.testing
                    .InMemoryRaces(),
                k.dispatcher,
            )
        k.clock.instant = k.at(9, 51)
        k.odds.add(Kit.snapshot(k.raceIds[0], k.at(9, 50, 30)))
        val step = processor.process(k.raceIds[0], k.at(9, 55), RaceCheckpoint(RaceStage.MONITORING), k.clock.now()) { false }
        assertTrue(step is RaceStep.NotCommitted)
        assertTrue(k.notifier.sent.isEmpty())
    }

    // ---- 運用モード ----

    @Test
    fun `開始前に STARTING STOPPING STOPPED なら 0 で何もしない UNKNOWN は 75`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val k = Kit().also { it.mode.mode = m }
            val out = k.run()
            assertEquals(RunExit.OK, out.exitCode)
            assertEquals("MODE_${m.name}", out.reason)
            assertTrue(k.runs.all().isEmpty() && k.notifier.sent.isEmpty())
        }
        val u = Kit().also { it.mode.mode = OpsMode.UNKNOWN }
        val out = u.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertEquals("MODE_UNKNOWN", out.reason)
        assertTrue(u.runs.all().isEmpty())
    }

    @Test
    fun `実行中に STOPPED になったら DRAINING して STOPPED_BY_MODE 0 で終わる 以後の通知はしない`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val k = Kit()
            k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.mode.mode = m }
            val out = k.run()
            assertEquals(RunExit.OK, out.exitCode)
            assertEquals("STOPPED_BY_MODE", out.reason)
            assertEquals(BotRunState.STOPPED_BY_MODE, out.state)
            assertEquals(1, k.notifier.signals.size)
            assertNull(k.runs.findActive(k.tenant, Cal.DATE))
            assertNotNull(
                RunCheckpoint.parse(
                    k.runs
                        .all()
                        .single()
                        .checkpoint,
                ),
            )
        }
    }

    @Test
    fun `モードが UNKNOWN になっても 連続 3 回までは新しいサイクルを始めないだけで続ける`() {
        val k = Kit()
        val seq = ArrayDeque(listOf(OpsMode.UNKNOWN, OpsMode.UNKNOWN))
        k.onPause = {
            if (k.clock.now() >= k.at(9, 30)) k.mode.mode = seq.removeFirstOrNull() ?: OpsMode.NORMAL
        }
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(2, k.events.count { it.startsWith("MODE_UNKNOWN_SKIP") })
        assertEquals(3, k.notifier.signals.size)
    }

    @Test
    fun `UNKNOWN が 3 回連続したら STOPPED_BY_MODE の 75 で終わり NORMAL とみなさない`() {
        val k = Kit()
        k.onPause = { if (k.clock.now() >= k.at(9, 30)) k.mode.mode = OpsMode.UNKNOWN }
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertEquals("MODE_UNKNOWN", out.reason)
        assertEquals(BotRunState.STOPPED_BY_MODE, out.state)
        assertTrue(k.notifier.errors.any { it.subject.contains("MODE_UNKNOWN") })
        assertTrue(k.notifier.signals.isEmpty()) // UNKNOWN の間に通知していない
    }

    @Test
    fun `ModeGate は Drain の後に NORMAL へ戻っても再開しない`() {
        val m =
            jp.jrabot.domain.calendar
                .FixedMode(OpsMode.STOPPED)
        val gate = ModeGate(m, 3)
        assertEquals(ModeDecision.Drain("STOPPED_BY_MODE", 0), gate.onCycleStart())
        m.mode = OpsMode.NORMAL
        assertEquals(ModeDecision.Drain("STOPPED_BY_MODE", 0), gate.onCycleStart())
        val g2 =
            ModeGate(
                jp.jrabot.domain.calendar
                    .FixedMode(OpsMode.UNKNOWN),
                3,
            )
        assertEquals(ModeDecision.Skip(1), g2.onCycleStart())
        assertEquals(ModeDecision.Skip(2), g2.onCycleStart())
        assertEquals(ModeDecision.Drain("MODE_UNKNOWN", 75), g2.onCycleStart())
    }

    // ---- 通知の出どころ・配送結果 ----

    @Test
    fun `BACKFILL と判定不能では 評価と記録はするが通知しない`() {
        for (origin in listOf(NotificationOrigin.BACKFILL, NotificationOrigin.UNDETERMINED)) {
            val k = Kit()
            val out = k.run(k.request(origin = origin, trigger = BotRunTrigger.MANUAL))
            assertEquals("ALL_RACES_OFF", out.reason)
            assertEquals(3, k.evaluations.rows.size)
            assertTrue(k.signals.all().isNotEmpty() && k.signals.all().all { it.state == NotificationState.UNSENT })
            assertTrue(k.notifier.signals.isEmpty())
        }
    }

    @Test
    fun `配送が DELIVERED なら SENT UNKNOWN と NOT_SENT は SEND_UNKNOWN で エラー通知する 再送はしない`() {
        val k = Kit()
        k.notifier.results.addAll(listOf(DeliveryResult.UNKNOWN, DeliveryResult.NOT_SENT, DeliveryResult.DELIVERED))
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        val byRace = k.signals.all().groupBy { it.raceId }
        assertTrue(byRace.getValue(k.raceIds[0]).all { it.state == NotificationState.SEND_UNKNOWN })
        assertTrue(byRace.getValue(k.raceIds[1]).all { it.state == NotificationState.SEND_UNKNOWN })
        assertTrue(byRace.getValue(k.raceIds[2]).all { it.state == NotificationState.SENT })
        assertEquals(3, k.notifier.signals.size) // 送信は 1 レース 1 回。UNKNOWN の再送なし
        assertTrue(k.notifier.errors.any { it.subject.contains("NOTIFY_UNKNOWN") })
    }

    @Test
    fun `SEND_UNKNOWN は 再起動しても自動で再送されない`() {
        val k = Kit()
        k.notifier.results.add(DeliveryResult.UNKNOWN)
        k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.stop = true }
        k.run()
        k.stop = false
        k.onPause = {}
        k.run(k.request(owner = "owner-B"))
        val r1 = k.signals.all().filter { it.raceId == k.raceIds[0] }
        assertTrue(r1.all { it.state == NotificationState.SEND_UNKNOWN })
        assertEquals(3, k.notifier.signals.size) // R1 は 1 回だけ（再送なし）+ R2・R3
    }

    @Test
    fun `通知の宛先が未設定なら claim せず 通知もしないが 処理は続ける`() {
        val k = Kit()
        k.notifier.configured = false
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(k.signals.all().all { it.state == NotificationState.UNSENT })
        assertEquals(3, k.evaluations.rows.size)
    }

    @Test
    fun `リースの取得は別の日付を区別する`() {
        val k = Kit()
        val a =
            k.runs.acquire(
                LeaseRequest(k.tenant, RunId.of(UUID.randomUUID()), Cal.DATE, BotRunTrigger.SCHEDULER, "a", k.clock.now(), Duration.ofMinutes(5)),
            )
        val b =
            k.runs.acquire(
                LeaseRequest(k.tenant, RunId.of(UUID.randomUUID()), Cal.DATE.plusDays(1), BotRunTrigger.SCHEDULER, "b", k.clock.now(), Duration.ofMinutes(5)),
            )
        assertTrue(a is AcquireResult.Acquired && b is AcquireResult.Acquired)
    }
}
