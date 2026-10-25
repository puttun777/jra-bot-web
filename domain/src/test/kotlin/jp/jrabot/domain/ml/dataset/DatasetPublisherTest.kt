package jp.jrabot.domain.ml.dataset

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.store.InMemoryDatasetStore
import jp.jrabot.domain.ml.store.LocalFileDatasetStore
import jp.jrabot.domain.tenant.TenantId
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** データセットの保管（CSV → manifest の順・冪等・途中状態からの再開・内容が違えば上書きしない）。合成データのみ。 */
class DatasetPublisherTest {
    private val tenant = TenantId.of(1)
    private val other = TenantId.of(2)

    private fun exported(
        id: String,
        odds: Double = 3.0,
    ): ExportedDataset {
        val f = InMemoryFeatureSource()
        val d = InMemoryDatasetSource()
        for (day in 0 until 5) {
            val date = LocalDate.of(2026, 1, 4).plusDays(day.toLong())
            val raceId = "%04d%02d%02d0511".format(date.year, date.monthValue, date.dayOfMonth)
            val post = date.atTime(15, 25).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
            f.putRace(tenant, raceId, RaceConditions(date, post, 1600, "11", "00", "1", "2", "3"))
            for (u in 1..3) {
                f.putEntry(tenant, raceId, u, "2020100%03d".format(u))
                f.putHorse(
                    tenant,
                    HorseMaster(
                        "2020100%03d".format(u),
                        LocalDate.of(2022, 1, 1),
                        "1",
                        ChakuTable.EMPTY,
                        ChakuTable.EMPTY,
                        ChakuTable.EMPTY,
                        listOf("s$u"),
                        Instant.parse("2025-01-01T00:00:00Z"),
                    ),
                )
                d.put(tenant, date, DatasetEntry(raceId, u, (u + day) % 3 + 1), listOf(OddsPoint(post.minusSeconds(3600), odds + u, u)))
            }
        }
        return DatasetExporter(f, d).export(
            tenant,
            DatasetId.of(id),
            FeatureSchema.CALIBRATION_40,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            Instant.parse("2026-02-01T00:00:00Z"),
        )
    }

    private fun gunzip(b: ByteArray) = GZIPInputStream(b.inputStream()).use { it.readAllBytes() }

    @Test
    fun `CSV の後に manifest を書き、manifest は正準 JSON + 改行 1 つ`() {
        val store = InMemoryDatasetStore()
        val e = exported("ds1")
        assertEquals(PublishOutcome.CREATED, DatasetPublisher(store).publish(tenant, e))
        val manifest = assertNotNull(store.readManifest(tenant, e.datasetId))
        assertEquals('\n'.code.toByte(), manifest.last())
        assertTrue(String(manifest, Charsets.UTF_8).contains(e.csvSha256))
        assertContentEquals(e.csv.toByteArray(), gunzip(store.open(tenant, e.datasetId)!!.readAllBytes()))
    }

    @Test
    fun `同じ内容の再実行は UNCHANGED（冪等）、内容が違えば CONFLICT で上書きしない`() {
        val store = InMemoryDatasetStore()
        val pub = DatasetPublisher(store)
        val e = exported("ds1")
        pub.publish(tenant, e)
        val before = store.readManifest(tenant, e.datasetId)!!.copyOf()
        assertEquals(PublishOutcome.UNCHANGED, pub.publish(tenant, e))
        assertEquals(PublishOutcome.CONFLICT, pub.publish(tenant, exported("ds1", odds = 9.0)))
        assertContentEquals(before, store.readManifest(tenant, e.datasetId))
        assertContentEquals(e.csv.toByteArray(), gunzip(store.open(tenant, e.datasetId)!!.readAllBytes()))
    }

    @Test
    fun `CSV だけが残った途中状態は、同じ内容なら manifest を書いて確定し、違えば CONFLICT`() {
        val e = exported("ds1")
        val store = InMemoryDatasetStore()
        store.putCsvOnly(tenant, e.datasetId, e.csvGz())
        assertNull(store.readManifest(tenant, e.datasetId))
        assertEquals(PublishOutcome.CREATED, DatasetPublisher(store).publish(tenant, e))
        assertNotNull(store.readManifest(tenant, e.datasetId))

        val store2 = InMemoryDatasetStore()
        store2.putCsvOnly(tenant, e.datasetId, exported("ds1", odds = 9.0).csvGz())
        assertEquals(PublishOutcome.CONFLICT, DatasetPublisher(store2).publish(tenant, e))
        assertNull(store2.readManifest(tenant, e.datasetId))

        val store3 = InMemoryDatasetStore()
        store3.putCsvOnly(tenant, e.datasetId, byteArrayOf(1, 2, 3)) // gzip でない
        assertEquals(PublishOutcome.CONFLICT, DatasetPublisher(store3).publish(tenant, e))
    }

    @Test
    fun `テナントが違えば別のデータセット`() {
        val store = InMemoryDatasetStore()
        val e = exported("ds1")
        DatasetPublisher(store).publish(tenant, e)
        assertNull(store.readManifest(other, e.datasetId))
        assertNull(store.open(other, e.datasetId))
    }

    @Test
    fun `ローカルファイル版も同じ契約（配置は tenant_id の datasets 配下）`() {
        val root = Files.createTempDirectory("ds-store")
        try {
            val store = LocalFileDatasetStore(root)
            val e = exported("ds1")
            assertEquals(PublishOutcome.CREATED, DatasetPublisher(store).publish(tenant, e))
            assertTrue(Files.isRegularFile(root.resolve("1/datasets/ds1.csv.gz")) && Files.isRegularFile(root.resolve("1/datasets/ds1.manifest.json")))
            assertEquals(PublishOutcome.UNCHANGED, DatasetPublisher(store).publish(tenant, e))
            assertEquals(PublishOutcome.CONFLICT, DatasetPublisher(store).publish(tenant, exported("ds1", odds = 9.0)))
            assertEquals(ArtifactWriteOutcome.CONFLICT, store.writeManifest(tenant, e.datasetId, byteArrayOf(1)))
            assertEquals(emptyList(), Files.list(root.resolve("1/datasets")).use { it.toList() }.filter { it.fileName.toString().endsWith(".tmp") })
            assertNull(store.readManifest(other, e.datasetId))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
