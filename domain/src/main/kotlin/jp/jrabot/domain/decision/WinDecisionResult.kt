package jp.jrabot.domain.decision

/**
 * 候補を採用しなかった理由。旧実装は一部の除外しか集計しない（E3）。全経路に理由を付け（分類 R。数値は変えない）、
 * 旧集計との対応を [legacyCounter] に持つ（null は旧実装が集計しない経路）。
 */
enum class DecisionReason(
    val legacyCounter: String?,
) {
    /** hit_rate: `odds > win_odds_max`。 */
    WIN_ODDS_ABOVE_MAX("odds_cap"),

    /** hit_rate: `p_final < win_prob_min`。 */
    WIN_PROB_BELOW_MIN("prob_below"),

    /** hit_rate: `p_final / 市場確率 < win_ratio_min`。 */
    WIN_RATIO_BELOW_MIN("ratio_below"),

    /** オッズ急落（旧 `drop_filtered` に急落と人気急上昇の両方が入る）。 */
    ODDS_DROP("drop_filtered"),

    /** 人気急上昇。 */
    POPULARITY_SURGE("drop_filtered"),

    /** `odds == 1.0` で Kelly が 0 除算（E5。旧実装は理由なしで除外）。 */
    ODDS_AT_PAR(null),

    /** 戦略が作れなかった（対象の賭式が無い）。旧実装は理由なしで除外。 */
    NO_STRATEGY(null),

    /** `best.ev <= 閾値`（閾値ちょうどは除外）。 */
    EV_NOT_ABOVE_THRESHOLD("ev_below"),

    /** 推奨賭け額が 0（Kelly 推奨額が min_bet 未満など）。旧集計の名前は `kelly_below_minbet`。 */
    NO_STAKE("kelly_below_minbet"),

    /** 単勝の `p / 市場確率 > win_max_over_market`。旧実装は理由なしで除外。 */
    WIN_OVER_MARKET(null),
}

data class Rejection(
    val umaban: Int,
    val reason: DecisionReason,
)

/** 旧 `reject_counts`（旧実装が集計する 6 項目と `other`）。E3 の経路は含まない（同じ数値を保つ）。 */
data class LegacyRejectCounts(
    val evBelow: Int = 0,
    val oddsCap: Int = 0,
    val probBelow: Int = 0,
    val ratioBelow: Int = 0,
    val dropFiltered: Int = 0,
    val kellyBelowMinbet: Int = 0,
    val other: Int = 0,
)

/** ML 特徴量カバレッジの表示項目（旧 `coverage_score` ほか）。T3.1 では特徴量を使わないので固定値（ML は T3.6）。 */
data class FeatureCoverage(
    val coverageScore: Double,
    val betaEff: Double,
    val hasHorseMaster: Boolean,
    val hasPastRuns: Boolean,
    val pastN: Int,
)

sealed interface DecisionSignal {
    val umaban: Int
    val betType: BetType

    /** 戦略（最推奨）の EV。[ProbUsed] 基準。 */
    val ev: Ev
    val probSource: String
}

/**
 * 通知対象の単勝シグナル（旧実装のシグナル dict）。
 *
 * 確率・EV・比の基準（混同しない）:
 * - [prob] = [pModel] = [pFinal] = [ProbRaw]（ブレンド・クリップ・再正規化後。縮小前）。
 * - [ev] は [ProbUsed]（縮小後）基準。[calcEv] は [ProbRaw] 基準の検算値（`_calc_ev`。縮小するとずれる: E4）。
 * - [ratio] は [ProbRaw] ÷ 市場確率 `1/max(odds, 0.1)`（[pMarket]）。[calcRatio] は戦略の odds で作り直した検算値。
 * - [betAmount] は「点」。
 */
data class BetSignal(
    override val umaban: Int,
    override val betType: BetType,
    val odds: Double,
    val popularity: Int,
    val prob: ProbRaw,
    override val ev: Ev,
    val betAmount: Double,
    override val probSource: String,
    val pModel: ProbRaw,
    val pMarket: Double,
    val pFinal: ProbRaw,
    val pMkt: Double,
    val ratio: Ratio,
    val expectedReturn: Double,
    val coverage: FeatureCoverage,
    val calcExpectedReturn: Double,
    val calcEv: Ev,
    val calcPMkt: Double,
    val calcRatio: Ratio,
    val reason: String,
    val betStrategy: BetStrategyBundle?,
) : DecisionSignal

/** `notify_skips=true` のときだけ返る、見送り理由つきのシグナル（投資額は 0）。上限縮小が適用されると [betAmount] = 0.0 が入る（旧実装のとおり）。 */
data class SkipSignal(
    val raceId: String?,
    override val umaban: Int,
    override val betType: BetType,
    override val ev: Ev,
    val odds: Double,
    val prob: ProbRaw,
    override val probSource: String,
    val betStrategy: BetStrategyBundle,
    val skipReason: String?,
    val payoutRate: Double?,
    val showAdjustedMkt: Boolean,
    val betAmount: Double? = null,
) : DecisionSignal

/**
 * 判定結果。[signals] は選別・上限縮小後の最終出力、[legacyRejectCounts] は旧 `reject_counts`（入力が空のときは旧実装が配列だけを返すので null）、
 * [rejections] は全経路の理由コード（E3 対応: 旧集計に無い経路も含む）。
 */
data class WinDecisionResult(
    val signals: List<DecisionSignal>,
    val legacyRejectCounts: LegacyRejectCounts?,
    val rejections: List<Rejection>,
    val probSource: String,
    /** 直近スナップショットの取得に失敗した馬番。旧実装は握り潰してフィルタなしで続行する（その挙動のまま、失敗を記録する分類 R の追加）。 */
    val snapshotFilterErrors: List<Int> = emptyList(),
)
