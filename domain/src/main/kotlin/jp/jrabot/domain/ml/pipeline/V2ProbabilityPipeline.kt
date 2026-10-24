package jp.jrabot.domain.ml.pipeline

import jp.jrabot.domain.decision.MarketBlend
import jp.jrabot.domain.decision.MarketProbability
import jp.jrabot.domain.decision.PythonCompat

/**
 * `pipeline_version=v2`（ML_DESIGN §7.1）: 新仕様の独立実装。**未承認。本番で有効にしない。自動で切り替えない**（D-037。高精度とは未証明）。
 * legacy との違い（旧欠陥の是正案）:
 * 1. **市場ブレンドは 1 回だけ**（H4）。モデル内ブレンドは無効（入力の [RoleOutput.Scored.internalBlendAlpha] が null でなければ拒否）。
 *    市場確率だけのフォールバックには、市場を市場とブレンドする意味が無いのでブレンドもクリップもしない。
 * 2. フォールバックは**段階的で明示**: ensemble（calibration + alpha）→ calibration 単体 → alpha 単体 → legacy_compat → 市場確率。要求した種別からその下へ。
 *    外した段階はすべて [FallbackStep] と [FallbackReason] に残す。
 * 3. 出どころは馬ごとに正確（[ProbabilityResult.horseSources]）。[ProbabilityResult.legacyLabel] は [ProbSource] の名前。
 * 4. alpha は alpha のスキーマ（35 次元）で作った特徴量を使う前提（入力の段階で保証。ここでは確率だけを受け取る）。
 * 他の段階（レース内正規化 ±10% 補正・ensemble の合成式・カバレッジ）は legacy と同じ関数を使う。
 */
object V2ProbabilityPipeline {
    fun run(
        requested: ProbabilityModelType,
        mlEnabled: Boolean,
        odds: List<Double>,
        raw: RawOutputs,
        coverage: List<Double?>?,
        config: PipelineConfig,
    ): ProbabilityResult {
        val n = odds.size
        require(odds.all { it.isFinite() }) { "オッズは有限な数" }
        ProbabilityStages.requireSizes(n, raw.legacy, raw.calibration, raw.alpha)
        for (o in listOf(raw.legacy, raw.calibration, raw.alpha)) {
            if (o is RoleOutput.Scored) require(o.internalBlendAlpha == null) { "v2 はモデル内の市場ブレンドを受け付けない（二重ブレンドの防止）" }
        }
        require(coverage == null || coverage.size == n) { "カバレッジの数が馬の数と違う" }
        val beta = ProbabilityStages.clampBeta(config.ensembleBeta)
        val steps = mutableListOf<FallbackStep>()

        fun unavailable(
            stage: String,
            out: RoleOutput?,
        ) {
            when (out) {
                is RoleOutput.Unavailable -> steps += FallbackStep(stage, out.reason, out.detail)
                null -> steps += FallbackStep(stage, FallbackReason.MODEL_NOT_FOUND, "要求されていない")
                is RoleOutput.Scored -> Unit
            }
        }

        if (!mlEnabled) {
            steps += FallbackStep("ml", FallbackReason.ML_DISABLED)
            return market(odds, steps, beta)
        }

        val pc = (raw.calibration as? RoleOutput.Scored)?.probabilities
        val pa = (raw.alpha as? RoleOutput.Scored)?.probabilities
        val pl = (raw.legacy as? RoleOutput.Scored)?.probabilities

        // 段階: 要求した種別から下へ
        val order =
            when (requested) {
                ProbabilityModelType.ENSEMBLE -> listOf("ensemble", "calibration", "alpha", "legacy")
                ProbabilityModelType.CALIBRATION -> listOf("calibration", "alpha", "legacy")
                ProbabilityModelType.ALPHA -> listOf("alpha", "legacy")
                ProbabilityModelType.LEGACY -> listOf("legacy")
            }
        for (stage in order) {
            when (stage) {
                "ensemble" -> {
                    if (pc != null && pa != null) {
                        val betaEffs = List(n) { beta * ProbabilityStages.coverageOf(coverage, it) }
                        val finals = List(n) { ProbabilityStages.ensembleOne(odds[it], pc[it], pa[it], betaEffs[it]) }
                        val scores = List(n) { ProbabilityStages.coverageOf(coverage, it) }
                        val sources = List(n) { ProbabilityStages.ensembleSource(odds[it], hasCalib = true, hasAlpha = true) }
                        finish(odds, ProbabilityStages.normalizeWithCorrection(finals), sources, steps, config, scores, betaEffs, beta)?.let { return it }
                        steps += FallbackStep("ensemble", FallbackReason.MODEL_PREDICTION_FAILED, "合成確率の合計が 0 以下")
                    } else {
                        if (pc == null) unavailable("ensemble.calibration", raw.calibration)
                        if (pa == null) unavailable("ensemble.alpha", raw.alpha)
                    }
                }

                "calibration" -> {
                    if (pc != null) {
                        val sources = List(n) { ProbSource.ML_CALIBRATION }
                        finish(
                            odds,
                            ProbabilityStages.normalizeWithCorrection(pc),
                            sources,
                            steps,
                            config,
                            List(n) { 1.0 },
                            List(n) { beta },
                            beta,
                        )?.let { return it }
                    } else if (requested != ProbabilityModelType.ENSEMBLE) {
                        unavailable("calibration", raw.calibration)
                    }
                }

                "alpha" -> {
                    if (pa != null) {
                        val sources = List(n) { ProbSource.ML_ALPHA }
                        finish(
                            odds,
                            ProbabilityStages.normalizeWithCorrection(pa),
                            sources,
                            steps,
                            config,
                            List(n) { 1.0 },
                            List(n) { beta },
                            beta,
                        )?.let { return it }
                    } else if (requested != ProbabilityModelType.ENSEMBLE) {
                        unavailable("alpha", raw.alpha)
                    }
                }

                "legacy" -> {
                    if (pl != null) {
                        val sources = List(n) { ProbSource.ML_LEGACY }
                        finish(
                            odds,
                            ProbabilityStages.normalizeWithCorrection(pl),
                            sources,
                            steps,
                            config,
                            List(n) { 1.0 },
                            List(n) { beta },
                            beta,
                        )?.let { return it }
                    } else {
                        unavailable("legacy", raw.legacy)
                    }
                }
            }
        }
        return market(odds, steps, beta)
    }

    /** 単回の市場ブレンド → 上限クリップ → 再正規化で結果を作る。[sources] がすべて同じなら採用、混在は MIXED。 */
    private fun finish(
        odds: List<Double>,
        preBlend: List<Double>,
        sources: List<ProbSource>,
        steps: List<FallbackStep>,
        config: PipelineConfig,
        coverageScores: List<Double>,
        betaEffs: List<Double>,
        beta: Double,
    ): ProbabilityResult? {
        if (preBlend.isEmpty() && odds.isNotEmpty()) return null
        val probs =
            MarketBlend.apply(
                preBlend,
                odds,
                PythonCompat.min(PythonCompat.max(config.marketBlendAlpha, 0.0), 1.0),
                config.enableProbClip,
                config.probClipMax,
            )
        val source = if (sources.isEmpty()) ProbSource.MARKET_FALLBACK else LegacyProbabilityPipeline.aggregate(sources)
        return ProbabilityResult(
            probs = probs,
            preBlend = preBlend,
            source = source,
            horseSources = sources,
            legacyLabel = source.name,
            fallbackReason = steps.firstOrNull()?.reason,
            steps = steps.toList(),
            coverageScores = coverageScores,
            betaEffs = betaEffs,
            version = ProbabilityPipelineVersion.V2,
        )
    }

    /** 市場確率だけ。ブレンドもクリップもしない。 */
    private fun market(
        odds: List<Double>,
        steps: MutableList<FallbackStep>,
        beta: Double,
    ): ProbabilityResult {
        steps += FallbackStep("market", FallbackReason.MARKET_FALLBACK)
        val p = MarketProbability.estimateProbNormalized(odds)
        return ProbabilityResult(
            probs = p,
            preBlend = p,
            source = ProbSource.MARKET_FALLBACK,
            horseSources = List(odds.size) { ProbSource.MARKET_FALLBACK },
            legacyLabel = ProbSource.MARKET_FALLBACK.name,
            fallbackReason = steps.first().reason,
            steps = steps.toList(),
            coverageScores = List(odds.size) { 1.0 },
            betaEffs = List(odds.size) { beta },
            version = ProbabilityPipelineVersion.V2,
        )
    }
}
