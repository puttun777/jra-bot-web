package jp.jrabot.cloudgcp.store

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.ml.MissingSignatureException
import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.dataset.DatasetEntry
import jp.jrabot.domain.ml.dataset.DatasetExporter
import jp.jrabot.domain.ml.dataset.DatasetPublisher
import jp.jrabot.domain.ml.dataset.InMemoryDatasetSource
import jp.jrabot.domain.ml.dataset.PublishOutcome
import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.model.ModelLoadResult
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.model.ModelTrust
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.tenant.TenantId
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** メモリ上のフェイク（実 GCP には接続しない）。失敗の注入と、作成済みのキーの記録ができる。 */
internal class FakeObjectClient : ObjectClient {
    val objects = LinkedHashMap<String, ByteArray>()
    val deleted = ArrayList<String>()
    var failCreateOn: ((String) -> Boolean)? = null
    var failReadsWith: Exception? = null

    private fun k(
        b: String,
        n: String,
    ) = "$b/$n"

    override fun read(
        bucket: String,
        name: String,
    ): ByteArray? {
        failReadsWith?.let { throw it }
        return objects[k(bucket, name)]?.copyOf()
    }

    override fun createIfAbsent(
        bucket: String,
        name: String,
        content: ByteArray,
        contentType: String,
    ): Boolean {
        if (failCreateOn?.invoke(name) == true) throw java.io.IOException("simulated")
        if (k(bucket, name) in objects) return false
        objects[k(bucket, name)] = content.copyOf()
        return true
    }

    override fun delete(
        bucket: String,
        name: String,
    ) {
        deleted += k(bucket, name)
        objects.remove(k(bucket, name))
    }
}

class GcsStoresTest {
    private val t1 = TenantId.of(1)
    private val t2 = TenantId.of(2)
    private val loc = GcsBucketPrefix.parse("gs://fake-model-store/env1")
    private val resources = File("../domain/src/test/resources/ml")

    private fun fixture(name: String) =
        ModelArtifact(ModelId.of(name), File(resources, "models/$name.json").readBytes(), File(resources, "models/$name.sig").readBytes())

    private fun trust(): ModelTrust {
        val expected = File(resources, "expected.json").readText()
        val key = Regex("\"public_key_primary\":\"([^\"]+)\"").find(expected)!!.groupValues[1]
        return ModelTrust.fromBase64(key)
    }

    @Test
    fun `URI の分解（バケットとプレフィックス）と不正な URI の拒否`() {
        assertEquals(GcsBucketPrefix("my-bucket", ""), GcsBucketPrefix.parse("gs://my-bucket"))
        assertEquals(GcsBucketPrefix("my-bucket", "a/b/"), GcsBucketPrefix.parse("gs://my-bucket/a/b/"))
        for (bad in listOf("my-bucket", "gs://", "gs://A_B", "gs://ab/../x", "gs://my-bucket/a b", "http://my-bucket/x", "gs://my-bucket//x")) {
            assertFailsWith<IllegalArgumentException>(bad) { GcsBucketPrefix.parse(bad) }
        }
    }

    @Test
    fun `モデルは tenant_id と model_id だけから組み立てたキーに、署名を先・JSON を最後に作成する`() {
        val client = FakeObjectClient()
        val store = GcsModelStore(loc, client)
        val a = fixture("cal_test_001")
        assertEquals(ArtifactWriteOutcome.CREATED, store.write(t1, a))
        assertEquals(
            listOf("fake-model-store/env1/1/models/cal_test_001.sig", "fake-model-store/env1/1/models/cal_test_001.json"),
            client.objects.keys.toList(),
            "署名が先、JSON が確定の印として最後",
        )
        assertEquals(a, store.read(t1, a.modelId))
        assertNull(store.read(t2, a.modelId), "他テナントのパスは別（越境しない）")
    }

    @Test
    fun `同一内容の再書込みは UNCHANGED、異なる内容は CONFLICT で上書きしない`() {
        val client = FakeObjectClient()
        val store = GcsModelStore(loc, client)
        val a = fixture("cal_test_001")
        store.write(t1, a)
        assertEquals(ArtifactWriteOutcome.UNCHANGED, store.write(t1, a))
        val other = ModelArtifact(a.modelId, a.json() + " ".toByteArray(), a.signature())
        assertEquals(ArtifactWriteOutcome.CONFLICT, store.write(t1, other))
        assertEquals(a, store.read(t1, a.modelId), "既存は変わらない")
    }

    @Test
    fun `JSON の作成に失敗したら、この呼び出しが作った署名を片付けて StorageException`() {
        val client = FakeObjectClient()
        client.failCreateOn = { it.endsWith(".json") }
        val store = GcsModelStore(loc, client)
        assertFailsWith<StorageException> { store.write(t1, fixture("cal_test_001")) }
        assertTrue(client.objects.isEmpty(), "署名だけが残らない: ${client.objects.keys}")
        assertEquals(listOf("fake-model-store/env1/1/models/cal_test_001.sig"), client.deleted)
    }

    @Test
    fun `JSON があって署名が無いと MissingSignatureException、読取りの I-O 失敗は StorageException`() {
        val client = FakeObjectClient()
        val store = GcsModelStore(loc, client)
        client.objects["fake-model-store/env1/1/models/m1.json"] = byteArrayOf(1)
        assertFailsWith<MissingSignatureException> { store.read(t1, ModelId.of("m1")) }
        client.failReadsWith = java.io.IOException("simulated")
        assertFailsWith<StorageException> { store.read(t1, ModelId.of("m1")) }
    }

    @Test
    fun `取得したモデルは署名を検証してから使う（正しい成果物は読め、改ざん・別鍵・署名なしは拒否される）`() {
        val client = FakeObjectClient()
        val store = GcsModelStore(loc, client)
        val good = fixture("cal_test_001")
        store.write(t1, good)
        val loader = ModelLoader(trust())
        assertIs<ModelLoadResult.Loaded>(loader.load(t1, good.modelId, assertNotNull(store.read(t1, good.modelId))))

        // バケットの書換え（1 バイト改ざん）→ 署名不正
        val tampered = good.json().also { it[10] = (it[10].toInt() xor 1).toByte() }
        client.objects["fake-model-store/env1/1/models/cal_test_001.json"] = tampered
        val r = loader.load(t1, good.modelId, assertNotNull(store.read(t1, good.modelId)))
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, assertIs<ModelLoadResult.Rejected>(r).reason)

        // 別の鍵で署名された成果物
        val other = fixture("signed_by_other_key")
        val client2 = FakeObjectClient()
        GcsModelStore(loc, client2).write(t1, other)
        val r2 = loader.load(t1, other.modelId, assertNotNull(GcsModelStore(loc, client2).read(t1, other.modelId)))
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, assertIs<ModelLoadResult.Rejected>(r2).reason)
    }

    private fun exported(id: String): jp.jrabot.domain.ml.dataset.ExportedDataset {
        val f = InMemoryFeatureSource()
        val d = InMemoryDatasetSource()
        for (day in 0 until 4) {
            val date = LocalDate.of(2026, 1, 4).plusDays(day.toLong())
            val raceId = "%04d%02d%02d0511".format(date.year, date.monthValue, date.dayOfMonth)
            val post = date.atTime(15, 25).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
            f.putRace(t1, raceId, RaceConditions(date, post, 1600, "11", "00", "1", "2", "3"))
            for (u in 1..2) {
                f.putEntry(t1, raceId, u, "2020100%03d".format(u))
                f.putHorse(
                    t1,
                    HorseMaster(
                        "2020100%03d".format(u),
                        LocalDate.of(2022, 1, 1),
                        "1",
                        ChakuTable.EMPTY,
                        ChakuTable.EMPTY,
                        ChakuTable.EMPTY,
                        listOf("s"),
                        Instant.parse("2025-01-01T00:00:00Z"),
                    ),
                )
                d.put(t1, date, DatasetEntry(raceId, u, (u + day) % 2 + 1), listOf(OddsPoint(post.minusSeconds(3600), 2.0 + u, u)))
            }
        }
        return DatasetExporter(
            f,
            d,
        ).export(t1, DatasetId.of(id), FeatureSchema.ALPHA_35, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), Instant.parse("2026-02-01T00:00:00Z"))
    }

    @Test
    fun `データセットは CSV の後に manifest を書き、再実行は冪等で、内容が違えば上書きしない`() {
        val client = FakeObjectClient()
        val store = GcsDatasetStore(loc, client)
        val pub = DatasetPublisher(store)
        val e = exported("ds1")
        assertEquals(PublishOutcome.CREATED, pub.publish(t1, e))
        assertEquals(
            listOf("fake-model-store/env1/1/datasets/ds1.csv.gz", "fake-model-store/env1/1/datasets/ds1.manifest.json"),
            client.objects.keys.toList(),
        )
        assertEquals(PublishOutcome.UNCHANGED, pub.publish(t1, e))
        assertContentEquals(e.csvGz(), store.open(t1, e.datasetId)!!.readAllBytes())
        assertNull(store.readManifest(t2, e.datasetId))
        assertNull(store.open(t2, e.datasetId))
        // 同じ ID で別の内容 → CONFLICT（既存は変わらない）
        val before = client.objects.mapValues { it.value.copyOf() }
        val f2 = exported("ds1")
        val bigger =
            jp.jrabot.domain.ml.dataset
                .ExportedDataset(f2.datasetId, f2.csvGz(), f2.csvBytes, "0".repeat(64), f2.manifest, f2.csv)
        assertEquals(PublishOutcome.CONFLICT, pub.publish(t1, bigger))
        assertEquals(before.keys, client.objects.keys)
        before.forEach { (k, v) -> assertContentEquals(v, client.objects[k]) }
    }

    @Test
    fun `CSV だけが残った途中状態からの再開と、書込み失敗は StorageException`() {
        val client = FakeObjectClient()
        val store = GcsDatasetStore(loc, client)
        val e = exported("ds2")
        client.failCreateOn = { it.endsWith(".manifest.json") }
        assertFailsWith<StorageException> { DatasetPublisher(store).publish(t1, e) }
        assertEquals(listOf("fake-model-store/env1/1/datasets/ds2.csv.gz"), client.objects.keys.toList())
        assertNull(store.readManifest(t1, e.datasetId), "manifest が無い = 未確定")
        client.failCreateOn = null
        assertEquals(PublishOutcome.CREATED, DatasetPublisher(store).publish(t1, e))
        assertIs<DatasetWriteResult.AlreadyExists>(store.write(t1, e.datasetId, e.csvGz().inputStream()))
    }
}

/** ml-train の成果物の置き場（GCS）。作成は前提条件つきで上書きしない。キーはプレフィックスの下だけ。 */
class GcsBlobStoreTest {
    private val client = FakeObjectClient()
    private val store = GcsBlobStore(GcsBucketPrefix.parse("gs://fake-model-store/env1"), client)

    @Test
    fun `create only under the prefix, unchanged and conflict are distinguished, delete only the given key`() {
        assertEquals("gs://fake-model-store/env1", store.uriPrefix)
        assertEquals(ArtifactWriteOutcome.CREATED, store.putIfAbsent("1/models/m.sig", byteArrayOf(1), "application/octet-stream"))
        assertTrue("fake-model-store/env1/1/models/m.sig" in client.objects)
        assertEquals(ArtifactWriteOutcome.UNCHANGED, store.putIfAbsent("1/models/m.sig", byteArrayOf(1), "application/octet-stream"))
        assertEquals(ArtifactWriteOutcome.CONFLICT, store.putIfAbsent("1/models/m.sig", byteArrayOf(2), "application/octet-stream"))
        assertContentEquals(byteArrayOf(1), store.get("1/models/m.sig"))
        store.delete("1/models/m.sig")
        assertEquals(listOf("fake-model-store/env1/1/models/m.sig"), client.deleted)
        assertNull(store.get("1/models/m.sig"))
        assertEquals("gs://bkt", GcsBlobStore(GcsBucketPrefix.parse("gs://bkt/"), client).uriPrefix)
    }

    @Test
    fun `invalid keys and io failures are storage errors`() {
        for (k in listOf("../x", "1/../x", "")) assertFailsWith<StorageException>(k) { store.get(k) }
        client.failReadsWith = java.io.IOException("simulated")
        assertFailsWith<StorageException> { store.get("1/models/m.json") }
        client.failReadsWith = null
        client.failCreateOn = { true }
        assertFailsWith<StorageException> { store.putIfAbsent("1/models/m.json", byteArrayOf(1), "application/json") }
    }
}
