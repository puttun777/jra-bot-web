package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.StrictJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 学習 Job の計算部分（分割・直近重み・学習・Platt・評価・品質ゲート・model_id）を、コミット済みの合成データセットに対して実行し、
 * 削除前の Python 実装で生成した `dataset_cases` と比べる。合成データセットは 60 行と小さく、校正用は 3 日分しかない（分離に近い）ので、
 * Platt の a・b は大きく、ソルバの停止位置の差が大きく出る。ここでは**目的関数（Kotlin ≤ Python）と、確率・指標・判定**で比べる。
 */
class PythonReferencePipelineTest {
    private val ref: JsonNode = ObjectMapper().readTree(requireNotNull(javaClass.getResourceAsStream("/python-reference/ml_reference_v1.json")))
    private val dir: Path = Path.of("..", "domain", "src", "test", "resources", "ml", "dataset")

    private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun load(kind: String): Dataset =
        Dataset.load(
            gz(Files.readAllBytes(dir.resolve("synthetic-$kind.csv"))),
            Files.readAllBytes(dir.resolve("synthetic-$kind.manifest.json")),
            1,
            "synthetic-$kind",
        )

    private fun JsonNode.ints(): List<Int> = (0 until size()).map { get(it).asInt() }

    private fun JsonNode.doubles(): DoubleArray = DoubleArray(size()) { get(it).asDouble() }

    private fun close(
        e: Double,
        a: Double,
        tol: Double,
        what: String,
    ) = assertTrue(abs(e - a) <= tol, "$what: 期待 $e 実際 $a 差 ${abs(e - a)}")

    @Test
    fun `pipeline on the synthetic datasets matches the Python reference`() {
        val cases = ref["dataset_cases"]
        assertEquals(4, cases.size())
        for ((i, c) in cases.withIndex()) {
            val kind = c["kind"].asString()
            val ds = load(kind)
            val cfg = TrainConfig.fromJson(StrictJson.parse(c["config"].toString()))
            val gate = if (c["gate"].isNull) GateConfig.unconfigured() else GateConfig.fromJson(StrictJson.parse(c["gate"].toString()))
            val tag = "case$i($kind l2_c=${cfg.l2C})"
            // 設定の正準形・ゲートの digest・model_id の導出が旧版とバイト単位で一致する（producer は旧版の値を渡して比べる）
            assertEquals(c["gate_digest"].asString(), gate.digest(), "$tag gate digest")
            assertEquals(c["model_id"].asString(), TrainingRun.deriveModelId(1, ds.kind, ds.csvSha256, cfg, gate, "ml-train/0.1.0"), "$tag model_id")
            val out = TrainingRun.run(ds, cfg, gate, null)
            assertEquals(c["train_idx"].ints(), out.trainIdx.toList(), "$tag train")
            assertEquals(c["calib_idx"].ints(), out.calibIdx.toList(), "$tag calib")
            assertEquals(c["eval_idx"].ints(), out.evalIdx.toList(), "$tag eval")
            val w =
                jp.jrabot.mltrain.numeric.Trainer
                    .sampleWeights(out.trainIdx.map { ds.raceDate[it] }, cfg.recencyHalflifeDays)
            c["sample_weights"].doubles().forEachIndexed { k, v -> close(v, w[k], 1e-12, "$tag w[$k]") }
            c["mean"].doubles().forEachIndexed { k, v -> close(v, out.base.standardizer.mean[k], 1e-12, "$tag mean[$k]") }
            c["std"].doubles().forEachIndexed { k, v -> close(v, out.base.standardizer.std[k], 1e-12, "$tag std[$k]") }
            val xs = out.base.standardizer.apply(ds.rows(out.trainIdx))
            val objK =
                jp.jrabot.mltrain.numeric.Logistic
                    .objective(xs, ds.labels(out.trainIdx), w, out.base.weights, out.base.bias, cfg.l2C)
            val objP = c["objective"].asDouble()
            assertTrue(objK <= objP * (1 + 1e-12) + 1e-12 && (objP - objK) / objP <= 1e-6, "$tag 目的関数 K=$objK P=$objP")
            c["weights"].doubles().forEachIndexed { k, v -> close(v, out.base.weights[k], 1e-4, "$tag weight[$k]") }
            close(c["bias"].asDouble(), out.base.bias, 1e-4, "$tag bias")
            val uncal = out.base.predict(ds.rows(out.evalIdx))
            c["eval_pred_uncalibrated"].doubles().forEachIndexed { k, v -> close(v, uncal[k], 1e-4, "$tag uncal[$k]") }
            val pred = out.model.predict(ds.rows(out.evalIdx))
            println(
                "MEASURED $tag: objective rel=${(objP - objK) / objP} platt K=${out.platt} P=${c["platt"]} |dpred|max=${c["eval_pred"].doubles().indices.maxOf {
                    abs(c["eval_pred"][it].asDouble() - pred[it])
                }}",
            )
            // Platt は分離に近いので、校正後の確率は 1e-3 で比べる（実測 ≤ 8.1e-5）（実測は下の MEASURED。判定・指標は完全一致を要求）
            c["eval_pred"].doubles().forEachIndexed { k, v -> close(v, pred[k], 1e-3, "$tag pred[$k]") }
            val m = out.eval.metrics
            assertEquals(c["metrics"]["n"].asInt(), m["n"])
            close(c["metrics"]["logloss"].asDouble(), m["logloss"] as Double, 1e-3, "$tag logloss")
            assertEquals(c["metrics"]["auc"].asDouble(), m["auc"] as Double, "$tag auc（順位だけで決まる）")
            assertEquals(c["status"].asString(), out.status, "$tag status")
            assertEquals(c["gate_result"]["verdict"].asString(), out.gate.verdict, "$tag verdict")
            assertEquals((0 until c["gate_result"]["reasons"].size()).map { c["gate_result"]["reasons"][it].asString() }, out.gate.reasons, "$tag reasons")
            close(c["constant_feature_ratio"].asDouble(), out.facts.constantFeatureRatio, 1e-12, "$tag constant ratio")
        }
    }
}
