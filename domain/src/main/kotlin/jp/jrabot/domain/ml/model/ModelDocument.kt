package jp.jrabot.domain.ml.model

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureSchemaException
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.pipeline.FallbackReason
import java.time.Instant
import java.time.format.DateTimeParseException

/** モデルを読めなかった理由（[FallbackReason] と詳細）。詳細に重み・署名・鍵・モデルの内容を含めない。 */
class ModelRejectedException(
    val reason: FallbackReason,
    message: String,
) : RuntimeException(message)

/** モデルの種別（`ml_model.kind`）。 */
enum class ModelKind(
    val json: String,
) {
    CALIBRATION("calibration"),
    ALPHA("alpha"),
    LEGACY_COMPAT("legacy_compat"),
    ENSEMBLE_SET("ensemble_set"),
    ;

    /** この種別のモデルが持てる特徴量スキーマ。 */
    fun allowedSchemas(): List<FeatureSchema> =
        when (this) {
            CALIBRATION -> listOf(FeatureSchema.CALIBRATION_40)
            ALPHA -> listOf(FeatureSchema.ALPHA_35)
            LEGACY_COMPAT -> listOf(FeatureSchema.MARKET_5, FeatureSchema.CALIBRATION_40)
            ENSEMBLE_SET -> emptyList()
        }

    companion object {
        fun fromJson(text: String): ModelKind? = entries.firstOrNull { it.json == text }
    }
}

/** 前処理 `standardize`（v2。`(x - mean) / std`）。std は正の有限値。 */
class Standardize(
    mean: DoubleArray,
    std: DoubleArray,
) {
    val mean: DoubleArray = mean.copyOf()
    val std: DoubleArray = std.copyOf()

    init {
        require(mean.size == std.size) { "mean と std の長さが違う" }
        require(mean.all { it.isFinite() } && std.all { it.isFinite() && it > 0.0 }) { "mean は有限、std は正の有限値" }
    }

    fun apply(x: DoubleArray): DoubleArray {
        require(x.size == mean.size) { "特徴量の数 ${x.size} と前処理の数 ${mean.size} が違う" }
        return DoubleArray(x.size) { (x[it] - mean[it]) / std[it] }
    }
}

/** ensemble セット（ML_DESIGN §5.2）。メンバーは calibration と alpha のモデル ID。β は [0, 1]、coverage_weights は 3 要素の非負の有限値。 */
class EnsembleSpec(
    val calibrationModelId: ModelId,
    val alphaModelId: ModelId,
    val beta: Double,
    coverageWeights: List<Double>,
) {
    val coverageWeights: List<Double> = coverageWeights.toList()

    init {
        require(beta.isFinite() && beta in 0.0..1.0) { "beta は [0, 1]" }
        require(this.coverageWeights.size == 3 && this.coverageWeights.all { it.isFinite() && it >= 0.0 }) { "coverage_weights は 3 要素の非負の有限値" }
        require(calibrationModelId != alphaModelId) { "メンバーが同じモデル" }
    }
}

/**
 * 検証前の（構文と意味を検査しただけの）モデル JSON。**署名と sha256 の検証は [ModelLoader] が行う**。この型を推論に使えない（[VerifiedModel] だけが使える）。
 * 形式は ML_DESIGN §5.2。未知のキーは拒否する（黙って無視しない）。
 */
class ModelDocument internal constructor(
    val modelSchemaVersion: Int,
    val modelId: ModelId,
    val tenantId: Long,
    val kind: ModelKind,
    val algorithm: String,
    val featureSchema: FeatureSchema?,
    val preprocessing: Standardize?,
    weights: DoubleArray?,
    val bias: Double?,
    val calibration: PlattCalibration?,
    val marketBlendAlphaDefault: Double,
    val ensemble: EnsembleSpec?,
    val training: JsonObject,
    val metrics: JsonObject,
    val createdAt: Instant,
    val producer: String,
    val sha256: String,
    /** 全体（sha256 を含む）の JSON 木。 */
    internal val json: JsonObject,
) {
    private val weightData = weights?.copyOf()

    fun weights(): DoubleArray? = weightData?.copyOf()

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val ALGORITHM_LOGISTIC = "logistic_regression"
        const val ALGORITHM_ENSEMBLE = "ensemble_set"
        private val TOP_KEYS =
            setOf(
                "model_schema_version",
                "model_id",
                "tenant_id",
                "kind",
                "algorithm",
                "feature_schema",
                "preprocessing",
                "weights",
                "bias",
                "calibration",
                "market_blend",
                "ensemble",
                "training",
                "metrics",
                "created_at",
                "producer",
                "sha256",
            )
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** `sha256` フィールドを除いた正準 JSON の SHA-256（小文字 16 進 64 桁）。 */
        fun computeSha256(json: JsonObject): String = CanonicalJson.sha256Hex(json.without("sha256"))

        /** 構文・型・値の範囲を検査して読む。検査に失敗すれば [ModelRejectedException]。署名・sha256 の一致は検査しない。 */
        fun parse(json: JsonObject): ModelDocument {
            val unknown = json.fields.keys - TOP_KEYS
            if (unknown.isNotEmpty()) reject(FallbackReason.MODEL_INVALID, "未知のキー: ${unknown.sorted()}")
            val missing = TOP_KEYS - json.fields.keys
            if (missing.isNotEmpty()) reject(FallbackReason.MODEL_INVALID, "必須キーが無い: ${missing.sorted()}")

            val version = int(json, "model_schema_version")
            if (version != SUPPORTED_SCHEMA_VERSION.toLong()) reject(FallbackReason.MODEL_SCHEMA_MISMATCH, "未対応の model_schema_version: $version")
            val modelId = ModelId.ofOrNull(str(json, "model_id")) ?: reject(FallbackReason.MODEL_INVALID, "model_id の形式が不正")
            val tenantId = int(json, "tenant_id")
            if (tenantId < 1) reject(FallbackReason.MODEL_INVALID, "tenant_id が不正")
            val kind = ModelKind.fromJson(str(json, "kind")) ?: reject(FallbackReason.MODEL_INVALID, "kind が不正")
            val algorithm = str(json, "algorithm")

            val training = obj(json, "training")
            val metrics = obj(json, "metrics")
            val createdAt =
                try {
                    Instant.parse(str(json, "created_at"))
                } catch (e: DateTimeParseException) {
                    reject(FallbackReason.MODEL_INVALID, "created_at が ISO-8601 (UTC) でない")
                }
            val producer = str(json, "producer")
            if (producer.isEmpty() || producer.length > 100) reject(FallbackReason.MODEL_INVALID, "producer が不正")
            val sha = str(json, "sha256")
            if (!SHA256.matches(sha)) reject(FallbackReason.MODEL_INVALID, "sha256 の形式が不正")

            val blend = obj(json, "market_blend")
            if (blend.fields.keys != setOf("alpha_default")) reject(FallbackReason.MODEL_INVALID, "market_blend のキーが不正")
            val alphaDefault = number(blend["alpha_default"], "market_blend.alpha_default")
            if (alphaDefault !in 0.0..1.0) reject(FallbackReason.MODEL_INVALID, "market_blend.alpha_default が [0, 1] の外")

            return if (kind == ModelKind.ENSEMBLE_SET) {
                parseEnsemble(json, modelId, tenantId, algorithm, alphaDefault, training, metrics, createdAt, producer, sha)
            } else {
                parseLinear(json, modelId, tenantId, kind, algorithm, alphaDefault, training, metrics, createdAt, producer, sha)
            }
        }

        private fun parseLinear(
            json: JsonObject,
            modelId: ModelId,
            tenantId: Long,
            kind: ModelKind,
            algorithm: String,
            alphaDefault: Double,
            training: JsonObject,
            metrics: JsonObject,
            createdAt: Instant,
            producer: String,
            sha: String,
        ): ModelDocument {
            if (algorithm != ALGORITHM_LOGISTIC) reject(FallbackReason.MODEL_INVALID, "algorithm が logistic_regression でない")
            if (json["ensemble"] != JsonNull) reject(FallbackReason.MODEL_INVALID, "線形モデルの ensemble は null")
            val schema =
                try {
                    FeatureSchema.fromJson(json["feature_schema"] as? JsonObject ?: reject(FallbackReason.MODEL_INVALID, "feature_schema がオブジェクトでない"))
                } catch (e: FeatureSchemaException) {
                    reject(FallbackReason.FEATURE_SCHEMA_MISMATCH, e.message ?: "feature_schema が不正")
                }
            // 種別が持てるスキーマ（かつ、このランタイムが特徴量を作れるスキーマ）だけを受け入れる。暗黙の補正をしない。
            if (kind.allowedSchemas().none { it == schema }) {
                reject(FallbackReason.FEATURE_SCHEMA_MISMATCH, "kind=${kind.json} に許されない feature_schema (${schema.size} 次元)")
            }
            val wArr = json["weights"] as? JsonArray ?: reject(FallbackReason.MODEL_INVALID, "weights が配列でない")
            if (wArr.items.size != schema.size) {
                reject(FallbackReason.INVALID_MODEL_DIMENSION, "重みの数 ${wArr.items.size} が特徴量の数 ${schema.size} と違う")
            }
            val weights = DoubleArray(wArr.items.size) { number(wArr.items[it], "weights[$it]") }
            val bias = number(json["bias"], "bias")

            val pre = json["preprocessing"]
            val standardize =
                when (pre) {
                    JsonNull -> {
                        null
                    }

                    is JsonObject -> {
                        if (pre.fields.keys != setOf("type", "mean", "std") || (pre["type"] as? JsonString)?.value != "standardize") {
                            reject(FallbackReason.MODEL_INVALID, "preprocessing が standardize でない")
                        }
                        val mean = doubles(pre["mean"], "preprocessing.mean")
                        val std = doubles(pre["std"], "preprocessing.std")
                        if (mean.size != schema.size || std.size != schema.size) {
                            reject(FallbackReason.INVALID_MODEL_DIMENSION, "前処理の長さが特徴量の数と違う")
                        }
                        try {
                            Standardize(mean, std)
                        } catch (e: IllegalArgumentException) {
                            reject(FallbackReason.INVALID_MODEL_DIMENSION, "前処理の値が不正")
                        }
                    }

                    else -> {
                        reject(FallbackReason.MODEL_INVALID, "preprocessing の型が不正")
                    }
                }

            val cal = json["calibration"]
            val platt =
                when (cal) {
                    JsonNull -> {
                        null
                    }

                    is JsonObject -> {
                        if (cal.fields.keys != setOf("type", "a", "b") || (cal["type"] as? JsonString)?.value != "platt") {
                            reject(FallbackReason.MODEL_INVALID, "calibration が platt でない")
                        }
                        PlattCalibration(number(cal["a"], "calibration.a"), number(cal["b"], "calibration.b"))
                    }

                    else -> {
                        reject(FallbackReason.MODEL_INVALID, "calibration の型が不正")
                    }
                }
            return ModelDocument(
                SUPPORTED_SCHEMA_VERSION,
                modelId,
                tenantId,
                kind,
                algorithm,
                schema,
                standardize,
                weights,
                bias,
                platt,
                alphaDefault,
                null,
                training,
                metrics,
                createdAt,
                producer,
                sha,
                json,
            )
        }

        private fun parseEnsemble(
            json: JsonObject,
            modelId: ModelId,
            tenantId: Long,
            algorithm: String,
            alphaDefault: Double,
            training: JsonObject,
            metrics: JsonObject,
            createdAt: Instant,
            producer: String,
            sha: String,
        ): ModelDocument {
            if (algorithm != ALGORITHM_ENSEMBLE) reject(FallbackReason.MODEL_INVALID, "ensemble_set の algorithm が不正")
            for (k in listOf("feature_schema", "preprocessing", "weights", "bias", "calibration")) {
                if (json[k] != JsonNull) reject(FallbackReason.MODEL_INVALID, "ensemble_set の $k は null")
            }
            val e = json["ensemble"] as? JsonObject ?: reject(FallbackReason.MODEL_INVALID, "ensemble がオブジェクトでない")
            if (e.fields.keys != setOf("members", "beta", "coverage_weights")) reject(FallbackReason.MODEL_INVALID, "ensemble のキーが不正")
            val members = e["members"] as? JsonObject ?: reject(FallbackReason.MODEL_INVALID, "ensemble.members がオブジェクトでない")
            if (members.fields.keys != setOf("calibration", "alpha")) reject(FallbackReason.MODEL_INVALID, "ensemble.members のキーが不正")
            val calId = ModelId.ofOrNull(str(members, "calibration")) ?: reject(FallbackReason.MODEL_INVALID, "メンバーの model_id が不正")
            val alphaId = ModelId.ofOrNull(str(members, "alpha")) ?: reject(FallbackReason.MODEL_INVALID, "メンバーの model_id が不正")
            val spec =
                try {
                    EnsembleSpec(calId, alphaId, number(e["beta"], "ensemble.beta"), doubles(e["coverage_weights"], "ensemble.coverage_weights").toList())
                } catch (ex: IllegalArgumentException) {
                    reject(FallbackReason.MODEL_INVALID, "ensemble の値が不正")
                }
            return ModelDocument(
                SUPPORTED_SCHEMA_VERSION,
                modelId,
                tenantId,
                ModelKind.ENSEMBLE_SET,
                algorithm,
                null,
                null,
                null,
                null,
                null,
                alphaDefault,
                spec,
                training,
                metrics,
                createdAt,
                producer,
                sha,
                json,
            )
        }

        private fun reject(
            reason: FallbackReason,
            message: String,
        ): Nothing = throw ModelRejectedException(reason, message)

        private fun str(
            o: JsonObject,
            key: String,
        ): String = (o[key] as? JsonString)?.value ?: reject(FallbackReason.MODEL_INVALID, "$key が文字列でない")

        private fun int(
            o: JsonObject,
            key: String,
        ): Long = (o[key] as? JsonInt)?.value ?: reject(FallbackReason.MODEL_INVALID, "$key が整数でない")

        private fun obj(
            o: JsonObject,
            key: String,
        ): JsonObject = o[key] as? JsonObject ?: reject(FallbackReason.MODEL_INVALID, "$key がオブジェクトでない")

        /** 整数または浮動小数を有限な Double として読む。 */
        private fun number(
            v: JsonValue?,
            name: String,
        ): Double =
            when (v) {
                is JsonInt -> v.value.toDouble()
                is JsonFloat -> v.value
                else -> reject(FallbackReason.INVALID_MODEL_DIMENSION, "$name が数値でない")
            }

        private fun doubles(
            v: JsonValue?,
            name: String,
        ): DoubleArray {
            val a = v as? JsonArray ?: reject(FallbackReason.MODEL_INVALID, "$name が配列でない")
            return DoubleArray(a.items.size) { number(a.items[it], "$name[$it]") }
        }
    }
}
