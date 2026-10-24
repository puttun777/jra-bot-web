package jp.jrabot.golden

import jp.jrabot.domain.decision.DecisionHorse
import jp.jrabot.domain.decision.FeatureCoverage
import jp.jrabot.domain.decision.MlProbabilityInput
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ml.features.EntryHorse
import jp.jrabot.domain.ml.features.FeatureMeta
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.LegacyDates
import jp.jrabot.domain.ml.features.LegacyFeatureBuilder
import jp.jrabot.domain.ml.features.LegacyRows
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.PedigreeHash
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.model.LogisticScoring
import jp.jrabot.domain.ml.model.PlattCalibration
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.ml.pipeline.LegacyProbabilityPipeline
import jp.jrabot.domain.ml.pipeline.PipelineConfig
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.pipeline.RawOutputs
import jp.jrabot.domain.ml.pipeline.RoleOutput
import jp.jrabot.domain.tenant.TenantId
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln

/**
 * ML のゴールデン（G1〜G7）の Kotlin 側。**旧実装の互換検証専用**: 旧 `WinProbabilityModel` の『重みの次元への切詰め/ゼロ埋め』（`_align_feature_matrix`）は、
 * 安全なモデルのロード契約（`:domain` の `ModelLoader`。次元が違えば拒否）とは別物なので、`:domain` ではなくここ（検証専用モジュール）にだけ置く。
 * 本番の経路（bot / ingest / dashboard）はこのモジュールに依存しない。
 */
internal object GoldenMl {
    private val tenant = TenantId.of(1)

    // ---- G2: ハッシュ特徴 -----------------------------------------------------------------------------------------

    fun hashBucket(a: JsonNode): Any = PedigreeHash.bucket(a.get("s").stringValue(), a.int("dim"))

    fun hashSign(a: JsonNode): Any = PedigreeHash.sign(a.get("s").stringValue())

    // ---- G1: 旧 WinProbabilityModel（切詰め/ゼロ埋めを含む）-------------------------------------------------------------

    fun modelExtractFeatures(a: JsonNode): Any = listOf(LegacyModel.extractFeatures(a.double("odds"), a.int("popularity")).toList())

    fun modelPredict(a: JsonNode): Any {
        val m = LegacyModel.of(a.get("model"))
        return m.predict(a.double("odds"), a.int("popularity"))
    }

    fun modelPredictBatch(a: JsonNode): Any {
        val m = LegacyModel.of(a.get("model"))
        return m.predictBatch(a.doubleList("odds_list"), a.get("popularity_list").values().map { it.asInt("popularity") })
    }

    /** 旧 `WinProbabilityModel`（推論部分）。`weights=null` は未学習。 */
    class LegacyModel(
        val weights: DoubleArray?,
        val bias: Double,
        val platt: PlattCalibration?,
        var marketBlendAlpha: Double,
        val featureNames: String = "default",
    ) {
        /** 旧 `_align_feature_matrix`: 長ければ先頭から切詰め、短ければ末尾をゼロ埋め。 */
        fun align(x: DoubleArray): DoubleArray {
            val w = weights ?: return x
            return if (x.size == w.size) {
                x
            } else if (x.size > w.size) {
                x.copyOf(w.size)
            } else {
                x.copyOf(w.size) // copyOf は不足分を 0.0 で埋める
            }
        }

        fun predictProbaArray(rows: List<DoubleArray>): List<Double> {
            val w = weights ?: return rows.map { 0.0 }
            return rows.map { LogisticScoring.sigmoid(LogisticScoring.linear(align(it), w, bias)) }
        }

        /** 旧 `predict_proba_from_features`（キャリブレーションと市場ブレンドを適用。odds は 0 列目）。 */
        fun predictProbaFromFeatures(rows: List<DoubleArray>): List<Double> {
            val p = predictProbaArray(rows)
            return rows.mapIndexed { i, x -> LogisticScoring.postprocess(p[i], if (x.isNotEmpty()) x[0] else 0.0, platt, marketBlendAlpha) }
        }

        fun predict(
            odds: Double,
            popularity: Int,
        ): Double {
            if (weights == null) return 1.0 / pyMax(odds, 0.1)
            val prob = predictProbaArray(listOf(extractFeatures(odds, popularity)))[0]
            val p2 = LogisticScoring.postprocess(prob, odds, platt, marketBlendAlpha)
            return pyMax(0.0, pyMin(1.0, p2))
        }

        fun predictBatch(
            oddsList: List<Double>,
            popularityList: List<Int>,
        ): List<Double> {
            if (weights == null) return oddsList.map { 1.0 / pyMax(it, 0.1) }
            val rows = oddsList.zip(popularityList).map { (o, p) -> extractFeatures(o, p) }
            val probs = predictProbaArray(rows)
            return probs.zip(oddsList).map { (p, o) -> pyMax(0.0, pyMin(1.0, LogisticScoring.postprocess(p, o, platt, marketBlendAlpha))) }
        }

        companion object {
            /** 旧 `extract_features`（5 市場特徴量）。odds ≤ 0 は 100.0。 */
            fun extractFeatures(
                oddsIn: Double,
                popularity: Int,
            ): DoubleArray {
                val odds = if (oddsIn <= 0) 100.0 else oddsIn
                return doubleArrayOf(odds, popularity.toDouble(), ln(pyMax(odds, 0.1)), 1.0 / maxOf(popularity, 1).toDouble(), odds * popularity)
            }

            fun of(spec: JsonNode): LegacyModel {
                val w = spec.get("weights")
                val a = spec.get("calib_a")
                val b = spec.get("calib_b")
                return LegacyModel(
                    weights = if (w == null || w.isNull) null else w.values().map { it.asDouble("weights[]") }.toDoubleArray(),
                    bias = spec.get("bias")?.asDouble("bias") ?: 0.0,
                    platt = if (a != null && !a.isNull && b != null && !b.isNull) PlattCalibration(a.asDouble("calib_a"), b.asDouble("calib_b")) else null,
                    marketBlendAlpha = spec.get("market_blend_alpha")?.asDouble("market_blend_alpha") ?: 1.0,
                    featureNames = spec.get("feature_names")?.stringValue() ?: "default",
                )
            }
        }
    }

    private fun pyMax(
        a: Double,
        b: Double,
    ): Double = if (b > a) b else a

    private fun pyMin(
        a: Double,
        b: Double,
    ): Double = if (b < a) b else a

    // ---- 旧 SQLite の合成行を FeatureSource にする ---------------------------------------------------------------------

    /** 旧 SQLite と同じ読み方（`race_id` / `ketto_num` の完全一致、過去走は `date DESC, race_id DESC`・NULL は最後・テキストの二進比較）。 */
    class LegacySqliteSource(
        db: JsonNode,
    ) : FeatureSource {
        private class RunRow(
            val raceId: String,
            val date: String?,
            val finish: Int?,
        )

        private val races = HashMap<String, RaceConditions>()
        private val entries = HashMap<Pair<String, Int>, String>()
        private val horses = HashMap<String, HorseMaster>()
        private val runs = HashMap<String, MutableList<RunRow>>()

        init {
            fun s(
                n: JsonNode,
                k: String,
            ): String? = n.get(k)?.takeUnless { it.isNull }?.stringValue()
            for (r in db.get("races").values()) {
                races[s(r, "race_id")!!] =
                    LegacyRows.race(
                        LegacyRows.RaceRow(
                            distanceM = r.get("distance_m")?.takeUnless { it.isNull }?.asInt("distance_m"),
                            trackTypeCd = s(r, "track_type_cd"),
                            courseKubunCd = s(r, "course_kubun_cd"),
                            tenkoCd = s(r, "tenko_cd"),
                            shibaBabaCd = s(r, "shiba_baba_cd"),
                            dirtBabaCd = s(r, "dirt_baba_cd"),
                        ),
                    )
            }
            for (r in db.get("race_horses").values()) entries[s(r, "race_id")!! to r.int("umaban")] = s(r, "ketto_num") ?: ""
            for (r in db.get("horses").values()) {
                val ketto = s(r, "ketto_num")!!
                horses[ketto] =
                    LegacyRows.horse(
                        LegacyRows.HorseRow(
                            kettoNum = ketto,
                            birthDate = s(r, "birth_date"),
                            pedigreeJson = s(r, "pedigree_json"),
                            styleTendencyJson = s(r, "style_tendency_json"),
                            chakuByDistanceJson = s(r, "chaku_by_distance_json"),
                            chakuBySurfaceJson = s(r, "chaku_by_surface_json"),
                            chakuByConditionJson = s(r, "chaku_by_condition_json"),
                        ),
                    )
            }
            for (r in db.get("horse_past_runs").values()) {
                runs.getOrPut(s(r, "ketto_num")!!) { ArrayList() } +=
                    RunRow(s(r, "race_id")!!, s(r, "date"), r.get("finish")?.takeUnless { it.isNull }?.asInt("finish"))
            }
        }

        override fun raceConditions(
            tenantId: TenantId,
            raceId: String,
        ): RaceConditions? = races[raceId]

        override fun entry(
            tenantId: TenantId,
            raceId: String,
            umaban: Int,
        ): EntryHorse? = entries[raceId to umaban]?.let { EntryHorse(it) }

        override fun horse(
            tenantId: TenantId,
            kettoNum: String,
        ): HorseMaster? = horses[kettoNum]

        override fun pastRuns(
            tenantId: TenantId,
            kettoNum: String,
            before: LocalDate?,
            limit: Int,
        ): List<PastRun> {
            require(before == null) { "旧互換の読み方（日付で絞らない）専用" }
            val dateOrder =
                Comparator<RunRow> { a, b ->
                    // SQLite の ORDER BY date DESC: NULL は最小なので最後。文字列は二進比較
                    when {
                        a.date == null && b.date == null -> 0
                        a.date == null -> 1
                        b.date == null -> -1
                        else -> b.date.compareTo(a.date)
                    }
                }
            return (runs[kettoNum] ?: emptyList())
                .filter { it.finish != null && it.finish > 0 }
                .sortedWith(dateOrder.thenComparator { a, b -> b.raceId.compareTo(a.raceId) })
                .take(limit)
                .map { PastRun(it.raceId, LegacyDates.parseCompact(it.date), it.finish!!) }
        }
    }

    // ---- G6: 特徴量ベクトル -----------------------------------------------------------------------------------------

    fun buildFeaturesBatch(a: JsonNode): Any {
        val source = LegacySqliteSource(a.get("db"))
        val builder = LegacyFeatureBuilder(source, tenant, LocalDateTime.parse(a.get("now").stringValue()))
        val rows =
            a.get("requests").values().map { q ->
                val kind = q.get("kind").stringValue()
                val schema = if (kind == "alpha") FeatureSchema.ALPHA_35 else FeatureSchema.CALIBRATION_40
                val withDb = q.get("with_db")?.booleanValue() ?: true
                val raceId = q.get("race_id").stringValue()
                val odds = if (kind == "alpha") 0.0 else q.double("odds")
                val pop = if (kind == "alpha") 0 else q.int("popularity")
                val v =
                    if (withDb) {
                        builder.build(schema, raceId, q.int("umaban"), odds, pop)
                    } else {
                        builder.buildWithoutContext(schema, odds, pop)
                    }
                obj("vector" to v.values().toList(), "meta" to metaJson(v.meta))
            }
        return obj(
            "names" to obj("calibration" to FeatureSchema.CALIBRATION_40.names, "alpha" to FeatureSchema.ALPHA_35.names),
            "rows" to rows,
        )
    }

    private fun metaJson(m: FeatureMeta) =
        obj("has_horse_master" to if (m.hasHorseMaster) 1 else 0, "has_past_runs" to if (m.hasPastRuns) 1 else 0, "past_n" to m.pastN)

    // ---- G3〜G5, G7: evaluate_odds の ML 経路（旧実装の再現: legacy パイプライン）---------------------------------------------------

    /** `evaluate_odds_ml` の ML 部分。判定の設定（確率源のラベル・α・クリップ）を更新した設定と、評価器に渡す ML の確率を返す。 */
    fun prepare(
        a: JsonNode,
        config: WinDecisionConfig,
        horses: List<DecisionHorse>,
        kwargs: JsonNode?,
    ): Pair<WinDecisionConfig, MlProbabilityInput> {
        val source = LegacySqliteSource(a.get("db"))
        val builder = LegacyFeatureBuilder(source, tenant, LocalDateTime.parse(a.get("now").stringValue()))
        val raceId = a.get("race_id").stringValue()
        val cfg = a.get("ml_cfg")
        val enabled = cfg.get("enabled")?.booleanValue() ?: true
        val typeText =
            cfg
                .get("model_type")
                ?.stringValue()
                ?.trim()
                ?.lowercase() ?: "legacy"
        if (typeText == "auto") throw UnsupportedGoldenCall("model_type=auto はファイルの有無で選ぶ旧仕様。レジストリの ACTIVE で決める新仕様では再現しない")
        val type =
            when (typeText) {
                "calibration" -> ProbabilityModelType.CALIBRATION
                "alpha" -> ProbabilityModelType.ALPHA
                "ensemble" -> ProbabilityModelType.ENSEMBLE
                else -> ProbabilityModelType.LEGACY
            }
        val betaNode = cfg.get("ensemble_beta")
        if (betaNode != null && betaNode.isString) throw UnsupportedGoldenCall("ensemble_beta=auto は外部ファイルの 14 日失効ロジック。新仕様では成果物（ensemble セット）が β を持つ")
        val beta = betaNode?.asDouble("ensemble_beta") ?: 0.35
        val alphaOverride =
            kwargs?.get("ml_market_blend_alpha")?.takeUnless { it.isNull }?.asDouble("ml_market_blend_alpha")
                ?: cfg.get("market_blend_alpha")?.asDouble("market_blend_alpha") ?: 0.25
        val clipMax =
            kwargs?.get("win_prob_clip_max")?.takeUnless { it.isNull }?.asDouble("win_prob_clip_max")
                ?: cfg.get("win_prob_clip_max")?.asDouble("win_prob_clip_max") ?: 0.35

        val models = a.get("models")

        fun spec(role: String): LegacyModel? =
            models.get(role)?.takeUnless { it.isNull }?.let {
                LegacyModel.of(it).also { m ->
                    m.marketBlendAlpha =
                        alphaOverride
                }
            }
        val passLegacyPath = a.get("pass_legacy_path")?.booleanValue() ?: true
        val mlAttempt = enabled && (passLegacyPath || type != ProbabilityModelType.LEGACY)

        fun guardLegacy(m: LegacyModel) = !(m.featureNames == "alpha35") // 旧: len(fn) >= 20 かつ 40 名と不一致 → ValueError。5 名/空はガードを通る

        fun guardOther(m: LegacyModel) = m.featureNames == "calibration40" || m.featureNames == "none" // 旧: 空でなく 40 名と違えば ValueError（5 名も不一致）

        val cal40 = horses.map { builder.build(FeatureSchema.CALIBRATION_40, raceId, it.umaban, it.odds, it.popularity).values() }
        val alphaRows = horses.map { builder.build(FeatureSchema.CALIBRATION_40, raceId, it.umaban, 0.0, 0).values() }

        fun scored(
            m: LegacyModel,
            rows: List<DoubleArray>,
        ) = RoleOutput.Scored(m.predictProbaFromFeatures(rows), alphaOverride)

        val mismatch = RoleOutput.Unavailable(FallbackReason.FEATURE_SCHEMA_MISMATCH, "feature_names が 40 名と一致しない")
        val missing = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND, "モデルのファイルが無い")
        var raw = RawOutputs()
        if (mlAttempt) {
            raw =
                when (type) {
                    ProbabilityModelType.LEGACY -> {
                        val m = spec("legacy")
                        RawOutputs(
                            legacy =
                                if (m == null) {
                                    missing
                                } else if (!guardLegacy(m)) {
                                    mismatch
                                } else {
                                    scored(m, cal40)
                                },
                        )
                    }

                    ProbabilityModelType.CALIBRATION -> {
                        val m = spec("calibration")
                        RawOutputs(
                            calibration =
                                if (m == null) {
                                    missing
                                } else if (!guardOther(m)) {
                                    mismatch
                                } else {
                                    scored(m, cal40)
                                },
                        )
                    }

                    ProbabilityModelType.ALPHA -> {
                        val m = spec("alpha")
                        RawOutputs(
                            alpha =
                                if (m == null) {
                                    missing
                                } else if (!guardOther(m)) {
                                    mismatch
                                } else {
                                    scored(m, alphaRows)
                                },
                        )
                    }

                    ProbabilityModelType.ENSEMBLE -> {
                        val alpha = spec("alpha")
                        val calib = spec("calibration")
                        RawOutputs(
                            calibration =
                                if (calib == null) {
                                    missing
                                } else if (!guardOther(calib)) {
                                    mismatch
                                } else {
                                    scored(calib, cal40)
                                },
                            alpha =
                                if (alpha == null) {
                                    missing
                                } else if (!guardOther(alpha)) {
                                    mismatch
                                } else {
                                    scored(alpha, alphaRows)
                                },
                        )
                    }
                }
        }

        val metas = horses.map { builder.build(FeatureSchema.CALIBRATION_40, raceId, it.umaban, it.odds, it.popularity).meta }
        val coverage = if (type == ProbabilityModelType.ENSEMBLE) metas.map { it.coverageScore() } else null
        val pc = PipelineConfig(alphaOverride, beta, config.enableProbClip, clipMax)
        val result = LegacyProbabilityPipeline.run(type, enabled, horses.map { it.odds }, raw, coverage, pc)

        val coverageByUmaban =
            horses.indices.associate { i ->
                horses[i].umaban to FeatureCoverage(result.coverageScores[i], result.betaEffs[i], metas[i].hasHorseMaster, metas[i].hasPastRuns, metas[i].pastN)
            }
        val config2 =
            config.copy(
                probSource = result.legacyLabel,
                marketBlendAlpha = alphaOverride,
                probClipMax = clipMax,
                ensembleBeta = beta.coerceIn(0.0, 1.0),
                raceId = raceId,
            )
        return config2 to MlProbabilityInput(result.probs, coverageByUmaban)
    }
}
