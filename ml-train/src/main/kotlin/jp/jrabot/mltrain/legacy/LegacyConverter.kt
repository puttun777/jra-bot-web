package jp.jrabot.mltrain.legacy

import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.mltrain.job.MlTrainError
import jp.jrabot.mltrain.job.ModelJson
import jp.jrabot.mltrain.job.TrainingRun

/** 変換を中止した（理由コードは固定語彙。メッセージに数値・名前の中身を含めない）。 */
class ConversionRefused(
    reason: String,
    message: String,
) : MlTrainError(reason, message)

class Conversion(
    val model: JsonObject,
    val warnings: List<String>,
)

/**
 * 旧 `WinProbabilityModel`（[RestrictedPickle] で読んだ入れ物）→ モデル JSON（kind=legacy_compat / alpha）。旧 convert.py の移植。
 * **パディング/スライスで次元を合わせない**（不一致は中止）。検査の順序と理由コードは旧版と同じ（削除前の Python で固定した判定と一致させる）。
 * 既知のスキーマは Kotlin の [FeatureSchema]（唯一の定義）から取る（旧版は Kotlin が書いた JSON ファイルを読んでいた）。
 */
object LegacyConverter {
    const val PRODUCER_SUFFIX = "/convert-pkl"
    private val ALLOWED = setOf("weights", "bias", "calib_a", "calib_b", "market_blend_alpha", "feature_names")
    private val REQUIRED = setOf("weights", "bias", "feature_names")

    private fun finiteFloat(
        v: PValue?,
        what: String,
    ): Double {
        val x =
            when (v) {
                is PFloat -> v.value
                is PInt -> v.value.toDouble()
                is PScalar -> v.float ?: v.integer?.toDouble()?.takeIf { v.dtype.kind != 'b' }
                else -> null
            } ?: throw ConversionRefused("INVALID_NUMBER", "$what が数値でない")
        if (!x.isFinite()) throw ConversionRefused("INVALID_NUMBER", "$what が有限でない")
        return x
    }

    private fun featureNames(v: PValue?): List<String> {
        val list = (v as? PList)?.items
        if (list == null || list.isEmpty() || list.size > 4096) throw ConversionRefused("INVALID_FEATURE_NAMES", "feature_names が空でないリストでない")
        val names = list.map { (it as? PStr)?.value?.takeIf { s -> s.isNotEmpty() && s.length <= 200 } ?: throw ConversionRefused("INVALID_FEATURE_NAMES", "feature_names に文字列以外/空/長すぎる要素") }
        if (names.toSet().size != names.size) throw ConversionRefused("INVALID_FEATURE_NAMES", "feature_names が重複している")
        return names
    }

    fun convert(
        shell: PModel,
        modelId: String,
        tenantId: Long,
        createdAt: String,
        sourceSha256: String,
        producer: String = TrainingRun.PRODUCER + PRODUCER_SUFFIX,
    ): Conversion {
        val state = shell.state
        val extra = state.keys - ALLOWED
        if (extra.isNotEmpty()) throw ConversionRefused("UNKNOWN_ATTRIBUTES", "想定外の属性がある（${extra.size} 個）。旧クラスの形ではない")
        val missing = REQUIRED - state.keys
        if (missing.isNotEmpty()) throw ConversionRefused("MISSING_ATTRIBUTES", "必須の属性が無い: ${missing.sorted()}")

        val w = state["weights"]?.let(RestrictedPickle::resolve) as? PArray
        val data = w?.data
        if (w == null || w.shape.size != 1 || w.dtype.kind != 'f' || data == null || data.size !in 1..4096) {
            throw ConversionRefused("INVALID_WEIGHTS", "weights が 1 次元の浮動小数配列でない（学習前のモデル/object 配列を含む）")
        }
        if (!data.all { it.isFinite() }) throw ConversionRefused("INVALID_NUMBER", "weights に NaN / Infinity がある")
        val names = featureNames(state["feature_names"])
        val bias = finiteFloat(state["bias"], "bias")

        val warnings = ArrayList<String>()
        val candidates =
            listOf(
                FeatureSchema.MARKET_5 to "legacy_compat",
                FeatureSchema.CALIBRATION_40 to "legacy_compat",
                FeatureSchema.ALPHA_35 to "alpha",
            )
        val (schema, kind) =
            candidates.firstOrNull { it.first.names == names }
                ?: throw ConversionRefused("UNKNOWN_FEATURE_SCHEMA", "feature_names（${names.size} 個）が既知のスキーマ（5・40・35 次元）と一致しない。変換しない")
        if (data.size != schema.size) {
            // H1/H2 の疑い（feature_names は 40 名だが重みは 35 など）。パディング/スライスで合わせない
            throw ConversionRefused("DIMENSION_MISMATCH", "重みの数 ${data.size} と feature_names の数 ${names.size} が違う（H1/H2 の疑い）。変換しない")
        }
        val a = state["calib_a"]?.takeIf { it != PNone }
        val b = state["calib_b"]?.takeIf { it != PNone }
        if ((a == null) != (b == null)) throw ConversionRefused("INVALID_CALIBRATION", "calib_a と calib_b の片方だけがある")
        val platt = if (a == null) null else finiteFloat(a, "calib_a") to finiteFloat(b, "calib_b")
        val blend = finiteFloat(state["market_blend_alpha"] ?: PFloat(1.0), "market_blend_alpha")
        if (blend !in 0.0..1.0) throw ConversionRefused("INVALID_NUMBER", "market_blend_alpha が [0, 1] の外")
        if (kind == "alpha" && platt != null) warnings += "alpha(35 次元) のモデルに Platt が付いている（旧 train.py 経路では通常付かない）。そのまま変換する"
        if (kind == "legacy_compat" && names.size == 40) warnings += "旧 40 次元モデルを legacy_compat として変換（v2 の calibration とは別物。学習方式は旧 GD・標準化なし）"

        val training =
            JsonObject.of(
                "converted_from" to JsonString("legacy_pkl"),
                "source_sha256" to JsonString(sourceSha256),
                "conversion" to JsonString("convert-pkl"),
                "lr" to JsonNull,
                "synthetic" to JsonBool(false),
            )
        val model =
            ModelJson.build(
                modelId = modelId,
                tenantId = tenantId,
                kind = kind,
                featureSchema = schema.toJson(),
                weights = data,
                bias = bias,
                mean = null,
                std = null,
                platt = platt,
                marketBlendAlphaDefault = blend,
                training = training,
                metrics = JsonObject(emptyMap()),
                createdAt = createdAt,
                producer = producer,
            )
        return Conversion(model, warnings)
    }

    /** 変換の記録（`{model_id}.conversion.json`）。正準 JSON。 */
    fun reportJson(c: Conversion): String =
        CanonicalJson.encode(
            JsonObject.of(
                "model_id" to (c.model["model_id"] ?: JsonNull),
                "kind" to (c.model["kind"] ?: JsonNull),
                "dims" to JsonInt(((c.model["weights"] as JsonArray).items.size).toLong()),
                "sha256" to (c.model["sha256"] ?: JsonNull),
                "warnings" to JsonArray(c.warnings.map(::JsonString)),
            ),
        )
}
