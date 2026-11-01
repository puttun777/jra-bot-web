package jp.jrabot.domain.backtest

import jp.jrabot.domain.botrun.RaceEvaluation
import jp.jrabot.domain.botrun.RaceJudge
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BacktestRunnerTest {
    private val t1 = Cal.T1
    private val races = Cal.races(count = 3) // 発走 09:55 / 10:20 / 10:45（JST）
    private val ids = races.map { it.raceId }

    private fun post(i: Int): Instant = races[i].scheduledPostAt!!

    private class Odds1(
        val snapshots: MutableMap<RaceId, MutableList<OddsSnapshot>> = HashMap(),
    ) : OddsSnapshotReader {
        override fun latest(
            tenantId: TenantId,
            raceId: RaceId,
            market: Market,
        ) = snapshots[raceId]?.maxByOrNull { it.observedAt }

        override fun history(
            tenantId: TenantId,
            raceId: RaceId,
            market: Market,
        ) = snapshots[raceId].orEmpty().sortedBy { it.observedAt }
    }

    private class Results(
        val rows: MutableMap<RaceId, List<ResultEntry>> = HashMap(),
        val pay: MutableMap<RaceId, List<StoredPayout>> = HashMap(),
    ) : ResultStore,
        PayoutStore {
        override fun upsertAll(
            tenantId: TenantId,
            raceId: RaceId,
            entries: List<ResultEntry>,
        ) = UpsertOutcome.CREATED

        override fun list(
            tenantId: TenantId,
            raceId: RaceId,
        ) = rows[raceId].orEmpty()

        override fun upsertAll(
            tenantId: TenantId,
            raceId: RaceId,
            market: Market,
            rows: List<PayoutRow>,
            provisional: Boolean,
        ) = UpsertOutcome.CREATED

        override fun list(
            tenantId: TenantId,
            raceId: RaceId,
            market: Market,
        ) = pay[raceId].orEmpty()
    }

    private fun snap(
        race: RaceId,
        at: Instant,
    ) = OddsSnapshot(t1, race, Market.WIN, at, listOf(OddsEntry(HorseNumber.of(2), Odds.of(8.0), 2), OddsEntry(HorseNumber.of(4), Odds.of(15.0), 4)))

    private val odds = Odds1()
    private val results = Results()

    /** 判定に渡された引数を記録する（未来の情報が渡っていないことの検査）。 */
    private class Recording(
        val inner: RaceJudge = StubJudge(),
    ) : RaceJudge {
        val calls = mutableListOf<Triple<RaceId, OddsSnapshot, Instant>>()

        override fun evaluate(
            tenantId: TenantId,
            raceId: RaceId,
            win: OddsSnapshot,
            now: Instant,
            minutesToPost: Double,
        ): RaceEvaluation {
            calls += Triple(raceId, win, now)
            return inner.evaluate(tenantId, raceId, win, now, minutesToPost)
        }
    }

    private fun req(vararg minutes: Double) = BacktestRequest(t1, Cal.DATE, Cal.DATE, minutes.toList(), Duration.ofMinutes(10))

    private fun runner(judge: RaceJudge) = BacktestRunner(InMemoryScheduleReader(races), odds, results, results, judge)

    private fun settleRace(
        i: Int,
        winner: Int,
    ) {
        results.rows[ids[i]] = listOf(ResultEntry(winner, 1), ResultEntry(if (winner == 2) 4 else 2, 2))
        results.pay[ids[i]] = listOf(StoredPayout("%02d".format(winner), 800.0, false))
    }

    @Test
    fun `判定には時点以前のオッズだけを渡し 発走後・時点より後の観測を渡さない 着順や払戻も渡さない`() {
        for (i in 0..2) {
            odds.snapshots[ids[i]] =
                mutableListOf(
                    snap(ids[i], post(i).minusSeconds(40 * 60)),
                    snap(ids[i], post(i).minusSeconds(8 * 60)),
                    snap(ids[i], post(i).minusSeconds(60)), // 5 分前の時点より後（使ってはならない）
                    snap(ids[i], post(i).plusSeconds(60)), // 発走後
                )
            settleRace(i, 2)
        }
        val judge = Recording()
        runner(judge).run(req(30.0, 5.0))
        assertTrue(judge.calls.isNotEmpty())
        for ((race, win, now) in judge.calls) {
            val postAt = races.first { it.raceId == race }.scheduledPostAt!!
            assertTrue(!win.observedAt.isAfter(now), "判定に渡したオッズが時点より後: $win > $now")
            assertTrue(now.isBefore(postAt), "判定の時点が発走以降")
        }
        // 5 分前の時点で選ばれるのは 8 分前の観測（1 分前や発走後ではない）
        val five = judge.calls.filter { Duration.between(it.third, post(ids.indexOf(it.first))).toMinutes() == 5L }
        assertTrue(five.all { Duration.between(it.second.observedAt, post(ids.indexOf(it.first))).toMinutes() == 8L })
    }

    @Test
    fun `除外は理由別の件数と率で報告し 黙って落とさない`() {
        // R1: 正常 / R2: 結果なし / R3: オッズが 5 分前の時点より新しいものしか無い（その時点では観測なし）
        odds.snapshots[ids[0]] = mutableListOf(snap(ids[0], post(0).minusSeconds(8 * 60)))
        settleRace(0, 2)
        odds.snapshots[ids[1]] = mutableListOf(snap(ids[1], post(1).minusSeconds(8 * 60)))
        odds.snapshots[ids[2]] = mutableListOf(snap(ids[2], post(2).minusSeconds(60)))
        settleRace(2, 2)
        val r = runner(Recording()).run(req(5.0))
        assertEquals(3, r.candidates)
        assertEquals(1, r.evaluated)
        assertEquals(1, r.excluded[ExclusionReason.NO_RESULT])
        assertEquals(1, r.excluded[ExclusionReason.NO_ODDS_AT_THAT_TIME])
        assertEquals(2, r.excludedTotal)
        assertEquals(2.0 / 3.0, r.exclusionRate!!, 1e-12)
    }

    @Test
    fun `古すぎる観測と判定の失敗も除外として数える`() {
        odds.snapshots[ids[0]] = mutableListOf(snap(ids[0], post(0).minusSeconds(60 * 60))) // 5 分前の時点で 55 分前の観測 = 古い
        settleRace(0, 2)
        odds.snapshots[ids[1]] = mutableListOf(snap(ids[1], post(1).minusSeconds(8 * 60)))
        settleRace(1, 2)
        val failing = StubJudge().also { it.onEvaluate = { _ -> throw IllegalStateException("leak") } }
        val r = runner(failing).run(req(5.0))
        assertEquals(1, r.excluded[ExclusionReason.STALE_ODDS])
        assertEquals(1, r.excluded[ExclusionReason.JUDGE_FAILED])
        assertEquals(0, r.evaluated)
    }

    @Test
    fun `単勝の紙上成績を計算し 複数の時点で同じ馬を推奨しても二重に数えない`() {
        for (i in 0..1) {
            odds.snapshots[ids[i]] =
                mutableListOf(snap(ids[i], post(i).minusSeconds(40 * 60)), snap(ids[i], post(i).minusSeconds(8 * 60)))
            settleRace(i, 2) // 2 番が 1 着。StubJudge は 2 番（3 点・オッズ 8）と 4 番（1 点・オッズ 15）を推奨
        }
        val r = runner(Recording()).run(req(30.0, 5.0))
        assertEquals(6, r.candidates) // 3 レース × 2 時点（3 レース目は結果なしで両方除外）
        assertEquals(4, r.evaluated)
        assertEquals(2, r.excluded[ExclusionReason.NO_RESULT])
        // 代表集計: 2 レース × 2 頭 = 4 件（30 分前と 5 分前の両方を数えると 8 件になる）
        assertEquals(4, r.win.count)
        assertEquals(2, r.win.hits) // 2 番だけ的中
        assertEquals(8.0, r.win.totalStake) // (3 + 1) × 2 レース
        assertEquals(2 * 3.0 * 8.0, r.win.paperPayout) // 通知時オッズ × 推奨額（的中のみ）
        assertEquals(2 * 3.0 * 800.0 / 100.0, r.win.actualPayout) // 実払戻
        assertEquals(0, r.unsettledBets)
        assertNull(ExclusionReason.entries.firstOrNull { it != ExclusionReason.NO_RESULT && (r.excluded[it] ?: 0) > 0 })
    }

    @Test
    fun `期間が空・時点が不正なら拒否する`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { BacktestRequest(t1, Cal.DATE, Cal.DATE.minusDays(1), listOf(5.0), Duration.ofMinutes(10)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { BacktestRequest(t1, Cal.DATE, Cal.DATE, emptyList(), Duration.ofMinutes(10)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { BacktestRequest(t1, Cal.DATE, Cal.DATE, listOf(-1.0), Duration.ofMinutes(10)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { BacktestRequest(t1, Cal.DATE, Cal.DATE.plusDays(400), listOf(5.0), Duration.ofMinutes(10)) }
    }
}
