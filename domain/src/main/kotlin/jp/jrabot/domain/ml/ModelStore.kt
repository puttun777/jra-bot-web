package jp.jrabot.domain.ml

import jp.jrabot.domain.tenant.TenantId

/**
 * 保存先のパスの一部（セグメント）に使う ID の検査。`/`・`..`・空白・制御文字を含められない（パストラバーサルの防止）。
 * 使える文字は英数字・`_`・`-`、1〜64 文字。
 */
private val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]{1,64}")

/** モデルの ID（`model_id`）。ULID/UUID などの形式は問わず、保存先のパスに安全な文字だけを許す。 */
@JvmInline
value class ModelId private constructor(
    val value: String,
) {
    override fun toString(): String = "ModelId($value)"

    companion object {
        fun ofOrNull(text: String): ModelId? = if (SAFE_SEGMENT.matches(text)) ModelId(text) else null

        fun of(text: String): ModelId = requireNotNull(ofOrNull(text)) { "model_id が不正: '$text'" }
    }
}

/** データセットの ID（`dataset_id`）。[ModelId] と同じ規則。 */
@JvmInline
value class DatasetId private constructor(
    val value: String,
) {
    override fun toString(): String = "DatasetId($value)"

    companion object {
        fun ofOrNull(text: String): DatasetId? = if (SAFE_SEGMENT.matches(text)) DatasetId(text) else null

        fun of(text: String): DatasetId = requireNotNull(ofOrNull(text)) { "dataset_id が不正: '$text'" }
    }
}

/**
 * モデル成果物（ML_DESIGN §5、D-035）: JSON（`model_schema_version: 1`）と、その全体への Ed25519 署名（`.sig`）。
 * pickle は扱わない。バイト列は生成時と取得時にコピーし、外から書き換えられない。
 * **この型は検証済みを意味しない**。`sha256`・署名・`feature_schema_hash`・重みの長さの検証は読込む側（T3.6）が行い、
 * 失敗したら読み込みを拒否してフォールバックの理由を記録する（CONTRACTS §5.1）。
 */
class ModelArtifact(
    val modelId: ModelId,
    json: ByteArray,
    signature: ByteArray,
) {
    private val jsonBytes = json.copyOf()
    private val signatureBytes = signature.copyOf()

    init {
        require(jsonBytes.isNotEmpty()) { "モデル JSON が空" }
        require(signatureBytes.isNotEmpty()) { "署名が空" }
    }

    fun json(): ByteArray = jsonBytes.copyOf()

    fun signature(): ByteArray = signatureBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ModelArtifact &&
            other.modelId == modelId &&
            other.jsonBytes.contentEquals(jsonBytes) &&
            other.signatureBytes.contentEquals(signatureBytes)

    override fun hashCode(): Int = (modelId.hashCode() * 31 + jsonBytes.contentHashCode()) * 31 + signatureBytes.contentHashCode()

    override fun toString(): String = "ModelArtifact($modelId, json=${jsonBytes.size}B, sig=${signatureBytes.size}B)"
}

/** モデル成果物の書込みの結果。 */
enum class ArtifactWriteOutcome {
    CREATED,

    /** 同じ ID で内容が同一のものが既にある（再実行しても結果が同じ）。 */
    UNCHANGED,

    /** 同じ ID で**内容が異なる**ものが既にある。成果物は不変なので上書きしない。 */
    CONFLICT,
}

/** 保存先の I/O に失敗した（到達不能・権限・タイムアウトなど）。Job としては一時的な失敗（exit 75。CONTRACTS §9.1）。 */
open class StorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 成果物はあるが署名（`.sig`）が無い。**未署名のモデルは存在しないものとして扱わず、署名不正として拒否する**（[jp.jrabot.domain.ml.pipeline.FallbackReason.MODEL_SIGNATURE_INVALID]）。 */
class MissingSignatureException(
    message: String,
) : StorageException(message)

/**
 * モデルの読取り（bot が使う。IAM は models の読取りのみ）。
 * 置き場は `MODEL_STORE_URI/{tenant_id}/models/{model_id}.json`（+ `.sig`）で、**パスは [TenantId] と [ModelId] だけから組み立てる**。
 */
interface ModelReader {
    /** 存在しなければ null。I/O の失敗は [StorageException]。 */
    fun read(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelArtifact?
}

/** モデルの書込み（学習 Job・移行ツールの Kotlin 側・テスト用）。成果物は不変。 */
interface ModelWriter {
    /** 作成のみ。既存があれば上書きせず、同一内容なら [ArtifactWriteOutcome.UNCHANGED]、異なれば [ArtifactWriteOutcome.CONFLICT]。 */
    fun write(
        tenantId: TenantId,
        artifact: ModelArtifact,
    ): ArtifactWriteOutcome
}

interface ModelStore :
    ModelReader,
    ModelWriter
