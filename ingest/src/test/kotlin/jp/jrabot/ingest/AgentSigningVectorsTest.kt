package jp.jrabot.ingest

import jp.jrabot.domain.ingest.AgentSigning
import jp.jrabot.domain.ingest.Hashing
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * エージェント（C#。agent/cs の SigningTests）と共有する署名の既知テストベクトル（OpenSSL で独立に計算。agent/tests/vectors/signing_vectors.json）。
 * サーバの `AgentSigning` が同じ鍵導出・署名対象・署名を出すことを検査する。ファイルが無い・0 件なら失敗する。
 */
class AgentSigningVectorsTest {
    private fun file(): Path {
        var p: Path? = Path.of("").toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("agent/tests/vectors/signing_vectors.json"))) p = p.parent
        return checkNotNull(p) { "署名ベクトルが見つからない" }.resolve("agent/tests/vectors/signing_vectors.json")
    }

    @Test
    fun `サーバの署名実装がベクトルと一致する`() {
        val doc = JsonMapper.builder().build().readTree(Files.readString(file()))
        val cases = doc["cases"]
        assertTrue(cases.size() >= 7)
        assertEquals("jra-bot/agent-sign/v1", doc["derive_label"].asString())
        for (c in cases) {
            val name = c["name"].asString()
            val body =
                c["bodyHex"]
                    .asString()
                    .chunked(2)
                    .filter { it.isNotEmpty() }
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
            val key = AgentSigning.deriveSigningKey(c["token"].asString())
            assertEquals(c["signingKeyHex"].asString(), key.joinToString("") { "%02x".format(it) }, name)
            assertEquals(c["bodySha256"].asString(), Hashing.sha256Hex(body), name)
            val canonical =
                AgentSigning.canonical(
                    c["method"].asString(),
                    c["requestUri"].asString(),
                    c["timestamp"].asLong(),
                    c["nonce"].asString(),
                    c["bodySha256"].asString(),
                )
            assertEquals(c["canonical"].asString(), canonical, name)
            assertEquals(c["signature"].asString(), AgentSigning.sign(key, canonical), name)
        }
    }
}
