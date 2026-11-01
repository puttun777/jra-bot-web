package jp.jrabot.domain.backtest

import jp.jrabot.domain.botrun.RaceJudge
import jp.jrabot.domain.decision.BetSignal
import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.performance.ComboOutcome
import jp.jrabot.domain.performance.ComboPaperSettlement
import jp.jrabot.domain.performance.ComboPerformance
import jp.jrabot.domain.performance.ComboSummary
import jp.jrabot.domain.performance.ComboTicketInput
import jp.jrabot.domain.performance.PaperOutcome
import jp.jrabot.domain.performance.PaperSettlement
import jp.jrabot.domain.performance.PerformanceAggregator
import jp.jrabot.domain.performance.PerformanceSummary
import jp.jrabot.domain.performance.SettledCombo
import jp.jrabot.domain.performance.SettledSignal
import jp.jrabot.domain.performance.WinSignal
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** バックテストで評価の対象から外した理由。**黙って落とさず件数と率で報告する**（G-28: 天候・馬場の履歴が無い行など、使えないデータを成績に混ぜない）。 */
enum class ExclusionReason {
    /** 発走時刻が不明。 */
    NO_POST_TIME,

    /** その時点（発走の N 分前）までに単勝オッズの観測が無い。 */
    NO_ODDS_AT_THAT_TIME,

    /** その時点の最新の観測が古すぎる（`maxOddsAge` 超）。 */
    STALE_ODDS,

    /** 着順が確定していない（成績を計算できない）。 */
    NO_RESULT,

    /** 判定が例外で失敗した（特徴量の漏洩検査による拒否を含む）。 */
    JUDGE_FAILED,
}

data class BacktestRequest(
    val tenantId: TenantId,
    val from: LocalDate,
    val to: LocalDate,
    /** 評価する時点（発走の何分前か）。例: 30.0 と 5.0。 */
    val minutesBeforePost: List<Double>,
    val maxOddsAge: Duration,
) {
    init {
        require(!to.isBefore(from)) { "to は from 以降" }
        require(minutesBeforePost.isNotEmpty() && minutesBeforePost.all { it > 0.0 && it.isFinite() }) { "評価の時点は正の有限値" }
        require(!maxOddsAge.isNegative && !maxOddsAge.isZero) { "maxOddsAge は正" }
        require(
            java.time.temporal.ChronoUnit.DAYS
                .between(from, to) <= MAX_DAYS,
        ) { "期間は $MAX_DAYS 日以内" }
    }

    companion object {
        const val MAX_DAYS = 366L
    }
}

data class BacktestReport(
    val request: BacktestRequest,
    /** 対象にしたレース × 時点の数（中止・削除のレースは含めない）。 */
    val candidates: Int,
    /** 判定まで行えた数。 */
    val evaluated: Int,
    val excluded: Map<ExclusionReason, Int>,
    /** 通知（買い目）になったが払戻などが無く損益に数えなかった数（見送りは含めない）。 */
    val unsettledBets: Int,
    val win: PerformanceSummary,
    val combos: List<ComboSummary>,
) {
    val excludedTotal: Int get() = excluded.values.sum()
    val exclusionRate: Double? get() = if (candidates == 0) null else excludedTotal.toDouble() / candidates
}

/**
 * バックテスト（F20）。保存済みの観測（単勝オッズの履歴・着順・払戻）だけから、**過去の時点の判定を再計算**して紙上成績を出す。通知もDBへの書込みもしない（D-234: 計算して返すだけ）。
 *
 * **未来の情報を渡さない**: 時点 `asOf = 発走 − N 分` を決め、① 判定に渡す単勝オッズは `observedAt <= asOf` の最新だけ、② 判定の `now` も `asOf`（特徴量・組オッズは
 * 判定側が `now` 以前だけを読む point-in-time。[jp.jrabot.domain.botrun.RaceEvaluator]）、③ 着順・払戻は判定が終わった後の**決済にだけ**使う（判定には渡さない）。
 * 同じ買い目を複数の時点で推奨した分は、代表集計（発走前の最新 1 件）で二重に数えない。
 */
class BacktestRunner(
    private val schedules: RaceScheduleReader,
    private val odds: OddsSnapshotReader,
    private val results: ResultStore,
    private val payouts: PayoutStore,
    private val judge: RaceJudge,
) {
    fun run(req: BacktestRequest): BacktestReport {
        var candidates = 0
        var evaluated = 0
        var unsettled = 0
        val excluded = LinkedHashMap<ExclusionReason, Int>()
        val winItems = mutableListOf<SettledSignal>()
        val comboItems = mutableListOf<SettledCombo>()

        fun exclude(r: ExclusionReason) {
            excluded[r] = (excluded[r] ?: 0) + 1
        }
        var date = req.from
        while (!date.isAfter(req.to)) {
            for (race in schedules.listByDate(req.tenantId, date)) {
                if (race.status == RaceScheduleStatus.CANCELLED || race.status == RaceScheduleStatus.DELETED) continue
                val postAt = race.scheduledPostAt?.takeIf { !race.postTimeUnknown }
                val finishers = results.list(req.tenantId, race.raceId)
                val hasResult = finishers.any { (it.finish ?: 0) > 0 }
                val history = odds.history(req.tenantId, race.raceId, Market.WIN)
                for (minutes in req.minutesBeforePost) {
                    candidates++
                    if (postAt == null) {
                        exclude(ExclusionReason.NO_POST_TIME)
                        continue
                    }
                    if (!hasResult) {
                        exclude(ExclusionReason.NO_RESULT)
                        continue
                    }
                    val asOf = postAt.minusSeconds(Math.round(minutes * 60.0))
                    val win = history.filter { !it.observedAt.isAfter(asOf) }.maxByOrNull { it.observedAt }
                    if (win == null) {
                        exclude(ExclusionReason.NO_ODDS_AT_THAT_TIME)
                        continue
                    }
                    if (Duration.between(win.observedAt, asOf) > req.maxOddsAge) {
                        exclude(ExclusionReason.STALE_ODDS)
                        continue
                    }
                    val evaluation =
                        try {
                            judge.evaluate(req.tenantId, race.raceId, win, asOf, minutes)
                        } catch (e: Exception) {
                            exclude(ExclusionReason.JUDGE_FAILED)
                            continue
                        }
                    evaluated++
                    for (signal in evaluation.result.signals) {
                        if (signal !is BetSignal) continue // 見送りは損益の対象外
                        settleWin(req, race.raceId, postAt, win.observedAt, signal, finishers)?.let { winItems += it } ?: unsettled++
                        for (entry in signal.betStrategy?.strategies.orEmpty()) {
                            if (entry.type == BetType.WIN) continue
                            val market = if (entry.type == BetType.WIDE) Market.WIDE else Market.SANRENPUKU
                            for (t in entry.tickets) {
                                val stake = t.stake ?: continue
                                if (!(stake > 0.0)) continue
                                val input = ComboTicketInput(race.raceId, market, t.kumi, stake, win.observedAt, postAt)
                                val outcome = ComboPaperSettlement.settle(input, payouts.list(req.tenantId, race.raceId, market), finishers)
                                val key = ComboPaperSettlement.comboKey(market, t.kumi)
                                if (outcome is ComboOutcome.Settled && key != null) {
                                    comboItems += SettledCombo(race.raceId, market, key, win.observedAt, postAt, outcome)
                                } else {
                                    unsettled++
                                }
                            }
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return BacktestReport(
            req,
            candidates,
            evaluated,
            excluded,
            unsettled,
            PerformanceAggregator.representative(winItems),
            ComboPerformance.representative(comboItems),
        )
    }

    private fun settleWin(
        req: BacktestRequest,
        raceId: RaceId,
        postAt: Instant,
        observedAt: Instant,
        s: BetSignal,
        finishers: List<jp.jrabot.domain.ingest.ResultEntry>,
    ): SettledSignal? {
        if (s.betType != BetType.WIN || !(s.betAmount > 0.0)) return null
        val ws = WinSignal(raceId, s.umaban, observedAt, s.odds, s.betAmount)
        val outcome = PaperSettlement.settleWin(ws, finishers, payouts.list(req.tenantId, raceId, Market.WIN))
        return (outcome as? PaperOutcome.Settled)?.let { SettledSignal(raceId, s.umaban, observedAt, postAt, it) }
    }
}
