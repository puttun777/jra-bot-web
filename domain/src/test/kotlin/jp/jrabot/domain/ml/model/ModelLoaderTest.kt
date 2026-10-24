package jp.jrabot.domain.ml.model

import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.features.FeatureMeta
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureVector
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.pipeline.FallbackReason
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** モデルの署名・sha256・スキーマ・次元の検証（G7: 新設計の仕様テスト。旧実装に対応物は無い）。成果物は `tools/ml` が生成した合成モデル。 */
class ModelLoaderTest {
    private val tenant = TenantId.of(1)
    private val loader = ModelLoader(MlFixtures.trust)

    private fun load(
        name: String,
        id: String = name,
        expected: ExpectedModel = ExpectedModel(),
        artifact: ModelArtifact = MlFixtures.artifact(name, id),
    ) = loader.load(tenant, ModelId.of(id), artifact, expected)

    private fun rejected(r: ModelLoadResult): FallbackReason = assertIs<ModelLoadResult.Rejected>(r).reason

    @ParameterizedTest
    @ValueSource(strings = ["cal_test_001", "alpha_test_001", "legacy_test_001", "cal_v2_001"])
    fun `署名・sha256・スキーマが正しい線形モデルは読める`(name: String) {
        val r = assertIs<ModelLoadResult.Loaded>(load(name))
        val info = MlFixtures.info(name)
        assertEquals((info["sha256"] as JsonString).value, r.model.sha256)
        assertEquals((info["feature_schema_hash"] as JsonString).value, r.model.schema.hash)
        assertEquals((info["kind"] as JsonString).value, r.model.kind.json)
        assertEquals(
            r.model.schema.size,
            r.model.document
                .weights()!!
                .size,
        )
    }

    @Test
    fun `ensemble セットは読め、メンバーと β が取れる`() {
        val r = assertIs<ModelLoadResult.LoadedEnsemble>(load("ens_test_001"))
        assertEquals("cal_test_001", r.set.spec.calibrationModelId.value)
        assertEquals("alpha_test_001", r.set.spec.alphaModelId.value)
        assertEquals(0.35, r.set.spec.beta)
        assertEquals(listOf(0.5, 0.3, 0.2), r.set.spec.coverageWeights)
        assertTrue(ModelLoader.ensembleSchemaHash(r.set.document).startsWith("sha256:"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "bad_sha256", "dim_mismatch", "schema_hash_wrong", "alpha_with_40", "wrong_tenant", "version2", "unknown_key", "wrong_algorithm",
            "non_canonical", "nan_weight", "inf_weight", "signed_by_other_key", "zero_signature",
        ],
    )
    fun `不正な成果物は理由コードつきでロードを拒否する`(name: String) {
        val expectedReason = FallbackReason.valueOf((MlFixtures.info(name)["reject"] as JsonString).value)
        assertEquals(expectedReason, rejected(load(name)), name)
    }

    @Test
    fun `未署名・署名不正・改ざんは JSON を解釈する前に拒否する`() {
        val good = MlFixtures.artifact("cal_test_001")
        // 署名なし相当（0 埋め）・1 バイト短い・別モデルの署名の流用
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, rejected(load("zero_signature")))
        assertEquals(
            FallbackReason.MODEL_SIGNATURE_INVALID,
            rejected(load("cal_test_001", artifact = ModelArtifact(good.modelId, good.json(), good.signature().copyOf(63)))),
        )
        assertEquals(
            FallbackReason.MODEL_SIGNATURE_INVALID,
            rejected(load("cal_test_001", artifact = ModelArtifact(good.modelId, good.json(), MlFixtures.sig("alpha_test_001")))),
        )
        // 内容の 1 バイト改ざん（重みの数字 1 桁）
        val tampered = good.json()
        val i = String(tampered, Charsets.UTF_8).indexOf("\"bias\":-1.5") + 9
        tampered[i] = '9'.code.toByte()
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, rejected(load("cal_test_001", artifact = ModelArtifact(good.modelId, tampered, good.signature()))))
        // 署名を 1 ビット反転
        val badSig = good.signature().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(FallbackReason.MODEL_SIGNATURE_INVALID, rejected(load("cal_test_001", artifact = ModelArtifact(good.modelId, good.json(), badSig))))
    }

    @Test
    fun `検証鍵が別なら拒否し、鍵のローテーション中は複数鍵のどれかで通る`() {
        val other = ModelLoader(MlFixtures.otherTrust)
        assertEquals(
            FallbackReason.MODEL_SIGNATURE_INVALID,
            assertIs<ModelLoadResult.Rejected>(other.load(tenant, ModelId.of("cal_test_001"), MlFixtures.artifact("cal_test_001"))).reason,
        )
        val rotating = ModelTrust.fromBase64(" ${expectedKey("public_key_other")} , ${expectedKey("public_key_primary")} ")
        assertIs<ModelLoadResult.Loaded>(ModelLoader(rotating).load(tenant, ModelId.of("cal_test_001"), MlFixtures.artifact("cal_test_001")))
        assertFailsWith<IllegalArgumentException> { ModelTrust(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ModelTrust.fromBase64("  ") }
        assertFailsWith<IllegalArgumentException> { ModelTrust.publicKeyFromRaw(ByteArray(31)) }
    }

    private fun expectedKey(name: String) = (MlFixtures.expected[name] as JsonString).value

    @Test
    fun `レジストリの記録と食い違う成果物は拒否する（差し替えの検出）`() {
        assertEquals(FallbackReason.MODEL_HASH_INVALID, rejected(load("cal_test_001", expected = ExpectedModel(sha256 = "0".repeat(64)))))
        assertEquals(
            FallbackReason.FEATURE_SCHEMA_MISMATCH,
            rejected(load("cal_test_001", expected = ExpectedModel(featureSchemaHash = FeatureSchema.ALPHA_35.hash))),
        )
        assertEquals(FallbackReason.MODEL_INVALID, rejected(load("cal_test_001", expected = ExpectedModel(kind = ModelKind.ALPHA))))
        // 記録と一致すれば通る
        val info = MlFixtures.info("cal_test_001")
        assertIs<ModelLoadResult.Loaded>(
            load("cal_test_001", expected = ExpectedModel((info["sha256"] as JsonString).value, FeatureSchema.CALIBRATION_40.hash, ModelKind.CALIBRATION)),
        )
    }

    @Test
    fun `要求した model_id と成果物・他テナントの要求は拒否する`() {
        assertEquals(
            FallbackReason.MODEL_INVALID,
            rejected(load("cal_test_001", id = "alpha_test_001", artifact = MlFixtures.artifact("cal_test_001", "alpha_test_001"))),
        )
        // 成果物の ModelId を別名にしても、中身の model_id と要求が違えば拒否
        val other = TenantId.of(2)
        val r = loader.load(other, ModelId.of("cal_test_001"), MlFixtures.artifact("cal_test_001"))
        assertEquals(FallbackReason.MODEL_INVALID, assertIs<ModelLoadResult.Rejected>(r).reason)
        assertEquals(FallbackReason.MODEL_INVALID, rejected(loader.load(tenant, ModelId.of("alpha_test_001"), MlFixtures.artifact("cal_test_001"))))
    }

    @Test
    fun `拒否の詳細に重み・署名・鍵が含まれない`() {
        val texts = listOf("bad_sha256", "dim_mismatch", "zero_signature", "nan_weight").map { (load(it) as ModelLoadResult.Rejected).detail }
        val weights = String(MlFixtures.json("cal_test_001"), Charsets.UTF_8).substringAfter("\"weights\":[").substringBefore("]")
        for (t in texts) {
            assertTrue(!t.contains(weights.take(20)), t)
            assertTrue(!t.contains(expectedKey("public_key_primary")), t)
        }
    }

    // ---- 推論 ----

    private fun vector(
        schema: FeatureSchema,
        fill: (Int) -> Double,
    ) = FeatureVector(schema, DoubleArray(schema.size, fill), FeatureMeta.EMPTY)

    @Test
    fun `検証済みモデルは重み・bias・Platt・内部ブレンドの手計算と一致する`() {
        val m = assertIs<ModelLoadResult.Loaded>(load("legacy_test_001")).model
        val v = vector(FeatureSchema.MARKET_5) { doubleArrayOf(2.5, 2.0, kotlin.math.ln(2.5), 0.5, 5.0)[it] }
        val w = doubleArrayOf(0.01, -0.05, -0.3, 1.2, -0.0002)
        var z = -1.5
        for (i in 0 until 5) z += v[i] * w[i]
        val p = LogisticScoring.sigmoid(z)
        // 内部ブレンドなし（α=1.0）: Platt a=1.1, b=-0.2 のみ
        val pp = LogisticScoring.postprocess(p, 2.5, PlattCalibration(1.1, -0.2), 1.0)
        assertEquals(pp, m.predict(listOf(v), listOf(2.5), 1.0)[0], 1e-15)
        // α=0.25 の内部ブレンド（旧: 実行時に上書き）
        val pb = LogisticScoring.postprocess(p, 2.5, PlattCalibration(1.1, -0.2), 0.25)
        assertEquals(pb, m.predict(listOf(v), listOf(2.5), 0.25)[0], 1e-15)
        assertTrue(pb != pp)
    }

    @Test
    fun `特徴量のスキーマが違えば推論せず例外（暗黙の補正をしない）`() {
        val cal = assertIs<ModelLoadResult.Loaded>(load("cal_test_001")).model
        val alphaRow = vector(FeatureSchema.ALPHA_35) { 0.1 }
        assertFailsWith<SchemaMismatchException> { cal.predict(listOf(alphaRow), listOf(3.0), 1.0) }
        val alpha = assertIs<ModelLoadResult.Loaded>(load("alpha_test_001")).model
        val calRow = vector(FeatureSchema.CALIBRATION_40) { 0.1 }
        assertFailsWith<SchemaMismatchException> { alpha.predict(listOf(calRow), listOf(3.0), 1.0) }
        // 35 次元を 40 次元モデルへ先頭からスライス/ゼロ埋めするといった入力は、そもそも FeatureVector が作れない
        assertFailsWith<IllegalArgumentException> { FeatureVector(FeatureSchema.CALIBRATION_40, DoubleArray(35), FeatureMeta.EMPTY) }
    }

    @Test
    fun `前処理つきモデルは standardize してから計算する`() {
        val m = assertIs<ModelLoadResult.Loaded>(load("cal_v2_001")).model
        val pre = m.document.preprocessing!!
        val row = vector(FeatureSchema.CALIBRATION_40) { (it % 3).toDouble() + 0.5 }
        val w = m.document.weights()!!
        val x = pre.apply(row.values())
        val z = x.indices.fold(0.0) { a, i -> a + x[i] * w[i] } + m.document.bias!!
        val expected = LogisticScoring.finalClamp(LogisticScoring.postprocess(LogisticScoring.sigmoid(z), 4.0, m.document.calibration, 1.0))
        assertEquals(expected, m.predict(listOf(row), listOf(4.0), 1.0)[0], 1e-15)
    }

    @Test
    fun `計算が有限でなくなれば例外で、黙って値を置換しない`() {
        val m = assertIs<ModelLoadResult.Loaded>(load("cal_test_001")).model
        val w = m.document.weights()!!
        val huge = vector(FeatureSchema.CALIBRATION_40) { if (w[it] >= 0) 1e308 else -1e308 } // 各項が同符号に積み上がる
        assertFailsWith<NonFiniteComputationException> { m.predict(listOf(huge), listOf(3.0), 1.0) }
    }

    @Test
    fun `toString に重みを含めない`() {
        val m = assertIs<ModelLoadResult.Loaded>(load("cal_test_001")).model
        assertTrue(m.toString().contains("cal_test_001") && !m.toString().contains("0."))
    }
}
