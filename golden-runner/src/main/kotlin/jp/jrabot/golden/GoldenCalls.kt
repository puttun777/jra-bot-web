package jp.jrabot.golden

import jp.jrabot.domain.decision.BetSignal
import jp.jrabot.domain.decision.BetStrategyBuilder
import jp.jrabot.domain.decision.BetStrategyBundle
import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.decision.BetTypeMode
import jp.jrabot.domain.decision.ComboOdds
import jp.jrabot.domain.decision.ComboOddsEstimate
import jp.jrabot.domain.decision.ComboRaceContext
import jp.jrabot.domain.decision.ComboSettings
import jp.jrabot.domain.decision.ComboTicket
import jp.jrabot.domain.decision.DecisionHorse
import jp.jrabot.domain.decision.DecisionSignal
import jp.jrabot.domain.decision.EvMath
import jp.jrabot.domain.decision.KellyMath
import jp.jrabot.domain.decision.LegacySimpleComboProbability
import jp.jrabot.domain.decision.LegacyZeroDivisionException
import jp.jrabot.domain.decision.MarketProbability
import jp.jrabot.domain.decision.OddsPopularitySnapshot
import jp.jrabot.domain.decision.PriorityMode
import jp.jrabot.domain.decision.ProbRaw
import jp.jrabot.domain.decision.SanrenpukuKey
import jp.jrabot.domain.decision.SkipSignal
import jp.jrabot.domain.decision.SnapshotFilters
import jp.jrabot.domain.decision.SnapshotsNewestFirst
import jp.jrabot.domain.decision.StrategyEntry
import jp.jrabot.domain.decision.WideKey
import jp.jrabot.domain.decision.WideOddsRange
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.decision.WinDecisionEvaluator
import jp.jrabot.domain.decision.WinStake
import tools.jackson.databind.JsonNode

/**
 * ゴールデンのフィクスチャの `input.calls[]`（`tools/golden/adapter.py` の `CALLS` と同じ名前・同じ引数）を :domain の計算に対応させる。
 * 対応していない関数・引数は [UnsupportedGoldenCall]（ケース全体が未対応になる）。
 */
object GoldenCalls {
    /** この版で実行できる関数名。ここに無い関数を含むケースは未対応。 */
    val SUPPORTED_FUNCTIONS: Set<String> =
        setOf(
            "calc_ev",
            "calc_expected_return",
            "calc_expected_profit",
            "calc_kelly_fraction",
            "suggest_bet_amount",
            "estimate_prob_raw",
            "estimate_prob",
            "estimate_prob_normalized",
            "check_odds_drop",
            "check_popularity_surge",
            "estimate_wide_odds",
            "estimate_trifecta_odds",
            "calculate_wide_prob",
            "calculate_trifecta_prob",
            "suggest_multiple_bet_strategies",
            "evaluate_odds",
            "evaluate_odds_ml",
            "hash_bucket",
            "hash_sign",
            "model_extract_features",
            "model_predict",
            "model_predict_batch",
            "build_features_batch",
        )

    /** 1 回の呼び出しを実行し、`{"status":"ok","value":…}` または `{"status":"exception",…}` を返す。 */
    fun run(call: JsonNode): JsonNode {
        val function = call.get("function").stringValue()
        if (function !in SUPPORTED_FUNCTIONS) throw UnsupportedGoldenCall("未対応の関数: $function")
        val args = call.get("args")
        return try {
            obj("status" to "ok", "value" to dispatch(function, args))
        } catch (e: LegacyZeroDivisionException) {
            // 旧 Python の ZeroDivisionError に対応（E5）。型だけが比較される（メッセージは各ケースの rules で ignore）
            obj("status" to "exception", "exception_type" to "ZeroDivisionError", "message" to (e.message ?: ""))
        }
    }

    private fun kwargs(
        args: JsonNode,
        allowed: Set<String>,
    ): JsonNode? {
        val k = args.get("kwargs") ?: return null
        val unknown = k.fieldNameList().filter { it !in allowed }
        if (unknown.isNotEmpty()) throw UnsupportedGoldenCall("未対応の引数: $unknown")
        return k
    }

    private fun JsonNode?.optDouble(
        name: String,
        default: Double,
    ): Double = this?.get(name)?.asDouble(name) ?: default

    private fun dispatch(
        function: String,
        a: JsonNode,
    ): Any? =
        when (function) {
            "calc_ev" -> {
                EvMath.calcEv(a.double("prob"), a.double("odds"))
            }

            "calc_expected_return" -> {
                EvMath.calcExpectedReturn(a.double("prob"), a.double("odds"))
            }

            "calc_expected_profit" -> {
                EvMath.calcExpectedProfit(a.double("stake"), a.double("prob"), a.double("odds"))
            }

            "calc_kelly_fraction" -> {
                val k = kwargs(a, setOf("max_fraction"))
                KellyMath.calcKellyFraction(a.double("prob"), a.double("odds"), k.optDouble("max_fraction", 0.25))
            }

            "suggest_bet_amount" -> {
                suggestBetAmount(a)
            }

            "estimate_prob_raw" -> {
                MarketProbability.estimateProbRaw(a.double("odds"))
            }

            "estimate_prob" -> {
                MarketProbability.estimateProb(a.double("odds"))
            }

            "estimate_prob_normalized" -> {
                if (a.get("correction_limit") != null) {
                    MarketProbability.estimateProbNormalized(a.doubleList("odds_list"), a.double("correction_limit"))
                } else {
                    MarketProbability.estimateProbNormalized(a.doubleList("odds_list"))
                }
            }

            "check_odds_drop" -> {
                val k = kwargs(a, setOf("drop_threshold"))
                SnapshotFilters.checkOddsDrop(snapshots(a.get("recent_snapshots")), a.double("current_odds"), k.optDouble("drop_threshold", 0.20))
            }

            "check_popularity_surge" -> {
                val k = kwargs(a, setOf("surge_threshold"))
                val threshold = k?.get("surge_threshold")?.asInt("surge_threshold") ?: 3
                SnapshotFilters.checkPopularitySurge(snapshots(a.get("recent_snapshots")), a.int("current_popularity"), threshold)
            }

            "estimate_wide_odds" -> {
                kwargs(a, setOf("num_horses")) // 旧実装は num_horses を使わない
                ComboOddsEstimate.wide(a.double("win_odds"))
            }

            "estimate_trifecta_odds" -> {
                kwargs(a, setOf("num_horses"))
                ComboOddsEstimate.trifecta(a.double("win_odds"))
            }

            "calculate_wide_prob" -> {
                LegacySimpleComboProbability.wide(a.double("win_prob"), a.get("odds_list").size() == 0)
            }

            "calculate_trifecta_prob" -> {
                LegacySimpleComboProbability.trifecta(a.double("win_prob"), a.get("odds_list").size() == 0)
            }

            "suggest_multiple_bet_strategies" -> {
                suggestMultipleBetStrategies(a)
            }

            "hash_bucket" -> {
                GoldenMl.hashBucket(a)
            }

            "hash_sign" -> {
                GoldenMl.hashSign(a)
            }

            "model_extract_features" -> {
                GoldenMl.modelExtractFeatures(a)
            }

            "model_predict" -> {
                GoldenMl.modelPredict(a)
            }

            "model_predict_batch" -> {
                GoldenMl.modelPredictBatch(a)
            }

            "build_features_batch" -> {
                GoldenMl.buildFeaturesBatch(a)
            }

            "evaluate_odds_ml" -> {
                evaluateOdds(a, ml = true)
            }

            "evaluate_odds" -> {
                evaluateOdds(a)
            }

            else -> {
                throw UnsupportedGoldenCall("未対応の関数: $function")
            }
        }

    private fun suggestBetAmount(a: JsonNode): JsonNode {
        val k = kwargs(a, setOf("bankroll", "max_fraction", "min_bet", "max_bet"))
        val r =
            KellyMath.suggestBetAmount(
                a.double("prob"),
                a.double("odds"),
                bankroll = k.optDouble("bankroll", 100.0),
                maxFraction = k.optDouble("max_fraction", 0.25),
                minBet = k.optDouble("min_bet", 1.0),
                maxBet = k.optDouble("max_bet", 10.0),
            )
        return obj(
            "kelly_fraction" to r.kellyFraction,
            "suggested_bet" to r.suggestedBet,
            "expected_profit" to r.expectedProfit,
            "strategy" to r.strategy,
            "ev" to r.ev,
        )
    }

    private fun snapshots(n: JsonNode?): SnapshotsNewestFirst {
        if (n == null || !n.isArray) throw UnsupportedGoldenCall("recent_snapshots は配列でなければならない")
        return SnapshotsNewestFirst.ofNewestFirst(n.values().map { OddsPopularitySnapshot(it.double("odds"), it.int("popularity")) })
    }

    // ---- evaluate_odds ------------------------------------------------------------------------------------------

    private val EVALUATE_KWARGS =
        setOf(
            "ev_threshold",
            "max_signals",
            "drop_threshold",
            "surge_threshold",
            "bankroll",
            "include_bet_strategy",
            "bet_types",
            "ev_shrink_factor",
            "win_max_over_market",
            "win_payout_rate",
            "wide_payout_rate",
            "sanrenpuku_payout_rate",
            "show_adjusted_mkt",
            "ml_market_blend_alpha",
            "win_prob_clip_max",
            "enable_prob_clip",
            "notify_skips",
            "kelly_fraction",
            "min_bet",
            "max_bet",
            "require_real_odds_for_combo",
            "ticket_allocation",
            "bet_type_mode",
            "max_total_bet_per_race",
            "race_id",
            "priority_mode",
            "hit_rate_min_ev",
            "hit_rate_bet_type_priority",
            "win_odds_max",
            "win_prob_min",
            "win_ratio_min",
            "top_k_partners",
            "max_tickets_per_type",
            "box_size",
            "wide_min_prob",
            "wide_max_prob",
            "sanrenpuku_min_prob",
            "sanrenpuku_max_prob",
            "plackett_luce_alpha",
            "wide_max_over_market",
            "sanrenpuku_max_over_market",
            "wide_odds_map",
            "sanrenpuku_odds_map",
        )

    /** 組合せ生成の引数（`ComboSettings`）。無い引数は [base] の値。 */
    private fun comboSettings(
        k: JsonNode?,
        base: ComboSettings,
    ): ComboSettings {
        fun d(name: String): Double? = k?.get(name)?.takeUnless { it.isNull }?.asDouble(name)

        fun i(name: String): Int? = k?.get(name)?.takeUnless { it.isNull }?.asInt(name)
        return ComboSettings(
            topKPartners = i("top_k_partners") ?: base.topKPartners,
            maxTicketsPerType = i("max_tickets_per_type") ?: base.maxTicketsPerType,
            boxSize = i("box_size") ?: base.boxSize,
            wideMinProb = d("wide_min_prob") ?: base.wideMinProb,
            wideMaxProb = d("wide_max_prob") ?: base.wideMaxProb,
            sanrenpukuMinProb = d("sanrenpuku_min_prob") ?: base.sanrenpukuMinProb,
            sanrenpukuMaxProb = d("sanrenpuku_max_prob") ?: base.sanrenpukuMaxProb,
            plackettLuceAlpha = d("plackett_luce_alpha") ?: base.plackettLuceAlpha,
            wideMaxOverMarket = d("wide_max_over_market") ?: base.wideMaxOverMarket,
            sanrenpukuMaxOverMarket = d("sanrenpuku_max_over_market") ?: base.sanrenpukuMaxOverMarket,
        )
    }

    /**
     * 実オッズの注入。JSON ではタプルのキーを表せないので、ワイドは `[[馬番, 馬番, 下限, 上限], …]`、三連複は `[[馬番, 馬番, 馬番, オッズ], …]`
     * （`tools/golden/adapter.py` が旧実装の辞書 `{(a, b): (low, high)}` / `{(a, b, c): odds}` に直す）。キーは昇順で与える（旧実装は昇順のキーで検索する）。
     */
    private fun comboOdds(k: JsonNode?): ComboOdds {
        val wide = LinkedHashMap<WideKey, WideOddsRange>()
        k?.get("wide_odds_map")?.takeUnless { it.isNull }?.values()?.forEach { r ->
            wide[WideKey.of(r[0].asInt("wide_odds_map[0]"), r[1].asInt("wide_odds_map[1]"))] =
                WideOddsRange(r[2].asDouble("wide_odds_map[2]"), r[3].asDouble("wide_odds_map[3]"))
        }
        val sanrenpuku = LinkedHashMap<SanrenpukuKey, Double>()
        k?.get("sanrenpuku_odds_map")?.takeUnless { it.isNull }?.values()?.forEach { r ->
            sanrenpuku[SanrenpukuKey.of(r[0].asInt("sanrenpuku_odds_map[0]"), r[1].asInt("sanrenpuku_odds_map[1]"), r[2].asInt("sanrenpuku_odds_map[2]"))] =
                r[3].asDouble("sanrenpuku_odds_map[3]")
        }
        return ComboOdds(wide, sanrenpuku)
    }

    private val SUGGEST_KWARGS =
        setOf(
            "bankroll",
            "allowed_bet_types",
            "probs_by_umaban",
            "wide_odds_map",
            "sanrenpuku_odds_map",
            "top_k_partners",
            "max_tickets_per_type",
            "box_size",
            "wide_min_prob",
            "wide_max_prob",
            "sanrenpuku_min_prob",
            "sanrenpuku_max_prob",
            "ev_shrink_factor",
            "plackett_luce_alpha",
            "wide_payout_rate",
            "sanrenpuku_payout_rate",
            "wide_max_over_market",
            "sanrenpuku_max_over_market",
            "kelly_fraction",
            "min_bet",
            "max_bet",
            "require_real_odds_for_combo",
            "ticket_allocation",
            "prob_source",
        )

    /**
     * 旧 `suggest_multiple_bet_strategies` の直接呼び出し（`evaluate_odds` と同じ経路: 賭けの方式の引数は `None`）。確率は `probs_by_umaban`
     * （`{"馬番": 確率}`）で**全馬分**を与える（無い馬の 1/オッズ近似は未対応）。戻り値は `evaluate_odds` のシグナルの `bet_strategy` と同じ形。
     */
    private fun suggestMultipleBetStrategies(a: JsonNode): JsonNode {
        val k = kwargs(a, SUGGEST_KWARGS)
        val horses =
            a.get("odds_list").values().map {
                DecisionHorse(umaban = it.int("umaban"), odds = it.double("odds"), popularity = it.int("popularity"))
            }
        val pm = k?.get("probs_by_umaban")?.takeUnless { it.isNull } ?: throw UnsupportedGoldenCall("probs_by_umaban が必要（1/オッズ近似は未対応）")
        val probs =
            horses.map { h ->
                (pm.get(h.umaban.toString()) ?: throw UnsupportedGoldenCall("probs_by_umaban に馬${h.umaban}が無い")).asDouble("probs_by_umaban")
            }
        val d = WinDecisionConfig.legacyFunctionDefaults()

        fun optD(name: String): Double? = k.get(name)?.takeUnless { it.isNull }?.asDouble(name)
        val betTypes =
            k
                .get("allowed_bet_types")
                ?.takeUnless { it.isNull }
                ?.values()
                ?.map { t ->
                    BetType.fromLabelOrNull(t.stringValue()) ?: throw UnsupportedGoldenCall("未知の賭式: ${t.stringValue()}")
                }?.toSet()
        val config =
            d.copy(
                bankroll = optD("bankroll") ?: d.bankroll,
                betTypes = betTypes,
                evShrinkFactor = optD("ev_shrink_factor") ?: d.evShrinkFactor,
                widePayoutRate = optD("wide_payout_rate") ?: d.widePayoutRate,
                sanrenpukuPayoutRate = optD("sanrenpuku_payout_rate") ?: d.sanrenpukuPayoutRate,
                kellyFraction = optD("kelly_fraction") ?: d.kellyFraction,
                minBet = optD("min_bet") ?: d.minBet,
                maxBet = optD("max_bet") ?: d.maxBet,
                requireRealOddsForCombo = k.get("require_real_odds_for_combo")?.let { it.booleanValue() } ?: d.requireRealOddsForCombo,
                ticketAllocation = k.get("ticket_allocation")?.takeUnless { it.isNull }?.stringValue() ?: d.ticketAllocation,
                probSource = k.get("prob_source")?.takeUnless { it.isNull }?.stringValue() ?: "unknown", // 旧関数の既定ラベル
                combo = comboSettings(k, d.combo),
            )
        val stake = WinStake(config.evShrinkFactor, config.kellyFraction, config.bankroll, config.minBet, config.maxBet, config.probSource)
        val race = ComboRaceContext(horses, probs, comboOdds(k), config.combo)
        val bundle =
            BetStrategyBuilder.build(a.int("umaban"), ProbRaw(a.double("win_prob")), a.double("win_odds"), config, stake, race)
                ?: throw UnsupportedGoldenCall("戦略が空になる呼び出しは未対応（旧実装は sum([]) = 0 の int を返す）")
        return strategyJson(bundle)
    }

    private fun evaluateOdds(
        a: JsonNode,
        ml: Boolean = false,
    ): JsonNode {
        val k = kwargs(a, EVALUATE_KWARGS)
        val d = WinDecisionConfig.legacyFunctionDefaults()

        fun optD(name: String): Double? = k?.get(name)?.takeUnless { it.isNull }?.asDouble(name)

        fun optB(name: String): Boolean? = k?.get(name)?.let { if (it.isBoolean) it.booleanValue() else throw UnsupportedGoldenCall("$name は真偽値") }

        fun optS(name: String): String? =
            k
                ?.get(name)
                ?.takeUnless {
                    it.isNull
                }?.let { if (it.isString) it.stringValue() else throw UnsupportedGoldenCall("$name は文字列") }

        val betTypes =
            k
                ?.get("bet_types")
                ?.takeUnless { it.isNull }
                ?.values()
                ?.map { t ->
                    BetType.fromLabelOrNull(t.stringValue()) ?: throw UnsupportedGoldenCall("未知の賭式: ${t.stringValue()}")
                }?.toSet()
        val config =
            d.copy(
                evThreshold = optD("ev_threshold") ?: d.evThreshold,
                maxSignals = k?.get("max_signals")?.asInt("max_signals") ?: d.maxSignals,
                dropThreshold = optD("drop_threshold") ?: d.dropThreshold,
                surgeThreshold = k?.get("surge_threshold")?.asInt("surge_threshold") ?: d.surgeThreshold,
                bankroll = optD("bankroll") ?: d.bankroll,
                includeBetStrategy = optB("include_bet_strategy") ?: d.includeBetStrategy,
                betTypes = betTypes ?: d.betTypes,
                evShrinkFactor = optD("ev_shrink_factor") ?: d.evShrinkFactor,
                winMaxOverMarket = if (k?.has("win_max_over_market") == true) optD("win_max_over_market") else d.winMaxOverMarket,
                winPayoutRate = optD("win_payout_rate") ?: d.winPayoutRate,
                widePayoutRate = optD("wide_payout_rate") ?: d.widePayoutRate,
                sanrenpukuPayoutRate = optD("sanrenpuku_payout_rate") ?: d.sanrenpukuPayoutRate,
                showAdjustedMkt = optB("show_adjusted_mkt") ?: d.showAdjustedMkt,
                // 旧実装の解決順: 引数 > ml_cfg（この呼び出しでは enabled だけ）> 既定 0.25 / 0.35
                marketBlendAlpha = optD("ml_market_blend_alpha") ?: d.marketBlendAlpha,
                probClipMax = optD("win_prob_clip_max") ?: d.probClipMax,
                enableProbClip = optB("enable_prob_clip") ?: d.enableProbClip,
                notifySkips = optB("notify_skips") ?: d.notifySkips,
                kellyFraction = optD("kelly_fraction") ?: d.kellyFraction,
                minBet = optD("min_bet") ?: d.minBet,
                maxBet = optD("max_bet") ?: d.maxBet,
                requireRealOddsForCombo = optB("require_real_odds_for_combo") ?: d.requireRealOddsForCombo,
                ticketAllocation = optS("ticket_allocation") ?: d.ticketAllocation,
                // 旧実装: str(bet_type_mode or "multi").lower() == "best_only"。priority_mode: "hit_rate" 以外は edge
                betTypeMode = if (optS("bet_type_mode")?.lowercase() == "best_only") BetTypeMode.BEST_ONLY else BetTypeMode.MULTI,
                maxTotalBetPerRace = optD("max_total_bet_per_race"),
                raceId = optS("race_id"),
                priorityMode = if (optS("priority_mode") == "hit_rate") PriorityMode.HIT_RATE else PriorityMode.EDGE,
                hitRateMinEv = optD("hit_rate_min_ev"),
                hitRateBetTypePriority =
                    k
                        ?.get("hit_rate_bet_type_priority")
                        ?.takeUnless { it.isNull }
                        ?.values()
                        ?.map { it.stringValue() },
                winOddsMax = optD("win_odds_max"),
                winProbMin = optD("win_prob_min"),
                winRatioMin = optD("win_ratio_min"),
                combo = comboSettings(k, d.combo),
            )

        val horses =
            a.get("odds").values().map {
                DecisionHorse(umaban = it.int("umaban"), odds = it.double("odds"), popularity = it.int("popularity"))
            }
        // 旧 reason 文字列は `{odds_data.odds}`（Python の float の repr）。整数のオッズ（Python では int の repr）は未対応 → double() が弾く
        val provider =
            a.get("snapshots")?.let { s ->
                jp.jrabot.domain.decision.RecentSnapshotsProvider { u ->
                    snapshots(s.get(u.toString()) ?: nodes.arrayNode())
                }
            }
        val result =
            if (ml) {
                val (mlConfig, mlInput) = GoldenMl.prepare(a, config, horses, k)
                WinDecisionEvaluator(mlConfig, provider).evaluate(horses, comboOdds(k), mlInput)
            } else {
                WinDecisionEvaluator(config, provider).evaluate(horses, comboOdds(k))
            }

        val rc = result.legacyRejectCounts
        return obj(
            "return_shape" to if (rc == null) "list" else "tuple",
            "signals" to result.signals.map { signalJson(it) },
            "reject_counts" to
                rc?.let {
                    obj(
                        "ev_below" to it.evBelow,
                        "odds_cap" to it.oddsCap,
                        "prob_below" to it.probBelow,
                        "ratio_below" to it.ratioBelow,
                        "drop_filtered" to it.dropFiltered,
                        "kelly_below_minbet" to it.kellyBelowMinbet,
                        "other" to it.other,
                    )
                },
        )
    }

    private fun signalJson(s: DecisionSignal): JsonNode =
        when (s) {
            is BetSignal -> betSignalJson(s)
            is SkipSignal -> skipSignalJson(s)
        }

    private fun betSignalJson(s: BetSignal): JsonNode {
        val o =
            obj(
                "market" to s.betType.marketCode,
                "bet_type" to s.betType.label,
                "umaban" to s.umaban,
                "odds" to s.odds,
                "popularity" to s.popularity,
                "prob" to s.prob.value,
                "ev" to s.ev.value,
                "bet_amount" to s.betAmount,
                "prob_source" to s.probSource,
                "p_model" to s.pModel.value,
                "p_market" to s.pMarket,
                "p_final" to s.pFinal.value,
                "p_mkt" to s.pMkt,
                "ratio" to s.ratio.value,
                "expected_return" to s.expectedReturn,
                "coverage_score" to s.coverage.coverageScore,
                "beta_eff" to s.coverage.betaEff,
                "has_horse_master" to s.coverage.hasHorseMaster,
                "has_past_runs" to s.coverage.hasPastRuns,
                "past_n" to s.coverage.pastN,
                "_calc_expected_return" to s.calcExpectedReturn,
                "_calc_ev" to s.calcEv.value,
                "_calc_p_mkt" to s.calcPMkt,
                "_calc_ratio" to s.calcRatio.value,
                "reason" to s.reason,
            )
        s.betStrategy?.let { o.set("bet_strategy", strategyJson(it)) }
        return o
    }

    private fun skipSignalJson(s: SkipSignal): JsonNode {
        val o =
            obj(
                "race_id" to s.raceId,
                "umaban" to s.umaban,
                "bet_type" to s.betType.label,
                "ev" to s.ev.value,
                "odds" to s.odds,
                "prob" to s.prob.value,
                "prob_source" to s.probSource,
                "bet_strategy" to strategyJson(s.betStrategy),
                "skip_reason" to s.skipReason,
                "payout_rate" to s.payoutRate,
                "show_adjusted_mkt" to s.showAdjustedMkt,
            )
        s.betAmount?.let { o.set("bet_amount", node(it)) }
        return o
    }

    private fun strategyJson(b: BetStrategyBundle): JsonNode {
        val strategies = nodes.objectNode()
        for (e in b.strategies) strategies.set(e.type.label, entryJson(e))
        val m = b.meta
        val meta =
            obj(
                "require_real_odds_for_combo" to m.requireRealOddsForCombo,
                "ticket_allocation" to m.ticketAllocation,
                "prob_source" to m.probSource,
                "kelly_fraction_input" to m.kellyFractionInput,
                "kelly_fraction_effective" to m.kellyFractionEffective,
                "ev_shrink_factor" to m.evShrinkFactor,
            )
        m.betTypeMode?.let { meta.set("bet_type_mode", node(it)) }
        if (m.raceBetCapApplied) {
            meta.set("race_bet_cap_applied", node(true))
            meta.set("race_bet_cap", node(m.raceBetCap))
            meta.set("race_bet_scale", node(m.raceBetScale))
        }
        return obj(
            "strategies" to strategies,
            "best_strategy" to entryJson(b.best),
            "total_suggested_bet" to b.totalSuggestedBet,
            "total_expected_profit" to b.totalExpectedProfit,
            "meta" to meta,
        )
    }

    private fun entryJson(e: StrategyEntry): JsonNode {
        val o =
            obj(
                "type" to e.type.label,
                "umaban" to e.umaban,
                "odds" to e.odds,
                "prob" to e.probRaw.value,
                "prob_used_for_bet" to e.probUsed.value,
                "ev" to e.ev.value,
                "suggested_bet" to e.suggestedBet,
                "expected_profit" to e.expectedProfit,
                "kelly_fraction" to e.kellyFraction,
                "kelly_raw" to e.kellyRaw,
                "kelly_adjusted" to e.kellyAdjusted,
                "kelly_bet_before_clip" to e.kellyBetBeforeClip,
                "clipped" to e.clipped,
                "skip_reason" to e.skipReason,
                "strategy" to e.strategy,
            )
        if (e.type != BetType.WIN) {
            // ワイド/三連複: 見送りエントリは note と空の tickets、買い目があるときは kumi / combo_key も付く（旧実装の dict のとおり）
            o.set("note", node(e.note))
            o.set("tickets", arr(e.tickets.map { ticketJson(it) }))
            if (e.kumi != null) {
                o.set("kumi", node(e.kumi))
                o.set("combo_key", node(e.kumi))
            }
        }
        return o
    }

    private fun ticketJson(t: ComboTicket): JsonNode {
        val o =
            obj(
                "kumi" to t.kumi,
                "odds" to t.odds,
                "odds_note" to t.oddsNote,
                "prob" to t.probRaw.value,
                "prob_used" to t.probUsed.value,
                "ev" to t.ev.value,
                "source" to t.source.label,
            )
        t.stake?.let { o.set("stake", node(it)) }
        return o
    }
}
