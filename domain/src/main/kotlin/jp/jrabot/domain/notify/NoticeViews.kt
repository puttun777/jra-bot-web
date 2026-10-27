package jp.jrabot.domain.notify

import jp.jrabot.domain.decision.BetSignal
import jp.jrabot.domain.decision.BetStrategyBundle
import jp.jrabot.domain.decision.ComboTicket
import jp.jrabot.domain.decision.DecisionSignal
import jp.jrabot.domain.decision.OddsSource
import jp.jrabot.domain.decision.SkipSignal
import jp.jrabot.domain.decision.StrategyEntry

// 通知本文の入力（読取り専用の表示モデル）。判定サービスが出した結果をそのまま運ぶだけで、EV・Kelly・確率を**再計算しない**。
// 値が無いものは null で、本文には出さない（0 などの架空の数値で埋めない）。金額の単位は旧実装の「点」（円ではない・自動投票ではない）。

/** レースの表示名。[raceNo] は数字だけ（`R` は本文側で付ける）。 */
data class RaceLabel(
    val track: String,
    val raceNo: Int?,
    val name: String,
)

/** 特徴量カバレッジ（旧 `coverage_score` ほか）。[betaEff] が null なら動的 β は出さない。 */
data class CoverageView(
    val coverageScore: Double,
    val betaEff: Double?,
    val hasHorseMaster: Boolean,
    val hasPastRuns: Boolean,
    val pastN: Int,
)

/** 買い目 1 点。[oddsText] は旧 `odds_note`（実オッズは `3.2〜5.1`、推定は `12.3（推定）`）。[prob] は賭けの判断に使った確率。 */
data class TicketView(
    val kumi: String,
    val oddsText: String,
    val prob: Double,
    val ev: Double,
    val stake: Double?,
)

/** Kelly の内訳（旧 `kelly_raw` ほか）。[skipReason] があれば見送り。 */
data class KellyView(
    val raw: Double,
    val adjusted: Double,
    val betBeforeClip: Double,
    val clipped: Boolean,
    val skipReason: String?,
    val probUsedForBet: Double?,
)

data class StrategyView(
    val type: String,
    val suggestedBet: Double,
    val expectedProfit: Double,
    val odds: Double,
    /** 表示用の生の確率（旧 `strategy["prob"]`）。 */
    val prob: Double,
    val ev: Double,
    val note: String?,
    val tickets: List<TicketView>,
    val kelly: KellyView?,
)

data class BestView(
    val type: String,
    val ev: Double,
    val suggestedBet: Double,
)

data class BundleView(
    val strategies: List<StrategyView>,
    val best: BestView?,
    val totalSuggestedBet: Double,
    val totalExpectedProfit: Double,
)

/**
 * 1 シグナル（購入候補または見送り）の表示モデル。[skipReason] があれば見送り（[betAmount] は表示しない）。
 * [pMkt]・[ratio]・[expectedReturn] が null/0 のときは、旧実装と同じく [odds]・[prob] から導出して表示する（導出できないときは行を出さない）。
 */
data class SignalView(
    val betType: String,
    val umaban: Int,
    val odds: Double,
    val prob: Double,
    val ev: Double,
    val pMkt: Double? = null,
    val ratio: Double? = null,
    val expectedReturn: Double? = null,
    val probSource: String? = null,
    val coverage: CoverageView? = null,
    val betAmount: Double? = null,
    val reason: String? = null,
    val skipReason: String? = null,
    val payoutRate: Double? = null,
    val showAdjustedMkt: Boolean = false,
    val bundle: BundleView? = null,
)

/** 判定サービスの結果（[DecisionSignal]）から表示モデルへ。値の変換だけで、数値は変えない。 */
object NoticeViews {
    fun from(signal: DecisionSignal): SignalView =
        when (signal) {
            is BetSignal ->
                SignalView(
                    betType = signal.betType.label,
                    umaban = signal.umaban,
                    odds = signal.odds,
                    prob = signal.prob.value,
                    ev = signal.ev.value,
                    pMkt = signal.pMkt,
                    ratio = signal.ratio.value,
                    expectedReturn = signal.expectedReturn,
                    probSource = signal.probSource,
                    coverage =
                        CoverageView(
                            coverageScore = signal.coverage.coverageScore,
                            betaEff = signal.coverage.betaEff,
                            hasHorseMaster = signal.coverage.hasHorseMaster,
                            hasPastRuns = signal.coverage.hasPastRuns,
                            pastN = signal.coverage.pastN,
                        ),
                    betAmount = signal.betAmount,
                    reason = signal.reason,
                    bundle = signal.betStrategy?.let(::bundle),
                )

            is SkipSignal ->
                SignalView(
                    betType = signal.betType.label,
                    umaban = signal.umaban,
                    odds = signal.odds,
                    prob = signal.prob.value,
                    ev = signal.ev.value,
                    probSource = signal.probSource,
                    betAmount = signal.betAmount,
                    skipReason = signal.skipReason ?: "見送り",
                    payoutRate = signal.payoutRate,
                    showAdjustedMkt = signal.showAdjustedMkt,
                    bundle = bundle(signal.betStrategy),
                )
        }

    private fun bundle(b: BetStrategyBundle): BundleView =
        BundleView(
            strategies = b.strategies.map(::strategy),
            best = BestView(b.best.type.label, b.best.ev.value, b.best.suggestedBet),
            totalSuggestedBet = b.totalSuggestedBet,
            totalExpectedProfit = b.totalExpectedProfit,
        )

    private fun strategy(e: StrategyEntry): StrategyView =
        StrategyView(
            type = e.type.label,
            suggestedBet = e.suggestedBet,
            expectedProfit = e.expectedProfit,
            odds = e.odds,
            prob = e.probRaw.value,
            ev = e.ev.value,
            note = e.note,
            tickets = e.tickets.map(::ticket),
            kelly = KellyView(e.kellyRaw, e.kellyAdjusted, e.kellyBetBeforeClip, e.clipped, e.skipReason, e.probUsed.value),
        )

    /** 推定オッズの買い目は、旧 `odds_note` に『（推定）』が無ければ付けて**実オッズと区別**する（実オッズに見せない）。 */
    private fun ticket(t: ComboTicket): TicketView {
        val text = if (t.source == OddsSource.ESTIMATED && !t.oddsNote.contains("推定")) "${t.oddsNote}（推定）" else t.oddsNote
        return TicketView(t.kumi, text, t.probUsed.value, t.ev.value, t.stake)
    }
}
