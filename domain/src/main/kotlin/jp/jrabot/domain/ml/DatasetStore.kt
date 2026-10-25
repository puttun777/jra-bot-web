package jp.jrabot.domain.ml

import jp.jrabot.domain.tenant.TenantId
import java.io.InputStream

/** 書き込んだデータセットの記録（学習メタデータの `dataset_sha256` に使う）。[sha256] は 64 桁の小文字 16 進。 */
data class DatasetDescriptor(
    val datasetId: DatasetId,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(sizeBytes >= 0) { "サイズは 0 以上: $sizeBytes" }
        require(SHA256.matches(sha256)) { "sha256 は 64 桁の小文字 16 進" }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

sealed interface DatasetWriteResult {
    data class Created(
        val descriptor: DatasetDescriptor,
    ) : DatasetWriteResult

    /** 同じ ID が既にある。データセットは不変なので上書きしない（`export-dataset` の再実行は冪等）。 */
    data object AlreadyExists : DatasetWriteResult
}

/** データセットの読取り（`MODEL_STORE_URI/{tenant_id}/datasets/{dataset_id}.csv.gz`）。形式は gzip CSV、列順は `feature_schema`。 */
interface DatasetReader {
    /** 存在しなければ null。返した [InputStream] は呼び出し側が閉じる。I/O の失敗は [StorageException]。 */
    fun open(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): InputStream?

    /**
     * manifest（正準 JSON + 改行 1 つ。`{dataset_id}.manifest.json`）。**manifest があることがデータセットの確定の印**（CSV の後に書く）。
     * 無ければ null。I/O の失敗は [StorageException]。
     */
    fun readManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
    ): ByteArray?
}

/**
 * データセットの書込み（bot の `export-dataset` が使う。IAM は datasets の書込みのみ）。
 * 内容は gzip 圧縮済みの CSV のバイト列で、ここでは解釈しない。**Git や CI のログには載せない**（実データを含み得る）。
 */
interface DatasetWriter {
    /** [content] は読み切って閉じる。作成のみで上書きしない。I/O の失敗は [StorageException]。 */
    fun write(
        tenantId: TenantId,
        datasetId: DatasetId,
        content: InputStream,
    ): DatasetWriteResult

    /**
     * manifest の書込み。CSV の書込みが済んだ後に呼ぶ（確定の印）。作成のみで上書きしない:
     * 同一内容なら [ArtifactWriteOutcome.UNCHANGED]、異なれば [ArtifactWriteOutcome.CONFLICT]。I/O の失敗は [StorageException]。
     */
    fun writeManifest(
        tenantId: TenantId,
        datasetId: DatasetId,
        manifest: ByteArray,
    ): ArtifactWriteOutcome
}

interface DatasetStore :
    DatasetReader,
    DatasetWriter
