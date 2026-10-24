package jp.jrabot.domain.ml.pipeline

import jp.jrabot.domain.decision.MarketBlend
import jp.jrabot.domain.decision.MarketProbability
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbabilityPipelineTest {
    private val odds = listOf(2.5, 4.0, 8.0, 15.0, 30.0, 0.0)
    private val cfg = PipelineConfig(marketBlendAlpha = 0.25, ensembleBeta = 0.35, enableProbClip = true, probClipMax = 0.35)
    private val calib = listOf(0.30, 0.22, 0.15, 0.08, 0.04, 0.02)
    private val alpha = listOf(0.10, 0.20, 0.30, 0.15, 0.05, 0.12)

    private fun scored(
        p: List<Double>,
        internal: Double? = 0.25,
    ) = RoleOutput.Scored(p, internal)

    private fun sum(p: List<Double>) = p.sum()

    // ---- 共通の段階 ----

    @Test
    fun `正規化と ±10 パーセント補正（旧 normalize_with_correction）`() {
        assertContentEquals(listOf(0.5, 0.3, 0.2), ProbabilityStages.normalizeWithCorrection(listOf(0.5, 0.3, 0.2)))
        // 合計が 1 でも、各馬の比率は 1 なので補正なし。合計 0.3 の入力は比率 3.33 > 1.1 → raw×1.1 に押し戻して再正規化
        val r = ProbabilityStages.normalizeWithCorrection(listOf(0.2, 0.1))
        assertEquals(0.2 * 1.1 / (0.2 * 1.1 + 0.1 * 1.1), r[0], 1e-15)
        assertEquals(1.0, sum(r), 1e-15)
        assertEquals(listOf(0.25, 0.25, 0.25, 0.25), ProbabilityStages.normalizeWithCorrection(listOf(0.0, 0.0, 0.0, 0.0)))
        assertEquals(emptyList(), ProbabilityStages.normalizeWithCorrection(emptyList()))
        // raw ≤ 0 の馬は補正せず正規化後の値
        val z = ProbabilityStages.normalizeWithCorrection(listOf(0.5, 0.0, -0.1, 0.5))
        assertEquals(1.0, sum(z), 1e-12)
    }

    @Test
    fun `ensemble の馬ごとの合成式`() {
        // オッズ有り・両方あり: (1-β)·calib + β·alpha
        assertEquals((1 - 0.2) * 0.3 + 0.2 * 0.1, ProbabilityStages.ensembleOne(3.0, 0.3, 0.1, 0.2))
        assertEquals(0.3, ProbabilityStages.ensembleOne(3.0, 0.3, null, 0.2)) // alpha 無し → calib のみ
        assertEquals(0.1, ProbabilityStages.ensembleOne(0.0, 0.3, 0.1, 0.2)) // オッズ無し → alpha
        assertEquals(0.3, ProbabilityStages.ensembleOne(0.0, 0.3, null, 0.2)) // オッズ無し・alpha 無し → calib
        assertEquals(0.0, ProbabilityStages.ensembleOne(3.0, null, null, 0.2))
        assertEquals(0.1, ProbabilityStages.ensembleOne(3.0, null, 0.1, 0.2)) // calib 無し → alpha
    }

    // ---- legacy ----

    @Test
    fun `legacy ML 無効は市場確率のフォールバックで理由が残り旧ラベルのまま`() {
        val r = LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, false, odds, RawOutputs(), null, cfg)
        val market = MarketProbability.estimateProbNormalized(odds)
        assertContentEquals(MarketBlend.apply(market, odds, 0.25, true, 0.35), r.probs)
        assertEquals(ProbSource.MARKET_FALLBACK, r.source)
        assertEquals("market:fallback(ml_disabled)", r.legacyLabel)
        assertEquals(FallbackReason.ML_DISABLED, r.fallbackReason)
        assertTrue(r.isMarketFallback)
        assertEquals(listOf(FallbackReason.ML_DISABLED, FallbackReason.MARKET_FALLBACK), r.steps.map { it.reason })
    }

    @Test
    fun `legacy calibration は正規化から二重ブレンドまで旧の順序`() {
        val r = LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(calibration = scored(calib)), null, cfg)
        val pre = ProbabilityStages.normalizeWithCorrection(calib)
        assertContentEquals(pre, r.preBlend)
        assertContentEquals(MarketBlend.apply(pre, odds, 0.25, true, 0.35), r.probs)
        assertEquals("ml:calibration", r.legacyLabel)
        assertEquals(ProbSource.ML_CALIBRATION, r.source)
        assertNull(r.fallbackReason)
        assertEquals(1.0, sum(r.probs), 1e-12)
        assertEquals(List(6) { ProbSource.ML_CALIBRATION }, r.horseSources)
    }

    @Test
    fun `legacy ensemble は β×coverage で合成し、旧のラベルは最後の馬で決まる（E2）`() {
        val coverage = listOf(1.0, 0.8, 0.5, 0.0, 1.0, null)
        val r =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = scored(calib), alpha = scored(alpha)),
                coverage,
                cfg,
            )
        val finals =
            odds.indices.map { i ->
                val cov = coverage[i] ?: 1.0
                val betaEff = if (coverage[i] != null) 0.35 * cov else 0.35
                if (odds[i] > 0) (1.0 - betaEff) * calib[i] + betaEff * alpha[i] else alpha[i]
            }
        val pre = ProbabilityStages.normalizeWithCorrection(finals)
        assertContentEquals(pre, r.preBlend)
        assertContentEquals(MarketBlend.apply(pre, odds, 0.25, true, 0.35), r.probs)
        // 最後の馬のオッズは 0 → 旧ラベルは alpha_only になる（実際は他の馬は calib+alpha）。正確な出どころは馬ごと・MIXED
        assertEquals("ml:ensemble(alpha_only)", r.legacyLabel)
        assertEquals(ProbSource.MIXED, r.source)
        assertEquals(ProbSource.ML_ENSEMBLE_CALIB_ALPHA, r.horseSources[0])
        assertEquals(ProbSource.ML_ENSEMBLE_ALPHA_ONLY, r.horseSources[5])
        assertEquals(listOf(1.0, 0.8, 0.5, 0.0, 1.0, 1.0), r.coverageScores)
        assertEquals(0.35 * 0.8, r.betaEffs[1])
        assertEquals(0.35, r.betaEffs[5])
    }

    @Test
    fun `legacy ensemble のラベル 4 種`() {
        val o = listOf(2.0, 3.0)
        val c = listOf(0.6, 0.4)
        val a = listOf(0.5, 0.5)

        fun label(
            raw: RawOutputs,
            odds: List<Double> = o,
        ) = LegacyProbabilityPipeline.run(ProbabilityModelType.ENSEMBLE, true, odds, raw, null, cfg).legacyLabel
        assertEquals("ml:ensemble(calib+alpha,beta=0.35)", label(RawOutputs(calibration = scored(c), alpha = scored(a))))
        assertEquals("ml:ensemble(calib_only)", label(RawOutputs(calibration = scored(c), alpha = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND))))
        assertEquals("ml:ensemble(alpha_only)", label(RawOutputs(calibration = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND), alpha = scored(a))))
        // 両方あっても最後の馬のオッズが 0 なら alpha_only（旧ラベルの癖）
        assertEquals("ml:ensemble(alpha_only)", label(RawOutputs(calibration = scored(c), alpha = scored(a)), listOf(2.0, 0.0)))
    }

    @Test
    fun `legacy 無かったモデルは無いものとして続け、スキーマ不一致は ML 全体を市場確率に落とす（旧の例外）`() {
        val partial =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = scored(calib), alpha = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND, "x")),
                null,
                cfg,
            )
        assertEquals(ProbSource.ML_ENSEMBLE_CALIB_ONLY, partial.source) // alpha が無かったので全馬 calibration のみ
        assertEquals(FallbackReason.MODEL_NOT_FOUND, partial.fallbackReason)
        assertTrue(!partial.isMarketFallback)

        val aborted =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = scored(calib), alpha = RoleOutput.Unavailable(FallbackReason.FEATURE_SCHEMA_MISMATCH, "x")),
                null,
                cfg,
            )
        assertEquals(ProbSource.MARKET_FALLBACK, aborted.source)
        assertEquals(FallbackReason.FEATURE_SCHEMA_MISMATCH, aborted.fallbackReason)
        assertContentEquals(MarketBlend.apply(MarketProbability.estimateProbNormalized(odds), odds, 0.25, true, 0.35), aborted.probs)

        // 両方無い・全部読めない → 市場確率
        val none =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(
                    calibration = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_ACTIVE),
                    alpha = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND),
                ),
                null,
                cfg,
            )
        assertEquals(ProbSource.MARKET_FALLBACK, none.source)
        assertEquals(FallbackReason.MODEL_NOT_ACTIVE, none.fallbackReason)
    }

    @Test
    fun `legacy 種別ごとの要求と旧ラベル`() {
        val a = LegacyProbabilityPipeline.run(ProbabilityModelType.ALPHA, true, odds, RawOutputs(alpha = scored(alpha)), null, cfg)
        assertEquals("ml:alpha", a.legacyLabel)
        val l = LegacyProbabilityPipeline.run(ProbabilityModelType.LEGACY, true, odds, RawOutputs(legacy = scored(calib)), null, cfg)
        assertEquals("ml:legacy", l.legacyLabel)
        // legacy 種別でモデルが読めない → 旧: market:fallback(no_model)
        val m =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.LEGACY,
                true,
                odds,
                RawOutputs(legacy = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND)),
                null,
                cfg,
            )
        assertEquals("market:fallback(no_model)", m.legacyLabel)
        // 要求されたモデルを誤って別の種別で渡しても使わない
        val wrong = LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(alpha = scored(alpha)), null, cfg)
        assertEquals(ProbSource.MARKET_FALLBACK, wrong.source)
    }

    @Test
    fun `性質 α=1 でモデルのみ α=0 で市場のみ 和は常に 1`() {
        val pre = ProbabilityStages.normalizeWithCorrection(calib)
        val only =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.CALIBRATION,
                true,
                odds,
                RawOutputs(calibration = scored(calib)),
                null,
                cfg.copy(marketBlendAlpha = 1.0),
            )
        assertContentEquals(pre, only.probs) // α ≥ 0.999 は判定側ブレンドをしない
        val mkt =
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.CALIBRATION,
                true,
                odds,
                RawOutputs(calibration = scored(calib)),
                null,
                cfg.copy(marketBlendAlpha = 0.0, enableProbClip = false),
            )
        // 判定側ブレンドの市場確率: odds ≤ 0 は 0.0 → [1e-6, 1-1e-6] に収める
        val expected = odds.map { o -> (if (o > 0) 1.0 / maxOf(o, 0.1) else 0.0).coerceIn(1e-6, 1 - 1e-6) }.let { raw -> raw.map { it / raw.sum() } }
        mkt.probs.zip(expected).forEach { (x, y) -> assertEquals(y, x, 1e-12) }
        for (a in listOf(0.0, 0.1, 0.25, 0.5, 0.9, 1.0)) {
            val r =
                LegacyProbabilityPipeline.run(
                    ProbabilityModelType.CALIBRATION,
                    true,
                    odds,
                    RawOutputs(calibration = scored(calib)),
                    null,
                    cfg.copy(marketBlendAlpha = a),
                )
            assertEquals(1.0, sum(r.probs), 1e-12, "α=$a")
            assertTrue(r.probs.all { it in 0.0..1.0 })
        }
    }

    @Test
    fun `入力の不整合は拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(calibration = scored(calib.take(3))), null, cfg)
        }
        assertFailsWith<IllegalArgumentException> {
            LegacyProbabilityPipeline.run(
                ProbabilityModelType.CALIBRATION,
                true,
                odds,
                RawOutputs(
                    calibration =
                        scored(
                            calib.map {
                                if (it >
                                    0.1
                                ) {
                                    Double.NaN
                                } else {
                                    it
                                }
                            },
                        ),
                ),
                null,
                cfg,
            )
        }
        assertFailsWith<IllegalArgumentException> { RoleOutput.Unavailable(FallbackReason.MARKET_FALLBACK) }
        assertFailsWith<IllegalArgumentException> { PipelineConfig(Double.NaN, 0.3, true, 0.35) }
    }

    // ---- v2 ----

    @Test
    fun `v2 はモデル内の市場ブレンドを受け付けない（二重ブレンドの防止）`() {
        assertFailsWith<IllegalArgumentException> {
            V2ProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(calibration = scored(calib, 0.25)), null, cfg)
        }
    }

    @Test
    fun `v2 の ensemble は正確な出どころと単回ブレンド、legacy と独立した結果`() {
        val coverage = listOf(1.0, 0.8, 0.5, 0.0, 1.0, 0.3)
        val raw = RawOutputs(calibration = scored(calib, null), alpha = scored(alpha, null))
        val v2 = V2ProbabilityPipeline.run(ProbabilityModelType.ENSEMBLE, true, odds, raw, coverage, cfg)
        assertEquals(ProbabilityPipelineVersion.V2, v2.version)
        assertEquals(ProbSource.MIXED, v2.source) // 最後の馬のオッズが 0 → alpha_only（旧は最後の馬のラベルが全体に付いた）
        assertEquals("MIXED", v2.legacyLabel)
        assertNull(v2.fallbackReason)
        assertEquals(1.0, sum(v2.probs), 1e-12)
        val finals = odds.indices.map { i -> ProbabilityStages.ensembleOne(odds[i], calib[i], alpha[i], 0.35 * coverage[i]) }
        assertContentEquals(MarketBlend.apply(ProbabilityStages.normalizeWithCorrection(finals), odds, 0.25, true, 0.35), v2.probs)
    }

    @Test
    fun `v2 のフォールバックは ensemble から市場確率まで段階的で理由がすべて残る`() {
        val n = RoleOutput.Unavailable(FallbackReason.MODEL_NOT_FOUND)
        // alpha 無し → calibration 単体
        val r1 =
            V2ProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = scored(calib, null), alpha = RoleOutput.Unavailable(FallbackReason.MODEL_SIGNATURE_INVALID, "s")),
                null,
                cfg,
            )
        assertEquals(ProbSource.ML_CALIBRATION, r1.source)
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, r1.fallbackReason)
        assertEquals(listOf("ensemble.alpha"), r1.steps.map { it.stage })
        // calibration 無し → alpha 単体
        val r2 =
            V2ProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = RoleOutput.Unavailable(FallbackReason.MODEL_HASH_INVALID), alpha = scored(alpha, null)),
                null,
                cfg,
            )
        assertEquals(ProbSource.ML_ALPHA, r2.source)
        assertEquals(FallbackReason.MODEL_HASH_INVALID, r2.fallbackReason)
        // calibration 要求で calibration が駄目 → alpha → legacy → 市場の順に探す
        val r3 =
            V2ProbabilityPipeline.run(
                ProbabilityModelType.CALIBRATION,
                true,
                odds,
                RawOutputs(calibration = n, alpha = n, legacy = scored(calib, null)),
                null,
                cfg,
            )
        assertEquals(ProbSource.ML_LEGACY, r3.source)
        assertEquals(listOf("calibration", "alpha"), r3.steps.map { it.stage })
        // すべて駄目 → 市場確率。ブレンドもクリップもしない
        val r4 =
            V2ProbabilityPipeline.run(
                ProbabilityModelType.ENSEMBLE,
                true,
                odds,
                RawOutputs(calibration = n, alpha = RoleOutput.Unavailable(FallbackReason.INVALID_MODEL_DIMENSION)),
                null,
                cfg,
            )
        assertEquals(ProbSource.MARKET_FALLBACK, r4.source)
        assertContentEquals(MarketProbability.estimateProbNormalized(odds), r4.probs)
        assertEquals(FallbackReason.MODEL_NOT_FOUND, r4.fallbackReason)
        assertEquals(FallbackReason.MARKET_FALLBACK, r4.steps.last().reason)
        assertEquals("MARKET_FALLBACK", r4.legacyLabel)
        // alpha 要求は alpha → legacy → 市場。calibration は見ない
        val r5 = V2ProbabilityPipeline.run(ProbabilityModelType.ALPHA, true, odds, RawOutputs(calibration = scored(calib, null), alpha = n), null, cfg)
        assertEquals(ProbSource.MARKET_FALLBACK, r5.source)
        // ML 無効
        val r6 = V2ProbabilityPipeline.run(ProbabilityModelType.ENSEMBLE, false, odds, RawOutputs(), null, cfg)
        assertEquals(FallbackReason.ML_DISABLED, r6.fallbackReason)
    }

    @Test
    fun `同じ入力ならパイプライン自体の段階は legacy も v2 も同じで、差はモデル内ブレンド（入力）から生じる`() {
        val legacy = LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(calibration = scored(calib, 0.25)), null, cfg)
        val v2 = V2ProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, odds, RawOutputs(calibration = scored(calib, null)), null, cfg)
        assertContentEquals(legacy.probs, v2.probs)
        assertEquals("ml:calibration", legacy.legacyLabel)
        assertEquals("ML_CALIBRATION", v2.legacyLabel)
    }

    @Test
    fun `空のレースは空の結果`() {
        val r = LegacyProbabilityPipeline.run(ProbabilityModelType.CALIBRATION, true, emptyList(), RawOutputs(calibration = scored(emptyList())), null, cfg)
        assertEquals(emptyList(), r.probs)
    }
}
