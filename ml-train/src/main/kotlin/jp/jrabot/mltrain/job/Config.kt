package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue

/**
 * 学習設定と品質ゲート設定の読込み・検証（旧 ml/src/ml_train/config.py の移植）。未知のキー・型違い（bool を数値にする等）・範囲外は拒否（終了コード 2）。
 *
 * 学習設定: {"kind": "calibration|alpha", "mode": "v2", "seed": 42, "l2_c": 1.0, "max_iter": 500, "tol": 1e-6,
 *           "recency_halflife_days": 90|180|240, "calibration_fraction": 0.5, "market_blend_alpha_default": 0.25}
 * 品質ゲート設定 — **数値の閾値は未承認（UNRESOLVED）。既定値を持たない**: {"purpose": "synthetic_test|production", "checks": {<check 名>: 数値}}。
 * check が 1 つも無い／ファイルが無い → 未設定 = fail closed（REJECTED。QUALITY_GATE_NOT_CONFIGURED）。
 *
 * 整数と浮動小数は区別する（旧 Python と同じ: `"max_iter": 500.0` は不正、`"l2_c": 1` は数値として可）。
 */
data class TrainConfig(
    val kind: String,
    val seed: Long = 42,
    val l2C: Double = 1.0,
    val maxIter: Int = 500,
    val tol: Double = 1e-6,
    val recencyHalflifeDays: Int = 180,
    val calibrationFraction: Double = 0.5,
    val marketBlendAlphaDefault: Double = 0.25,
    val mode: String = "v2",
) {
    /** 旧版の `to_json`（model_id の導出・レポートに使う。浮動小数のフィールドは必ず浮動小数）。 */
    fun toJson(): JsonObject =
        JsonObject.of(
            "kind" to JsonString(kind),
            "mode" to JsonString(mode),
            "seed" to JsonInt(seed),
            "l2_c" to JsonFloat(l2C),
            "max_iter" to JsonInt(maxIter.toLong()),
            "tol" to JsonFloat(tol),
            "recency_halflife_days" to JsonInt(recencyHalflifeDays.toLong()),
            "calibration_fraction" to JsonFloat(calibrationFraction),
            "market_blend_alpha_default" to JsonFloat(marketBlendAlphaDefault),
        )

    companion object {
        val KINDS = setOf("calibration", "alpha")
        val HALFLIVES = setOf(90, 180, 240)
        private val KEYS =
            setOf("kind", "mode", "seed", "l2_c", "max_iter", "tol", "recency_halflife_days", "calibration_fraction", "market_blend_alpha_default")

        fun fromJson(v: JsonValue): TrainConfig {
            if (v !is JsonObject) throw UsageError("CONFIG_INVALID", "学習設定がオブジェクトでない")
            val unknown = v.fields.keys - KEYS
            if (unknown.isNotEmpty()) throw UsageError("CONFIG_INVALID", "学習設定に未知のキー: ${unknown.sorted()}")
            val kind = (v["kind"] as? JsonString)?.value
            if (kind !in KINDS) throw UsageError("CONFIG_INVALID", "kind は $KINDS")
            val mode = v["mode"]?.let { (it as? JsonString)?.value ?: throw UsageError("CONFIG_INVALID", "mode は文字列") } ?: "v2"
            if (mode != "v2") {
                // 旧学習方式（legacy_compat の再学習: GD・class_weight=balanced）は実装しない。legacy_compat のモデルは pkl 変換でだけ作る（D-165）
                throw UsageError("LEGACY_COMPAT_TRAINING_NOT_IMPLEMENTED", "mode は v2 のみ（legacy_compat の再学習は未実装。旧モデルは convert-pkl で JSON 化する）")
            }
            val halflife = int(v, "recency_halflife_days", 1, 100000, 180)
            if (halflife.toInt() !in HALFLIVES) throw UsageError("CONFIG_INVALID", "recency_halflife_days は $HALFLIVES（ML_DESIGN §11.7）")
            return TrainConfig(
                kind = checkNotNull(kind),
                seed = int(v, "seed", 0, Int.MAX_VALUE.toLong(), 42),
                l2C = num(v, "l2_c", 1e-6, 1e6, 1.0),
                maxIter = int(v, "max_iter", 10, 100000, 500).toInt(),
                tol = num(v, "tol", 1e-12, 1e-1, 1e-6),
                recencyHalflifeDays = halflife.toInt(),
                calibrationFraction = num(v, "calibration_fraction", 0.1, 0.9, 0.5),
                marketBlendAlphaDefault = num(v, "market_blend_alpha_default", 0.0, 1.0, 0.25),
                mode = mode,
            )
        }

        private fun num(
            o: JsonObject,
            key: String,
            lo: Double,
            hi: Double,
            default: Double,
        ): Double {
            val x =
                when (val raw = o[key]) {
                    null -> return default
                    is JsonInt -> raw.value.toDouble()
                    is JsonFloat -> raw.value
                    else -> throw UsageError("CONFIG_INVALID", "$key は数値")
                }
            if (!x.isFinite() || x < lo || x > hi) throw UsageError("CONFIG_INVALID", "$key は [$lo, $hi] の有限値")
            return x
        }

        private fun int(
            o: JsonObject,
            key: String,
            lo: Long,
            hi: Long,
            default: Long,
        ): Long {
            val raw = o[key] ?: return default
            val x = (raw as? JsonInt)?.value ?: throw UsageError("CONFIG_INVALID", "$key は整数")
            if (x < lo || x > hi) throw UsageError("CONFIG_INVALID", "$key は [$lo, $hi] の整数")
            return x
        }
    }
}

/** check 名 → (下限, 上限, 整数か)。値の向き（以上・以下）は [QualityGate] の評価で決まる。旧版の GATE_CHECKS と同じ。 */
val GATE_CHECKS: Map<String, Triple<Double, Double, Boolean>> =
    mapOf(
        "min_train_rows" to Triple(1.0, 1e12, true),
        "min_train_positives" to Triple(1.0, 1e12, true),
        "min_valid_rows" to Triple(1.0, 1e12, true),
        "min_valid_positives" to Triple(1.0, 1e12, true),
        "max_valid_logloss" to Triple(0.0, 100.0, false),
        "max_valid_brier" to Triple(0.0, 1.0, false),
        "min_valid_auc" to Triple(0.0, 1.0, false),
        "max_logloss_minus_market" to Triple(-100.0, 100.0, false),
        "calibration_slope_min" to Triple(-100.0, 100.0, false),
        "calibration_slope_max" to Triple(-100.0, 100.0, false),
        "max_abs_calibration_intercept" to Triple(0.0, 100.0, false),
        "max_constant_feature_ratio" to Triple(0.0, 1.0, false),
        "min_horse_master_coverage" to Triple(0.0, 1.0, false),
        "max_logloss_regression_vs_active" to Triple(-100.0, 100.0, false),
    )

/** 品質ゲート設定。check の値は設定ファイルの JSON の型（整数 / 浮動小数）のまま保つ（digest が旧版と一致するように）。 */
class GateConfig(
    val purpose: String,
    val checks: Map<String, JsonValue>,
) {
    val configured: Boolean get() = checks.isNotEmpty()

    fun threshold(name: String): Double =
        when (val v = checks.getValue(name)) {
            is JsonInt -> v.value.toDouble()
            is JsonFloat -> v.value
            else -> error("検証済みの check が数値でない")
        }

    fun toJson(): JsonObject = JsonObject.of("purpose" to JsonString(purpose), "checks" to JsonObject(LinkedHashMap(checks.toSortedMap())))

    /** 旧版の `digest()`（正準 JSON の SHA-256。model_id の導出に使う）。 */
    fun digest(): String = CanonicalJson.sha256Hex(toJson())

    companion object {
        fun unconfigured(): GateConfig = GateConfig("unconfigured", emptyMap())

        fun fromJson(v: JsonValue): GateConfig {
            if (v !is JsonObject || v.fields.keys != setOf("purpose", "checks")) throw UsageError("GATE_CONFIG_INVALID", "品質ゲート設定は {purpose, checks} のオブジェクト")
            val purpose = (v["purpose"] as? JsonString)?.value
            if (purpose != "synthetic_test" && purpose != "production") throw UsageError("GATE_CONFIG_INVALID", "purpose は synthetic_test または production")
            val raw = v["checks"] as? JsonObject ?: throw UsageError("GATE_CONFIG_INVALID", "checks がオブジェクトでない")
            val checks = LinkedHashMap<String, JsonValue>()
            for ((name, value) in raw.fields) {
                val (lo, hi, integer) = GATE_CHECKS[name] ?: throw UsageError("GATE_CONFIG_INVALID", "未知の check: $name")
                val x =
                    when (value) {
                        is JsonInt -> value.value.toDouble()
                        is JsonFloat -> if (integer) null else value.value
                        else -> null
                    }
                if (x == null || !x.isFinite() || x < lo || x > hi) throw UsageError("GATE_CONFIG_INVALID", "$name の値が不正")
                checks[name] = value
            }
            val g = GateConfig(purpose, checks)
            if (("calibration_slope_min" in checks) != ("calibration_slope_max" in checks)) {
                throw UsageError("GATE_CONFIG_INVALID", "calibration_slope_min と calibration_slope_max は両方指定する")
            }
            if ("calibration_slope_min" in checks && g.threshold("calibration_slope_min") > g.threshold("calibration_slope_max")) {
                throw UsageError("GATE_CONFIG_INVALID", "calibration_slope_min > calibration_slope_max")
            }
            return g
        }
    }
}
