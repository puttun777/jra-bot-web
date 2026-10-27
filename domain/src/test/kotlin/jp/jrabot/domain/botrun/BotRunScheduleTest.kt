package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.decision.PriorityMode
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** サンプル設定（30 分前と 5 分前の評価・heartbeat 60 秒 / リース 300 秒 / モード確認 15 秒）での時間まわりの試験。手で進める時計だけを使い、実時間は待たない。 */
class BotRunScheduleTest {
    private fun cfg(
        tighten: TightenMode = TightenMode.OFF,
        decision: jp.jrabot.domain.decision.WinDecisionConfig = Kit.defaultConfig().decision,
        schedule: HitRateSchedule? = null,
    ) = Kit.defaultConfig().copy(snapshot = SnapshotPolicy(listOf(30.0, 5.0), tighten), decision = decision, hitRateSchedule = schedule)

    @Test
    fun `30 分前と 5 分前に それぞれ 1 回ずつ評価して通知する`() {
        val k = Kit(config = cfg())
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        // 3 レース × 2 タイミング = 6 通知。R1（発走 09:55）は 09:25 と 09:50 の直後のサイクル（モード確認の間隔 15 秒以内）
        assertEquals(6, k.notifier.signals.size)
        // 発走 09:55 の R1 は 09:25 と 09:50、R2（10:20）の 30 分前は 09:50（R1 の 5 分前と同時刻でも別の通知）
        val early =
            k.notifier.signals
                .filter { it.at < k.at(9, 55) }
                .map { it.at }
        assertEquals(3, early.size)
        assertTrue(early[0] in k.at(9, 25)..k.at(9, 25, 15), "R1 の 30 分前: ${early[0]}")
        assertTrue(early.drop(1).all { it in k.at(9, 50)..k.at(9, 50, 15) }, "R1 の 5 分前と R2 の 30 分前: $early")
        assertEquals(6, k.evaluations.rows.size)
        assertTrue(k.signals.all().all { it.state == NotificationState.SENT })
    }

    @Test
    fun `同じタイミングで 2 回送らない 再起動をまたいでも発火済みは再通知しない`() {
        val k = Kit(config = cfg())
        k.onPause = { if (k.clock.now() >= k.at(9, 30)) k.stop = true } // 30 分前の通知の後に SIGTERM
        assertEquals("SIGTERM", k.run().reason)
        val first = k.notifier.signals.size
        assertEquals(1, first) // 発走の早い R1 だけが 30 分前に到達済み（R2・R3 は 09:50・10:15）
        k.stop = false
        k.onPause = {}
        assertEquals("ALL_RACES_OFF", k.run(k.request(owner = "owner-B")).reason)
        // 合計: R1 は 30 分前（1 回目の run）+ 5 分前（2 回目）。R2・R3 は 2 回ずつ。R1 の 30 分前は再通知されない
        assertEquals(6, k.notifier.signals.size)
        val r1Signals =
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .map { it.observedAt }
        assertEquals(2, r1Signals.toSet().size) // 30 分前と 5 分前の 2 つの観測だけ。再起動で 30 分前が増えない（各観測に購入候補 2 件）
        assertEquals(4, r1Signals.size)
        assertEquals(
            setOf(5.0, 30.0),
            RunCheckpoint
                .parse(
                    k.runs
                        .all()
                        .first {
                            it.state == BotRunState.SUCCEEDED
                        }.checkpoint,
                )!!
                .races
                .getValue(k.raceIds[0])
                .firedMinutes,
        )
    }

    @Test
    fun `途中から始まった run は 到達済みの複数のタイミングを 1 回の評価にまとめる`() {
        val k = Kit(start = Cal3.at(9, 52), config = cfg())
        k.days.rows =
            listOf(
                jp.jrabot.domain.calendar.Cal
                    .day(),
            )
        val judge = k.judge as StubJudge
        k.run()
        // R1 の 30 分前も 5 分前も既に到達済み: 1 回の評価（= 通知 1 回）で両方を発火済みにする
        assertEquals(
            1,
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .map { it.observedAt }
                .toSet()
                .size,
        )
        assertEquals(
            setOf(30.0, 5.0),
            RunCheckpoint
                .parse(
                    k.runs
                        .all()
                        .single()
                        .checkpoint,
                )!!
                .races
                .getValue(k.raceIds[0])
                .firedMinutes,
        )
        assertTrue(judge.calls >= 1)
    }

    @Test
    fun `評価に渡す発走までの残りは 30 分前と 5 分前の時刻`() {
        val k = Kit(config = cfg())
        val seen = mutableListOf<Double>()
        val inner = StubJudge()
        k.judge =
            RaceJudge { t, r, w, n, m ->
                if (r == k.raceIds[0]) seen += m
                inner.evaluate(t, r, w, n, m)
            }
        k.run()
        assertEquals(2, seen.size)
        assertTrue(seen[0] in 29.7..30.0 && seen[1] in 4.7..5.0, "$seen")
    }

    @Test
    fun `hit_rate_min_ev_schedule は 30 分前と 5 分前で閾値が変わり 記録される閾値も実際に使った値 未設定は固定のまま`() {
        val hit = Kit.defaultConfig().decision.copy(priorityMode = PriorityMode.HIT_RATE, hitRateMinEv = 0.5, evThreshold = -9.0)
        val on = Kit(config = cfg(decision = hit, schedule = HitRateSchedule(0.3, 0.2, 0.1)), judge = null)
        on.run()
        val used =
            on.evaluations.rows.entries
                .filter { it.key.second == on.raceIds[0] }
                .sortedBy { it.key.third }
                .map { it.value.evThreshold }
        assertEquals(listOf(0.3, 0.2), used) // ちょうど 30 分・ちょうど 5 分の評価。旧の式は >= なので far・mid（境界は上側）
        val off = Kit(config = cfg(decision = hit), judge = null)
        off.run()
        assertTrue(
            off.evaluations.rows.values
                .all { it.evThreshold == 0.5 },
            "未設定なら固定閾値のまま",
        )
    }

    @Test
    fun `5 分前の強化 CORRECTED は 5 分前の評価だけに働き OFF は一切変えない`() {
        val seenNear = mutableListOf<Boolean>()

        fun run(mode: TightenMode): List<Double> {
            val k = Kit(config = cfg(tighten = mode))
            val drops = mutableListOf<Double>()
            val inner = StubJudge()
            val evaluator = RaceEvaluator(k.config, k.odds, k.combos, k.featureSource, MlRunContext.DISABLED)
            k.judge =
                RaceJudge { t, r, w, n, m ->
                    if (r == k.raceIds[0]) drops += evaluator.decisionConfig(m).dropThreshold
                    inner.evaluate(t, r, w, n, m)
                }
            k.run()
            return drops
        }
        val base = Kit.defaultConfig().decision.dropThreshold
        assertEquals(listOf(base, base), run(TightenMode.OFF))
        assertEquals(listOf(base, base * 0.7), run(TightenMode.CORRECTED))
        assertEquals(listOf(base, base), run(TightenMode.LEGACY_QUIRK)) // 旧の癖: 30,5 では 5 分前でも強化されない
        seenNear.clear()
    }

    @Test
    fun `UNKNOWN の 3 回連続は 最初の UNKNOWN から 45 秒以内に停止する heartbeat 60 秒でも`() {
        val k = Kit(config = cfg())
        var unknownAt: java.time.Instant? = null
        k.onPause = {
            if (unknownAt == null && k.clock.now() >= k.at(9, 0)) {
                unknownAt = k.clock.now()
                k.mode.mode = OpsMode.UNKNOWN
            }
        }
        val out = k.run()
        assertEquals("MODE_UNKNOWN", out.reason)
        assertEquals(75, out.exitCode)
        val finished =
            k.runs
                .all()
                .single()
                .finishedAt!!
        val elapsed = Duration.between(unknownAt, finished)
        assertTrue(elapsed <= Duration.ofSeconds(45), "停止まで $elapsed")
        assertTrue(elapsed >= Duration.ofSeconds(30), "3 回の確認の間隔は 15 秒: $elapsed")
        assertEquals(2, k.events.count { it.startsWith("MODE_UNKNOWN_SKIP") })
    }

    @Test
    fun `STOPPED は 15 秒以内に検知される`() {
        val k = Kit(config = cfg())
        var stoppedAt: java.time.Instant? = null
        k.onPause = {
            if (stoppedAt == null && k.clock.now() >= k.at(9, 0, 7)) {
                stoppedAt = k.clock.now()
                k.mode.mode = OpsMode.STOPPED
            }
        }
        assertEquals("STOPPED_BY_MODE", k.run().reason)
        assertTrue(
            Duration.between(
                stoppedAt,
                k.runs
                    .all()
                    .single()
                    .finishedAt!!,
            ) <= Duration.ofSeconds(15),
        )
    }

    @Test
    fun `heartbeat は 60 秒以内に更新され リースの 300 秒を超えて空かない 長い待機でも`() {
        val k = Kit(config = cfg())
        var worstGap = Duration.ZERO
        var samples = 0
        k.onPause = {
            val row = k.runs.findActive(k.tenant, jp.jrabot.domain.calendar.Cal.DATE)
            if (row != null) {
                samples++
                val gap = Duration.between(row.heartbeatAt!!, k.clock.now())
                if (gap > worstGap) worstGap = gap
                assertTrue(row.leaseExpiresAt!! > k.clock.now(), "リースが切れた")
            }
        }
        k.run()
        assertTrue(samples > 300, "samples=$samples")
        assertTrue(worstGap <= Duration.ofSeconds(75), "heartbeat の最大の間隔 $worstGap") // 60 秒 + モード確認の 15 秒
        assertNotNull(
            k.runs
                .all()
                .single()
                .finishedAt,
        )
    }

    @Test
    fun `BACKFILL でも 30 分前と 5 分前の評価は記録するが 通知は禁止`() {
        val k = Kit(config = cfg())
        k.run(k.request(origin = NotificationOrigin.BACKFILL))
        assertEquals(0, k.notifier.sent.size)
        assertEquals(6, k.evaluations.rows.size)
    }
}

private object Cal3 {
    fun at(
        h: Int,
        m: Int,
    ) = jp.jrabot.domain.calendar.Cal
        .jst(jp.jrabot.domain.calendar.Cal.DATE, h, m)
}
