package jp.jrabot.mltrain.numeric

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 旧 Python 実装（ml/src/ml_train、scikit-learn 1.9.1 の lbfgs）で**削除前に生成した**数値フィクスチャ（python-reference/ml_reference_v1.json）と、
 * Kotlin の学習・校正・指標が許容差内で一致することを検査する（LANGUAGE_MIGRATION §4.2・ML_DESIGN §16）。期待値を Kotlin の出力から作り直さない。
 *
 * 許容差の考え方: 同じ目的関数の一意な最適解を、Kotlin は Newton 法で 1e-10 まで、lbfgs は tol で早めに止めるので、
 * **Kotlin の目的関数値は Python 以下（悪化しない）**であることを第一に確認し、パラメータ・予測はその差から決まる幅で比べる。
 * 閉じた式で決まる指標（logloss・Brier・AUC・分位点・信頼度ビン・正規化）は 1e-12 で比べる。
 * 実測（2026-11-02、JDK 25）: 目的関数は 4 ケースとも Kotlin ≤ Python（相対差 ≤ 1.7e-12）、重み・切片の差 ≤ 2.4e-6、確率の差 ≤ 2.0e-6、Platt の差 ≤ 1.6e-9。
 * 許容差（重み・確率 1e-4、Platt 1e-6）は実測の 40 倍以上の余裕で、lbfgs の停止誤差（tol=1e-6）の上界から決めた（ML_DESIGN §16）。
 */
class PythonReferenceTest {
    private val ref: JsonNode =
        ObjectMapper().readTree(requireNotNull(javaClass.getResourceAsStream("/python-reference/ml_reference_v1.json")) { "フィクスチャが無い" })

    private fun JsonNode.doubles(): DoubleArray = DoubleArray(size()) { get(it).asDouble() }

    private fun JsonNode.ints(): IntArray = IntArray(size()) { get(it).asInt() }

    private fun JsonNode.matrix(): Array<DoubleArray> = Array(size()) { get(it).doubles() }

    private fun assertClose(
        expected: Double,
        actual: Double,
        tol: Double,
        what: String,
    ) = assertTrue(abs(expected - actual) <= tol, "$what: 期待 $expected 実際 $actual 差 ${abs(expected - actual)} > $tol")

    private fun assertClose(
        expected: DoubleArray,
        actual: DoubleArray,
        tol: Double,
        what: String,
    ) {
        assertEquals(expected.size, actual.size, "$what の長さ")
        for (i in expected.indices) assertClose(expected[i], actual[i], tol, "$what[$i]")
    }

    @Test
    fun `fixture provenance is the Python implementation`() {
        assertEquals(1, ref["fixture_schema_version"].asInt())
        assertEquals("1.9.1", ref["runtime"]["scikit-learn"].asString())
        assertTrue(ref["generated_from_commit"].asString().matches(Regex("[0-9a-f]{40}")))
        assertTrue(ref["generator_source"].asString().contains("trainer.train_linear"))
    }

    @Test
    fun `solver reaches the same optimum as sklearn lbfgs (objective not worse, parameters close)`() {
        val cases = ref["solver_cases"]
        assertEquals(4, cases.size())
        for (c in cases) {
            val name = c["name"].asString()
            val x = c["x"].matrix()
            val y = c["y"].ints()
            val w = c["w"].doubles()
            val m = Trainer.trainLinear(x, y, w, c["l2_c"].asDouble(), c["max_iter"].asInt(), c["tol"].asDouble())
            assertClose(c["mean"].doubles(), m.standardizer.mean, 1e-12, "$name mean")
            assertClose(c["std"].doubles(), m.standardizer.std, 1e-12, "$name std")
            val xs = m.standardizer.apply(x)
            val objK = Logistic.objective(xs, y, w, m.weights, m.bias, c["l2_c"].asDouble())
            val objP = c["objective"].asDouble()
            assertTrue(objK <= objP * (1 + 1e-12) + 1e-12, "$name: Kotlin の目的関数 $objK が Python $objP より悪い")
            assertTrue((objP - objK) / objP <= 1e-6, "$name: 目的関数の相対差 ${(objP - objK) / objP} > 1e-6")
            val wDiff = c["weights"].doubles().zip(m.weights.toList()).maxOf { (a, b) -> abs(a - b) }
            val pDiff = DoubleArray(x.size) { abs(m.rawProbability(x[it]) - c["raw_probability"][it].asDouble()) }.max()
            println(
                "MEASURED $name: objective P=$objP K=$objK rel=${(objP - objK) / objP} |dw|max=$wDiff |db|=${abs(
                    m.bias - c["bias"].asDouble(),
                )} |dp|max=$pDiff",
            )
            assertClose(c["weights"].doubles(), m.weights, 1e-4, "$name weights")
            assertClose(c["bias"].asDouble(), m.bias, 1e-4, "$name bias")
            assertClose(c["raw_probability"].doubles(), DoubleArray(x.size) { m.rawProbability(x[it]) }, 1e-4, "$name prob")
        }
    }

    @Test
    fun `platt calibration matches on well-conditioned data`() {
        for (c in ref["platt_cases"]) {
            val (a, b) = Trainer.fitPlatt(c["p"].doubles(), c["y"].ints())
            println("MEASURED ${c["name"].asString()}: |da|=${abs(a - c["a"].asDouble())} |db|=${abs(b - c["b"].asDouble())}")
            assertClose(c["a"].asDouble(), a, 1e-6, "${c["name"].asString()} a")
            assertClose(c["b"].asDouble(), b, 1e-6, "${c["name"].asString()} b")
        }
    }

    @Test
    fun `closed-form metrics match exactly and calibration slope within the solver tolerance`() {
        for (c in ref["metrics_cases"]) {
            val name = c["name"].asString()
            val y = c["y"].ints()
            val p = c["p"].doubles()
            val race = (0 until c["race_id"].size()).map { c["race_id"][it].asString() }
            val ev = Metrics.evaluate(y, p, race, c["odds"].doubles())
            val exp = c["metrics"]
            for (k in listOf(
                "logloss",
                "brier",
                "auc",
                "accuracy_at_0_5",
                "logloss_race_normalized",
                "brier_race_normalized",
                "top1_hit_rate",
                "positive_rate",
                "baseline_market_logloss",
                "baseline_market_brier",
                "baseline_market_auc",
                "baseline_market_top1_hit_rate",
                "baseline_market_logloss_raw_overround",
            )) {
                assertClose(exp[k].asDouble(), (ev.metrics[k] as Number).toDouble(), 1e-12, "$name $k")
            }
            for (k in listOf("n", "n_positive", "n_races")) assertEquals(exp[k].asInt(), (ev.metrics[k] as Number).toInt(), "$name $k")
            assertClose(exp["calibration_slope"].asDouble(), (ev.metrics["calibration_slope"] as Number).toDouble(), 1e-4, "$name slope")
            assertClose(exp["calibration_intercept"].asDouble(), (ev.metrics["calibration_intercept"] as Number).toDouble(), 1e-4, "$name intercept")
            @Suppress("UNCHECKED_CAST")
            val dist = ev.metrics["distribution"] as Map<String, Any>
            assertClose(exp["distribution"]["mean"].asDouble(), dist["mean"] as Double, 1e-12, "$name mean")
            @Suppress("UNCHECKED_CAST")
            val qs = dist["quantiles"] as Map<String, Double>
            assertEquals(exp["distribution"]["quantiles"].propertyNames().toList(), qs.keys.toList(), "$name 分位点のキー")
            for (k in qs.keys) assertClose(exp["distribution"]["quantiles"][k].asDouble(), qs.getValue(k), 1e-12, "$name q$k")
            @Suppress("UNCHECKED_CAST")
            val rel = ev.metrics["reliability"] as List<Map<String, Any>>
            assertEquals(exp["reliability"].size(), rel.size, "$name ビン数")
            for (i in rel.indices) {
                assertEquals(exp["reliability"][i]["n"].asInt(), rel[i]["n"], "$name bin $i n")
                assertClose(exp["reliability"][i]["mean_pred"].asDouble(), rel[i]["mean_pred"] as Double, 1e-12, "$name bin $i")
                assertClose(exp["reliability"][i]["observed"].asDouble(), rel[i]["observed"] as Double, 1e-12, "$name bin $i obs")
            }
            assertClose(c["race_normalized"].doubles(), Metrics.raceNormalize(race, p), 1e-12, "$name race_normalized")
            assertNotNull(ev.metrics["market_baseline_available"])
        }
    }
}
