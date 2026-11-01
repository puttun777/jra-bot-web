package jp.jrabot.domain.botrun.testing

import jp.jrabot.domain.botrun.RaceEvaluation
import jp.jrabot.domain.botrun.RaceJudge
import jp.jrabot.domain.decision.BetSignal
import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.decision.DecisionHorse
import jp.jrabot.domain.decision.DecisionSignal
import jp.jrabot.domain.decision.Ev
import jp.jrabot.domain.decision.FeatureCoverage
import jp.jrabot.domain.decision.ProbRaw
import jp.jrabot.domain.decision.Ratio
import jp.jrabot.domain.decision.WinDecisionResult
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

/** 固定の買い目（単勝の購入候補 2 つ）を返す偽物の判定。市場確率だけの経路では期待値が常に負（旧の仕様）で買い目が出ないため、通知までの接続の検証に使う。 */
class StubJudge(
    private val signalsFor: (RaceId) -> List<DecisionSignal> = { listOf(bet(2), bet(4, odds = 15.0, amount = 1.0)) },
) : RaceJudge {
    var calls = 0

    /** 判定の最中に呼ばれる（SIGTERM・例外の注入に使う）。 */
    var onEvaluate: (RaceId) -> Unit = {}

    override fun evaluate(
        tenantId: TenantId,
        raceId: RaceId,
        win: OddsSnapshot,
        now: Instant,
        minutesToPost: Double,
    ): RaceEvaluation {
        calls++
        onEvaluate(raceId)
        val horses = win.entries.map { DecisionHorse(it.umaban.value, it.odds.value, it.popularity ?: 0) }
        val result = WinDecisionResult(signalsFor(raceId), null, emptyList(), "market:fallback(ml_disabled)")
        return RaceEvaluation(raceId, win.observedAt, horses, result, null, "market", null, FallbackReason.ML_DISABLED, false)
    }

    companion object {
        fun bet(
            umaban: Int,
            odds: Double = 8.0,
            amount: Double = 3.0,
        ) = BetSignal(
            umaban = umaban,
            betType = BetType.WIN,
            odds = odds,
            popularity = umaban,
            prob = ProbRaw(0.2),
            ev = Ev(0.6),
            betAmount = amount,
            probSource = "market:fallback(ml_disabled)",
            pModel = ProbRaw(0.2),
            pMarket = 0.125,
            pFinal = ProbRaw(0.2),
            pMkt = 0.125,
            ratio = Ratio(1.6),
            expectedReturn = 1.6,
            coverage = FeatureCoverage(0.0, 0.35, false, false, 0),
            calcExpectedReturn = 1.6,
            calcEv = Ev(0.6),
            calcPMkt = 0.125,
            calcRatio = Ratio(1.6),
            reason = "",
            betStrategy = null,
        )
    }
}
