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
                "NG" to fixture("NG", """"function":"calc_ev","args":{"prob":0.5,"odds":2.0}""", """"function":"model_predict","args":{}"""),
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
                .contains("model_predict"),
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
            runOn("E" to fixture("E", """"function":"evaluate_odds","args":{"odds":[{"umaban":1,"odds":3.0,"popularity":1}],"kwargs":{"top_k_partners":3}}"""))
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

        // 対応表（tools/golden/catalog.py の KOTLIN_PORT と同じ内容。食い違いは kotlin_compare が失敗にする）
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
            ),
            supported.keys,
        )
        assertEquals(setOf("G1-MODEL-001", "G2-HASH-001", "GD05-COMBO-001", "GD10-DEFAULTS-001"), unsupported.keys)

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
        assertEquals(562, supported.values.sum() + unsupported.values.sum()) // 旧 Python の 14 ケース・562 呼び出し（10 ケース・447 に 4 ケース・115 を追加）
        assertEquals(380, supported.values.sum())
        assertEquals(182, unsupported.values.sum())
    }

    @Test
    fun `フィクスチャの JSON を読み直しても入力の浮動小数と整数の区別が保たれる`() {
        val n = mapper.readTree("""{"a":3.0,"b":3}""")
        assertTrue(n.get("a").isFloatingPointNumber)
        assertTrue(n.get("b").isIntegralNumber)
    }
}
