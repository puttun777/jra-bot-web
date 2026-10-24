package jp.jrabot.domain.ml.features

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeatureSchemaTest {
    @Test
    fun `calibration は 40 次元・alpha は 35 次元で別のスキーマ`() {
        assertEquals(40, FeatureSchema.CALIBRATION_40.size)
        assertEquals(35, FeatureSchema.ALPHA_35.size)
        assertEquals(5, FeatureSchema.MARKET_5.size)
        assertNotEquals(FeatureSchema.CALIBRATION_40.hash, FeatureSchema.ALPHA_35.hash)
        assertEquals(listOf("odds", "popularity", "log_odds", "inv_popularity", "odds_popularity_interaction"), FeatureSchema.CALIBRATION_40.names.take(5))
        assertEquals("has_horse_master", FeatureSchema.ALPHA_35.names.first())
        assertEquals("pedigree_hash_15", FeatureSchema.ALPHA_35.names.last())
        assertTrue(FeatureSchema.isValidHash(FeatureSchema.CALIBRATION_40.hash))
        assertEquals(71, FeatureSchema.CALIBRATION_40.hash.length)
    }

    @Test
    fun `特徴量の順序が変わるとハッシュが変わる`() {
        val names = FeatureSchema.ALPHA_35.names
        val swapped =
            names.toMutableList().also {
                it[0] = names[1]
                it[1] = names[0]
            }
        val other = FeatureSchema.create(swapped, 16, FeatureSchema.HASH_ALGO_MD5)
        assertNotEquals(FeatureSchema.ALPHA_35.hash, other.hash)
        // 同じ内容なら同じハッシュ（再現性）
        assertEquals(FeatureSchema.ALPHA_35.hash, FeatureSchema.create(names, 16, FeatureSchema.HASH_ALGO_MD5).hash)
    }

    @Test
    fun `ハッシュはハッシュ自身を除いた正準 JSON の SHA-256`() {
        val s = FeatureSchema.CALIBRATION_40
        assertEquals("sha256:" + CanonicalJson.sha256Hex(s.bodyJson()), s.hash)
        assertEquals(s, FeatureSchema.fromJson(s.toJson()))
    }

    @Test
    fun `JSON から読むときは内容とハッシュが一致しなければ拒否する`() {
        val good = FeatureSchema.ALPHA_35.toJson()
        val tampered =
            good.with(
                "names",
                JsonArray(
                    FeatureSchema.ALPHA_35.names
                        .reversed()
                        .map { JsonString(it) },
                ),
            )
        assertFailsWith<FeatureSchemaException> { FeatureSchema.fromJson(tampered) }
        assertFailsWith<FeatureSchemaException> { FeatureSchema.fromJson(good.with("hash", JsonString("sha256:" + "0".repeat(64)))) }
        assertFailsWith<FeatureSchemaException> { FeatureSchema.fromJson(good.with("hash", JsonString("md5:abc"))) }
        assertFailsWith<FeatureSchemaException> { FeatureSchema.fromJson(good.with("extra", JsonString("x"))) }
        assertFailsWith<FeatureSchemaException> { FeatureSchema.fromJson(good.without("missing_policy")) }
    }

    @Test
    fun `重複する名前と空のスキーマは作れない`() {
        assertFailsWith<IllegalArgumentException> { FeatureSchema.create(listOf("a", "a"), 0, "none") }
        assertFailsWith<IllegalArgumentException> { FeatureSchema.create(emptyList(), 0, "none") }
    }
}
