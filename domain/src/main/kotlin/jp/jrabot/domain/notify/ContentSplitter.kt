package jp.jrabot.domain.notify

/** 文字数の数え方。Discord が 2000 を「何の単位」で数えるかは公式資料で明記が見つからないため、既定は余裕のある [UTF16_UNITS]（UNRESOLVED G-20）。 */
enum class LengthUnit {
    /** Unicode のコードポイント数（Python の `len(str)` と同じ。旧実装との比較用）。 */
    CODE_POINTS,

    /** UTF-16 のコード単位数（Kotlin の `String.length`）。コードポイント数以上になるので、どちらで数えられても上限を超えない。 */
    UTF16_UNITS,
}

/**
 * Discord の本文上限（2000 文字）を超えないための分割（旧 `_split_content`）。
 *
 * - 本文が [limit] 以下ならそのまま 1 件。空文字も 1 件（`[""]`）。
 * - 行（Python の `str.splitlines(keepends=True)` と同じ区切り）単位で詰める。区切り文字は失わない。
 * - 1 行が [limit] を超えるときだけ、その行を [limit] ごとに強制分割する（サロゲートペアは分割しない）。強制分割した最後の断片は次の行と結合しない（旧実装のとおり）。
 * - すべての断片を連結すると元の文字列になる（欠落・重複なし）。ZWJ で連結した絵文字など**書記素クラスタ**は分割し得る（コードポイント単位）。
 */
object ContentSplitter {
    /** Discord の本文の上限。 */
    const val MESSAGE_LIMIT = 2000

    /** 安全な分割幅（旧 `_DISCORD_SAFE_CHUNK_LIMIT`。余白込み）。 */
    const val SAFE_CHUNK_LIMIT = 1900

    fun split(
        content: String,
        limit: Int = SAFE_CHUNK_LIMIT,
        unit: LengthUnit = LengthUnit.UTF16_UNITS,
    ): List<String> {
        require(limit >= 2) { "limit は 2 以上（サロゲートペアが入る幅）: $limit" }
        if (length(content, unit) <= limit) return listOf(content)

        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        var bufLen = 0
        for (line in splitLinesKeepEnds(content)) {
            val lineLen = length(line, unit)
            if (lineLen > limit) {
                if (buf.isNotEmpty()) {
                    chunks += buf.toString()
                    buf.setLength(0)
                    bufLen = 0
                }
                chunks += hardSplit(line, limit, unit)
                continue
            }
            if (bufLen + lineLen > limit) {
                if (buf.isNotEmpty()) chunks += buf.toString()
                buf.setLength(0)
                buf.append(line)
                bufLen = lineLen
            } else {
                buf.append(line)
                bufLen += lineLen
            }
        }
        if (buf.isNotEmpty()) chunks += buf.toString()
        return chunks
    }

    fun length(
        s: String,
        unit: LengthUnit,
    ): Int = if (unit == LengthUnit.CODE_POINTS) s.codePointCount(0, s.length) else s.length

    private fun hardSplit(
        line: String,
        limit: Int,
        unit: LengthUnit,
    ): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var n = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val charCount = Character.charCount(cp)
            val w = if (unit == LengthUnit.CODE_POINTS) 1 else charCount
            if (n + w > limit) {
                out += sb.toString()
                sb.setLength(0)
                n = 0
            }
            sb.appendCodePoint(cp)
            n += w
            i += charCount
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Python `str.splitlines(keepends=True)` と同じ行区切り: `\n` `\r` `\r\n` `\v` `\f` `\x1c` `\x1d` `\x1e` `\x85` ` ` ` `。 */
    internal fun splitLinesKeepEnds(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (isLineBreak(c)) {
                val end = if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i + 2 else i + 1
                out += s.substring(start, end)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    private fun isLineBreak(c: Char): Boolean =
        c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u001C' || c == '\u001D' || c == '\u001E' || c == '\u0085' || c == ' ' || c == ' '
}
