package jp.jrabot.domain.ml.pipeline

import jp.jrabot.domain.decision.MarketBlend
import jp.jrabot.domain.decision.MarketProbability
import jp.jrabot.domain.decision.PythonCompat

/**
 * `pipeline_version=legacy`: 参照元 `ev/decision.py` の `evaluate_odds` の確率部分（ML 推定 → レース内正規化 → ensemble → 判定側の市場ブレンド → 上限クリップ → 再正規化）を、
 * **分岐の順序・二重ブレンド（H4）・正規化・クリップ・ensemble の合成式**まで同じに再現する。ゴールデン G3〜G5 の比較対象。
 *
 * 旧実装との違い（**数値は変えず、記録だけ直す = 分類 R**）:
 * - ML の失敗を握り潰さず、[FallbackReason] と [FallbackStep] を必ず記録する（E1）。確率の数値は旧実装の市場フォールバックと同じ。
 * - 馬ごとの出どころ（[ProbabilityResult.horseSources]）と正確な [ProbabilityResult.source] を持つ（E2。旧は最後の馬の変数で決まる）。
 *   旧 `prob_source` 文字列は [ProbabilityResult.legacyLabel] に**そのまま**残す（Kelly の確率ソース係数 H10 が文字列の完全一致で決まるため、数値を変えない）。
 * - 重みの次元不一致を暗黙に補正しない（モデルのロードで拒否済み。旧 `_align_feature_matrix` は互換検証専用モジュールにだけある）。
 * 旧実装で再現しないもの: ① `model_type=auto`（ファイルの有無で選ぶ。レジストリの ACTIVE で決める）② legacy 種別でモデルがあるのに特徴量の作成で失敗したとき、
 * 旧実装が 5 市場特徴量だけで予測する『ml:legacy(no_odds)』の経路（市場特徴量のみ・次元のパディング。H2 と同種の無意味な予測）。この場合は市場フォールバックにして理由を記録する。
 * H4（二重ブレンド）・H10（Kelly 係数が常に 0.8）は**未承認のため旧のまま**。
 */
object LegacyProbabilityPipeline {
    fun run(
        modelType: ProbabilityModelType,
        mlEnabled: Boolean,
        odds: List<Double>,
        raw: RawOutputs,
        coverage: List<Double?>?,
        config: PipelineConfig,
    ): ProbabilityResult {
        val n = odds.size
        require(odds.all { it.isFinite() }) { "オッズは有限な数" }
        ProbabilityStages.requireSizes(n, raw.legacy, raw.calibration, raw.alpha)
        require(coverage == null || coverage.size == n) { "カバレッジの数が馬の数と違う" }
        val beta = ProbabilityStages.clampBeta(config.ensembleBeta)

        val steps = mutableListOf<FallbackStep>()
        var preBlend: List<Double>? = null
        var source = ProbSource.MARKET_FALLBACK
        var horseSources: List<ProbSource> = emptyList()
        var label = ""
        // 旧: カバレッジは ensemble 種別のとき（DB とレース ID があれば）ML が成功したかに関係なく馬ごとに求める。取れなかった馬は coverage=1.0・β そのまま
        val coverageScores = List(n) { if (modelType == ProbabilityModelType.ENSEMBLE) ProbabilityStages.coverageOf(coverage, it) else 1.0 }
        val betaEffs = List(n) { if (modelType == ProbabilityModelType.ENSEMBLE && coverage?.getOrNull(it) != null) beta * coverageScores[it] else beta }

        fun note(
            stage: String,
            out: RoleOutput?,
        ): Boolean {
            if (out is RoleOutput.Unavailable) {
                steps += FallbackStep(stage, out.reason, out.detail)
                return true
            }
            return false
        }

        if (!mlEnabled) {
            steps += FallbackStep("ml", FallbackReason.ML_DISABLED)
        } else {
            when (modelType) {
                ProbabilityModelType.LEGACY -> {
                    val out = raw.legacy
                    if (out is RoleOutput.Scored) {
                        preBlend = ProbabilityStages.normalizeWithCorrection(out.probabilities)
                        source = ProbSource.ML_LEGACY
                        label = "ml:legacy"
                    } else if (out == null) {
                        steps += FallbackStep("legacy", FallbackReason.MODEL_NOT_FOUND, "legacy モデルが要求されていない")
                    } else {
                        note("legacy", out)
                        label = "market:fallback(no_model)"
                    }
                }

                ProbabilityModelType.CALIBRATION -> {
                    val out = raw.calibration
                    if (out is RoleOutput.Scored) {
                        preBlend = ProbabilityStages.normalizeWithCorrection(out.probabilities)
                        source = ProbSource.ML_CALIBRATION
                        label = "ml:calibration"
                    } else {
                        note("calibration", out) || steps.add(FallbackStep("calibration", FallbackReason.MODEL_NOT_FOUND, "要求されていない"))
                    }
                }

                ProbabilityModelType.ALPHA -> {
                    val out = raw.alpha
                    if (out is RoleOutput.Scored) {
                        preBlend = ProbabilityStages.normalizeWithCorrection(out.probabilities)
                        source = ProbSource.ML_ALPHA
                        label = "ml:alpha"
                    } else {
                        note("alpha", out) || steps.add(FallbackStep("alpha", FallbackReason.MODEL_NOT_FOUND, "要求されていない"))
                    }
                }

                ProbabilityModelType.ENSEMBLE -> {
                    val alpha = raw.alpha
                    val calib = raw.calibration
                    note("calibration", calib)
                    note("alpha", alpha)
                    // 旧実装: ロードできたモデルの特徴量名が違えば例外 → ML 全体が市場確率へ。無かった（None）モデルは無いものとして続ける。
                    val aborted = steps.any { it.reason.abortsLegacyPipeline }
                    val pa = (alpha as? RoleOutput.Scored)?.probabilities
                    val pc = (calib as? RoleOutput.Scored)?.probabilities
                    if (!aborted && (pa != null || pc != null)) {
                        val finals = List(n) { ProbabilityStages.ensembleOne(odds[it], pc?.get(it), pa?.get(it), betaEffs[it]) }
                        if (PythonCompat.sum(finals) > 0) {
                            preBlend = ProbabilityStages.normalizeWithCorrection(finals)
                            horseSources = List(n) { ProbabilityStages.ensembleSource(odds[it], pc != null, pa != null) }
                            source = if (n > 0) aggregate(horseSources) else ProbSource.MARKET_FALLBACK
                            // 旧のラベルは『ループの最後の馬』の変数で決まる（E2）。数値には影響しない
                            val oddsOkLast = n > 0 && odds[n - 1] > 0.0
                            label =
                                if (oddsOkLast && pc != null && pa != null) {
                                    "ml:ensemble(calib+alpha,beta=${PythonCompat.fixed(beta, 2)})"
                                } else if (oddsOkLast && pc != null) {
                                    "ml:ensemble(calib_only)"
                                } else if (pa != null) {
                                    "ml:ensemble(alpha_only)"
                                } else {
                                    "ml:ensemble(fallback)"
                                }
                        } else {
                            steps += FallbackStep("ensemble", FallbackReason.MODEL_PREDICTION_FAILED, "合成確率の合計が 0 以下")
                        }
                    }
                }
            }
        }

        val isMarket = preBlend == null
        if (isMarket) {
            steps += FallbackStep("market", FallbackReason.MARKET_FALLBACK)
            preBlend = MarketProbability.estimateProbNormalized(odds)
            source = ProbSource.MARKET_FALLBACK
            horseSources = List(n) { ProbSource.MARKET_FALLBACK }
            if (label.isEmpty()) label = "market:fallback(ml_disabled)"
        } else if (horseSources.isEmpty()) {
            horseSources = List(n) { source }
        }

        // 判定側の市場ブレンド（H4: モデル内ブレンドに続く 2 回目）→ 上限クリップ → 再正規化
        val probs =
            MarketBlend.apply(
                preBlend,
                odds,
                PythonCompat.min(PythonCompat.max(config.marketBlendAlpha, 0.0), 1.0),
                config.enableProbClip,
                config.probClipMax,
            )

        val reason = steps.firstOrNull { it.reason != FallbackReason.MARKET_FALLBACK }?.reason ?: if (isMarket) FallbackReason.MARKET_FALLBACK else null
        return ProbabilityResult(
            probs = probs,
            preBlend = preBlend,
            source = source,
            horseSources = horseSources,
            legacyLabel = label,
            fallbackReason = reason,
            steps = steps,
            coverageScores = coverageScores,
            betaEffs = betaEffs,
            version = ProbabilityPipelineVersion.LEGACY,
        )
    }

    /** 全馬が同じ出どころならそれ、混在なら MIXED。 */
    internal fun aggregate(sources: List<ProbSource>): ProbSource = sources.distinct().singleOrNull() ?: ProbSource.MIXED
}
