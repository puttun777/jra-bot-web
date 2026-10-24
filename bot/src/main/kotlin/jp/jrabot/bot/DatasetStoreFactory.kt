package jp.jrabot.bot

import jp.jrabot.cloudgcp.store.GcsStores
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.store.LocalFileDatasetStore
import java.net.URI
import java.nio.file.Path

/**
 * `MODEL_STORE_URI` から [DatasetStore] を作る。`gs://` は GCS（ADC は最初の呼び出し時に取得。起動時に GCP へ接続しない）、
 * `file:///絶対パス` はローカルの合成検証用（[BotSettings] が `APP_ENV=local` のときだけ許す）。
 */
object DatasetStoreFactory {
    fun create(uri: String): DatasetStore =
        when {
            uri.startsWith("gs://") -> GcsStores.datasetStore(uri)
            uri.startsWith("file:///") -> LocalFileDatasetStore(Path.of(URI.create(uri)))
            else -> throw IllegalArgumentException("未対応の MODEL_STORE_URI")
        }
}
