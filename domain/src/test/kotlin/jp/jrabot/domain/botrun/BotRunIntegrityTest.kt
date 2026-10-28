package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 監査（2026-10-27）の C1/C2・E に対する回帰試験。合成データ・手で進める時計だけを使う。
 * - C1/C2: 予定を信頼できず評価を逃したのに SUCCEEDED にしない。正常な 0 件・中止・通知対象なしは異常にしない。
 * - E: checkpoint が欠損・破損していても、通知済みのレースを新しいオッズで再通知しない（初回の空 checkpoint は正常）。
 */
class BotRunIntegrityTest {
    private fun staleCfg() = Kit.defaultConfig().copy(calendar = CalendarSettings(Cal.WATCH, staleAfter = Duration.ofHours(3)))

    private fun errorsOf(k: Kit) = k.notifier.errors.map { it.subject }

    // ---- C1/C2 ----

    @Test
    fun `実行中に予定が古くなり評価を逃したら SUCCEEDED にしない FAILED と警告`() {
        // 予定の確認は 06:00。staleAfter=3h なので 09:00 から信頼できない。R1 の 5 分前（09:50）にはもう評価できない
        val k = Kit(config = staleCfg())
        val out = k.run()
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(RunExit.FAILURE, out.exitCode)
        assertEquals(BotRunState.FAILED, out.state)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(k.evaluations.rows.isEmpty())
        assertTrue(errorsOf(k).any { it.contains("EVALUATION_OPPORTUNITY_LOST") }, "警告が出る: ${errorsOf(k)}")
        assertEquals(
            BotRunState.FAILED,
            k.runs
                .all()
                .single()
                .state,
        )
    }

    @Test
    fun `一部のレースだけ信頼できなくなった場合も 評価を逃したレースがあれば FAILED`() {
        // R1 は評価できるが、予定の確認が古くなった後の R3 は評価できない（staleAfter=3h40m → 09:40 から古い。R1 の 5 分前 09:50 には既に古いので別の設定）
        val k = Kit(config = Kit.defaultConfig().copy(calendar = CalendarSettings(Cal.WATCH, staleAfter = Duration.ofHours(4).plusMinutes(30))))
        val out = k.run()
        // 10:30 を過ぎると古い: R1・R2 は評価済み、R3（発走 10:45、5 分前 10:40）は逃した
        assertEquals(2, k.notifier.signals.size)
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
    }

    @Test
    fun `評価した結果 通知対象が 0 件でも正常終了 異常にしない`() {
        val k = Kit(judge = StubJudge { emptyList() })
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals(3, k.evaluations.rows.size)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(errorsOf(k).isEmpty(), "警告なし: ${errorsOf(k)}")
    }

    @Test
    fun `中止されたレースは評価しなくても正常終了`() {
        val races = Cal.races(count = 3).mapIndexed { i, r -> if (i == 1) r.copy(status = RaceScheduleStatus.CANCELLED) else r }
        val k = Kit(races = races)
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(2, k.evaluations.rows.size)
        assertTrue(errorsOf(k).isEmpty(), "警告なし: ${errorsOf(k)}")
    }

    @Test
    fun `オッズが一度も届かなかったレースは 成功扱いのまま警告する`() {
        val k = Kit()
        k.autoOdds = false
        val out = k.run()
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertTrue(errorsOf(k).any { it.contains("ALL_RACES_OFF_WITH_GAPS") }, "警告が出る: ${errorsOf(k)}")
    }

    // ---- E ----

    private fun interruptedAfterR1(k: Kit) {
        k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertEquals(1, k.notifier.signals.size)
        k.stop = false
        k.onPause = {}
    }

    @Test
    fun `checkpoint が壊れていても 通知済みのレースを新しいオッズで再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.runs.rewriteCheckpoints { "{broken" }
        val out = k.run(k.request(owner = "owner-B"))
        // R1 は止める（どのタイミングを通知したか分からない）。R2・R3 は通常どおり
        assertEquals(3, k.notifier.signals.size)
        assertEquals(
            1,
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .map { it.observedAt }
                .toSet()
                .size,
            "R1 に新しい観測の Signal が増えない",
        )
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertTrue(k.events.any { it.startsWith("CHECKPOINT_CORRUPT") })
        assertTrue(errorsOf(k).any { it.contains("CHECKPOINT_UNRELIABLE") })
    }

    @Test
    fun `checkpoint が欠損していても 通知済みの記録があるレースは再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.runs.rewriteCheckpoints { null }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals(3, k.notifier.signals.size)
        assertEquals(
            1,
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .map { it.observedAt }
                .toSet()
                .size,
        )
        assertEquals("ALL_RACES_OFF_WITH_GAPS", out.reason)
        assertTrue(errorsOf(k).any { it.contains("CHECKPOINT_UNRELIABLE") })
    }

    @Test
    fun `初回起動の空 checkpoint は正常 止めない`() {
        val k = Kit()
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
    }

    @Test
    fun `前の run が開始前に中断して checkpoint が null でも 通知記録が無ければ止めない`() {
        val k = Kit()
        // 前の run: 開始してすぐ SIGTERM（何も通知していない）。checkpoint は null にする
        k.stop = true
        k.run()
        k.stop = false
        k.runs.rewriteCheckpoints { null }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
    }

    @Test
    fun `未送信のままの Signal だけなら checkpoint が無くても止めない 通知はまだされていない`() {
        val k = Kit()
        // 判定の最中に SIGTERM: 記録はあるが通知（claim）はしていない。checkpoint は保存済みなので消して再現
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertTrue(k.signals.all().all { it.state == NotificationState.UNSENT })
        k.stop = false
        (k.judge as StubJudge).onEvaluate = {}
        k.runs.rewriteCheckpoints { null }
        k.run(k.request(owner = "owner-B"))
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
        assertTrue(k.notifier.signals.isNotEmpty())
    }

    // ---- B ----

    @Test
    fun `保存済みの Signal と計算結果が違うときは通知しない 一致するものだけ通知する`() {
        var amount = 3.0
        val k = Kit(judge = StubJudge { listOf(StubJudge.bet(2, amount = amount), StubJudge.bet(4, odds = 15.0, amount = 1.0)) })
        k.autoOdds = false
        k.raceIds.forEach { k.odds.add(Kit.snapshot(it, k.at(9, 52))) } // 同じ観測（自然キーが同じ）を再評価する状況
        k.clock.advance(Duration.between(k.clock.now(), k.at(9, 52)))
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertTrue(k.signals.all().all { it.state == NotificationState.UNSENT })
        val before = k.signals.all().associate { it.umaban.value to it.stake }
        assertEquals(mapOf(2 to 3.0, 4 to 1.0), before)

        // 再起動: checkpoint は無い（通知はまだ。safe）。推奨額が変わった計算結果で、同じ観測の行が既にある
        k.stop = false
        (k.judge as StubJudge).onEvaluate = {}
        k.runs.rewriteCheckpoints { null }
        amount = 7.0
        k.run(k.request(owner = "owner-B"))

        val r1 = k.signals.all().filter { it.raceId == k.raceIds[0] }
        assertEquals(3.0, r1.single { it.umaban.value == 2 }.stake, "DB の値は書き換えない")
        assertEquals(NotificationState.UNSENT, r1.single { it.umaban.value == 2 }.state, "不一致の行は claim も通知もしない")
        assertEquals(NotificationState.SENT, r1.single { it.umaban.value == 4 }.state, "一致する行は通知する")
        assertTrue(errorsOf(k).any { it.contains("SIGNAL_MISMATCH") }, "原因を記録: ${errorsOf(k)}")
        assertTrue(k.notifier.signals.none { it.body.contains("7.0") }, "計算し直した値（推奨額 7.0）を通知していない")
    }

    // ---- G1: 実オッズが無いワイド・三連複は推定オッズで買い目にしない ----

    @Test
    fun `組み合わせの実オッズが無いとき ワイド 三連複を推定オッズで通知しない 見送りとして理由を示す`() {
        val k = Kit(judge = null) // 実物の判定。組み合わせオッズは保存していない（INGEST_STORE_COMBO_ODDS=false の既定）
        k.run()
        assertTrue(k.signals.all().none { it.market != jp.jrabot.domain.odds.Market.WIN }, "ワイド・三連複の Signal を作らない")
        assertEquals(3, k.evaluations.rows.size, "判定は行われている（全レース・1 タイミング）")
        assertTrue(k.notifier.signals.all { !it.body.contains("（推定）") || it.body.contains("実オッズ未取得") }, "推定を実オッズとして示さない")
    }
}
