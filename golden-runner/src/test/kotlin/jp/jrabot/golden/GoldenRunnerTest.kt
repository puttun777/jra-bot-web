package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenRunnerTest {
    private val mapper = JsonMapper.builder().build()

    /** 呼び出し列から最小のフィクスチャ JSON を作る（旧実装の期待値は不要。ランナーは input だけを読む）。 */
    private fun fixture(
        caseId: String,
        vararg calls: String,
    ): String = """{"case_id":"$caseId","input":{"calls":[${calls.mapIndexed { i, c -> """{"call_id":"c-$i",$c}""" }.joinToString(",")}]}}"""

    private fun runOn(vararg fixtures: Pair<String, String>): RunOutput {
        val dir = Files.createTempDirectory("golden-runner-test")
        try {
            for ((name, body) in fixtures) Files.writeString(dir.resolve("$name.json"), body)
            Files.writeString(dir.resolve("manifest.json"), "{}") // manifest は無視される
            return GoldenRunner.run(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun outcome(
        out: RunOutput,
        caseId: String,
        index: Int = 0,
    ): JsonNode =
        out.results
            .get("results")
            .get(caseId)
            .get("results")
            .get(index)
            .get("outcome")

    @Test
    fun `浮動小数は小数、整数は整数で出力される（比較器は 1 と 1_0 を別の型として扱う）`() {
        val out =
            runOn(
                "A" to
                    fixture(
                        "A",
                        """"function":"calc_ev","args":{"prob":0.5,"odds":2.0}""",
                        """"function":"check_popularity_surge","args":{"recent_snapshots":[{"odds":9.0,"popularity":5},{"odds":10.0,"popularity":6}],"current_popularity":1}""",
                    ),
            )
        val ev = outcome(out, "A", 0)
        assertEquals("ok", ev.get("status").stringValue())
        assertTrue(ev.get("value").isFloatingPointNumber, "calc_ev の値は float: ${ev.get("value")}")
        assertEquals(0.0, ev.get("value").doubleValue())
        assertTrue(GoldenRunner.toJson(out.results).contains("\"value\":0.0"))
        assertEquals(true, outcome(out, "A", 1).get("value").booleanValue())
    }

    @Test
    fun `odds が 1_0 の Kelly は Python の ZeroDivisionError として出力される`() {
        val out = runOn("K" to fixture("K", """"function":"calc_kelly_fraction","args":{"prob":0.5,"odds":1.0}"""))
        val o = outcome(out, "K")
        assertEquals("exception", o.get("status").stringValue())
        assertEquals("ZeroDivisionError", o.get("exception_type").stringValue())
    }

    @Test
    fun `未対応の関数を含むケースは、一部を実行せずケース全体を未対応にする（結果に含めない）`() {
        val out =
            runOn(
                "OK" to fixture("OK", """"function":"calc_ev","args":{"prob":0.5,"odds":2.0}"""),
                "NG" to fixture("NG", """"function":"calc_ev","args":{"prob":0.5,"odds":2.0}""", """"function":"signature_defaults","args":{}"""),
            )
        assertEquals(
            setOf("OK"),
            out.results
                .get("results")
                .propertyNames()
                .toSet(),
        )
        val uns = out.coverage.get("unsupported")
        assertEquals(1, uns.size())
        assertEquals("NG", uns.get(0).get("case_id").stringValue())
        assertEquals(2, uns.get(0).get("calls").intValue())
        assertTrue(
            uns
                .get(0)
                .get("reason")
                .stringValue()
                .contains("signature_defaults"),
        )
        assertEquals(1, out.coverage.get("supported").size())
    }

    @Test
    fun `整数リテラルを小数の引数に渡す入力は Python では int 演算になるので未対応にする`() {
        val out = runOn("I" to fixture("I", """"function":"calc_ev","args":{"prob":1,"odds":2.0}"""))
        assertEquals(0, out.coverage.get("supported").size())
        assertTrue(
            out.coverage
                .get("unsupported")
                .get(0)
                .get("reason")
                .stringValue()
                .contains("整数"),
        )
    }

    @Test
    fun `未知のキーワード引数は黙って無視せず未対応にする`() {
        val out = runOn("U" to fixture("U", """"function":"calc_kelly_fraction","args":{"prob":0.5,"odds":2.0,"kwargs":{"unknown_option":1.0}}"""))
        assertEquals(0, out.coverage.get("supported").size())
        assertTrue(
            out.coverage
                .get("unsupported")
                .get(0)
                .get("reason")
                .stringValue()
                .contains("unknown_option"),
        )
        val e =
            runOn("E" to fixture("E", """"function":"evaluate_odds","args":{"odds":[{"umaban":1,"odds":3.0,"popularity":1}],"kwargs":{"unknown_option":3}}"""))
        assertEquals(0, e.coverage.get("supported").size())
    }

    @Test
    fun `evaluate_odds の出力の形 - 入力が空なら list と reject_counts の null`() {
        val out = runOn("V" to fixture("V", """"function":"evaluate_odds","args":{"odds":[],"kwargs":{}}"""))
        val v = outcome(out, "V").get("value")
        assertEquals("list", v.get("return_shape").stringValue())
        assertTrue(v.get("reject_counts").isNull)
        assertEquals(0, v.get("signals").size())
    }

    @Test
    fun `evaluate_odds の信号は旧 dict と同じキー集合で、整数のキーは整数`() {
        val horses = """[{"umaban":1,"odds":3.0,"popularity":1},{"umaban":2,"odds":4.5,"popularity":2},{"umaban":3,"odds":7.0,"popularity":3}]"""
        val out = runOn("S" to fixture("S", """"function":"evaluate_odds","args":{"odds":$horses,"kwargs":{"ev_threshold":-0.5,"ev_shrink_factor":1.0}}"""))
        val v = outcome(out, "S").get("value")
        assertEquals("tuple", v.get("return_shape").stringValue())
        val s = v.get("signals").get(0)
        val keys = s.propertyNames().toSet()
        assertEquals(
            setOf(
                "market",
                "bet_type",
                "umaban",
                "odds",
                "popularity",
                "prob",
                "ev",
                "bet_amount",
                "prob_source",
                "p_model",
                "p_market",
                "p_final",
                "p_mkt",
                "ratio",
                "expected_return",
                "coverage_score",
                "beta_eff",
                "has_horse_master",
                "has_past_runs",
                "past_n",
                "_calc_expected_return",
                "_calc_ev",
                "_calc_p_mkt",
                "_calc_ratio",
                "reason",
                "bet_strategy",
            ),
            keys,
        )
        assertTrue(s.get("umaban").isIntegralNumber)
        assertTrue(s.get("popularity").isIntegralNumber)
        assertTrue(s.get("past_n").isIntegralNumber)
        assertTrue(s.get("odds").isFloatingPointNumber)
        assertTrue(s.get("bet_amount").isFloatingPointNumber)
        assertEquals(
            setOf("単勝", "ワイド", "三連複"),
            s
                .get("bet_strategy")
                .get("strategies")
                .propertyNames()
                .toSet(),
        )
        assertEquals(
            setOf("ev_below", "odds_cap", "prob_below", "ratio_below", "drop_filtered", "kelly_below_minbet", "other"),
            v.get("reject_counts").propertyNames().toSet(),
        )
    }

    @Test
    fun `実際のフィクスチャ - 対応と未対応でフィクスチャの全ケース・全呼び出しを過不足なく分類する`() {
        val fixtures = Path.of("..", "tools", "golden", "fixtures")
        check(Files.isDirectory(fixtures)) { "フィクスチャが無い: ${fixtures.toAbsolutePath()}（スキップではなく失敗にする）" }
        val out = GoldenRunner.run(fixtures)
        val supported =
            out.coverage
                .get("supported")
                .values()
                .associate { it.get("case_id").stringValue() to it.get("calls").intValue() }
        val unsupported =
            out.coverage
                .get("unsupported")
                .values()
                .associate { it.get("case_id").stringValue() to it.get("calls").intValue() }

        // 対応表（tools/golden/catalog.json の kotlin_port と同じ内容。食い違いは golden-runner check が失敗にする）
        assertEquals(
            setOf(
                "GD01-EV-001",
                "GD01-KELLY-002",
                "GD02-PROB-001",
                "GD02-NORM-002",
                "GD03-FILTER-001",
                "GD09-EVAL-001",
                "GD03-SNAPSHOT-002",
                "GD04-WIN-HITRATE-003",
                "GD07-SIZING-004",
                "GD08-SELECT-003",
                // T3.2 で対応
                "GD05-COMBO-001",
                "GD05-PL-005",
                "GD05-FLOW-006",
                "GD05-BOX-007",
                "GD05-REAL-008",
                "GD05-FILTER-009",
                "GD07-COMBO-STAKE-010",
                "GD06-EVAL-011",
                // T3.6-A で対応（G1/G2 の既存フィクスチャと、ML 経路・特徴量の新規 5 ケース）
                "G1-MODEL-001",
                "G2-HASH-001",
                "G6-FEATURES-001",
                "G3-ML-NORM-002",
                "G4-ENSEMBLE-002",
                "G5-BLEND-002",
                "G7-LEGACY-FAIL-001",
                // T3.7 で対応（Discord 通知: 分割・Retry-After・重複除外・本文・理由文）
                "G8-SPLIT-001",
                "G8-RETRY-002",
                "G8-DEDUP-003",
                "G8-SINGLE-004",
                "G8-GROUPED-005",
                "G8-REASON-006",
                // T3.9 ① で対応（結果の決済: 当たりのキー・評価の決済・単勝 bets の決済・払戻の取得）
                "GD11-WINKEYS-001",
                "GD11-SETTLE-EVAL-002",
                "GD11-SETTLE-WIN-003",
            ),
            supported.keys,
        )
        assertEquals(setOf("GD10-DEFAULTS-001"), unsupported.keys)

        // 出力されたケースは申告した対応ケースと一致し、全呼び出しに結果がある
        assertEquals(
            supported.keys,
            out.results
                .get("results")
                .propertyNames()
                .toSet(),
        )
        for ((id, n) in supported) {
            assertEquals(
                n,
                out.results
                    .get("results")
                    .get(id)
                    .get("results")
                    .size(),
                id,
            )
        }
        // 旧 Python の 35 ケース・1048 呼び出し（T3.6-A 時点の 26 ケース・880 + T3.7 で追加した Discord 通知 6 ケース・114 + T3.9 ① の決済 3 ケース・54）
        assertEquals(1048, supported.values.sum() + unsupported.values.sum())
        assertEquals(1042, supported.values.sum()) // T3.6-A の 874 + G8 の 114 + GD11 の 54
        assertEquals(6, unsupported.values.sum()) // GD10-DEFAULTS-001（旧関数の既定値。T1.20）だけ
    }

    // ---- T3.2: ワイド/三連複 -----------------------------------------------------------------------------------------

    private val horses4 =
        listOf(3.0, 4.5, 7.0, 12.0).withIndex().joinToString(",", "[", "]") { (i, o) -> """{"umaban":${i + 1},"odds":$o,"popularity":${i + 1}}""" }
    private val probs4 = """{"1":0.4,"2":0.3,"3":0.2,"4":0.1}"""

    @Test
    fun `推定オッズと旧の粗い近似の関数を実行できる`() {
        val out =
            runOn(
                "T" to
                    fixture(
                        "T",
                        """"function":"estimate_wide_odds","args":{"win_odds":3.0,"kwargs":{"num_horses":8}}""",
                        """"function":"estimate_trifecta_odds","args":{"win_odds":10.01}""",
                        """"function":"calculate_wide_prob","args":{"win_prob":0.66,"odds_list":[{"umaban":1,"odds":3.0,"popularity":1}],"target_umaban":1}""",
                        """"function":"calculate_trifecta_prob","args":{"win_prob":0.3,"odds_list":[],"target_umaban":1}""",
                    ),
            )
        assertEquals(1.2000000000000002, outcome(out, "T", 0).get("value").doubleValue())
        assertEquals(0.1001, outcome(out, "T", 1).get("value").doubleValue())
        assertEquals(0.99, outcome(out, "T", 2).get("value").doubleValue())
        assertEquals(0.0, outcome(out, "T", 3).get("value").doubleValue())
    }

    @Test
    fun `suggest_multiple_bet_strategies - 実オッズの配列がタプルキーの辞書として使われ、買い目が旧 dict と同じキーで出力される`() {
        val out =
            runOn(
                "C" to
                    fixture(
                        "C",
                        """"function":"suggest_multiple_bet_strategies","args":{"umaban":1,"win_prob":0.4,"win_odds":3.0,"odds_list":$horses4,
                            |"kwargs":{"probs_by_umaban":$probs4,"ev_shrink_factor":1.0,"wide_max_prob":0.0,"max_tickets_per_type":9,"box_size":3,
                            |"wide_odds_map":[[1,2,50.0,60.0],[1,3,40.0,45.0]],"ticket_allocation":"proportional_ev"}}
                        """.trimMargin(),
                    ),
            )
        val v = outcome(out, "C").get("value")
        assertEquals(setOf("strategies", "best_strategy", "total_suggested_bet", "total_expected_profit", "meta"), v.propertyNames().toSet())
        val wide = v.get("strategies").get("ワイド")
        assertEquals(
            setOf(
                "type",
                "umaban",
                "odds",
                "prob",
                "prob_used_for_bet",
                "ev",
                "suggested_bet",
                "expected_profit",
                "kelly_fraction",
                "kelly_raw",
                "kelly_adjusted",
                "kelly_bet_before_clip",
                "clipped",
                "skip_reason",
                "strategy",
                "note",
                "tickets",
                "kumi",
                "combo_key",
            ),
            wide.propertyNames().toSet(),
        )
        val t = wide.get("tickets").get(0)
        assertEquals(setOf("kumi", "odds", "odds_note", "prob", "prob_used", "ev", "source", "stake"), t.propertyNames().toSet())
        assertEquals("実オッズ", t.get("source").stringValue())
        assertEquals("50.0\u301c60.0", t.get("odds_note").stringValue())
        assertTrue(t.get("stake").isFloatingPointNumber)
        assertEquals(wide.get("kumi").stringValue(), wide.get("combo_key").stringValue())
        // 実オッズの無い三連複は見送りエントリ（kumi / combo_key を持たない）
        val tri = v.get("strategies").get("三連複")
        assertEquals(0, tri.get("tickets").size())
        assertTrue(!tri.has("kumi"))
        assertEquals("※ 実オッズが取れた場合のみ判定/投資します", tri.get("note").stringValue())
    }

    @Test
    fun `evaluate_odds にも実オッズを渡せる - ワイド・三連複の設定キーを受け付ける`() {
        val out =
            runOn(
                "E" to
                    fixture(
                        "E",
                        """"function":"evaluate_odds","args":{"odds":$horses4,"kwargs":{"ev_threshold":-0.5,"ev_shrink_factor":1.0,"bankroll":2000.0,"max_bet":50.0,
                            |"bet_types":["ワイド"],"wide_odds_map":[[1,2,80.0,90.0],[1,3,80.0,90.0],[1,4,80.0,90.0],[2,3,80.0,90.0],[2,4,80.0,90.0],[3,4,80.0,90.0]],
                            |"top_k_partners":2,"max_tickets_per_type":3,"box_size":3,"plackett_luce_alpha":1.5,"wide_max_over_market":0.0}}
                        """.trimMargin(),
                    ),
            )
        val signals = outcome(out, "E").get("value").get("signals")
        assertTrue(signals.size() > 0)
        assertEquals("ワイド", signals.get(0).get("bet_type").stringValue())
        assertEquals("wide", signals.get(0).get("market").stringValue())
    }

    @Test
    fun `確率が与えられない直接呼び出しや、戦略が空になる呼び出しは未対応（旧実装の 1 オッズ近似・int の 0 を再現しない）`() {
        val noProbs =
            runOn(
                "N" to
                    fixture(
                        "N",
                        """"function":"suggest_multiple_bet_strategies","args":{"umaban":1,"win_prob":0.4,"win_odds":3.0,"odds_list":$horses4,"kwargs":{}}""",
                    ),
            )
        assertEquals(0, noProbs.coverage.get("supported").size())
        val empty =
            runOn(
                "M" to
                    fixture(
                        "M",
                        """"function":"suggest_multiple_bet_strategies","args":{"umaban":1,"win_prob":0.4,"win_odds":3.0,"odds_list":$horses4,"kwargs":{"probs_by_umaban":$probs4,"allowed_bet_types":[]}}""",
                    ),
            )
        assertEquals(0, empty.coverage.get("supported").size())
        assertTrue(
            empty.coverage
                .get("unsupported")
                .get(0)
                .get("reason")
                .stringValue()
                .contains("戦略が空"),
        )
    }

    @Test
    fun `整数の実オッズや不明な賭式は未対応にする`() {
        val intOdds =
            runOn(
                "I" to
                    fixture(
                        "I",
                        """"function":"suggest_multiple_bet_strategies","args":{"umaban":1,"win_prob":0.4,"win_odds":3.0,"odds_list":$horses4,"kwargs":{"probs_by_umaban":$probs4,"wide_odds_map":[[1,2,50,60]]}}""",
                    ),
            )
        assertEquals(0, intOdds.coverage.get("supported").size())
        val badType =
            runOn(
                "B" to
                    fixture(
                        "B",
                        """"function":"suggest_multiple_bet_strategies","args":{"umaban":1,"win_prob":0.4,"win_odds":3.0,"odds_list":$horses4,"kwargs":{"probs_by_umaban":$probs4,"allowed_bet_types":["馬単"]}}""",
                    ),
            )
        assertEquals(0, badType.coverage.get("supported").size())
    }

    @Test
    fun `フィクスチャの JSON を読み直しても入力の浮動小数と整数の区別が保たれる`() {
        val n = mapper.readTree("""{"a":3.0,"b":3}""")
        assertTrue(n.get("a").isFloatingPointNumber)
        assertTrue(n.get("b").isIntegralNumber)
    }
}
