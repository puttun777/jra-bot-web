package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.notify.NotificationChannel
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * bot-run の業務ループの統合試験（合成データ・メモリ上の偽物。実 DB・実 Discord に触れない）。
 * 開催日の判定・通常の 1 日・終了条件・発走時刻の扱い。
 */
class BotRunLifecycleTest {
    @Test
    fun `通常の開催日 全レースを評価して通知し SUCCEEDED で終わる`() {
        val k = Kit()
        val out = k.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        // 3 レースそれぞれ発走 5 分前に 1 回ずつ評価し、1 通知にまとめて送る
        assertEquals(3, k.evaluations.rows.size)
        assertEquals(listOf(k.at(9, 50), k.at(10, 15), k.at(10, 40)).map { it }, k.notifier.signals.map { it.at })
        assertTrue(k.signals.all().isNotEmpty())
        assertTrue(k.signals.all().all { it.state == NotificationState.SENT && it.isPaper })
        // 終了後はリースを解放している（実行中の行が無い）
        assertNull(k.runs.findActive(k.tenant, Cal.DATE))
        val row = k.runs.all().single()
        assertEquals(BotRunState.SUCCEEDED, row.state)
        assertNotNull(row.finishedAt)
        // checkpoint は発火済みのタイミングと全レース OFF を持つ
        val cp = RunCheckpoint.parse(row.checkpoint)!!
        assertEquals(
            setOf(RaceStage.OFF),
            cp.races.values
                .map { it.stage }
                .toSet(),
        )
        assertTrue(cp.races.values.all { it.firedMinutes == setOf(5.0) })
        // 単勝の評価では ML 無効（市場確率）。理由が記録される
        assertTrue(
            k.evaluations.rows.values
                .all { it.modelTypeUsed == "market" && it.fallbackReason == "ML_DISABLED" && it.modelId == null },
        )
    }

    @Test
    fun `曜日や祝日では判定しない 土曜でも予定が無ければ起動しない`() {
        val k = Kit()
        k.days.rows = emptyList()
        k.schedules.rows = emptyList()
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode) // 予定が不明 = 開催なしの確認ではない（要確認 75）
        assertEquals("SCHEDULE_UNKNOWN", out.reason)
        assertTrue(k.runs.all().isEmpty())
        assertTrue(k.notifier.sent.isEmpty())
    }

    @Test
    fun `非開催日が確認できていれば何もせず 0 で終わる`() {
        val k = Kit()
        k.overrides.rows = listOf(Cal.override(OverrideAction.MARK_NO_RACE))
        val out = k.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("NOT_A_RACE_DAY", out.reason)
        assertTrue(k.runs.all().isEmpty())
    }

    @Test
    fun `情報が古い予定は信頼せず 75 で終わる`() {
        val k = Kit()
        k.days.rows = listOf(Cal.day(confirmedAt = Cal.jst(Cal.DATE.minusDays(2), 6, 0)))
        val out = k.run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode)
        assertEquals("SCHEDULE_STALE", out.reason)
        assertTrue(k.runs.all().isEmpty())
    }

    @Test
    fun `開催日の中止と延期では起動しない`() {
        val cancelled = Kit().also { it.overrides.rows = listOf(Cal.override(OverrideAction.CANCEL)) }
        assertEquals("DAY_CANCELLED", cancelled.run().reason)
        val postponed = Kit().also { it.overrides.rows = listOf(Cal.override(OverrideAction.POSTPONE_TO)) }
        assertEquals("DAY_POSTPONED", postponed.run().reason)
        assertTrue(cancelled.runs.all().isEmpty() && postponed.runs.all().isEmpty())
    }

    @Test
    fun `DENY_RUN は起動を拒否する`() {
        val k = Kit()
        k.overrides.rows = listOf(Cal.override(OverrideAction.DENY_RUN))
        val out = k.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("DENIED_BY_OVERRIDE", out.reason)
        assertTrue(k.runs.all().isEmpty())
    }

    @Test
    fun `監視ウィンドウの前と後では起動しない`() {
        val before = Kit(start = Cal.jst(Cal.DATE, 7, 0))
        assertEquals("BEFORE_WATCH_WINDOW", before.run().reason)
        val after = Kit(start = Cal.jst(Cal.DATE, 12, 30))
        assertEquals("AFTER_SETTLE_DEADLINE", after.run().reason)
        assertTrue(before.runs.all().isEmpty() && after.runs.all().isEmpty())
    }

    @Test
    fun `発走時刻が不明のレースには時刻を補完せず 評価も通知もしない 窓の終わりで未完了として終わる`() {
        val races = Cal.races(count = 3).toMutableList()
        races[2] = Cal.race(no = 3, post = null)
        val k = Kit(races = races)
        val out = k.run()
        // R3 は時刻不明のまま。R1・R2 だけ通知し、窓の締切（最後の既知の発走 + 90 分）で FAILED（reconcile の担当）
        assertEquals(RunExit.FAILURE, out.exitCode)
        assertEquals("INCOMPLETE_AT_DEADLINE", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
        assertEquals(2, k.notifier.signals.size)
        assertTrue(k.signals.all().none { it.raceId == races[2].raceId })
        assertTrue(
            k.evaluations.rows.keys
                .none { it.second == races[2].raceId },
        )
        assertTrue(k.notifier.errors.any { it.subject.contains("UNRESOLVED_RACES") })
    }

    @Test
    fun `発走時刻の変更は次のサイクルで反映される`() {
        val k = Kit()
        val r2 = k.schedules.rows[1]
        // 09:00 に R2 の発走が 10:20 → 10:30 に変更された
        var changed = false
        k.onPause = {
            if (!changed && k.clock.now() >= k.at(9, 0)) {
                changed = true
                k.schedules.rows = k.schedules.rows.map { if (it.raceId == r2.raceId) Cal.race(no = 2, post = java.time.LocalTime.of(10, 30)) else it }
            }
        }
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        // R2 の 5 分前は新しい時刻の 10:25（旧時刻の 10:15 ではない）
        assertEquals(k.at(10, 25), k.notifier.signals[1].at)
    }

    @Test
    fun `途中でレースが中止になると そのレースは評価せず 残りで完了する`() {
        val k = Kit()
        val r2 = k.schedules.rows[1].raceId
        var done = false
        k.onPause = {
            if (!done && k.clock.now() >= k.at(9, 0)) {
                done = true
                k.overrides.rows = listOf(Cal.override(OverrideAction.CANCEL, scope = jp.jrabot.domain.calendar.OverrideScope.RACE, raceNo = 2))
            }
        }
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(2, k.notifier.signals.size)
        assertTrue(k.signals.all().none { it.raceId == r2 })
    }

    @Test
    fun `途中で開催日全体が中止になると通知せずに終わる`() {
        val k = Kit()
        var done = false
        k.onPause = {
            if (!done && k.clock.now() >= k.at(9, 0)) {
                done = true
                k.overrides.rows = listOf(Cal.override(OverrideAction.CANCEL))
            }
        }
        val out = k.run()
        assertEquals("DAY_CANCELLED", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertTrue(k.notifier.signals.isEmpty())
    }

    @Test
    fun `最大稼働時間を超えると ABORTED で終わりエラー通知する`() {
        val cfg = Kit.defaultConfig().copy(maxRuntime = Duration.ofMinutes(30))
        val k = Kit(config = cfg)
        val out = k.run()
        assertEquals(RunExit.FAILURE, out.exitCode)
        assertEquals("MAX_RUNTIME_EXCEEDED", out.reason)
        assertEquals(BotRunState.ABORTED, out.state)
        assertTrue(k.notifier.errors.any { it.subject.contains("MAX_RUNTIME_EXCEEDED") })
        assertNull(k.runs.findActive(k.tenant, Cal.DATE))
    }

    @Test
    fun `監視の開始前は待つだけで何も評価しない`() {
        val k = Kit(start = Cal.jst(Cal.DATE, 7, 46))
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        // 07:55 より前に評価・通知していない
        assertTrue(k.notifier.signals.all { it.at >= k.at(9, 50) })
        // 待機は heartbeat の間隔（1 分）以下
        assertTrue(k.pauses.all { it <= Duration.ofMinutes(1) })
    }

    @Test
    fun `オッズが無いレースは評価も通知もしない 後から届けば評価する`() {
        val k = Kit()
        k.autoOdds = false
        val r1 = k.raceIds[0]
        // R1 だけ 09:52 に初めてオッズが届く（R2・R3 は届かない）
        k.onPause = { if (k.clock.now() == k.at(9, 52)) k.odds.add(Kit.snapshot(r1, k.at(9, 52))) }
        val out = k.run()
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertEquals(1, k.evaluations.rows.size)
        assertEquals(
            r1,
            k.evaluations.rows.keys
                .single()
                .second,
        )
        assertEquals(1, k.notifier.signals.size)
        assertEquals(
            k.at(9, 52),
            k.notifier.signals
                .single()
                .at,
        )
    }

    @Test
    fun `古いオッズでは通知しない 運用者へは警告する`() {
        val k = Kit()
        k.autoOdds = false
        k.raceIds.forEach { k.odds.add(Kit.snapshot(it, k.at(9, 40))) } // 発走 5 分前の時点で 10 分前の観測 = 古い
        val out = k.run()
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(k.evaluations.rows.isEmpty())
        assertTrue(k.signals.all().isEmpty())
        assertTrue(k.notifier.errors.any { it.subject.contains("STALE_ODDS") })
        assertTrue(k.events.any { it.startsWith("STALE_ODDS") })
    }

    @Test
    fun `未来の観測時刻のオッズは信頼せず通知しない`() {
        val k = Kit()
        k.autoOdds = false
        k.raceIds.forEach { k.odds.add(Kit.snapshot(it, k.at(11, 0))) }
        k.run()
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(k.events.any { it.startsWith("ODDS_FROM_FUTURE") })
    }

    @Test
    fun `監視対象の通知は正しいチャンネルへ送られエラー用ではない`() {
        val k = Kit()
        k.run()
        assertTrue(k.notifier.signals.isNotEmpty())
        assertTrue(k.notifier.sent.all { it.channel == NotificationChannel.SIGNAL || it.channel == NotificationChannel.ERROR })
        assertEquals(0, k.notifier.errors.size)
    }
}
