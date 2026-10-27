package jp.jrabot.domain.notify

import jp.jrabot.domain.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** ログ・エラー通知に出す文字列から秘密を取り除く。完全ではない（既知の形だけ）ので、そもそも秘密を例外メッセージに入れないことが前提。 */
object SecretRedactor {
    private val RULES: List<Pair<Regex, String>> =
        listOf(
            Regex("https?://(?:[\\w.-]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\S*", RegexOption.IGNORE_CASE) to "[redacted-webhook-url]",
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)") to "[redacted-private-key]",
            Regex("\\b(proxy-)?authorization\\b\\s*[:=]\\s*[^\\r\\n]*", RegexOption.IGNORE_CASE) to "$1authorization: [redacted]",
            Regex("\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE) to "Bearer [redacted]",
            Regex("\\b(password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b(\\s*[=:]\\s*)[^\\s,;]+", RegexOption.IGNORE_CASE) to "$1$2[redacted]",
        )

    fun redact(text: String): String = RULES.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}

/**
 * 運用者向けのエラー通知（`DISCORD_ERROR_WEBHOOK_URL`。旧 `run_daemon._notify_error`）の送り口。**どのイベントから呼ぶかは T3.8 の責務**（ここは基盤だけ）。
 *
 * - **例外を投げない・再帰しない**: 送信に失敗しても、その失敗を再度エラー通知しない。報告の最中に別の報告が来たら捨てる（[Outcome.REENTRANT]）。
 * - 同じ内容は [cooldown] の間は 1 回だけ（旧実装の既定 300 秒）。記憶する件数には上限がある。
 * - 本文から秘密（Webhook URL・Authorization・トークン・秘密鍵）を取り除き、`@` を無害化し、長さを切り詰める（旧実装と同じ 180/900/800 文字、全体 1900）。
 * - 宛先が未設定なら何もしない（[Outcome.NOT_CONFIGURED]）。結果は送信できたかの目安で、呼び出し側はこれを理由に本処理を止めない。
 */
class ErrorReporter(
    private val notifier: Notifier,
    private val clock: Clock,
    private val cooldown: Duration = Duration.ofMinutes(5),
) {
    enum class Outcome { SENT, NOT_CONFIGURED, COOLDOWN, NOT_DELIVERED, REENTRANT }

    private val inFlight = AtomicBoolean(false)
    private val lastSent = object : LinkedHashMap<String, Instant>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>?): Boolean = size > MAX_KEYS
    }

    fun report(
        title: String,
        message: String,
        details: String = "",
    ): Outcome {
        if (!inFlight.compareAndSet(false, true)) return Outcome.REENTRANT
        return try {
            if (!notifier.canDeliver(NotificationChannel.ERROR)) return Outcome.NOT_CONFIGURED
            val t = clean(title, TITLE_MAX)
            val m = clean(message, MESSAGE_MAX)
            val d = clean(details, DETAILS_MAX).replace("```", "` ` `")
            val key = "$t|${m.take(120)}"
            val now = clock.now()
            synchronized(lastSent) {
                val last = lastSent[key]
                if (last != null && Duration.between(last, now) < cooldown) return Outcome.COOLDOWN
                lastSent[key] = now
            }
            val body = "【JRA EV Bot エラー】\n**$t**\n$m\n\n```text\n$d\n```".let { cut(it, BODY_MAX) }
            val result = notifier.send(Notification(null, NotificationChannel.ERROR, "JRA EV Bot エラー: $t".take(200), body))
            if (result == DeliveryResult.DELIVERED) Outcome.SENT else Outcome.NOT_DELIVERED
        } catch (e: Exception) {
            Outcome.NOT_DELIVERED
        } finally {
            inFlight.set(false)
        }
    }

    private fun clean(
        s: String,
        max: Int,
    ): String = cut(DiscordText.neutralizeMentions(SecretRedactor.redact(s)), max, truncateMark = true)

    /** [max] 文字（UTF-16）以内に切る。サロゲートペアの途中では切らない。[truncateMark] なら旧実装と同じ『…(truncated)』を付ける。 */
    private fun cut(
        s: String,
        max: Int,
        truncateMark: Boolean = false,
    ): String {
        if (s.length <= max) return s
        val keep = if (truncateMark) maxOf(0, max - MARK.length) else max
        var end = keep
        if (end in 1 until s.length && Character.isHighSurrogate(s[end - 1]) && Character.isLowSurrogate(s[end])) end--
        return s.substring(0, end) + if (truncateMark) MARK else ""
    }

    private companion object {
        const val TITLE_MAX = 180
        const val MESSAGE_MAX = 900
        const val DETAILS_MAX = 800
        const val BODY_MAX = 1900
        const val MAX_KEYS = 256
        const val MARK = "\n...(truncated)"
    }
}
