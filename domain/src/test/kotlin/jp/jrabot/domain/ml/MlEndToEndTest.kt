package jp.jrabot.domain.ml

import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.LegacyFeatureBuilder
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.model.MlFixtures
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.ml.pipeline.PipelineConfig
import jp.jrabot.domain.ml.pipeline.ProbSource
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ml.pipeline.ProbabilityPipelineVersion
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelRunPin
import jp.jrabot.domain.ml.registry.ModelSession
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.ml.store.InMemoryModelRegistry
import jp.jrabot.domain.ml.store.LocalFileModelStore
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.FixedClock
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 合成のローカル [LocalFileModelStore]・インメモリのレジストリで、モデルの固定 → 検証つき読込み → 特徴量 → 確率までを通す（実バケット・実 DB・本番鍵は使わない）。
 * 未承認（CANDIDATE）のモデルは使われず、ACTIVE への自動昇格もしない。
 */
class MlEndToEndTest {
    private val tenant = TenantId.of(1)
    private val clock = FixedClock(Instant.parse("2026-01-04T05:00:00Z"))
    private val loader = ModelLoader(MlFixtures.trust)
    private val raceId = "202601040511"
    private val cfg = PipelineConfig(0.25, 0.35, true, 0.35)

    private fun record(
        name: String,
        status: ModelStatus,
        tenantId: TenantId = tenant,
        kind: ModelKind? = null,
    ): ModelRecord {
        val info = MlFixtures.info(name)
        val k = kind ?: ModelKind.fromJson((info["kind"] as JsonString).value)!!
        val schemaHash =
            (info["feature_schema_hash"] as? JsonString)?.value ?: ModelLoader.ensembleSchemaHash(
                (loader.load(tenant, ModelId.of(name), MlFixtures.artifact(name)) as jp.jrabot.domain.ml.model.ModelLoadResult.LoadedEnsemble).set.document,
            )
        return ModelRecord(
            tenantId = tenantId,
            modelId = ModelId.of(name),
            kind = k,
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
        names: List<String>,
    ): LocalFileModelStore =
        LocalFileModelStore(root).also { s -> names.forEach { assertEquals(ArtifactWriteOutcome.CREATED, s.write(tenant, MlFixtures.artifact(it))) } }

    private fun source(): InMemoryFeatureSource {
        val s = InMemoryFeatureSource()
        s.putRace(tenant, raceId, RaceConditions(LocalDate.of(2026, 1, 4), Instant.parse("2026-01-04T06:25:00Z"), 1600, "11", "00", "1", "2", "3"))
        for (u in 1..6) s.putEntry(tenant, raceId, u, if (u <= 4) "20201000$u" else "")
        for (u in 1..4) {
            s.putHorse(
                tenant,
                HorseMaster(
                    "20201000$u",
                    LocalDate.of(2022, u, 1),
                    "$u",
                    ChakuTable(listOf(listOf(u.toLong(), 1L, 1L, 2L))),
                    ChakuTable.EMPTY,
                    ChakuTable.EMPTY,
                    listOf("sire$u", "dam$u"),
                    Instant.parse("2025-12-01T00:00:00Z"),
                ),
            )
            for (k in 1..u) s.putPastRun(tenant, "20201000$u", PastRun("2025120${k}0611", LocalDate.of(2025, 12, k), k))
        }
        return s
    }

    private val horses =
        listOf(HorseOdds(1, 2.5, 1), HorseOdds(2, 4.0, 2), HorseOdds(3, 8.0, 3), HorseOdds(4, 15.0, 4), HorseOdds(5, 30.0, 5), HorseOdds(6, 0.0, 0))

    private fun inference(
        root: Path,
        registry: InMemoryModelRegistry,
    ): MlInference {
        val pin = ModelRunPin.capture(registry, tenant, clock)
        val session = ModelSession(pin, LocalFileModelStore(root), loader, registry)
        val builder = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2026, 1, 4, 12, 0))
        // 旧互換: 特徴量はそのモデルのスキーマで作る（alpha は 35 次元・calibration は 40 次元）
        return MlInference(session.loaded).also {
            features =
                FeatureProvider { schema, i -> builder.build(schema, raceId, horses[i].umaban, horses[i].odds, horses[i].popularity) }
        }
    }

    private lateinit var features: FeatureProvider

    @Test
    fun `ACTIVE の calibration を固定して読み、検証済みモデルで確率が出る`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        store(root, listOf("cal_test_001"))
        val r = inference(root, registry).probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.CALIBRATION, true, horses, features, cfg)
        assertEquals(ProbSource.ML_CALIBRATION, r.source)
        assertNull(r.fallbackReason)
        assertEquals(1.0, r.probs.sum(), 1e-12)
        // 独立に計算した値と一致
        val m =
            (
                ModelSession(
                    ModelRunPin.capture(registry, tenant, clock),
                    LocalFileModelStore(root),
                    loader,
                    registry,
                ).loaded.calibration as jp.jrabot.domain.ml.registry.RoleLoad.Ready
            ).model
        val raw = m.predict(horses.indices.map { features.vector(FeatureSchema.CALIBRATION_40, it) }, horses.map { if (it.odds <= 0) 100.0 else it.odds }, 0.25)
        assertContentEquals(
            jp.jrabot.domain.ml.pipeline.ProbabilityStages
                .normalizeWithCorrection(raw),
            r.preBlend,
        )
    }

    @Test
    fun `未承認の CANDIDATE は使われず市場確率に落ちて理由が残る（自動昇格しない）`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.CANDIDATE)) }
        store(root, listOf("cal_test_001"))
        val r = inference(root, registry).probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.CALIBRATION, true, horses, features, cfg)
        assertEquals(ProbSource.MARKET_FALLBACK, r.source)
        assertEquals(FallbackReason.MODEL_NOT_ACTIVE, r.fallbackReason)
        assertNull(registry.active(tenant, ModelKind.CALIBRATION)) // 読んだだけで昇格していない
        assertEquals(ModelStatus.CANDIDATE, registry.find(tenant, ModelId.of("cal_test_001"))!!.status)
    }

    @Test
    fun `run 開始時に固定したモデルは、途中で別のモデルが ACTIVE になっても変わらない`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        store(root, listOf("cal_test_001", "cal_v2_001"))
        val pin = ModelRunPin.capture(registry, tenant, clock)
        assertEquals("cal_test_001", pin.record(ModelKind.CALIBRATION)!!.modelId.value)
        // run の途中で管理者が別モデルを昇格したと仮定（レジストリを差し替える）
        val promoted = InMemoryModelRegistry().also { it.seed(record("cal_v2_001", ModelStatus.ACTIVE)) }
        val after = ModelRunPin.capture(promoted, tenant, clock)
        assertEquals("cal_v2_001", after.record(ModelKind.CALIBRATION)!!.modelId.value)
        // 固定済みの pin は不変
        assertEquals("cal_test_001", pin.record(ModelKind.CALIBRATION)!!.modelId.value)
        assertEquals(mapOf(ModelKind.CALIBRATION to ModelId.of("cal_test_001")), pin.modelIds())
        assertEquals(clock.now(), pin.pinnedAt)
    }

    @Test
    fun `他テナントの ACTIVE モデルは固定されない`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE, tenantId = TenantId.of(2))) }
        val pin = ModelRunPin.capture(registry, tenant, clock)
        assertNull(pin.record(ModelKind.CALIBRATION))
    }

    @Test
    fun `成果物が無い・署名ファイルが無い・改ざん・レジストリとの不一致はそれぞれ理由つきで拒否される`(
        @TempDir root: Path,
    ) {
        val registry =
            InMemoryModelRegistry().also {
                it.seed(record("cal_test_001", ModelStatus.ACTIVE))
                it.seed(record("alpha_test_001", ModelStatus.ACTIVE))
                it.seed(record("legacy_test_001", ModelStatus.ACTIVE))
            }
        val pin = ModelRunPin.capture(registry, tenant, clock)
        // calibration: 成果物なし / alpha: 署名ファイルなし / legacy: 内容が違う（レジストリの sha256 と不一致）
        val dir = root.resolve("1").resolve("models")
        Files.createDirectories(dir)
        Files.write(dir.resolve("alpha_test_001.json"), MlFixtures.json("alpha_test_001"))
        Files.write(dir.resolve("legacy_test_001.json"), MlFixtures.json("cal_test_001"))
        Files.write(dir.resolve("legacy_test_001.sig"), MlFixtures.sig("cal_test_001"))
        val loaded = ModelSession(pin, LocalFileModelStore(root), loader, registry).loaded
        assertEquals(FallbackReason.MODEL_NOT_FOUND, assertIs<jp.jrabot.domain.ml.registry.RoleLoad.Missing>(loaded.calibration).reason)
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, assertIs<jp.jrabot.domain.ml.registry.RoleLoad.Missing>(loaded.alpha).reason)
        assertTrue(
            assertIs<jp.jrabot.domain.ml.registry.RoleLoad.Missing>(loaded.legacy).reason in
                setOf(FallbackReason.MODEL_INVALID, FallbackReason.MODEL_HASH_INVALID),
        )
    }

    @Test
    fun `ensemble セットはメンバーを検証して読み、legacy と v2 の両方で確率が出る`(
        @TempDir root: Path,
    ) {
        val registry =
            InMemoryModelRegistry().also {
                it.seed(record("ens_test_001", ModelStatus.ACTIVE))
                it.seed(record("cal_test_001", ModelStatus.RETIRED))
                it.seed(record("alpha_test_001", ModelStatus.RETIRED))
            }
        store(root, listOf("ens_test_001", "cal_test_001", "alpha_test_001"))
        val inf = inference(root, registry)
        val legacy = inf.probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.ENSEMBLE, true, horses, features, cfg)
        val v2 = inf.probabilities(ProbabilityPipelineVersion.V2, ProbabilityModelType.ENSEMBLE, true, horses, features, cfg)
        assertEquals(1.0, legacy.probs.sum(), 1e-12)
        assertEquals(1.0, v2.probs.sum(), 1e-12)
        assertEquals(0.0, legacy.betaEffs.last()) // カバレッジ 0（馬マスタ・過去走が無い馬）は β_eff = β × 0 = 0
        assertEquals(0.35 * legacy.coverageScores[0], legacy.betaEffs[0]) // β はセットの 0.35
        assertNotEquals(legacy.probs, v2.probs) // 二重ブレンド（legacy）と単回ブレンド（v2）で違う
        assertEquals(ProbabilityPipelineVersion.V2, v2.version)
        assertTrue(legacy.coverageScores.take(4).all { it > 0.0 })
        // 馬マスタ・過去走の無い馬 5・6 は coverage 0（旧と同じ式）
        assertEquals(0.0, legacy.coverageScores[4])
    }

    @Test
    fun `同じ calibration モデルでも legacy と v2 は市場確率からの乖離が違う（H4 二重ブレンド）`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        store(root, listOf("cal_test_001"))
        val inf = inference(root, registry)
        val legacy = inf.probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.CALIBRATION, true, horses, features, cfg)
        val v2 = inf.probabilities(ProbabilityPipelineVersion.V2, ProbabilityModelType.CALIBRATION, true, horses, features, cfg)
        val market =
            jp.jrabot.domain.decision.MarketProbability
                .estimateProbNormalized(horses.map { it.odds })
        val dLegacy = legacy.probs.zip(market).sumOf { (p, m) -> kotlin.math.abs(p - m) }
        val dV2 = v2.probs.zip(market).sumOf { (p, m) -> kotlin.math.abs(p - m) }
        assertTrue(dV2 > dLegacy, "市場確率との L1 距離 legacy=$dLegacy v2=$dV2")
        println(
            "ML_PIPELINE_DIFF legacy_vs_market_L1=$dLegacy v2_vs_market_L1=$dV2 legacy_vs_v2_max_abs=${legacy.probs.zip(v2.probs).maxOf { (a, b) ->
                kotlin.math.abs(a - b)
            }}",
        )
    }

    @Test
    fun `メンバーが REJECTED の ensemble は使えず、理由が残る`(
        @TempDir root: Path,
    ) {
        val registry =
            InMemoryModelRegistry().also {
                it.seed(record("ens_test_001", ModelStatus.ACTIVE))
                it.seed(record("cal_test_001", ModelStatus.REJECTED))
                it.seed(record("alpha_test_001", ModelStatus.RETIRED))
            }
        store(root, listOf("ens_test_001", "cal_test_001", "alpha_test_001"))
        val r = inference(root, registry).probabilities(ProbabilityPipelineVersion.V2, ProbabilityModelType.ENSEMBLE, true, horses, features, cfg)
        assertEquals(ProbSource.ML_ALPHA, r.source) // v2: calibration を外して alpha 単体
        assertEquals(FallbackReason.MODEL_NOT_ACTIVE, r.fallbackReason)
    }

    @Test
    fun `モデルの特徴量が作れない入力は推論失敗として理由を残す（発走後の asOf など）`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        store(root, listOf("cal_test_001"))
        val broken = FeatureProvider { _, _ -> error("特徴量が作れない") }
        val r = inference(root, registry).probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.CALIBRATION, true, horses, broken, cfg)
        assertEquals(ProbSource.MARKET_FALLBACK, r.source)
        assertEquals(FallbackReason.MODEL_PREDICTION_FAILED, r.fallbackReason)
    }

    @Test
    fun `ローカルストアは作成のみで上書きせず、パスはテナントとモデルから組み立てる`(
        @TempDir root: Path,
    ) {
        val s = store(root, listOf("cal_test_001"))
        assertEquals(ArtifactWriteOutcome.UNCHANGED, s.write(tenant, MlFixtures.artifact("cal_test_001")))
        val other = ModelArtifact(ModelId.of("cal_test_001"), MlFixtures.json("alpha_test_001"), MlFixtures.sig("alpha_test_001"))
        assertEquals(ArtifactWriteOutcome.CONFLICT, s.write(tenant, other))
        assertTrue(Files.isRegularFile(root.resolve("1/models/cal_test_001.json")))
        assertNull(s.read(TenantId.of(2), ModelId.of("cal_test_001"))) // 他テナントのパスは別
        assertNull(s.read(tenant, ModelId.of("nope")))
        assertFailsWith<IllegalArgumentException> { ModelId.of("../x") }
    }

    @Test
    fun `ACTIVE の 2 重登録と activatedAt の無い ACTIVE は作れない`() {
        val registry = InMemoryModelRegistry().also { it.seed(record("cal_test_001", ModelStatus.ACTIVE)) }
        assertFailsWith<IllegalArgumentException> { registry.seed(record("cal_v2_001", ModelStatus.ACTIVE)) }
        assertFailsWith<IllegalArgumentException> { record("cal_test_001", ModelStatus.ACTIVE).copy(activatedAt = null) }
    }

    @Test
    fun `ML 無効・モデルが 1 つも無いときの結果`(
        @TempDir root: Path,
    ) {
        val registry = InMemoryModelRegistry()
        val inf = inference(root, registry)
        val off = inf.probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.ENSEMBLE, false, horses, features, cfg)
        assertEquals(FallbackReason.ML_DISABLED, off.fallbackReason)
        val none = inf.probabilities(ProbabilityPipelineVersion.LEGACY, ProbabilityModelType.ENSEMBLE, true, horses, features, cfg)
        assertEquals(ProbSource.MARKET_FALLBACK, none.source)
        assertEquals(FallbackReason.MODEL_NOT_ACTIVE, none.fallbackReason)
    }
}
