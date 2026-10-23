package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAuthTest {
    private val now = Instant.parse("2026-11-01T01:00:00Z")
    private val tenant = TenantId.of(1)
    private val agent = AgentId.of("ag_one")
    private val kek1 = ByteArray(32) { (it + 1).toByte() }
    private val kek2 = ByteArray(32) { (it + 101).toByte() }
    private val keks = KeyEncryptionKeys(mapOf("k1" to kek1), "k1")
    private val cipher = SigningKeyCipher(keks)
    private val issuer = AgentCredentialIssuer(cipher, ByteArray(16) { 9 })

    private class Store(
        var credential: AgentCredential?,
    ) : AgentCredentialStore {
        override fun findByAgentId(agentId: AgentId) = credential?.takeIf { it.agentId == agentId }
    }

    private class Nonces : NonceStore {
        val seen = mutableSetOf<String>()

        override fun consume(
            tenantId: TenantId,
            agentId: AgentId,
            nonce: String,
            expiresAt: Instant,
        ) = seen.add("${tenantId.value}/${agentId.value}/$nonce")

        override fun purgeExpired(
            before: Instant,
            limit: Int,
        ) = 0
    }

    private val issued = issuer.issue(tenant, agent)
    private val nonces = Nonces()

    private fun credential(
        status: AgentStatus = AgentStatus.ACTIVE,
        expires: Instant = now.plus(Duration.ofDays(90)),
        key: EncryptedSigningKey? = issued.signingKey,
    ) = AgentCredential(tenant, agent, status, expires, key)

    private fun authenticator(c: AgentCredential? = credential()) = AgentAuthenticator(Store(c), nonces, cipher)

    private fun request(
        token: String = issued.token,
        method: String = "POST",
        uri: String = "/v1/agent/ingest",
        ts: Long = now.epochSecond,
        nonce: String = "nonce-0123456789ab",
        body: String = "{}",
        tamper: (String) -> String = { it },
    ): AgentAuthRequest {
        val bodyHash = Hashing.sha256Hex(body.toByteArray())
        val sig = AgentSigning.sign(AgentSigning.deriveSigningKey(token), AgentSigning.canonical(method, uri, ts, nonce, bodyHash))
        return AgentAuthRequest(method, uri, "ag_one", ts.toString(), nonce, tamper(sig), bodyHash)
    }

    @Test
    fun `一方向ハッシュだけではHMACを検証できない（契約0_4の方式が成り立たない理由の確認）`() {
        // 契約0.4: サーバは SHA-256(pepper + token) だけを持つ。署名鍵は token。ハッシュから token は復元できないので、
        // サーバは HMAC(token, …) を計算して照合できない（= このハッシュ列は検証に使えない）。
        val hash = issued.tokenHash
        assertEquals(64, hash.length)
        val canonical = AgentSigning.canonical("POST", "/v1/agent/ingest", now.epochSecond, "nonce-0123456789ab", Hashing.sha256Hex(ByteArray(0)))
        val forgedWithHash = AgentSigning.sign(hash.toByteArray(), canonical)
        val real = AgentSigning.sign(AgentSigning.deriveSigningKey(issued.token), canonical)
        assertFalse(AgentSigning.constantTimeEquals(forgedWithHash, real), "ハッシュを鍵にしても本物の署名とは一致しない")
        // 導出した署名鍵は token と異なり、token からしか作れない
        assertFalse(AgentSigning.deriveSigningKey(issued.token).contentEquals(issued.token.toByteArray()))
    }

    @Test
    fun `署名が正しければ認証され、テナントはエージェントの行から決まる`() {
        val r = authenticator().authenticate(request(), now)
        val ok = assertIs<AuthResult.Authenticated>(r)
        assertEquals(tenant, ok.tenantId)
        assertEquals(agent, ok.agentId)
    }

    @Test
    fun `不正な署名・本文の改ざん・パスやメソッドの差し替えは401`() {
        val a = authenticator()

        fun denied(req: AgentAuthRequest) = assertIs<AuthResult.Denied>(a.authenticate(req, now)).failure
        assertEquals(AuthFailure.BAD_SIGNATURE, denied(request(tamper = { it.reversed() })))
        // 署名した後に本文のハッシュが変わった
        assertEquals(
            AuthFailure.BAD_SIGNATURE,
            denied(
                request().let {
                    AgentAuthRequest(
                        it.method,
                        it.requestUri,
                        "ag_one",
                        it.timestampHeader,
                        it.nonceHeader,
                        it.signatureHeader,
                        Hashing.sha256Hex("x".toByteArray()),
                    )
                },
            ),
        )
        assertEquals(
            AuthFailure.BAD_SIGNATURE,
            denied(
                request().let {
                    AgentAuthRequest("GET", it.requestUri, "ag_one", it.timestampHeader, it.nonceHeader, it.signatureHeader, it.bodySha256Hex)
                },
            ),
        )
        assertEquals(
            AuthFailure.BAD_SIGNATURE,
            denied(
                request().let {
                    AgentAuthRequest(it.method, "/v1/agent/heartbeat", "ag_one", it.timestampHeader, it.nonceHeader, it.signatureHeader, it.bodySha256Hex)
                },
            ),
        )
        assertEquals(AuthFailure.BAD_SIGNATURE, denied(request(token = "jrat1_wrong")))
        assertEquals(AuthFailure.MALFORMED_HEADERS, denied(request(tamper = { "XYZ" })))
    }

    @Test
    fun `時刻のずれは±300秒まで`() {
        val a = authenticator()
        assertIs<AuthResult.Authenticated>(a.authenticate(request(ts = now.epochSecond - 299, nonce = "nonce-aaaaaaaaaaaa"), now))
        assertEquals(
            AuthFailure.TIMESTAMP_OUT_OF_RANGE,
            assertIs<AuthResult.Denied>(a.authenticate(request(ts = now.epochSecond - 301, nonce = "nonce-bbbbbbbbbbbb"), now)).failure,
        )
        assertEquals(
            AuthFailure.TIMESTAMP_OUT_OF_RANGE,
            assertIs<AuthResult.Denied>(a.authenticate(request(ts = now.epochSecond + 301, nonce = "nonce-cccccccccccc"), now)).failure,
        )
    }

    @Test
    fun `nonce の再利用は拒否（リプレイ）、署名が不正な要求は nonce を消費しない`() {
        val a = authenticator()
        assertIs<AuthResult.Authenticated>(a.authenticate(request(), now))
        assertEquals(AuthFailure.REPLAYED_NONCE, assertIs<AuthResult.Denied>(a.authenticate(request(), now)).failure)
        // 不正署名の要求では nonce 表を増やさない（未認証で表を埋められない）
        val before = nonces.seen.size
        a.authenticate(request(nonce = "nonce-zzzzzzzzzzzz", tamper = { it.reversed() }), now)
        assertEquals(before, nonces.seen.size)
    }

    @Test
    fun `失効は署名が正しい相手にだけ403、期限切れは401、未登録・鍵なしは401`() {
        assertEquals(
            AuthFailure.REVOKED,
            assertIs<AuthResult.Denied>(authenticator(credential(status = AgentStatus.REVOKED)).authenticate(request(), now)).failure,
        )
        assertEquals(403, AuthFailure.REVOKED.status)
        assertEquals(AuthFailure.TOKEN_EXPIRED, assertIs<AuthResult.Denied>(authenticator(credential(expires = now)).authenticate(request(), now)).failure)
        assertEquals(AuthFailure.UNKNOWN_OR_UNUSABLE_AGENT, assertIs<AuthResult.Denied>(authenticator(null).authenticate(request(), now)).failure)
        assertEquals(
            AuthFailure.UNKNOWN_OR_UNUSABLE_AGENT,
            assertIs<AuthResult.Denied>(authenticator(credential(key = null)).authenticate(request(), now)).failure,
        )
        // 失効済みのエージェントに対して、署名が不正なら失効かどうかを明かさない（401）
        val revoked = authenticator(credential(status = AgentStatus.REVOKED))
        assertEquals(AuthFailure.BAD_SIGNATURE, assertIs<AuthResult.Denied>(revoked.authenticate(request(tamper = { it.reversed() }), now)).failure)
    }

    @Test
    fun `署名鍵の暗号化： 往復できる・鍵が違えば復号できない・別のエージェントの行へ移し替えても復号できない`() {
        val key = AgentSigning.deriveSigningKey(issued.token)
        assertContentEquals(key, cipher.decrypt(tenant, agent, issued.signingKey))
        // 暗号文は鍵そのものを含まない
        assertFalse(String(issued.signingKey.blob, Charsets.ISO_8859_1).contains(String(key, Charsets.ISO_8859_1)))
        val otherKek = SigningKeyCipher(KeyEncryptionKeys(mapOf("k1" to kek2), "k1"))
        assertNull(otherKek.decrypt(tenant, agent, issued.signingKey))
        assertNull(cipher.decrypt(tenant, AgentId.of("ag_two"), issued.signingKey), "別エージェントの行へ移した暗号文")
        assertNull(cipher.decrypt(TenantId.of(2), agent, issued.signingKey), "別テナントの行へ移した暗号文")
        val tampered =
            issued.signingKey.blob
                .copyOf()
                .also { it[20] = (it[20] + 1).toByte() }
        assertNull(cipher.decrypt(tenant, agent, EncryptedSigningKey("k1", tampered)))
        assertNull(cipher.decrypt(tenant, agent, EncryptedSigningKey("nope", issued.signingKey.blob)))
        assertNull(cipher.decrypt(tenant, agent, EncryptedSigningKey("k1", ByteArray(5))))
    }

    @Test
    fun `KEK のローテーション： 新しい鍵で暗号化し、旧い鍵の暗号文も復号できる`() {
        val rotated = SigningKeyCipher(KeyEncryptionKeys(mapOf("k1" to kek1, "k2" to kek2), "k2"))
        assertNotNull(rotated.decrypt(tenant, agent, issued.signingKey), "旧 kid の暗号文")
        val fresh = rotated.encrypt(tenant, agent, ByteArray(32) { 5 })
        assertEquals("k2", fresh.kid)
        assertNull(cipher.decrypt(tenant, agent, fresh), "旧設定（k2 を知らない）では復号できない")
    }

    @Test
    fun `KEK の設定値は形式を検証し、鍵の値を文字列表現に出さない`() {
        val spec = "k1:${Base64.getEncoder().encodeToString(kek1)}"
        val parsed = KeyEncryptionKeys.parse(spec, "k1")
        assertContentEquals(kek1, parsed.key("k1"))
        assertFalse(parsed.toString().contains(Base64.getEncoder().encodeToString(kek1)))
        assertFailsWithMessageNotContaining(spec) { KeyEncryptionKeys.parse("k1:@@@", "k1") }
        assertFailsWithMessageNotContaining(spec) { KeyEncryptionKeys.parse("k1:${Base64.getEncoder().encodeToString(ByteArray(16))}", "k1") } // 128bit は不可
        assertFailsWithMessageNotContaining(spec) { KeyEncryptionKeys.parse(spec, "k9") } // active が無い
    }

    @Test
    fun `発行： トークンは毎回違い、toString にトークンを出さない`() {
        val other = issuer.issue(tenant, agent)
        assertTrue(issued.token.startsWith("jrat1_"))
        assertTrue(issued.token != other.token)
        assertFalse(issued.toString().contains(issued.token))
        assertFalse(credential().toString().contains("jrat1_"))
        assertTrue(Regex("[0-9a-f]{64}").matches(issued.tokenHash))
    }

    private fun assertFailsWithMessageNotContaining(
        secret: String,
        block: () -> Unit,
    ) {
        val e = runCatching(block).exceptionOrNull()
        assertNotNull(e, "例外になるはず")
        assertFalse(e.message.orEmpty().contains(secret.substringAfter(':')), "例外メッセージに鍵の値を含めない")
    }
}
