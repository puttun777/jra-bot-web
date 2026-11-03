package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 署名鍵の扱い（RFC 8032 の試験ベクトル。シード → 公開鍵の導出と署名が規格どおりであること）。 */
class ModelJsonTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `rfc 8032 test vectors 1 and 2`() {
        val seed1 = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", CanonicalJson.hex(ModelJson.publicKeyOf(seed1)))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            CanonicalJson.hex(ModelJson.sign(seed1, ByteArray(0))),
        )
        val seed2 = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", CanonicalJson.hex(ModelJson.publicKeyOf(seed2)))
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            CanonicalJson.hex(ModelJson.sign(seed2, hex("72"))),
        )
    }

    @Test
    fun `signing key and public keys are validated without echoing values`() {
        assertEquals("MODEL_SIGNING_KEY_MISSING", assertFailsWith<UsageError> { ModelJson.loadSigningSeed(null) }.reason)
        val e = assertFailsWith<UsageError> { ModelJson.loadSigningSeed("not*base64") }
        assertEquals("MODEL_SIGNING_KEY_INVALID", e.reason)
        assertEquals(false, "not*base64" in (e.message ?: ""))
        assertEquals("MODEL_SIGNING_KEY_INVALID", assertFailsWith<UsageError> { ModelJson.loadSigningSeed("AAAA") }.reason)
        assertEquals("MODEL_VERIFY_PUBKEY_INVALID", assertFailsWith<UsageError> { ModelJson.parsePubkeys("AAAA") }.reason)
        assertEquals(emptyList(), ModelJson.parsePubkeys(" , "))
        val k = ByteArray(32) { 1 }
        assertContentEquals(
            k,
            ModelJson
                .parsePubkeys(
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(k),
                ).single(),
        )
    }

    @Test
    fun `negative zero is written as zero and non finite numbers are refused`() {
        assertEquals("0.0", CanonicalJson.encode(ModelJson.f(-0.0)))
        assertEquals("MODEL_INVALID", assertFailsWith<ModelVerificationError> { ModelJson.f(Double.NaN) }.reason)
        assertEquals("MODEL_INVALID", assertFailsWith<ModelVerificationError> { ModelJson.of(mapOf("x" to Double.POSITIVE_INFINITY)) }.reason)
    }
}

class SynthReprTest {
    @Test
    fun `python float repr`() {
        val cases =
            mapOf(
                1.0 to "1.0",
                0.1 to "0.1",
                5.16632428930245E-4 to "0.000516632428930245",
                1.5e-5 to "1.5e-05",
                -2.5e-7 to "-2.5e-07",
                1e16 to "1e+16",
                1.2345e17 to "1.2345e+17",
                123456789012345.0 to "123456789012345.0",
                -3.0 to "-3.0",
                0.0001 to "0.0001",
            )
        for ((v, want) in cases) assertEquals(want, SynthDataset.repr(v), v.toString())
    }
}

class LogisticRoundoffTest {
    @Test
    fun `a nearly unregularised fit stops at the roundoff limit instead of using up the iterations`() {
        // Platt と同じ設定（C = 1e6・tol = 1e-10）で、目的関数が大きく最適解の近くの減少が丸めに埋もれるデータ
        val rnd = java.util.Random(3)
        val n = 4000
        val z = Array(n) { doubleArrayOf(rnd.nextGaussian() * 3.0) }
        val y = IntArray(n) { if (rnd.nextDouble() < 1.0 / (1.0 + kotlin.math.exp(-(1.7 * z[it][0] - 0.4)))) 1 else 0 }
        val fit =
            jp.jrabot.mltrain.numeric.Logistic
                .fit(z, y, null, 1e6, 1e-10, 1000)
        kotlin.test.assertTrue(fit.iterations < 100, "反復 ${fit.iterations}")
        kotlin.test.assertTrue(fit.gradientMax <= 1e-8, "勾配 ${fit.gradientMax}")
        kotlin.test.assertTrue(kotlin.math.abs(fit.weights[0] - 1.7) < 0.2 && kotlin.math.abs(fit.bias + 0.4) < 0.2, "a=${fit.weights[0]} b=${fit.bias}")
    }
}
