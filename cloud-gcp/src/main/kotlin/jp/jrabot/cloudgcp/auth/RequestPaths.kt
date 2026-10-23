package jp.jrabot.cloudgcp.auth

/**
 * リクエストのパスを、サーブレットコンテナや Spring MVC が**ハンドラを選ぶときと同じ形**に近づけて正規化する（Spring 非依存の純関数）。
 * フィルタのパス判定とハンドラの対応づけがずれると、認証・モードのガードを素通りできる（例: `/internal;x=1/health`、`//internal/health`、
 * `/internal/./health`、`/%69nternal/health`、`/INTERNAL/health`）。
 *
 * 正規化: クエリ除去 → セグメントごとに `;` 以降（パスパラメータ）を除去 → パーセントデコード（不正なら null）→ 空・`.` を除去、`..` は親へ → 小文字化。
 * 結果は常に `/` で始まる。**判定に使うだけ**で、リクエストを書き換えない。大文字小文字を区別しない比較にしているのは、保護の対象を
 * 広めにとる（誤って広く守る側に倒す）ため。
 */
object RequestPaths {
    /** 正規化したパス。デコードできない（不正な `%`）ときは null。 */
    fun normalize(rawUri: String): String? {
        val path = rawUri.substringBefore('?').substringBefore('#')
        val segments = ArrayDeque<String>()
        for (rawSegment in path.split('/')) {
            val withoutParams = rawSegment.substringBefore(';')
            val decoded = percentDecode(withoutParams) ?: return null
            // デコード後にもう一度 `;` や `/` が現れる場合（%3b, %2f）は、別のセグメント/パラメータとして扱われ得るので同様に処理する
            for (piece in decoded.split('/')) {
                val seg = piece.substringBefore(';')
                when (seg) {
                    "", "." -> Unit
                    ".." -> segments.removeLastOrNull()
                    else -> segments.addLast(seg.lowercase())
                }
            }
        }
        return "/" + segments.joinToString("/")
    }

    /** [prefix]（例: `/internal`）そのもの、またはその配下か。正規化できないパスは**配下とみなす**（守る側に倒す）。 */
    fun isUnder(
        rawUri: String,
        prefix: String,
    ): Boolean {
        val p = normalize(rawUri) ?: return true
        val base = prefix.lowercase().trimEnd('/')
        return p == base || p.startsWith("$base/")
    }

    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
