package jp.jrabot.domain.ml

/**
 * 学習 Job（ml-train）の成果物の置き場（`MODEL_STORE_URI` 配下）を、キー（`{tenant_id}/{datasets|models|reports}/…`）で扱う最小の操作。
 * 旧 Python の `LocalBlobStore` と同じ契約:
 *
 * - **作成のみで上書きしない**（成果物は不変）。同一内容の再書込みは [ArtifactWriteOutcome.UNCHANGED]、異なる内容は [ArtifactWriteOutcome.CONFLICT]。
 * - 削除は『**この実行が新しく作ったもの**を片付ける』ためだけに使う（既存の成果物を消さない。呼び出し側が作成したキーだけを渡す）。
 * - キーは [ArtifactKeys.validate] を通ったものだけ（`/` 区切りの各セグメントが英数字・`_`・`.`・`-`、`.`・`..` は不可）。
 * - I/O の失敗は [StorageException]（Job としては一時的な失敗: exit 75）。メッセージにキー・内容を入れない。
 */
interface ArtifactBlobStore {
    /** 成果物の URI の接頭辞（`file:///…` または `gs://bucket[/prefix]`。末尾の `/` なし）。レジストリの `artifact_uri` に使う。 */
    val uriPrefix: String

    /** 無ければ null。 */
    fun get(key: String): ByteArray?

    fun putIfAbsent(
        key: String,
        data: ByteArray,
        contentType: String,
    ): ArtifactWriteOutcome

    /** 無くてもよい（冪等）。 */
    fun delete(key: String)
}

object ArtifactKeys {
    private val SEGMENT = Regex("[A-Za-z0-9_.-]{1,100}")

    /** 不正なら [StorageException]（キーの値をメッセージに含めない）。分割したセグメントを返す。 */
    fun validate(key: String): List<String> {
        val parts = key.split('/')
        if (parts.any { !SEGMENT.matches(it) || it == "." || it == ".." }) throw StorageException("保管キーが不正")
        return parts
    }
}
