package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.value.Probability
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T3.9: 発走後の結果待ち・開催日の状態遷移と履歴・G-34（LIVE が非 LIVE に塞がれたときの警告）・M2（確定したが送れなかった通知の警告）。 */
class BotRunResultWaitTest {
    private fun waitConfig(
        after: Long = 30,
        pollSeconds: Long = 300,
    ) = Kit.defaultConfig().copy(resultWait = ResultWaitConfig(Duration.ofMinutes(after), Duration.ofSeconds(pollSeconds)))

    private fun dayStatus(k: Kit) =
        k.days.rows
            .single()
            .status

    private fun settleAfter(
        k: Kit,
        state: ResultState,
        minutesAfterLastPost: Long,
    ) {
        // 最終レースは 10:45。発走 + minutes 後に全レースの結果がそろう（待機のたびに時計が進む）
        val at = Cal.jst(Cal.DATE, 10, 45).plus(Duration.ofMinutes(minutesAfterLastPost))
        k.onPause = { if (!k.clock.now().isBefore(at)) k.raceIds.forEach { k.resultStates[it] = state } }
    }

    @Test
    fun `結果がそろうまで待ち そろったら開催日を COMPLETED にして ALL_SETTLED で終わる`() {
        val k = Kit(config = waitConfig())
        settleAfter(k, ResultState.COMPLETE, 40)
        val out = k.run()
        assertEquals("ALL_SETTLED", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertEquals(0, out.exitCode)
        assertEquals(RaceDayStatus.COMPLETED, dayStatus(k))
        assertTrue(k.events.any { it.startsWith("SETTLE_WAIT_STARTED") })
        // 履歴: RUNNING → SETTLING → COMPLETED が DAY_STATUS として追記される（遷移表を通った）
        val trail = k.days.events.map { it.newValue.toString() }
        assertTrue(trail.any { "RUNNING" in it } && trail.any { "SETTLING" in it } && trail.any { "COMPLETED" in it }, trail.toString())
    }

    @Test
    fun `発走から resultAfter が過ぎるまでは結果を確認しない`() {
        val k = Kit(config = waitConfig(after = 30))
        settleAfter(k, ResultState.COMPLETE, 35)
        k.run()
        val firstPost = Cal.jst(Cal.DATE, 9, 55)
        for ((race, at) in k.availabilityCalls) {
            val post =
                k.schedules.rows
                    .first { it.raceId == race }
                    .scheduledPostAt!!
            assertTrue(!at.isBefore(post.plus(Duration.ofMinutes(30))), "$race を $at に確認した（発走 $post + 30 分より前）")
        }
        assertTrue(k.availabilityCalls.isNotEmpty() && firstPost.isBefore(k.availabilityCalls.first().second))
    }

    @Test
    fun `締切までにそろわなければ INCOMPLETE と FAILED で終わり 後から reconcile で回復できる状態になる`() {
        val k = Kit(config = waitConfig())
        val out = k.run() // 結果は最後まで届かない
        assertEquals("INCOMPLETE_AT_DEADLINE", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
        assertEquals(1, out.exitCode)
        assertEquals(RaceDayStatus.INCOMPLETE, dayStatus(k))
        assertTrue(k.events.any { it.startsWith("SETTLE_DEADLINE") })
    }

    @Test
    fun `一部のレースだけ払戻待ちなら待ち続ける RESULT_PARTIAL を checkpoint に残す`() {
        val k = Kit(config = waitConfig())
        k.onPause = {
            if (!k.clock.now().isBefore(Cal.jst(Cal.DATE, 10, 45).plus(Duration.ofMinutes(35)))) {
                k.raceIds.forEachIndexed { i, id -> k.resultStates[id] = if (i == 0) ResultState.COMPLETE else ResultState.RESULT_ONLY }
            }
        }
        val out = k.run()
        assertEquals("INCOMPLETE_AT_DEADLINE", out.reason)
        val cp =
            RunCheckpoint.parse(
                k.runs
                    .all()
                    .last()
                    .checkpoint,
            )!!
        assertEquals(RaceStage.SETTLED, cp.races.getValue(k.raceIds[0]).stage)
        assertEquals(RaceStage.RESULT_PARTIAL, cp.races.getValue(k.raceIds[1]).stage)
    }

    @Test
    fun `結果待ちの途中で止められても 再起動した run が続きから完了できる`() {
        val k = Kit(config = waitConfig())
        k.onPause = { if (!k.clock.now().isBefore(Cal.jst(Cal.DATE, 10, 45).plus(Duration.ofMinutes(31)))) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertEquals(RaceDayStatus.SETTLING, dayStatus(k))
        k.stop = false
        k.onPause = {}
        k.raceIds.forEach { k.resultStates[it] = ResultState.COMPLETE }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_SETTLED", out.reason)
        assertEquals(RaceDayStatus.COMPLETED, dayStatus(k))
    }

    @Test
    fun `LIVE 以外の run は開催日の状態を変えない`() {
        val k = Kit(config = waitConfig())
        settleAfter(k, ResultState.COMPLETE, 40)
        k.run(k.request(origin = NotificationOrigin.BACKFILL, trigger = BotRunTrigger.BACKFILL))
        assertEquals(RaceDayStatus.CONFIRMED, dayStatus(k))
        assertEquals(0, k.days.events.size)
    }

    @Test
    fun `結果待ちを有効にしない構成では 従来どおり全レース発走で終わる`() {
        val k = Kit()
        assertEquals("ALL_RACES_OFF", k.run().reason)
        assertEquals(RaceDayStatus.RUNNING, dayStatus(k), "RUNNING までは進む。SETTLING 以降は結果待ちの構成だけ")
    }

    @Test
    fun `実行中の LIVE 以外の run に LIVE が塞がれたら 黙って終わらず警告する G-34`() {
        val k = Kit(config = waitConfig())
        val backfill = RunId.of(UUID.randomUUID())
        val acquired = k.runs.acquire(LeaseRequest(k.tenant, backfill, Cal.DATE, BotRunTrigger.BACKFILL, "backfill-host", k.clock.now(), Duration.ofMinutes(5)))
        assertNotNull(acquired)
        val out = k.run()
        assertEquals("ALREADY_ACTIVE", out.reason)
        assertTrue(k.events.any { it.startsWith("LIVE_BLOCKED_BY_NON_LIVE_RUN") }, k.events.toString())
        assertTrue(k.notifier.errors.any { "LIVE" in it.subject + it.body }, "運用者に届くエラー通知がある")
    }

    @Test
    fun `LIVE どうしの二重起動は警告しない`() {
        val k = Kit(config = waitConfig())
        k.runs.acquire(LeaseRequest(k.tenant, RunId.of(UUID.randomUUID()), Cal.DATE, BotRunTrigger.SCHEDULER, "other", k.clock.now(), Duration.ofMinutes(5)))
        assertEquals("ALREADY_ACTIVE", k.run().reason)
        assertTrue(k.events.none { it.startsWith("LIVE_BLOCKED_BY_NON_LIVE_RUN") })
    }

    @Test
    fun `通知タイミングを確定したのに Signal が未送信のままなら 1 回だけ警告し 再送はしない M2`() {
        val k = Kit(config = waitConfig())
        val r1 = k.raceIds[0]
        val observed = Cal.jst(Cal.DATE, 9, 40)
        k.signals.insertIfAbsent(
            Signal(
                tenantId = k.tenant,
                id = null,
                raceId = r1,
                market = Market.WIN,
                umaban = HorseNumber.of(1),
                observedAt = observed,
                odds = Odds.of(1.2),
                prob = Probability.of(0.8),
                ev = 0.5,
                isPaper = true,
                stake = 100.0,
                probSource = "market",
            ),
        )
        val centi = TimingKey.centi(5.0)
        k.timings.claim(listOf(TimingNotice(k.tenant, r1, centi, "previous-run", 1L, observed, 1, "{}", "0".repeat(64), observed)))
        val sentBefore = k.notifier.signals.size
        k.run()
        assertEquals(1, k.events.count { it.startsWith("COMMITTED_BUT_UNSENT") })
        assertEquals(1, k.notifier.errors.count { "確定した" in it.body || "確定" in it.subject + it.body })
        // 未送信の Signal を再送していない（確定済みのタイミングは評価し直さない）
        assertTrue(
            k.signals
                .all()
                .first { it.observedAt == observed }
                .state == NotificationState.UNSENT,
        )
        assertTrue(k.notifier.signals.size >= sentBefore)
    }
}
