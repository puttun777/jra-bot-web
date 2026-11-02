package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.model.ExpectedModel
import jp.jrabot.domain.ml.model.ModelDocument
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoadResult
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.model.ModelTrust
import jp.jrabot.domain.tenant.TenantId
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** 作った（または読んだ）モデル成果物の検証に失敗した。[reason] は推論側の FallbackReason と同じ語彙。 */
class ModelVerificationError(
    reason: String,
    message: String,
) : MlTrainError(reason, message)

/**
 * モデル JSON の生成・署名・検証（旧 ml/src/ml_train/modeljson.py の移植。契約は CONTRACTS §5.1・ML_DESIGN §5.2）。
 *
 * - 正準 JSON（[CanonicalJson]）。浮動小数のフィールドは必ず [JsonFloat]（`-0.0` は `0.0` に）。非有限は作れない。
 * - `sha256` = `sha256` を除いた正準 JSON の SHA-256。署名 = Ed25519（RFC 8032）で **JSON ファイルのバイト列そのもの**に付ける（`.sig` は生の 64 バイト）。
 * - **検証は推論側と同じ [ModelLoader]**（署名 → 構文・正準形 → 版 → 意味 → sha256 → 取り違え）。学習側だけの別の検証器を持たない（二重管理しない）。
 * - 秘密鍵（`MODEL_SIGNING_KEY`: 生 32 バイトのシードの Base64）はログ・例外・成果物に出さない。
 */
object ModelJson {
    // Ed25519 の PKCS#8（RFC 8410）の固定の接頭辞。続く 32 バイトがシード
    private val PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)

    /** 浮動小数（`-0.0` を `0.0` に。非有限は拒否）。 */
    fun f(x: Double): JsonFloat {
        if (!x.isFinite()) throw ModelVerificationError("MODEL_INVALID", "非有限の数値は JSON にできない")
        return JsonFloat(x + 0.0)
    }

    fun floats(xs: DoubleArray): JsonArray = JsonArray(xs.map(::f))

    /** 計算結果（Map・List・数値・文字列・真偽・null）を JSON 木にする。Double は有限だけ、整数は [JsonInt]。旧版の `clean` と同じ。 */
    fun of(v: Any?): JsonValue =
        when (v) {
            null -> JsonNull
            is JsonValue -> v
            is Boolean -> JsonBool(v)
            is Int -> JsonInt(v.toLong())
            is Long -> JsonInt(v)
            is Double -> f(v)
            is String -> JsonString(v)
            is DoubleArray -> floats(v)
            is Map<*, *> -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> for ((k, x) in v) m[k.toString()] = of(x) })
            is Iterable<*> -> JsonArray(v.map(::of))
            else -> throw ModelVerificationError("MODEL_INVALID", "JSON にできない型: ${v.javaClass.simpleName}")
        }

    fun build(
        modelId: String,
        tenantId: Long,
        kind: String,
        featureSchema: JsonObject,
        weights: DoubleArray,
        bias: Double,
        mean: DoubleArray?,
        std: DoubleArray?,
        platt: Pair<Double, Double>?,
        marketBlendAlphaDefault: Double,
        training: JsonObject,
        metrics: JsonObject,
        createdAt: String,
        producer: String,
    ): JsonObject {
        val m =
            JsonObject.of(
                "model_schema_version" to JsonInt(1),
                "model_id" to JsonString(modelId),
                "tenant_id" to JsonInt(tenantId),
                "kind" to JsonString(kind),
                "algorithm" to JsonString("logistic_regression"),
                "feature_schema" to featureSchema,
                "preprocessing" to
                    if (mean == null || std == null) JsonNull else JsonObject.of("type" to JsonString("standardize"), "mean" to floats(mean), "std" to floats(std)),
                "weights" to floats(weights),
                "bias" to f(bias),
                "calibration" to if (platt == null) JsonNull else JsonObject.of("type" to JsonString("platt"), "a" to f(platt.first), "b" to f(platt.second)),
                "market_blend" to JsonObject.of("alpha_default" to f(marketBlendAlphaDefault)),
                "ensemble" to JsonNull,
                "training" to training,
                "metrics" to metrics,
                "created_at" to JsonString(createdAt),
                "producer" to JsonString(producer),
                "sha256" to JsonString("0".repeat(64)),
            )
        return m.with("sha256", JsonString(ModelDocument.computeSha256(m)))
    }

    /** `MODEL_SIGNING_KEY`（生 32 バイトのシードの Base64）。鍵の値を例外に含めない。 */
    fun loadSigningSeed(value: String?): ByteArray {
        if (value.isNullOrBlank()) throw UsageError("MODEL_SIGNING_KEY_MISSING", "MODEL_SIGNING_KEY が未設定")
        val seed =
            try {
                Base64.getDecoder().decode(value.trim())
            } catch (_: IllegalArgumentException) {
                throw UsageError("MODEL_SIGNING_KEY_INVALID", "MODEL_SIGNING_KEY が Base64 でない")
            }
        if (seed.size != 32) throw UsageError("MODEL_SIGNING_KEY_INVALID", "MODEL_SIGNING_KEY は生 32 バイトの Base64")
        return seed
    }

    /** `MODEL_VERIFY_PUBKEY`（カンマ区切りの Base64 の生 32 バイト）。空なら空のリスト。 */
    fun parsePubkeys(value: String?): List<ByteArray> =
        (value ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val raw =
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    throw UsageError("MODEL_VERIFY_PUBKEY_INVALID", "MODEL_VERIFY_PUBKEY が Base64 でない")
                }
            if (raw.size != 32) throw UsageError("MODEL_VERIFY_PUBKEY_INVALID", "MODEL_VERIFY_PUBKEY は生 32 バイト")
            raw
        }

    private fun privateKey(seed: ByteArray): PrivateKey {
        require(seed.size == 32) { "シードは 32 バイト" }
        return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + seed))
    }

    fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(privateKey(seed))
            update(message)
            sign()
        }

    /**
     * シードから公開鍵（生 32 バイト）を求める。JCA には秘密鍵から公開鍵を取り出す API が無いので、鍵生成器にシードをそのまま返す乱数源を渡す
     * （RFC 8032 の鍵生成: シード 32 バイト → 公開鍵）。**得た公開鍵で署名を検証できることを必ず確かめる**（実装が乱数の使い方を変えたら失敗する = fail closed）。
     */
    fun publicKeyOf(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "シードは 32 バイト" }
        val fixed =
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    check(bytes.size == 32) { "想定外の乱数の要求" }
                    seed.copyInto(bytes)
                }
            }
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        kpg.initialize(NamedParameterSpec.ED25519, fixed)
        val encoded = kpg.generateKeyPair().public.encoded // SubjectPublicKeyInfo（12 バイトの接頭辞 + 32 バイト）
        check(encoded.size == 44) { "公開鍵の形式が想定外" }
        val raw = encoded.copyOfRange(12, 44)
        val probe = "jra-bot ml-train key check".toByteArray()
        if (!ModelTrust.of(raw).verify(probe, sign(seed, probe))) throw UsageError("MODEL_SIGNING_KEY_INVALID", "署名鍵から公開鍵を導けない")
        return raw
    }

    class Verified(
        val sha256: String,
        val signatureSha256: String,
    )

    /** 推論側と同じ [ModelLoader] で検証する。失敗は [ModelVerificationError]（理由は FallbackReason の名前）。 */
    fun verify(
        raw: ByteArray,
        sig: ByteArray,
        pubkeys: List<ByteArray>,
        tenantId: Long,
        modelId: String,
        expectedKind: String?,
        expectedSchemaHash: String?,
        expectedSha256: String?,
    ): Verified {
        if (pubkeys.isEmpty()) throw ModelVerificationError("MODEL_SIGNATURE_INVALID", "検証鍵が無い")
        val id = ModelId.ofOrNull(modelId) ?: throw ModelVerificationError("MODEL_INVALID", "model_id の形式が不正")
        val kind = expectedKind?.let { ModelKind.fromJson(it) ?: throw ModelVerificationError("MODEL_INVALID", "未知の種別") }
        if (raw.isEmpty() || sig.isEmpty()) throw ModelVerificationError("MODEL_INVALID", "成果物が空")
        val loader = ModelLoader(ModelTrust(pubkeys.map(ModelTrust::publicKeyFromRaw)))
        return when (
            val r =
                loader.load(TenantId.of(tenantId), id, ModelArtifact(id, raw, sig), ExpectedModel(expectedSha256, expectedSchemaHash, kind))
        ) {
            is ModelLoadResult.Loaded -> Verified(r.model.document.sha256, sha256Hex(sig))
            is ModelLoadResult.LoadedEnsemble -> throw ModelVerificationError("MODEL_INVALID", "学習 Job は ensemble を作らない")
            is ModelLoadResult.Rejected -> throw ModelVerificationError(r.reason.name, r.detail)
        }
    }

    fun sha256Hex(b: ByteArray): String = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(b))
}
