package jp.jrabot.domain.ml.model

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * モデル署名の検証鍵（Ed25519 の公開鍵）。**公開鍵だけ**を持つ（`MODEL_VERIFY_PUBKEY`。秘密鍵は ml-train の Secret Manager にあり、bot は持たない: CONTRACTS §2）。
 * 書式: 生の 32 バイトを標準 Base64（パディングあり）で書いた文字列。ローテーション中は複数の鍵を並べ、いずれかで検証できれば有効。
 *
 * 署名の契約（ML_DESIGN §5.2・CONTRACTS §5.1）: `.sig` は **Ed25519 の署名 64 バイト（生）**で、対象は**モデル JSON ファイルのバイト列そのもの**
 * （正準 JSON: [jp.jrabot.domain.ml.json.CanonicalJson] と同一のバイト列でなければ拒否する）。RFC 8032 の Ed25519（pure。コンテキストなし・事前ハッシュなし）。
 */
class ModelTrust(
    keys: List<PublicKey>,
) {
    private val keys: List<PublicKey> = keys.toList()

    init {
        require(this.keys.isNotEmpty()) { "検証鍵が 1 つも無い（鍵が無いときは全モデルを拒否する）" }
    }

    /** いずれかの鍵で署名が正しければ true。長さが 64 でない署名・例外は false（fail closed）。 */
    fun verify(
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        var ok = false
        for (key in keys) {
            val r =
                try {
                    Signature.getInstance("Ed25519").run {
                        initVerify(key)
                        update(message)
                        verify(signature)
                    }
                } catch (e: java.security.GeneralSecurityException) {
                    false
                }
            ok = ok or r // すべての鍵を試す（どの鍵で通ったかで処理時間を変えない）
        }
        return ok
    }

    companion object {
        const val SIGNATURE_BYTES = 64
        const val PUBLIC_KEY_BYTES = 32

        // Ed25519 の SubjectPublicKeyInfo (X.509) の固定の接頭辞（OID 1.3.101.112）
        private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun publicKeyFromRaw(raw: ByteArray): PublicKey {
            require(raw.size == PUBLIC_KEY_BYTES) { "Ed25519 の公開鍵は $PUBLIC_KEY_BYTES バイト" }
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
        }

        /** `MODEL_VERIFY_PUBKEY` の書式（Base64 の生 32 バイト。カンマ区切りで複数）。 */
        fun fromBase64(text: String): ModelTrust =
            ModelTrust(
                text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                    publicKeyFromRaw(Base64.getDecoder().decode(it))
                },
            )

        fun of(vararg raw: ByteArray): ModelTrust = ModelTrust(raw.map(::publicKeyFromRaw))
    }
}
