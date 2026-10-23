package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.tenant.TenantId
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * エージェントのリクエスト署名（**提案 P-1。未承認。D-130**）。
 *
 * CONTRACTS 0.4.x §3.3 は「`X-Signature = HMAC-SHA256(token, …)`、サーバは SHA-256+ペッパーのハッシュだけを保存」と定めるが、
 * **この 2 つは両立しない**: 一方向ハッシュからは HMAC の鍵（token）を復元できないので、サーバは署名を検証できない
 * （検証できるようにハッシュを鍵に使えば、ハッシュ = 鍵なので DB の漏えいで署名を偽造できる）。
 *
 * 採用した案（実装・テストはこの案。契約への反映は承認待ち）: トークンから**署名鍵を導出**する。
 * `K = HMAC-SHA256(token, "jra-bot/agent-sign/v1")`。エージェントは K で署名し、サーバは K を **サーバ側の鍵（KEK）で AES-256-GCM 暗号化**して
 * 保存する（[SigningKeyCipher]）。DB だけが漏れても署名は偽造できない（KEK は Secret Manager 由来で DB に無い）。トークン自体はサーバに保存しない
 * （発行の瞬間にだけ見る）。ストアの暗号化鍵 KEK の漏えいと DB の漏えいが**両方**起きたときだけ署名を偽造できる。
 */
object AgentSigning {
    const val SCHEME = "hmac-sha256-derived-v1"
    private const val DERIVE_LABEL = "jra-bot/agent-sign/v1"

    fun deriveSigningKey(token: String): ByteArray = hmac(token.toByteArray(Charsets.UTF_8), DERIVE_LABEL.toByteArray(Charsets.UTF_8))

    /** 署名対象。`method|requestUri|timestamp|nonce|sha256(body)`（requestUri はクエリを含む。body は**送信した（圧縮後の）バイト列**）。 */
    fun canonical(
        method: String,
        requestUri: String,
        timestamp: Long,
        nonce: String,
        bodySha256Hex: String,
    ): String = "$method|$requestUri|$timestamp|$nonce|$bodySha256Hex"

    fun sign(
        signingKey: ByteArray,
        canonical: String,
    ): String = hmac(signingKey, canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** 署名鍵を暗号化する鍵（KEK）の集合。[active] で暗号化し、保存済みの鍵は kid で復号する（ローテーション: 新しい kid を足して active を切り替え、旧 kid は残す）。 */
class KeyEncryptionKeys(
    keys: Map<String, ByteArray>,
    val activeKid: String,
) {
    private val keys: Map<String, ByteArray> = keys.mapValues { it.value.copyOf() }

    init {
        require(keys.isNotEmpty()) { "KEK が 1 つも無い" }
        require(activeKid in keys) { "active の kid がキーに無い" }
        keys.forEach { (kid, k) ->
            require(KID.matches(kid)) { "kid の形式が不正: $kid" }
            require(k.size == 32) { "KEK は 256bit: $kid" }
        }
    }

    fun key(kid: String): ByteArray? = keys[kid]?.copyOf()

    override fun toString() = "KeyEncryptionKeys(active=$activeKid, count=${keys.size})" // 鍵の値は出さない

    companion object {
        private val KID = Regex("[A-Za-z0-9_-]{1,16}")

        /** `kid:base64(32バイト)` をカンマ区切りで並べた設定値から作る（例: `k1:AAAA…=`）。不正なら例外（値は例外に含めない）。 */
        fun parse(
            spec: String,
            activeKid: String,
        ): KeyEncryptionKeys {
            val map =
                spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
                    val kid = entry.substringBefore(':', "")
                    val b64 = entry.substringAfter(':', "")
                    val raw =
                        try {
                            Base64.getDecoder().decode(b64)
                        } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException("KEK の Base64 が不正（kid=$kid）")
                        }
                    kid to raw
                }
            return KeyEncryptionKeys(map, activeKid)
        }
    }
}

/**
 * 署名鍵の AES-256-GCM 暗号化。**認証付き追加データ（AAD）にテナント・エージェント・kid を含める**ので、暗号文を別のエージェントの行へ
 * 移し替えても復号できない（行の入れ替えによる成り済ましを防ぐ）。保存形式は `nonce(12) || 暗号文 || タグ(16)`。
 */
class SigningKeyCipher(
    private val keks: KeyEncryptionKeys,
    private val random: SecureRandom = SecureRandom(),
) {
    fun encrypt(
        tenantId: TenantId,
        agentId: AgentId,
        signingKey: ByteArray,
    ): EncryptedSigningKey {
        val kid = keks.activeKid
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(requireNotNull(keks.key(kid)), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(tenantId, agentId, kid))
        return EncryptedSigningKey(kid, nonce + cipher.doFinal(signingKey))
    }

    /** 復号できなければ null（鍵不明・改ざん・別行の暗号文）。理由は区別して返さない。 */
    fun decrypt(
        tenantId: TenantId,
        agentId: AgentId,
        stored: EncryptedSigningKey,
    ): ByteArray? {
        val key = keks.key(stored.kid) ?: return null
        if (stored.blob.size <= NONCE + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, stored.blob.copyOfRange(0, NONCE)))
            cipher.updateAAD(aad(tenantId, agentId, stored.kid))
            cipher.doFinal(stored.blob, NONCE, stored.blob.size - NONCE)
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun aad(
        tenantId: TenantId,
        agentId: AgentId,
        kid: String,
    ) = "jra-bot/agent-key/v1|${tenantId.value}|${agentId.value}|$kid".toByteArray(Charsets.UTF_8)

    private companion object {
        const val NONCE = 12
        const val TAG_BITS = 128
    }
}

class EncryptedSigningKey(
    val kid: String,
    val blob: ByteArray,
)

enum class AgentStatus { ACTIVE, REVOKED }

/** 認証に必要なエージェントの行。署名鍵（暗号化）が無い行は認証できない。 */
class AgentCredential(
    val tenantId: TenantId,
    val agentId: AgentId,
    val status: AgentStatus,
    val tokenExpiresAt: Instant,
    val signingKey: EncryptedSigningKey?,
) {
    override fun toString() = "AgentCredential(tenant=${tenantId.value}, agent=${agentId.value}, status=$status)" // 鍵・期限以外の秘密は出さない
}

interface AgentCredentialStore {
    /** `agent_id` は全体で一意（V3 の UNIQUE）。無ければ null。テナントはこの行から決まる（リクエストの値からは決めない）。 */
    fun findByAgentId(agentId: AgentId): AgentCredential?
}

interface NonceStore {
    /** nonce を消費する。既にあれば false（リプレイ）。成功と同時に永続する（後続の処理が失敗しても消費済み）。 */
    fun consume(
        tenantId: TenantId,
        agentId: AgentId,
        nonce: String,
        expiresAt: Instant,
    ): Boolean

    /** 期限切れの nonce を（最大 [limit] 件）削除する。 */
    fun purgeExpired(
        before: Instant,
        limit: Int,
    ): Int
}

/** 認証に使うリクエストの要素（ヘッダの生の値）。 */
class AgentAuthRequest(
    val method: String,
    val requestUri: String,
    val agentIdHeader: String?,
    val timestampHeader: String?,
    val nonceHeader: String?,
    val signatureHeader: String?,
    val bodySha256Hex: String,
)

enum class AuthFailure(
    val status: Int,
) {
    MALFORMED_HEADERS(401),
    TIMESTAMP_OUT_OF_RANGE(401),
    UNKNOWN_OR_UNUSABLE_AGENT(401),
    BAD_SIGNATURE(401),
    TOKEN_EXPIRED(401),
    REPLAYED_NONCE(401),

    /** 署名は正しいが失効している（署名が正しい相手にだけ区別して返す）。 */
    REVOKED(403),
}

sealed interface AuthResult {
    data class Authenticated(
        val tenantId: TenantId,
        val agentId: AgentId,
    ) : AuthResult

    /** [failure] はログ・メトリクス用。HTTP 応答には理由を出さない（状態コードのみ）。 */
    data class Denied(
        val failure: AuthFailure,
    ) : AuthResult
}

/**
 * エージェントの認証。順序は **ヘッダ形式 → 時刻 → エージェント解決 → 署名（定数時間比較）→ 失効・期限 → nonce 消費**。
 * 署名が正しいと確認できるまで DB を更新しない（未認証の呼び出しで nonce 表を増やせない）。存在しないエージェントでもダミーの署名計算をして、
 * 存在の有無を応答時間から推測されにくくする。
 */
class AgentAuthenticator(
    private val credentials: AgentCredentialStore,
    private val nonces: NonceStore,
    private val cipher: SigningKeyCipher,
    private val skew: Duration = Duration.ofSeconds(300),
    private val nonceRetention: Duration = Duration.ofMinutes(10),
) {
    fun authenticate(
        req: AgentAuthRequest,
        now: Instant,
    ): AuthResult {
        val agentId = req.agentIdHeader?.let { AgentId.ofOrNull(it) } ?: return denied(AuthFailure.MALFORMED_HEADERS)
        val timestamp = req.timestampHeader?.takeIf { TIMESTAMP.matches(it) }?.toLongOrNull() ?: return denied(AuthFailure.MALFORMED_HEADERS)
        val nonce = req.nonceHeader?.takeIf { NONCE.matches(it) } ?: return denied(AuthFailure.MALFORMED_HEADERS)
        val signature = req.signatureHeader?.takeIf { SIGNATURE.matches(it) } ?: return denied(AuthFailure.MALFORMED_HEADERS)
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs() > skew) return denied(AuthFailure.TIMESTAMP_OUT_OF_RANGE)

        val canonical = AgentSigning.canonical(req.method, req.requestUri, timestamp, nonce, req.bodySha256Hex)
        val credential = credentials.findByAgentId(agentId)
        val key = credential?.signingKey?.let { cipher.decrypt(credential.tenantId, credential.agentId, it) }
        if (credential == null || key == null) {
            AgentSigning.sign(DUMMY_KEY, canonical) // 時間を揃える
            return denied(AuthFailure.UNKNOWN_OR_UNUSABLE_AGENT)
        }
        if (!AgentSigning.constantTimeEquals(AgentSigning.sign(key, canonical), signature)) return denied(AuthFailure.BAD_SIGNATURE)
        if (credential.status == AgentStatus.REVOKED) return denied(AuthFailure.REVOKED)
        if (!now.isBefore(credential.tokenExpiresAt)) return denied(AuthFailure.TOKEN_EXPIRED)
        if (!nonces.consume(credential.tenantId, credential.agentId, nonce, now.plus(nonceRetention))) return denied(AuthFailure.REPLAYED_NONCE)
        return AuthResult.Authenticated(credential.tenantId, credential.agentId)
    }

    private fun denied(f: AuthFailure) = AuthResult.Denied(f)

    private companion object {
        val TIMESTAMP = Regex("[0-9]{1,12}")
        val NONCE = Regex("[A-Za-z0-9_-]{16,64}")
        val SIGNATURE = Regex("[0-9a-f]{64}")
        val DUMMY_KEY = ByteArray(32) { 7 }
    }
}

/** 発行直後のエージェント資格情報。[token] はこの 1 回だけ利用者に渡し、サーバは保存しない。 */
class IssuedAgentCredential(
    val agentId: AgentId,
    val token: String,
    val signingKey: EncryptedSigningKey,
    /** 識別用のハッシュ（SHA-256 + ペッパー）。**署名の検証には使えない・使わない**（V2 の列を満たすための値）。 */
    val tokenHash: String,
) {
    override fun toString() = "IssuedAgentCredential(agent=${agentId.value})" // token を出さない
}

class AgentCredentialIssuer(
    private val cipher: SigningKeyCipher,
    private val pepper: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(pepper.size >= 16) { "ペッパーは 16 バイト以上" }
    }

    fun issue(
        tenantId: TenantId,
        agentId: AgentId,
    ): IssuedAgentCredential {
        val raw = ByteArray(32).also(random::nextBytes)
        val token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val key = AgentSigning.deriveSigningKey(token)
        val hash = Hashing.sha256Hex(pepper + token.toByteArray(Charsets.UTF_8))
        return IssuedAgentCredential(agentId, token, cipher.encrypt(tenantId, agentId, key), hash)
    }

    companion object {
        const val TOKEN_PREFIX = "jrat1_"
    }
}
