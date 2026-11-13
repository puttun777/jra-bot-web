package jp.jrabot.bot

import jp.jrabot.cloudgcp.store.GcsStores
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.domain.ml.store.LocalFileModelStore
import java.net.URI
import java.nio.file.Path

/** `MODEL_STORE_URI` から [ModelStore] を作る（[DatasetStoreFactory] と同じ規則。`gs://` は ADC を最初の呼び出しまで取得しない）。bot-run は読むだけ。 */
object ModelStoreFactory {
    fun create(uri: String): ModelStore =
        when {
            uri.startsWith("gs://") -> {
                GcsStores.modelStore(uri)
            }

            uri.startsWith("s3://") -> {
                jp.jrabot.cloudaws.store.S3Stores
                    .modelStore(uri)
            }

            uri.startsWith("file:///") -> {
                LocalFileModelStore(Path.of(URI.create(uri)))
            }

            else -> {
                throw IllegalArgumentException("未対応の MODEL_STORE_URI")
            }
        }
}
