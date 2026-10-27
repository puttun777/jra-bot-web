package jp.jrabot.domain.botrun

import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.model.MlFixtures
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoadResult
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.ml.store.InMemoryModelRegistry
import jp.jrabot.domain.ml.store.LocalFileModelStore
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ML の統合（合成の署名つきモデル・ローカルの保管先・メモリのレジストリ）。**予測精度の証明ではない**: モデルの固定・検証・フォールバックの記録・
 * 通知への配線を確かめる。実データ・実 pkl・本番の GCS は使わない。ACTIVE への昇格・v2 の有効化は行わない。
 */
class BotRunMlTest {
    private val tenant = Cal.T1

    private fun record(
        name: String,
        status: ModelStatus,
    ): ModelRecord {
        val info = MlFixtures.info(name)
        val kind = ModelKind.fromJson((info["kind"] as JsonString).value)!!
        val schemaHash =
            (info["feature_schema_hash"] as? JsonString)?.value ?: ModelLoader.ensembleSchemaHash(
                (ModelLoader(MlFixtures.trust).load(tenant, ModelId.of(name), MlFixtures.artifact(name)) as ModelLoadResult.LoadedEnsemble).set.document,
            )
        return ModelRecord(
            tenantId = tenant,
            modelId = ModelId.of(name),
            kind = kind,
            schemaVersion = 1,
            featureSchemaHash = schemaHash,
            artifactUri = "file:///synthetic/$name",
            sha256 = (info["sha256"] as JsonString).value,
            signatureSha256 = "a".repeat(64),
            status = status,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            activatedAt = if (status == ModelStatus.ACTIVE) Instant.parse("2026-01-02T00:00:00Z") else null,
            activatedBy = if (status == ModelStatus.ACTIVE) "synthetic-admin" else null,
        )
    }

    private fun store(
        root: Path,
        vararg names: String,
    ) = LocalFileModelStore(root).also { s -> names.forEach { assertEquals(ArtifactWriteOutcome.CREATED, s.write(tenant, MlFixtures.artifact(it))) } }

    private fun features(): InMemoryFeatureSource {
        val s = InMemoryFeatureSource()
        Cal.races(count = 3).forEach { r ->
            val id = r.raceId.value
            s.putRace(tenant, id, RaceConditions(Cal.DATE, r.scheduledPostAt, 1600, "11", "00", "1", "2", "3"))
            for (u in 1..6) s.putEntry(tenant, id, u, "20201000$u")
        }
        for (u in 1..6) {
            s.putHorse(
                tenant,
                HorseMaster(
                    "20201000$u", LocalDate.of(2022, u, 1), "$u", ChakuTable(listOf(listOf(u.toLong(), 1L, 1L, 2L))), ChakuTable.EMPTY, ChakuTable.EMPTY,
                    listOf("sire$u", "dam$u"), Instant.parse("2025-12-01T00:00:00Z"),
                ),
            )
            for (d in 1..3) s.putPastRun(tenant, "20201000$u", PastRun("2025120${d}0611", LocalDate.of(2025, 12, d), d))
        }
        return s
    }

    private fun ctx(
        type: ProbabilityModelType,
        registry: InMemoryModelRegistry,
        store: LocalFileModelStore,
        loader: ModelLoader = ModelLoader(MlFixtures.trust),
    ) = MlRunStarter.start(type, tenant, registry, store, loader, jp.jrabot.domain.botrun.testing.ManualClock(Instant.parse("2026-10-24T00:00:00Z")))

    private fun kit(mlc: MlRunContext) = Kit(mlContext = mlc, judge = null, config = Kit.defaultConfig(mlc.modelType), features = features())

    @Test
    fun `ACTIVE の calibration を run の開始時に固定し 署名検証済みのモデルで確率を出し model_id を記録する`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, store(root, "cal_test_001"))
        assertEquals("cal_test_001", mlc.pinnedModelId)
        val k = kit(mlc)
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.evaluations.rows.size)
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "calibration" && it.modelId == "cal_test_001" && it.fallbackReason == null })
        assertEquals(0, k.notifier.errors.size) // フォールバックの警告なし
    }

    @Test
    fun `run の途中でレジストリの ACTIVE が替わっても 固定したモデルを使い続ける`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        val st = store(root, "cal_test_001", "cal_v2_001")
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, st)
        val k = kit(mlc)
        k.onPause = {
            if (k.clock.now() >= k.at(9, 0)) registry.seed(record("cal_v2_001", ModelStatus.ACTIVE).copy(modelId = ModelId.of("cal_v2_001")))
        }
        k.run()
        assertTrue(k.evaluations.rows.values.all { it.modelId == "cal_test_001" })
    }

    @Test
    fun `未承認の CANDIDATE しか無ければ 市場確率に落ちて理由を記録し 運用者に警告する 昇格はしない`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.CANDIDATE)) }
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, store(root, "cal_test_001"))
        val k = kit(mlc)
        k.run()
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.modelId == null && it.fallbackReason == "MODEL_NOT_ACTIVE" })
        assertEquals(1, k.notifier.errors.count { it.subject.contains("ML_MARKET_FALLBACK") }) // run あたり 1 回
        assertNull(registry.active(tenant, ModelKind.CALIBRATION))
        assertEquals(ModelStatus.CANDIDATE, registry.find(tenant, ModelId.of("cal_test_001"))!!.status)
    }

    @Test
    fun `署名の検証に失敗したモデルは使わない`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, store(root, "cal_test_001"), ModelLoader(MlFixtures.otherTrust)) // 別の鍵 = 検証 NG
        val k = kit(mlc)
        k.run()
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.fallbackReason == "MODEL_SIGNATURE_INVALID" })
        assertTrue(k.notifier.errors.any { it.subject.contains("ML_MARKET_FALLBACK") })
    }

    @Test
    fun `成果物が保管先に無ければ MODEL_NOT_FOUND で市場確率に落ちる`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, store(root)) // 何も書かない
        val k = kit(mlc)
        k.run()
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.fallbackReason == "MODEL_NOT_FOUND" })
    }

    @Test
    fun `レジストリの障害は run を止めず 理由つきで市場確率に落ちる`() {
        val failing =
            object : jp.jrabot.domain.ml.registry.ModelRegistryReader {
                override fun find(
                    tenantId: TenantId,
                    modelId: ModelId,
                ) = throw IllegalStateException("registry down")

                override fun active(
                    tenantId: TenantId,
                    kind: ModelKind,
                ) = throw IllegalStateException("registry down")
            }
        val mlc = MlRunStarter.start(ProbabilityModelType.CALIBRATION, tenant, failing, LocalFileModelStore(Path.of("/nonexistent")), ModelLoader(MlFixtures.trust), jp.jrabot.domain.botrun.testing.ManualClock(Instant.EPOCH))
        val k = kit(mlc)
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.fallbackReason == "MODEL_STORAGE_ERROR" })
    }

    @Test
    fun `特徴量の元データが欠けていても 判定は止まらず 市場確率に落ちて理由が残る point-in-time の検査`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        val mlc = ctx(ProbabilityModelType.CALIBRATION, registry, store(root, "cal_test_001"))
        val k = Kit(mlContext = mlc, judge = null, config = Kit.defaultConfig(mlc.modelType)) // 特徴量の元データ（レース条件）が空
        k.run()
        assertEquals(3, k.evaluations.rows.size)
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.fallbackReason == "MODEL_PREDICTION_FAILED" })
    }

    @Test
    fun `ML 無効のときは市場確率で ML_DISABLED を記録し 警告しない`() {
        val k = Kit(judge = null)
        k.run()
        assertTrue(k.evaluations.rows.values.all { it.modelTypeUsed == "market" && it.fallbackReason == "ML_DISABLED" })
        assertEquals(0, k.notifier.errors.size)
    }

    @Test
    fun `fixes や v2 のような未承認の設定は構築できない`() {
        val cfg = Kit.defaultConfig().decision
        val e =
            runCatching { cfg.copy(fixes = jp.jrabot.domain.decision.DecisionFixes(applyMaxFraction = true)) }.exceptionOrNull()
        assertTrue(e != null, "未承認の数値変更は拒否される")
    }
}
