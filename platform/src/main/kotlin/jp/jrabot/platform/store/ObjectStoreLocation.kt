package jp.jrabot.platform.store

/**
 * オブジェクトストレージの `<scheme>://<bucket>[/<prefix>]`（`MODEL_STORE_URI`。CONTRACTS §2）の分解結果。GCS（`gs`）と S3（`s3`）で共通（D-280）。
 * [prefix] は空か、末尾が `/` の相対パス（各段は英数字・`.`・`_`・`-` の 1〜100 文字で、`.`・`..` は不可）。
 */
data class ObjectStoreLocation(
    val scheme: String,
    val bucket: String,
    val prefix: String,
) {
    /** 保存先の表示・記録用（`gs://bucket/prefix` の形。末尾の `/` は付けない）。 */
    val uriPrefix: String get() = "$scheme://$bucket" + if (prefix.isEmpty()) "" else "/" + prefix.trimEnd('/')

    companion object {
        /** GCS のバケット名（3〜222 文字。英小文字・数字・`.`・`_`・`-`）。 */
        val GCS_BUCKET = Regex("^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$")

        /** S3 のバケット名（3〜63 文字。英小文字・数字・`.`・`-`。`_` は不可）。 */
        val S3_BUCKET = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")

        private val prefixSegment = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** 不正なら [IllegalArgumentException]（メッセージに入力の値を含めない）。 */
        fun parse(
            uri: String,
            scheme: String,
            bucketPattern: Regex,
        ): ObjectStoreLocation {
            val head = "$scheme://"
            require(uri.startsWith(head)) { "$head で始まる URI ではない" }
            val rest = uri.removePrefix(head).trimEnd('/')
            val bucket = rest.substringBefore('/')
            require(bucketPattern.matches(bucket)) { "バケット名が不正" }
            val segments = if ('/' in rest) rest.substringAfter('/').split('/') else emptyList()
            require(segments.all { prefixSegment.matches(it) && it != "." && it != ".." }) { "プレフィックスが不正" }
            return ObjectStoreLocation(scheme, bucket, if (segments.isEmpty()) "" else segments.joinToString("/") + "/")
        }
    }
}

/**
 * オブジェクトストレージの最小の操作（[ObjectModelStore]・[ObjectDatasetStore]・[ObjectBlobStore] が使う）。実装は `:cloud-gcp`（GCS）と `:cloud-aws`（S3）。
 * SDK の例外はここを越えて型を漏らさず、呼び出し側で [jp.jrabot.domain.ml.StorageException] に変換する。
 * **削除は『この実行が新しく作ったものを片付ける』ためだけ**に使う（既存の成果物を消さない）。
 */
interface ObjectClient {
    /** 無ければ null。 */
    fun read(
        bucket: String,
        name: String,
    ): ByteArray?

    /** 作成のみ（既にあれば作らず false）。上書きしない。同時の作成でも 1 つだけが成功する（条件付きの作成）。 */
    fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean

    fun delete(
        bucket: String,
        name: String,
    )
}

/** 最初の操作で実クライアントを作る（設定の検証だけの段階で認証・通信をしない）。 */
class LazyObjectClient(
    factory: () -> ObjectClient,
) : ObjectClient {
    private val delegate by lazy(factory)

    override fun read(
        bucket: String,
        name: String,
    ): ByteArray? = delegate.read(bucket, name)

    override fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean = delegate.createIfAbsent(bucket, name, content, contentType)

    override fun delete(
        bucket: String,
        name: String,
    ) = delegate.delete(bucket, name)
}

/**
 * 認証・権限の拒否（HTTP 401・403）。[ObjectClient] の実装は SDK の例外をこれに変換して投げる（[ObjectBlobStore] が
 * [jp.jrabot.domain.ml.StoragePermissionException] に分けて、一時的な失敗として再試行させないため）。メッセージに URL・内容を入れない。
 */
class ObjectAccessDeniedException(
    val status: Int,
    cause: Throwable? = null,
) : RuntimeException("オブジェクトストレージの権限が無い(HTTP $status)", cause)
