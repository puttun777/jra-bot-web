package jp.jrabot.domain.botrun

import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.decision.ComboOdds
import jp.jrabot.domain.decision.DecisionHorse
import jp.jrabot.domain.decision.FeatureCoverage
import jp.jrabot.domain.decision.MlProbabilityInput
import jp.jrabot.domain.decision.OddsPopularitySnapshot
import jp.jrabot.domain.decision.RecentSnapshotsProvider
import jp.jrabot.domain.decision.SanrenpukuKey
import jp.jrabot.domain.decision.SnapshotsNewestFirst
import jp.jrabot.domain.decision.WideKey
import jp.jrabot.domain.decision.WideOddsRange
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.decision.WinDecisionEvaluator
import jp.jrabot.domain.decision.WinDecisionResult
import jp.jrabot.domain.ml.FeatureProvider
import jp.jrabot.domain.ml.HorseOdds
import jp.jrabot.domain.ml.MlInference
import jp.jrabot.domain.ml.features.FeatureRequest
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.FeatureVector
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.ml.pipeline.PipelineConfig
import jp.jrabot.domain.ml.pipeline.ProbSource
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.pipeline.ProbabilityPipelineVersion
import jp.jrabot.domain.ml.pipeline.ProbabilityResult
import jp.jrabot.domain.odds.ComboOddsSnapshot
import jp.jrabot.domain.odds.ComboOddsSnapshotReader
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant

/**
 * run で**固定した** ML の状態（[jp.jrabot.domain.ml.registry.ModelRunPin] で開始時に 1 回だけ固定。run の間は変わらない）。
 * [inference] が null のときは ML 無効（`mlModelType = null`）。有効でモデルが取れない・検証に失敗したときも [inference] は non-null で、
 * 取れなかったモデルは `Missing(理由)` として入っているので、パイプラインが**理由つき**で市場確率にフォールバックする（黙って成功にしない）。
 */
class MlRunContext(
    val modelType: ProbabilityModelType?,
    val inference: MlInference?,
    /** 固定したモデルの ID（`race_evaluations.model_id`）。ensemble なら ensemble_set の ID。固定できなかった・無効なら null。 */
    val pinnedModelId: String?,
    /** 固定の時点で分かっている理由（レジストリや保管先の障害など）。ログ用。 */
    val startupNote: String? = null,
) {
    init {
        require((modelType == null) == (inference == null)) { "modelType と inference は両方 null か両方 non-null" }
    }

    companion object {
        val DISABLED = MlRunContext(null, null, null)
    }
}

/** 1 レース・1 回の判定の結果。[snapshotAt] は評価した単勝オッズの観測時刻（`race_evaluations.snapshot_at` と Signal の `observed_at`）。 */
data class RaceEvaluation(
    val raceId: RaceId,
    val snapshotAt: Instant,
    val horses: List<DecisionHorse>,
    val result: WinDecisionResult,
    /** ML を使った（有効だった）ときの確率の結果。無効なら null。 */
    val probability: ProbabilityResult?,
    /** `race_evaluations.model_type_used`（`legacy`・`ensemble`・… または ML が使えなかったとき `market`）。 */
    val modelTypeUsed: String,
    val modelId: String?,
    val fallbackReason: FallbackReason?,
    /** ワイド・三連複の実オッズを使えた（新鮮なものがあった）か。 */
    val comboRealOdds: Boolean,
) {
    /** ML を有効にしたのに市場確率だけになった（アラート対象）。 */
    val isMlMarketFallback: Boolean get() = probability?.isMarketFallback == true
}

/** 1 レースの判定の口（[RaceCycleProcessor] が使う。実装は [RaceEvaluator]。テストでは固定の結果を返す偽物に差し替える）。 */
fun interface RaceJudge {
    fun evaluate(
        tenantId: TenantId,
        raceId: RaceId,
        win: OddsSnapshot,
        now: Instant,
        isNearTiming: Boolean,
    ): RaceEvaluation
}

/**
 * 1 レースの判定（オッズの取得 → 特徴量 → 確率 → EV・Kelly・買い目）。**既存の部品を正しい順序で接続するだけ**で、数値は変えない。
 *
 * 1. 単勝の最新スナップショット [win] から馬ごとのオッズ・人気（人気が無い馬は 0。旧実装の人気の欠損と同じく『順位なし』）。
 * 2. 組み合わせオッズ（ワイド・三連複）は、観測が [maxOddsAge] 以内の最新スナップショットだけを**実オッズ**として渡す
 *    （古い・無いものは渡さない = 『実オッズ未取得』。推定オッズに黙って置き換わるかは `requireRealOddsForCombo` の設定どおり）。
 * 3. 急落・人気急上昇の判定用に、馬ごとの直近スナップショット（新しい順、最大 [BotRunConfig.snapshotHistoryLimit] 件、評価した観測以前）。
 * 4. ML 有効なら、**point-in-time** の特徴量（評価時刻 [now] 以前の情報だけ）で [MlInference] → [ProbabilityResult] を作り、[MlProbabilityInput] として
 *    判定へ渡す。確率源のラベルは legacy パイプラインの旧ラベル（Kelly の係数 H10 が文字列で決まるため）。ML 無効なら市場確率の経路（T3.1/T3.2 と同一）。
 * 5. 判定は [WinDecisionEvaluator]（未承認の数値変更 `fixes` は設定の段階で拒否される）。
 *
 * 評価時刻 [now] は発走時刻より前でなければならない（特徴量が [jp.jrabot.domain.ml.features.LeakageException] で拒否する。拒否された馬のモデルは
 * `MODEL_PREDICTION_FAILED` として市場確率にフォールバックし、理由が記録される）。IO は読取りだけ。
 */
class RaceEvaluator(
    private val config: BotRunConfig,
    private val odds: OddsSnapshotReader,
    private val comboOdds: ComboOddsSnapshotReader,
    private val features: FeatureSource,
    private val ml: MlRunContext,
) : RaceJudge {
    override fun evaluate(
        tenantId: TenantId,
        raceId: RaceId,
        win: OddsSnapshot,
        now: Instant,
        isNearTiming: Boolean,
    ): RaceEvaluation {
        require(win.tenantId == tenantId && win.raceId == raceId && win.market == Market.WIN) { "単勝のスナップショットがレースと合わない" }
        val horses = win.entries.sortedBy { it.umaban.value }.map { DecisionHorse(it.umaban.value, it.odds.value, it.popularity ?: 0) }
        val history = odds.history(tenantId, raceId, Market.WIN).filter { !it.observedAt.isAfter(win.observedAt) }
        val decision = decisionConfig(isNearTiming).copy(raceId = raceId.value)

        val real = realCombo(tenantId, raceId, now)
        val oddsHistory = oddsPoints(history)

        var probability: ProbabilityResult? = null
        var mlInput: MlProbabilityInput? = null
        var cfg = decision
        val modelType = ml.modelType
        val inference = ml.inference
        if (modelType != null && inference != null) {
            val builder = PointInTimeFeatureBuilder(features)
            // 同じ (スキーマ, 馬) の特徴量は 1 回だけ作る（DB の読取りを増やさない。失敗も同じ結果を返す）
            val memo = HashMap<Pair<FeatureSchema, Int>, Result<FeatureVector>>()
            val provider =
                FeatureProvider { schema: FeatureSchema, index: Int ->
                    memo
                        .getOrPut(schema to index) {
                            val h = horses[index]
                            runCatching { builder.build(schema, FeatureRequest(tenantId, raceId.value, h.umaban, now, oddsHistory[h.umaban].orEmpty())) }
                        }.getOrThrow()
                }
            val pc = PipelineConfig(decision.marketBlendAlpha, decision.ensembleBeta.coerceIn(0.0, 1.0), decision.enableProbClip, decision.probClipMax)
            val result =
                inference.probabilities(
                    ProbabilityPipelineVersion.LEGACY,
                    modelType,
                    true,
                    horses.map { HorseOdds(it.umaban, it.odds, it.popularity) },
                    provider,
                    pc,
                )
            probability = result
            val coverage =
                horses.indices.associate { i ->
                    val meta = runCatching { provider.vector(FeatureSchema.CALIBRATION_40, i).meta }.getOrNull()
                    horses[i].umaban to
                        FeatureCoverage(
                            result.coverageScores[i],
                            result.betaEffs[i],
                            meta?.hasHorseMaster ?: false,
                            meta?.hasPastRuns ?: false,
                            meta?.pastN ?: 0,
                        )
                }
            mlInput = MlProbabilityInput(result.probs, coverage)
            cfg = decision.copy(probSource = result.legacyLabel)
        }

        val snapshots = snapshotsProvider(history, horses)
        val evaluated = WinDecisionEvaluator(cfg, snapshots).evaluate(horses, real.odds, mlInput)
        val fallback = probability?.fallbackReason ?: if (modelType == null) FallbackReason.ML_DISABLED else null
        val usedMl = probability != null && probability.source != ProbSource.MARKET_FALLBACK
        return RaceEvaluation(
            raceId = raceId,
            snapshotAt = win.observedAt,
            horses = horses,
            result = evaluated,
            probability = probability,
            modelTypeUsed = if (usedMl) modelType!!.name.lowercase() else "market",
            modelId = if (usedMl) ml.pinnedModelId else null,
            fallbackReason = fallback,
            comboRealOdds = real.any,
        )
    }

    /** 旧の『5 分前の場合はフィルタを強化』（`drop_threshold × 0.7`、正の `*_max_over_market × 0.8`）。[isNearTiming] が true のときだけ。 */
    internal fun decisionConfig(isNearTiming: Boolean): WinDecisionConfig {
        if (!isNearTiming) return config.decision
        val d = config.decision

        fun tighten(v: Double): Double = if (v > 0.0) v * NEAR_OVER_MARKET_FACTOR else v
        return d.copy(
            dropThreshold = d.dropThreshold * NEAR_DROP_FACTOR,
            winMaxOverMarket = d.winMaxOverMarket?.let(::tighten),
            combo =
                d.combo.copy(
                    wideMaxOverMarket = tighten(d.combo.wideMaxOverMarket),
                    sanrenpukuMaxOverMarket = tighten(d.combo.sanrenpukuMaxOverMarket),
                ),
        )
    }

    private class RealCombo(
        val odds: ComboOdds,
        val any: Boolean,
    )

    private fun realCombo(
        tenantId: TenantId,
        raceId: RaceId,
        now: Instant,
    ): RealCombo {
        val wide = freshLatest(comboOdds.history(tenantId, raceId, Market.WIDE), now)
        val trio = freshLatest(comboOdds.history(tenantId, raceId, Market.SANRENPUKU), now)
        val wideMap = LinkedHashMap<WideKey, WideOddsRange>()
        wide?.rows?.forEach { r ->
            val parts = r.comboKey.split('-').mapNotNull { it.toIntOrNull() }
            if (parts.size == 2 && parts.all { it > 0 } &&
                parts[0] != parts[1]
            ) {
                wideMap[WideKey.of(parts[0], parts[1])] = WideOddsRange(r.odds.value, (r.oddsHigh ?: r.odds).value)
            }
        }
        val trioMap = LinkedHashMap<SanrenpukuKey, Double>()
        trio?.rows?.forEach { r ->
            val parts = r.comboKey.split('-').mapNotNull { it.toIntOrNull() }
            if (parts.size == 3 && parts.all { it > 0 } && parts.toSet().size == 3) trioMap[SanrenpukuKey.of(parts[0], parts[1], parts[2])] = r.odds.value
        }
        return RealCombo(ComboOdds(wideMap, trioMap), wideMap.isNotEmpty() || trioMap.isNotEmpty())
    }

    private fun freshLatest(
        snapshots: List<ComboOddsSnapshot>,
        now: Instant,
    ): ComboOddsSnapshot? {
        val latest = snapshots.filter { !it.collectedAt.isAfter(now) }.maxByOrNull { it.collectedAt } ?: return null
        return if (Duration.between(latest.collectedAt, now) <= config.maxOddsAge) latest else null
    }

    private fun oddsPoints(history: List<OddsSnapshot>): Map<Int, List<OddsPoint>> {
        val out = LinkedHashMap<Int, MutableList<OddsPoint>>()
        for (s in history) {
            for (e in s.entries) out.getOrPut(e.umaban.value) { mutableListOf() } += OddsPoint(s.observedAt, e.odds.value, e.popularity ?: 0)
        }
        return out
    }

    /** 旧 `get_recent_snapshots(race_id, umaban, limit)`: その馬の直近 [BotRunConfig.snapshotHistoryLimit] 件、新しい順。 */
    private fun snapshotsProvider(
        history: List<OddsSnapshot>,
        horses: List<DecisionHorse>,
    ): RecentSnapshotsProvider {
        val byHorse = HashMap<Int, SnapshotsNewestFirst>()
        for (h in horses) {
            val items =
                history
                    .sortedByDescending { it.observedAt }
                    .mapNotNull { s -> s.entries.firstOrNull { it.umaban.value == h.umaban } }
                    .take(config.snapshotHistoryLimit)
                    .map { OddsPopularitySnapshot(it.odds.value, it.popularity ?: 0) }
            byHorse[h.umaban] = SnapshotsNewestFirst.ofNewestFirst(items)
        }
        return RecentSnapshotsProvider { umaban -> byHorse[umaban] ?: SnapshotsNewestFirst.EMPTY }
    }

    companion object {
        const val NEAR_DROP_FACTOR = 0.7
        const val NEAR_OVER_MARKET_FACTOR = 0.8
    }
}

/** [BetType] の賭式コード（`signals.market`）。 */
internal fun BetType.market(): Market = Market.fromCodeOrNull(marketCode) ?: error("未知の賭式: $this")
