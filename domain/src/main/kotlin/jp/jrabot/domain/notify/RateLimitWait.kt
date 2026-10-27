package jp.jrabot.domain.notify

import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson

/**
 * Discord の 429 応答から待機秒数を取り出す（旧 `_parse_retry_after_seconds`）。
 *
 * 優先順位は旧実装のとおり: ① JSON 本文の `retry_after`（秒。小数） ② `Retry-After` ヘッダ ③ `X-RateLimit-Reset-After` ヘッダ ④ 既定 1.0 秒。
 * 0 以下・数値でないものは次の候補に進む。**上限・下限の丸めはここではしない**（呼び出し側の方針: [DiscordRetryPolicy]）。
 * `X-RateLimit-Scope`（user / global / shared）は待機秒数の決定には使わず、ログ用の分類に使う（[scope]）。
 */
object RateLimitWait {
    const val DEFAULT_SECONDS = 1.0

    fun parseSeconds(
        body: String?,
        retryAfterHeader: String?,
        resetAfterHeader: String?,
    ): Double {
        fromBody(body)?.let { return it }
        for (h in listOf(retryAfterHeader, resetAfterHeader)) {
            if (h.isNullOrEmpty()) continue
            val v = pythonFloatOrNull(h) ?: continue
            if (v > 0) return v
        }
        return DEFAULT_SECONDS
    }

    /** `X-RateLimit-Scope` の値（小文字化）。`user` / `global` / `shared` 以外は `other`、無ければ null。値をそのままログに出さない。 */
    fun scope(header: String?): String? {
        val v = header?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return if (v == "user" || v == "global" || v == "shared") v else "other"
    }

    private fun fromBody(body: String?): Double? {
        if (body.isNullOrBlank()) return null
        val obj =
            try {
                StrictJson.parse(body) as? JsonObject
            } catch (e: RuntimeException) {
                null
            } ?: return null
        val v =
            when (val raw = obj["retry_after"] ?: return null) {
                is JsonInt -> raw.value.toDouble()
                is JsonFloat -> raw.value
                is JsonBool -> if (raw.value) 1.0 else 0.0
                is JsonNull -> 0.0
                is JsonString -> pythonFloatOrNull(raw.value) ?: return null
                else -> return null
            }
        return if (v > 0) v else null
    }

    private val NUMBER = Regex("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

    /** Python の `float(str)` のうち実用上の範囲（前後の空白・10 進・指数・inf/nan）。16 進・`1.5f` などは受け付けない。 */
    internal fun pythonFloatOrNull(raw: String): Double? {
        val t = raw.trim()
        return when {
            NUMBER.matches(t) -> t.toDoubleOrNull()
            t.equals("inf", true) || t.equals("+inf", true) || t.equals("infinity", true) || t.equals("+infinity", true) -> Double.POSITIVE_INFINITY
            t.equals("-inf", true) || t.equals("-infinity", true) -> Double.NEGATIVE_INFINITY
            t.equals("nan", true) || t.equals("+nan", true) || t.equals("-nan", true) -> Double.NaN
            else -> null
        }
    }
}
