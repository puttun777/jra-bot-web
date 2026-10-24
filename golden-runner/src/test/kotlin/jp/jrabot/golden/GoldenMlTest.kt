package jp.jrabot.golden

import jp.jrabot.domain.ml.model.PlattCalibration
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 旧 `WinProbabilityModel` の互換モジュール（切詰め/ゼロ埋め）と、旧 SQLite の読み方の単体テスト。
 * この切詰め/ゼロ埋めは **互換検証専用**で、`:domain` の安全なロード契約（次元が違えば拒否）とは別物であることを確認する。
 */
class GoldenMlTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `旧 _align_feature_matrix は長い特徴量を先頭から切り詰め、短ければ末尾をゼロで埋める`() {
        val m = GoldenMl.LegacyModel(doubleArrayOf(1.0, 2.0, 3.0), 0.0, null, 1.0)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), m.align(doubleArrayOf(1.0, 2.0, 3.0)))
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), m.align(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)))
        assertContentEquals(doubleArrayOf(1.0, 2.0, 0.0), m.align(doubleArrayOf(1.0, 2.0)))
        // 5 次元の重みを 40 次元の特徴量へ当てると先頭 5 列だけが使われる（旧 H2 の元）
        val five = GoldenMl.LegacyModel(DoubleArray(5) { 0.1 }, 0.0, null, 1.0)
        assertEquals(5, five.align(DoubleArray(40) { it.toDouble() }).size)
    }

    @Test
    fun `未学習のモデルは 1 を max(odds, 0_1) で割った値`() {
        val m = GoldenMl.LegacyModel(null, 0.0, null, 1.0)
        assertEquals(1.0 / 2.5, m.predict(2.5, 1))
        assertEquals(10.0, m.predict(0.0, 0)) // odds=0 → max(0, 0.1)=0.1 → 10（最終クリップ前の旧挙動）
        assertEquals(listOf(0.4, 10.0), m.predictBatch(listOf(2.5, 0.0), listOf(1, 0)))
    }

    @Test
    fun `5 市場特徴量は odds が 0 以下なら 100 に置換して対数と交互作用も置換後で計算する`() {
        val f = GoldenMl.LegacyModel.extractFeatures(0.0, 3)
        assertEquals(100.0, f[0])
        assertEquals(3.0, f[1])
        assertEquals(kotlin.math.ln(100.0), f[2])
        assertEquals(1.0 / 3.0, f[3])
        assertEquals(300.0, f[4])
    }

    @Test
    fun `Platt は calib_a と calib_b が両方あるときだけ`() {
        val spec = mapper.readTree("""{"weights":[0.1],"bias":0.0,"calib_a":1.1,"market_blend_alpha":1.0}""")
        assertEquals(null, GoldenMl.LegacyModel.of(spec).platt)
        val both = mapper.readTree("""{"weights":[0.1],"bias":0.0,"calib_a":1.1,"calib_b":-0.2}""")
        assertEquals(PlattCalibration(1.1, -0.2), GoldenMl.LegacyModel.of(both).platt)
    }

    @Test
    fun `旧 SQLite の過去走の順序は date の降順・NULL は最後・同日は race_id の降順`() {
        val db =
            mapper.readTree(
                """{"races":[],"race_horses":[],"horses":[],"horse_past_runs":[
                {"ketto_num":"k","race_id":"A","date":"20251227","finish":2},
                {"ketto_num":"k","race_id":"C","date":"20251227","finish":6},
                {"ketto_num":"k","race_id":"B","date":"20251227","finish":1},
                {"ketto_num":"k","race_id":"N","date":null,"finish":4},
                {"ketto_num":"k","race_id":"E","date":"","finish":7},
                {"ketto_num":"k","race_id":"Z","date":"20251228","finish":0},
                {"ketto_num":"k","race_id":"Y","date":"20251101","finish":null}]}""",
            )
        val src = GoldenMl.LegacySqliteSource(db)
        val runs =
            src.pastRuns(
                jp.jrabot.domain.tenant.TenantId
                    .of(1),
                "k",
                null,
                5,
            )
        assertEquals(listOf("C", "B", "A", "E", "N"), runs.map { it.raceId }) // 着順 0/NULL の行は除外
        assertTrue(runs.take(3).all { it.runDate != null })
        assertTrue(runs.drop(3).all { it.runDate == null })
    }

    @Test
    fun `model_type が auto と ensemble_beta が auto は旧仕様のファイル依存なので未対応にする`() {
        fun run(cfg: String) =
            GoldenCalls.run(
                mapper.readTree(
                    """{"call_id":"x","function":"evaluate_odds_ml","args":{"db":{"races":[],"race_horses":[],"horses":[],"horse_past_runs":[]},"now":"2026-01-01T00:00:00",
                    "race_id":"202601010101","odds":[{"umaban":1,"odds":2.0,"popularity":1}],"models":{},"ml_cfg":$cfg,"kwargs":{}}}""",
                ),
            )
        assertFailsWith<UnsupportedGoldenCall> { run("""{"enabled":true,"model_type":"auto"}""") }
        assertFailsWith<UnsupportedGoldenCall> { run("""{"enabled":true,"model_type":"ensemble","ensemble_beta":"auto"}""") }
        // モデルが無ければ市場確率（旧ラベル）
        val ok = run("""{"enabled":true,"model_type":"calibration"}""")
        assertEquals("ok", ok.get("status").stringValue())
    }
}
