package jp.jrabot.bot

import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.DatasetStore
import jp.jrabot.domain.ml.DatasetWriteResult
import jp.jrabot.domain.ml.StorageException
import jp.jrabot.domain.ml.dataset.DatasetEntry
import jp.jrabot.domain.ml.dataset.InMemoryDatasetSource
import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.store.InMemoryDatasetStore
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** export-dataset の Job としての結果（終了コードと理由）。合成データのみ。DB・GCS には接続しない。 */
class ExportDatasetTaskHandlerTest {
    private val tenant = TenantId.of(1)
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-02-01T00:00:00Z")
        }

    private fun world(withResults: Boolean = true): Pair<InMemoryFeatureSource, InMemoryDatasetSource> {
        val f = InMemoryFeatureSource()
        val d = InMemoryDatasetSource()
        for (day in 0 until 5) {
            val date = LocalDate.of(2026, 1, 4).plusDays(day.toLong())
            val raceId = "%04d%02d%02d0511".format(date.year, date.monthValue, date.dayOfMonth)
            val post = date.atTime(15, 25).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
            f.putRace(tenant, raceId, RaceConditions(date, post, 1600, "11", "00", "1", "2", "3"))
            for (u in 1..3) {
                f.putEntry(tenant, raceId, u, "2020100%03d".format(u))
                f.putHorse(tenant, HorseMaster("2020100%03d".format(u), LocalDate.of(2022, 1, 1), "1", ChakuTable.EMPTY, ChakuTable.EMPTY, ChakuTable.EMPTY, listOf("s$u"), Instant.parse("2025-01-01T00:00:00Z")))
                d.put(tenant, date, DatasetEntry(raceId, u, if (withResults) (u + day) % 3 + 1 else null), listOf(OddsPoint(post.minusSeconds(3600), 2.0 + u, u)))
            }
        }
        return f to d
    }

    private fun command(id: String = "ds1") = (BotArgsParser.parse(args("export-dataset", "1", "--dataset-id=$id", "--from=2026-01-01", "--to=2026-01-31", "--feature-schema=calibration")) as ParsedArgs.Valid).command

    private fun handler(
        store: DatasetStore,
        withResults: Boolean = true,
    ): ExportDatasetTaskHandler {
        val (f, d) = world(withResults)
        return ExportDatasetTaskHandler(f, d, store, clock)
    }

    @Test
    fun `新しいデータセットは保管して 0、再実行は同じ内容なら 0 で変えない（冪等）`() {
        val store = InMemoryDatasetStore()
        assertEquals(JobResult(0, "DATASET_CREATED"), handler(store).execute(command()))
        val manifest = assertNotNull(store.readManifest(tenant, DatasetId.of("ds1")))
        assertEquals(JobResult(0, "DATASET_UNCHANGED"), handler(store).execute(command()))
        assertEquals(manifest.toList(), store.readManifest(tenant, DatasetId.of("ds1"))!!.toList())
    }

    @Test
    fun `同じ ID で内容が違うと 1(CONFLICT)で、既存を上書きしない`() {
        val store = InMemoryDatasetStore()
        handler(store).execute(command())
        val before = store.readManifest(tenant, DatasetId.of("ds1"))!!.toList()
        val (f, d) = world()
        d.put(tenant, LocalDate.of(2026, 1, 20), DatasetEntry("202601200511", 1, 1), listOf(OddsPoint(Instant.parse("2026-01-20T05:00:00Z"), 3.0, 1)))
        f.putRace(tenant, "202601200511", RaceConditions(LocalDate.of(2026, 1, 20), Instant.parse("2026-01-20T06:25:00Z"), 1600, "11", "", "", "", ""))
        f.putEntry(tenant, "202601200511", 1, "2020100001")
        assertEquals(JobResult(1, "DATASET_ID_CONFLICT"), ExportDatasetTaskHandler(f, d, store, clock).execute(command()))
        assertEquals(before, store.readManifest(tenant, DatasetId.of("ds1"))!!.toList())
    }

    @Test
    fun `結果が1件も無く出力できる行が 0 なら 1(DATASET_EMPTY)で何も保管しない`() {
        val store = InMemoryDatasetStore()
        assertEquals(JobResult(1, "DATASET_EMPTY"), handler(store, withResults = false).execute(command()))
        assertNull(store.readManifest(tenant, DatasetId.of("ds1")))
        assertEquals(false, store.hasCsv(tenant, DatasetId.of("ds1")))
    }

    @Test
    fun `保管先の I-O 失敗は 75(STORAGE_ERROR)`() {
        val failing =
            object : DatasetStore by InMemoryDatasetStore() {
                override fun write(
                    tenantId: TenantId,
                    datasetId: DatasetId,
                    content: InputStream,
                ): DatasetWriteResult = throw StorageException("simulated")

                override fun writeManifest(
                    tenantId: TenantId,
                    datasetId: DatasetId,
                    manifest: ByteArray,
                ): ArtifactWriteOutcome = throw StorageException("simulated")
            }
        assertEquals(JobResult(75, "STORAGE_ERROR"), handler(failing).execute(command()))
    }

    @Test
    fun `テナントが違えば別のデータ（他テナントのデータセットは作られない）`() {
        val store = InMemoryDatasetStore()
        val other = (BotArgsParser.parse(args("export-dataset", "2", "--dataset-id=ds1", "--from=2026-01-01", "--to=2026-01-31", "--feature-schema=calibration")) as ParsedArgs.Valid).command
        // テナント 2 の出走馬は無いので出力 0 行 → EMPTY（テナント 1 のデータは読まれない）
        assertEquals(JobResult(1, "DATASET_EMPTY"), handler(store).execute(other))
        assertNull(store.readManifest(TenantId.of(2), DatasetId.of("ds1")))
        assertNull(store.readManifest(tenant, DatasetId.of("ds1")))
    }

    @Test
    fun `設定 — export-dataset は MODEL_STORE_URI が必須で、本番は gs だけ`() {
        val cmd = command()
        val ok = BotSettings.from(baseEnv("/dev/null"), cmd.task)
        assertEquals("file:///tmp/jrabot-test-store", ok.modelStoreUri)
        for (bad in listOf(null, "", "http://x/y", "/abs/path", "file://host/x")) {
            kotlin.test.assertFailsWith<IllegalStateException>("$bad") { BotSettings.from(baseEnv("/dev/null", "MODEL_STORE_URI" to bad), cmd.task) }
        }
        kotlin.test.assertFailsWith<IllegalStateException> { BotSettings.from(baseEnv("/dev/null", "APP_ENV" to "prod", "DB_USER" to "bot"), cmd.task) } // 本番で file:///
        assertEquals("gs://b/p", BotSettings.from(baseEnv("/dev/null", "APP_ENV" to "prod", "DB_USER" to "bot", "MODEL_STORE_URI" to "gs://b/p"), cmd.task).modelStoreUri)
        assertNull(BotSettings.from(baseEnv("/dev/null"), BotTask.RECONCILE).modelStoreUri) // 他のタスクには要らない
    }
}
