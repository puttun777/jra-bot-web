package jp.jrabot.cloudgcp.store

import jp.jrabot.domain.ml.ArtifactBlobStore
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.ModelStore
import jp.jrabot.platform.store.LazyObjectClient
import jp.jrabot.platform.store.ObjectBlobStore
import jp.jrabot.platform.store.ObjectClient
import jp.jrabot.platform.store.ObjectDatasetStore
import jp.jrabot.platform.store.ObjectModelStore
import jp.jrabot.platform.store.ObjectStoreLocation

/**
 * `MODEL_STORE_URI=gs://bucket[/prefix]` の保管（モデル・データセット・ml-train の成果物）。ADC のクライアントは最初の操作で作る（生成時に GCP へ接続しない）。
 * 配置・作成のみ・競合の判定は provider 中立の [ObjectModelStore]・[ObjectDatasetStore]・[ObjectBlobStore]（`:platform`）。不正な URI は [IllegalArgumentException]。
 */
object GcsStores {
    fun location(uri: String): ObjectStoreLocation = ObjectStoreLocation.parse(uri, "gs", ObjectStoreLocation.GCS_BUCKET)

    fun modelStore(uri: String): ModelStore = ObjectModelStore(location(uri), lazyAdcClient())

    fun datasetStore(uri: String): DatasetStore = ObjectDatasetStore(location(uri), lazyAdcClient())

    fun blobStore(uri: String): ArtifactBlobStore = ObjectBlobStore(location(uri), lazyAdcClient())

    private fun lazyAdcClient(): ObjectClient = LazyObjectClient { GoogleObjectClient(GoogleObjectClient.adc()) }
}
