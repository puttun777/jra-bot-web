package jp.jrabot.domain.ml.features

import jp.jrabot.domain.ml.json.CanonicalJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `feature_schema` の JSON 契約（`domain/src/test/resources/ml/feature_schema.*.json`）が、Kotlin の定義（唯一の正）から生成した正準 JSON と**バイト単位で同じ**であることを確認する。
 * Python 側（`tools/ml`）はこのファイルを読み、**名前の一覧を再実装せずに**ハッシュを独立に再計算して検証する（CI の `ml tools` ジョブ）。
 * ファイルを更新するときは `ML_UPDATE_FIXTURES=1 ./gradlew :domain:test --tests '*FeatureSchemaFixtureTest'`（差分を PR で確認すること）。
 */
class FeatureSchemaFixtureTest {
    private val update = System.getenv("ML_UPDATE_FIXTURES") == "1"

    private fun check(
        file: String,
        schema: FeatureSchema,
    ) {
        val expected = CanonicalJson.encodeBytes(schema.toJson())
        if (update) {
            Files.write(Path.of("src/test/resources/ml/$file"), expected)
            return // 更新モード: 書き出すだけ（クラスパスの資源は次回のビルドで更新される）。もう一度（環境変数なしで）実行して確認する
        }
        val actual = javaClass.getResourceAsStream("/ml/$file")?.readAllBytes() ?: error("fixture が無い: $file")
        assertEquals(String(expected, Charsets.UTF_8), String(actual, Charsets.UTF_8), "$file が Kotlin の定義と違う")
    }

    @Test
    fun `feature_schema の JSON 契約は Kotlin の定義と一致する`() {
        check("feature_schema.calibration40.json", FeatureSchema.CALIBRATION_40)
        check("feature_schema.alpha35.json", FeatureSchema.ALPHA_35)
        check("feature_schema.market5.json", FeatureSchema.MARKET_5)
    }
}
