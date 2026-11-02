package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.mltrain.numeric.Metrics
import jp.jrabot.mltrain.numeric.Trainer
import kotlin.math.ceil

/**
 * 学習の計算部分（データセット → 学習 → 校正 → 評価 → 品質ゲート）。保存・登録はしない（[TrainJob] が行う）。旧 job.py の手順 3〜4 の移植。
 * 計算は決定的（乱数なし。seed は記録のためだけに持つ）。
 */
object TrainingRun {
    /** 成果物の producer。旧 Python 版は `ml-train/0.1.0`。実装（ソルバ）が変わったので版を上げる（model_id も変わる = 旧版の登録と衝突しない）。 */
    const val PRODUCER = "ml-train/1.0.0"

    class Outcome(
        val trainIdx: IntArray,
        val calibIdx: IntArray,
        val evalIdx: IntArray,
        val base: Trainer.LinearModel,
        val model: Trainer.LinearModel,
        val platt: Pair<Double, Double>,
        val eval: Metrics.Evaluation,
        val uncalibrated: Map<String, Double>,
        val train: Map<String, Double?>,
        val facts: QualityGate.Facts,
        val gate: QualityGate.Result,
    ) {
        val status: String get() = if (gate.passed) "CANDIDATE" else "REJECTED"
    }

    /** 決定的な model_id（`[A-Za-z0-9_-]{1,64}`）。(テナント, 種別, CSV の hash, 設定, ゲート設定, producer) から決まる。 */
    fun deriveModelId(
        tenantId: Long,
        kind: String,
        csvSha256: String,
        cfg: TrainConfig,
        gate: GateConfig,
        producer: String = PRODUCER,
    ): String {
        val key =
            JsonObject.of(
                "t" to JsonInt(tenantId),
                "k" to JsonString(kind),
                "d" to JsonString(csvSha256),
                "c" to cfg.toJson(),
                "g" to JsonString(gate.digest()),
                "p" to JsonString(producer),
            )
        return "m${kind[0]}-${CanonicalJson.sha256Hex(key).substring(0, 32)}"
    }

    /** valid を日付順に校正用（前半）と評価用（後半）に分ける（同じ日は分けない）。校正に使ったデータで評価しない。 */
    fun splitValid(
        ds: Dataset,
        fraction: Double,
    ): Pair<IntArray, IntArray> {
        val validIdx = ds.isTrain.indices.filter { !ds.isTrain[it] }
        val dates = validIdx.map { ds.raceDate[it] }.toSortedSet().toList()
        val k = minOf(maxOf(1, ceil(dates.size * fraction).toInt()), dates.size - 1)
        val calibDates = dates.subList(0, k).toSet()
        val calib = validIdx.filter { ds.raceDate[it] in calibDates }.toIntArray()
        val ev = validIdx.filter { ds.raceDate[it] !in calibDates }.toIntArray()
        for ((name, idx) in listOf("校正用" to calib, "評価用" to ev)) {
            val pos = idx.sumOf { ds.y[it] }
            if (pos == 0 || pos == idx.size) throw DatasetInsufficient("valid の${name}部分が片方のクラスしか含まない（positives=$pos, rows=${idx.size}）")
        }
        return calib to ev
    }

    fun run(
        ds: Dataset,
        cfg: TrainConfig,
        gate: GateConfig,
        activeLogloss: Double?,
        checkTerminate: () -> Unit = {},
    ): Outcome {
        val tr =
            ds.isTrain.indices
                .filter { ds.isTrain[it] }
                .toIntArray()
        val (calib, ev) = splitValid(ds, cfg.calibrationFraction)
        val w = Trainer.sampleWeights(tr.map { ds.raceDate[it] }, cfg.recencyHalflifeDays)
        val base = Trainer.trainLinear(ds.rows(tr), ds.labels(tr), w, cfg.l2C, cfg.maxIter, cfg.tol)
        checkTerminate()
        val platt = Trainer.fitPlatt(base.predict(ds.rows(calib)), ds.labels(calib))
        val model = base.withPlatt(platt.first, platt.second)
        val evX = ds.rows(ev)
        val evY = ds.labels(ev)
        val oddsCol = ds.schemaNames.indexOf("odds")
        val odds = if (ds.hasOdds) DoubleArray(ev.size) { evX[it][oddsCol] } else null
        val evaluation = Metrics.evaluate(evY, model.predict(evX), ev.map { ds.raceId[it] }, odds)
        val basePred = base.predict(evX)
        val uncal = linkedMapOf("logloss" to Metrics.logloss(evY, basePred), "brier" to Metrics.brier(evY, basePred))
        val trainPred = model.predict(ds.rows(tr))
        val trainM = linkedMapOf<String, Double?>("logloss" to Metrics.logloss(ds.labels(tr), trainPred), "auc" to Metrics.auc(ds.labels(tr), trainPred))
        checkTerminate()
        val validIdx = ds.isTrain.indices.filter { !ds.isTrain[it] }
        val coverage =
            (ds.manifest["quality"] as? JsonObject)?.get("has_horse_master_ratio").asDouble()
                ?: throw DatasetInvalid("manifest.quality.has_horse_master_ratio が数値でない")
        val facts =
            QualityGate.Facts(
                metrics = evaluation.metrics,
                nTrain = tr.size,
                nTrainPos = tr.sumOf { ds.y[it] },
                nValid = validIdx.size,
                nValidPos = validIdx.sumOf { ds.y[it] },
                constantFeatureRatio = Metrics.constantFeatureRatio(ds.rows(tr)),
                horseMasterCoverage = coverage,
                activeLogloss = activeLogloss,
            )
        return Outcome(tr, calib, ev, base, model, platt, evaluation, uncal, trainM, facts, QualityGate.evaluate(gate, facts))
    }
}
