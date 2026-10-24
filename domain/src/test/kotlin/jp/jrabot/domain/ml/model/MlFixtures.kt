package jp.jrabot.domain.ml.model

import jp.jrabot.domain.ml.ModelArtifact
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson

/** `domain/src/test/resources/ml`（`tools/ml/make_test_artifacts.py` が生成した合成モデルとテスト専用の署名鍵）を読む。 */
object MlFixtures {
    private fun bytes(path: String): ByteArray = MlFixtures::class.java.getResourceAsStream("/ml/$path")?.readAllBytes() ?: error("fixture が無い: $path")

    val expected: JsonObject by lazy { StrictJson.parse(bytes("expected.json")) as JsonObject }

    val trust: ModelTrust by lazy { ModelTrust.fromBase64((expected["public_key_primary"] as JsonString).value) }
    val otherTrust: ModelTrust by lazy { ModelTrust.fromBase64((expected["public_key_other"] as JsonString).value) }

    fun json(name: String): ByteArray = bytes("models/$name.json")

    fun sig(name: String): ByteArray = bytes("models/$name.sig")

    fun artifact(
        name: String,
        modelId: String = name,
    ): ModelArtifact = ModelArtifact(ModelId.of(modelId), json(name), sig(name))

    fun info(name: String): JsonObject = (expected["models"] as JsonObject)[name] as JsonObject

    val GOOD = listOf("cal_test_001", "alpha_test_001", "legacy_test_001", "cal_v2_001", "ens_test_001")
}
