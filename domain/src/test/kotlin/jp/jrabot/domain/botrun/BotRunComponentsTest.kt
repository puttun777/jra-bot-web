package jp.jrabot.domain.botrun

import jp.jrabot.domain.botrun.testing.InMemoryComboOdds
import jp.jrabot.domain.botrun.testing.InMemoryOdds
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.EffectiveCalendar
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.decision.DecisionReason
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.odds.ComboOddsRow
import jp.jrabot.domain.odds.ComboOddsSnapshot
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceScheduleStatus
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 小さな部品の単体試験（計画・checkpoint・鮮度・旧実装の分岐・判定への接続）。合成データのみ。 */
class BotRunComponentsTest {
    private val t = Cal.T1
    private val race1 = Cal.race(no = 1).raceId

    // ---- RaceMonitorPlanner ----

    private fun plan(
        now: java.time.Instant,
        races: List<jp.jrabot.domain.race.RaceSchedule> = Cal.races(count = 3),
        overrides: List<jp.jrabot.domain.calendar.CalendarOverride> = emptyList(),
    ): MonitorPlan {
        val day = EffectiveCalendar.derive(t, Cal.DATE, now, Kit.defaultConfig().calendar, listOf(Cal.day()), races, overrides, null)
        return RaceMonitorPlanner.plan(day, day.window(Cal.WATCH), now)
    }

    @Test
    fun `監視の開始前は PENDING 開始後は MONITORING 発走時刻ちょうどから OFF`() {
        assertEquals(setOf(RaceStage.PENDING), plan(Cal.jst(Cal.DATE, 7, 50)).stages.values.toSet())
        val monitoring = plan(Cal.jst(Cal.DATE, 7, 55)) // 最初の発走 09:55 の 120 分前ちょうど
        assertEquals(RaceStage.MONITORING, monitoring.stages.getValue(race1))
        assertEquals(3, monitoring.monitoring.size)
        val atPost = plan(Cal.jst(Cal.DATE, 9, 55))
        assertEquals(RaceStage.OFF, atPost.stages.getValue(race1)) // 発走ちょうどは OFF（asOf は発走より前でなければならない）
        assertFalse(atPost.allDone)
        assertTrue(plan(Cal.jst(Cal.DATE, 10, 45)).allDone)
    }

    @Test
    fun `中止と発走時刻不明は監視しない 時刻を補完しない`() {
        val races = Cal.races(count = 3).toMutableList()
        races[1] = Cal.race(no = 2, status = RaceScheduleStatus.CANCELLED)
        races[2] = Cal.race(no = 3, post = null)
        val p = plan(Cal.jst(Cal.DATE, 9, 0), races)
        assertEquals(RaceStage.CANCELLED, p.stages.getValue(races[1].raceId))
        assertEquals(RaceStage.UNKNOWN_TIME, p.stages.getValue(races[2].raceId))
        assertEquals(listOf(race1), p.monitoring)
        assertEquals(listOf(races[2].raceId), p.unresolved)
        assertFalse(p.allDone)
    }

    @Test
    fun `手動の発走時刻の設定が反映される`() {
        val o = Cal.override(OverrideAction.SET_POST_TIME, raceNo = 1)
        // 手動で 10:00 に設定 → 09:57 でもまだ発走前（元の予定 09:55 なら OFF）
        val p = plan(Cal.jst(Cal.DATE, 9, 57), overrides = listOf(o))
        assertEquals(RaceStage.MONITORING, p.stages.getValue(race1))
    }

    // ---- RunCheckpoint ----

    @Test
    fun `checkpoint は JSON で往復でき 壊れていれば null`() {
        val cp =
            RunCheckpoint(
                7,
                Cal.jst(Cal.DATE, 9, 50),
                mapOf(race1 to RaceCheckpoint(RaceStage.MONITORING, Cal.jst(Cal.DATE, 9, 49), setOf(30.0, 5.0)), Cal.race(no = 2).raceId to RaceCheckpoint(RaceStage.OFF)),
            )
        assertEquals(cp, RunCheckpoint.parse(cp.toJson()))
        assertEquals(cp.toJson(), RunCheckpoint.parse(cp.toJson())!!.toJson())
        for (bad in listOf(null, "", "not json", "[]", "{}", """{"v":2,"cycle":1,"races":{}}""", """{"v":1,"cycle":-1,"races":{}}""", """{"v":1,"cycle":1,"races":{"x":{"stage":"OFF"}}}""", """{"v":1,"cycle":1,"races":{"202610240501":{"stage":"???"}}}""")) {
            assertNull(RunCheckpoint.parse(bad), "壊れた checkpoint は使わない: $bad")
        }
        assertTrue(cp.toJson().length < jp.jrabot.domain.bot.BotRun.MAX_CHECKPOINT_LENGTH)
    }

    // ---- 鮮度・旧実装の分岐 ----

    @Test
    fun `オッズの鮮度 ちょうど上限は新鮮 超えたら古い 未来と無しは別`() {
        val now = Cal.jst(Cal.DATE, 9, 50)
        val max = Duration.ofSeconds(180)
        assertEquals(OddsFreshness.FRESH, OddsFreshness.of(now.minusSeconds(180), now, max))
        assertEquals(OddsFreshness.STALE, OddsFreshness.of(now.minusSeconds(181), now, max))
        assertEquals(OddsFreshness.FUTURE, OddsFreshness.of(now.plusSeconds(1), now, max))
        assertEquals(OddsFreshness.MISSING, OddsFreshness.of(null, now, max))
    }

    @Test
    fun `ポーリング間隔は旧 get_polling_interval の分岐 30 分超 far 5 分超 mid それ以下 near`() {
        val p = PollingIntervals(Duration.ofSeconds(300), Duration.ofSeconds(60), Duration.ofSeconds(30))
        assertEquals(Duration.ofSeconds(300), p.forMinutesToPost(30.1))
        assertEquals(Duration.ofSeconds(60), p.forMinutesToPost(30.0))
        assertEquals(Duration.ofSeconds(60), p.forMinutesToPost(5.01))
        assertEquals(Duration.ofSeconds(30), p.forMinutesToPost(5.0))
        assertEquals(Duration.ofSeconds(30), p.forMinutesToPost(0.0))
    }

    @Test
    fun `評価のタイミングの旧の癖 最大のタイミングで決まる 5 分以下のときだけ強化`() {
        val onlyFive = SnapshotPolicy(listOf(5.0), tightenNear = true)
        assertNull(onlyFive.legacyCurrent(5.1))
        assertEquals(5.0, onlyFive.legacyCurrent(5.0))
        assertTrue(onlyFive.isNear(4.0))
        val multi = SnapshotPolicy(listOf(5.0, 30.0, 10.0), tightenNear = true)
        assertEquals(listOf(30.0, 10.0, 5.0), multi.descending)
        assertEquals(30.0, multi.legacyCurrent(3.0)) // 旧実装: 大きい順の最初の一致 = 30
        assertFalse(multi.isNear(3.0)) // よって [30,10,5] では強化は働かない（旧の癖をそのまま移す）
        assertFalse(SnapshotPolicy(listOf(5.0), tightenNear = false).isNear(4.0))
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(emptyList(), false) }
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(5.0, 5.0), false) }
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(0.0), false) }
    }

    @Test
    fun `設定には既定値が無く 範囲外は拒否される`() {
        val ok = Kit.defaultConfig()
        assertFailsWith<IllegalArgumentException> { ok.copy(maxRuntime = Duration.ofHours(15)) } // 契約の上限は 14 時間
        assertFailsWith<IllegalArgumentException> { ok.copy(maxOddsAge = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { ok.copy(snapshotHistoryLimit = 1) }
        assertFailsWith<IllegalArgumentException> { ok.copy(maxConsecutiveUnknown = 0) }
        assertFailsWith<IllegalArgumentException> { PollingIntervals(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)) }
    }

    // ---- RaceEvaluator（判定への接続） ----

    private fun judge(
        config: BotRunConfig = Kit.defaultConfig(),
        odds: InMemoryOdds = InMemoryOdds(),
        combos: InMemoryComboOdds = InMemoryComboOdds(),
    ) = RaceEvaluator(config, odds, combos, InMemoryFeatureSource(), MlRunContext.DISABLED)

    @Test
    fun `5 分前の強化は 急落の閾値を 0,7 倍 正の over_market 上限を 0,8 倍にする 0 以下は変えない`() {
        val base = Kit.defaultConfig()
        val d = base.decision.copy(dropThreshold = 0.2, winMaxOverMarket = 2.5, combo = base.decision.combo.copy(wideMaxOverMarket = 2.0, sanrenpukuMaxOverMarket = 0.0))
        val e = judge(base.copy(decision = d))
        val near = e.decisionConfig(true)
        assertEquals(0.2 * 0.7, near.dropThreshold, 1e-15)
        assertEquals(2.5 * 0.8, near.winMaxOverMarket!!, 1e-15)
        assertEquals(2.0 * 0.8, near.combo.wideMaxOverMarket, 1e-15)
        assertEquals(0.0, near.combo.sanrenpukuMaxOverMarket, 0.0)
        assertEquals(d, e.decisionConfig(false))
    }

    @Test
    fun `急落の判定は 直近の履歴の件数で決まる 旧 snapshot_history_limit`() {
        val odds = InMemoryOdds()
        fun snap(
            minute: Int,
            h2: Double,
        ) = OddsSnapshot(t, race1, Market.WIN, Cal.jst(Cal.DATE, 9, minute), Kit.ODDS.mapIndexed { i, o -> OddsEntry(HorseNumber.of(i + 1), Odds.ofOrNull(if (i == 1) h2 else o)!!, i + 1) })
        listOf(snap(40, 12.0), snap(42, 11.0), snap(44, 10.0), snap(46, 8.0)).forEach(odds::add)
        val now = Cal.jst(Cal.DATE, 9, 46, 30)
        val base = Kit.defaultConfig()
        val cfg = base.decision.copy(dropThreshold = 0.2, evThreshold = -5.0)
        fun reasons(limit: Int) =
            judge(base.copy(decision = cfg, snapshotHistoryLimit = limit), odds)
                .evaluate(t, race1, odds.latest(t, race1, Market.WIN)!!, now, false)
                .result.rejections.filter { it.umaban == 2 }.map { it.reason }
        // limit=3: 窓は [11,10,8]（新しい順 [8,10,11]）、最古 11 → (11-8)/11 = 27% > 20% で急落
        assertTrue(DecisionReason.ODDS_DROP in reasons(3))
        // limit=2: 窓は [10,8] → (10-8)/10 = 20% はちょうどで急落ではない
        assertFalse(DecisionReason.ODDS_DROP in reasons(2))
    }

    @Test
    fun `組み合わせの実オッズは 鮮度が上限内のものだけを使う 古いものは実オッズ未取得として扱う`() {
        val odds = InMemoryOdds()
        val combos = InMemoryComboOdds()
        val win = Kit.snapshot(race1, Cal.jst(Cal.DATE, 9, 50))
        odds.add(win)
        val now = Cal.jst(Cal.DATE, 9, 50, 30)
        val e = judge(odds = odds, combos = combos)
        assertFalse(e.evaluate(t, race1, win, now, false).comboRealOdds) // 組み合わせオッズが無い
        combos.add(ComboOddsSnapshot(t, race1, Market.WIDE, Cal.jst(Cal.DATE, 9, 40), listOf(ComboOddsRow("01-02", Odds.ofOrNull(3.0)!!, Odds.ofOrNull(4.0)))))
        assertFalse(e.evaluate(t, race1, win, now, false).comboRealOdds) // 10 分前 = 古い
        combos.add(ComboOddsSnapshot(t, race1, Market.WIDE, Cal.jst(Cal.DATE, 9, 49), listOf(ComboOddsRow("01-02", Odds.ofOrNull(3.0)!!, Odds.ofOrNull(4.0)))))
        assertTrue(e.evaluate(t, race1, win, now, false).comboRealOdds)
        combos.add(ComboOddsSnapshot(t, race1, Market.SANRENPUKU, Cal.jst(Cal.DATE, 9, 49), listOf(ComboOddsRow("01-02-03", Odds.ofOrNull(12.0)!!))))
        assertTrue(e.evaluate(t, race1, win, now, false).comboRealOdds)
    }

    @Test
    fun `別のテナント・別のレースのオッズは混ざらない`() {
        val odds = InMemoryOdds()
        odds.add(Kit.snapshot(race1, Cal.jst(Cal.DATE, 9, 50)))
        assertNull(odds.latest(Cal.T2, race1, Market.WIN))
        assertNull(odds.latest(t, Cal.race(no = 2).raceId, Market.WIN))
    }

    @Test
    fun `判定結果には ML 無効の理由と 単勝の確率源ラベルが入る`() {
        val odds = InMemoryOdds()
        val win = Kit.snapshot(race1, Cal.jst(Cal.DATE, 9, 50))
        odds.add(win)
        val ev = judge(odds = odds).evaluate(t, race1, win, Cal.jst(Cal.DATE, 9, 50, 10), false)
        assertEquals("market", ev.modelTypeUsed)
        assertNull(ev.modelId)
        assertEquals(jp.jrabot.domain.ml.pipeline.FallbackReason.ML_DISABLED, ev.fallbackReason)
        assertEquals(win.observedAt, ev.snapshotAt)
        assertEquals(6, ev.horses.size)
        assertFalse(ev.isMlMarketFallback)
    }
}
