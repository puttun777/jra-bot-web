package jp.jrabot.domain.ml.features

import java.security.MessageDigest

/**
 * 血統ハッシュ特徴（G2-HASH-001。旧 `ml/features.py` の `_hash_bucket` / `_hash_sign`）。
 *
 * - bucket = `int.from_bytes(md5(name)[:4], "little") % max(1, dim)`（**符号なし** 32bit のリトルエンディアン）
 * - sign   = `+1` if `md5("sign:" + name)[0] % 2 == 0` else `-1`
 * - 名前は**正規化しない**（NFC/NFKC を適用しない。全角と半角、結合文字は別物として扱う）。重複した名前は重複したまま足す（旧仕様）。
 * - 符号化は UTF-8 の `errors="ignore"` 相当（孤立サロゲートは捨てる）。
 */
object PedigreeHash {
    const val DEFAULT_DIM = 16
    const val MAX_NAMES = 8

    fun bucket(
        name: String,
        dim: Int,
    ): Int {
        val h = md5(PythonText.utf8Ignore(name))
        val v = (h[0].toLong() and 0xFF) or ((h[1].toLong() and 0xFF) shl 8) or ((h[2].toLong() and 0xFF) shl 16) or ((h[3].toLong() and 0xFF) shl 24)
        return (v % maxOf(1, dim).toLong()).toInt()
    }

    /** `+1.0` または `-1.0`。 */
    fun sign(name: String): Double {
        val h = md5(PythonText.utf8Ignore("sign:$name"))
        return if ((h[0].toInt() and 0xFF) % 2 == 0) 1.0 else -1.0
    }

    /** 祖先名（先頭から最大 [MAX_NAMES] 件）を [dim] 次元に集計する（`vec[bucket] += sign`）。 */
    fun accumulate(
        names: List<String>,
        dim: Int = DEFAULT_DIM,
    ): DoubleArray {
        require(dim >= 1) { "pedigree_dim は 1 以上: $dim" }
        val vec = DoubleArray(dim)
        for (nm in names.take(MAX_NAMES)) {
            vec[bucket(nm, dim)] += sign(nm)
        }
        return vec
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)
}
