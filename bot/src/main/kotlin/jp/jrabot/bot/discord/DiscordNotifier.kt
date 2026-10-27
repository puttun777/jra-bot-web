package jp.jrabot.bot.discord

import jp.jrabot.domain.notify.ContentSplitter
import jp.jrabot.domain.notify.DeliveryResult
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.Notification
import jp.jrabot.domain.notify.NotificationChannel
import jp.jrabot.domain.notify.Notifier
import jp.jrabot.domain.notify.RateLimitWait
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/** 配送の方針。旧実装（5 回・指数バックオフ・5xx も再試行）との違いは `docs/CONTRACTS.md` §9.2・D-185。 */
data class DiscordRetryPolicy(
    /** 1 チャンクあたりの最大試行回数（429 と接続前の失敗だけが再試行の対象）。 */
    val maxAttempts: Int = 5,
    /** 接続前の失敗の待機の基準（試行ごとに 2 倍、上限 [maxBackoff]）。 */
    val baseBackoff: Duration = Duration.ofSeconds(1),
    val maxBackoff: Duration = Duration.ofSeconds(30),
    /** 429 の待機の下限・上限（旧実装と同じ 0.5〜60 秒）。上限を超える待機を求められたら、短く待って早すぎる再試行をせず、あきらめる。 */
    val minRetryAfter: Duration = Duration.ofMillis(500),
    val maxRetryAfter: Duration = Duration.ofSeconds(60),
    /** 本文を分割する幅（旧実装の安全余白）。 */
    val chunkLimit: Int = ContentSplitter.SAFE_CHUNK_LIMIT,
) {
    init {
        require(maxAttempts in 1..10) { "maxAttempts は 1〜10" }
        require(!baseBackoff.isNegative && !maxBackoff.isNegative && minRetryAfter <= maxRetryAfter) { "待機時間の設定が不正" }
        require(chunkLimit in 100..ContentSplitter.MESSAGE_LIMIT) { "chunkLimit は 100〜${ContentSplitter.MESSAGE_LIMIT}" }
    }
}

/**
 * Discord の Webhook 送信（[Notifier] の実装）。
 *
 * **配送の安全規則（CONTRACTS §9.2）**: 本文は [ContentSplitter] でチャンクに分け、順に 1 通ずつ POST する。
 * - 2xx → そのチャンクは成功。全チャンクが成功したときだけ [DeliveryResult.DELIVERED]。
 * - 429 → 『確実に処理されていない』ので、待機（`retry_after` → `Retry-After` → `X-RateLimit-Reset-After`、既定 1 秒。0.5〜60 秒）して再試行（最大 [DiscordRetryPolicy.maxAttempts]）。
 *   60 秒を超える待機を求められたら再試行せず終了する。
 * - 接続前の失敗（接続拒否・名前解決・接続タイムアウト・TLS）→ 受け取られていないので指数バックオフで再試行（同じ上限）。
 * - 400/401/403/404 などの 4xx → 確実に拒否。再試行しない（設定の誤り・Webhook の削除）。
 * - **5xx・要求のタイムアウト・切断・想定外のステータス → [DeliveryResult.UNKNOWN]。再送しない**（Discord が受け取った可能性があり、再送すると二重通知になる）。
 * - **2 チャンク目以降の失敗は、理由に関わらず UNKNOWN**（一部は届いている。全体を再送しない）。1 通目が確実に届いていないときだけ [DeliveryResult.NOT_SENT]。
 * 停止要求（[stopRequested]）と中断（[InterruptedException]）では新しい送信を始めない。この失敗を同じ Webhook へ通知し直すことはしない（再帰しない）。
 * ログに URL・本文・ヘッダを出さない。
 */
class DiscordNotifier(
    private val signalUrl: DiscordWebhookUrl?,
    private val errorUrl: DiscordWebhookUrl?,
    private val mention: MentionPolicy,
    private val transport: WebhookTransport,
    private val sleeper: Sleeper = Sleeper.SYSTEM,
    private val policy: DiscordRetryPolicy = DiscordRetryPolicy(),
    private val stopRequested: () -> Boolean = { false },
    private val log: Logger = LoggerFactory.getLogger(DiscordNotifier::class.java),
) : Notifier {
    override fun canDeliver(channel: NotificationChannel): Boolean = urlFor(channel) != null

    override fun send(notification: Notification): DeliveryResult {
        val url = urlFor(notification.channel) ?: return DeliveryResult.NOT_SENT
        val chunks = ContentSplitter.split(notification.body, policy.chunkLimit)
        for ((index, chunk) in chunks.withIndex()) {
            when (val r = postChunk(url, notification.channel, chunk, index + 1, chunks.size)) {
                ChunkResult.DELIVERED -> Unit
                ChunkResult.NOT_SENT -> return if (index == 0) DeliveryResult.NOT_SENT else DeliveryResult.UNKNOWN
                ChunkResult.UNKNOWN -> return DeliveryResult.UNKNOWN
            }
        }
        return DeliveryResult.DELIVERED
    }

    private fun urlFor(channel: NotificationChannel): DiscordWebhookUrl? = if (channel == NotificationChannel.SIGNAL) signalUrl else errorUrl

    private enum class ChunkResult { DELIVERED, NOT_SENT, UNKNOWN }

    private fun postChunk(
        url: DiscordWebhookUrl,
        channel: NotificationChannel,
        chunk: String,
        index: Int,
        total: Int,
    ): ChunkResult {
        val body = payload(channel, chunk)
        for (attempt in 1..policy.maxAttempts) {
            if (stopRequested() || Thread.currentThread().isInterrupted) {
                log.warn("Discord 送信を停止要求で中止 chunk={}/{} attempt={}", index, total, attempt)
                return ChunkResult.NOT_SENT
            }
            when (val outcome = transport.post(url.uri, body)) {
                is PostOutcome.NotSent -> {
                    log.warn("Discord 接続前の失敗 cause={} chunk={}/{} attempt={}/{}", outcome.cause, index, total, attempt, policy.maxAttempts)
                    if (attempt == policy.maxAttempts || !wait(backoff(attempt))) return ChunkResult.NOT_SENT
                }

                is PostOutcome.Unknown -> {
                    log.error("Discord 送信結果が不明 cause={} chunk={}/{}", outcome.cause, index, total)
                    return ChunkResult.UNKNOWN
                }

                is PostOutcome.Response -> {
                    val status = outcome.status
                    when {
                        status in 200..299 -> return ChunkResult.DELIVERED

                        status == 429 -> {
                            val seconds = RateLimitWait.parseSeconds(outcome.body, outcome.retryAfterHeader, outcome.resetAfterHeader)
                            val scope = RateLimitWait.scope(outcome.scopeHeader)
                            log.warn("Discord 429 retry_after={}s scope={} chunk={}/{} attempt={}/{}", seconds, scope, index, total, attempt, policy.maxAttempts)
                            if (!seconds.isFinite() || seconds > policy.maxRetryAfter.toMillis() / 1000.0) return ChunkResult.NOT_SENT
                            if (attempt == policy.maxAttempts || !wait(retryAfter(seconds))) return ChunkResult.NOT_SENT
                        }

                        status in 400..499 && status != 408 -> {
                            log.error("Discord が拒否 status={} chunk={}/{}", status, index, total)
                            return ChunkResult.NOT_SENT
                        }

                        else -> {
                            log.error("Discord の応答が想定外または 5xx、結果不明 status={} chunk={}/{}", status, index, total)
                            return ChunkResult.UNKNOWN
                        }
                    }
                }
            }
        }
        return ChunkResult.NOT_SENT
    }

    /** 待機する。中断されたら false（以降の送信を始めない）。 */
    private fun wait(duration: Duration): Boolean =
        try {
            sleeper.sleep(duration)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun backoff(attempt: Int): Duration {
        val factor = 1L shl (attempt - 1).coerceAtMost(20)
        val millis = policy.baseBackoff.toMillis().coerceAtMost(Long.MAX_VALUE / factor) * factor
        return Duration.ofMillis(millis.coerceAtMost(policy.maxBackoff.toMillis()))
    }

    private fun retryAfter(seconds: Double): Duration {
        val millis = Math.ceil(seconds * 1000.0).toLong()
        return Duration.ofMillis(millis.coerceIn(policy.minRetryAfter.toMillis(), policy.maxRetryAfter.toMillis()))
    }

    private fun payload(
        channel: NotificationChannel,
        chunk: String,
    ): String {
        val content = if (chunk.length > ContentSplitter.MESSAGE_LIMIT) chunk.substring(0, ContentSplitter.MESSAGE_LIMIT) else chunk
        val username = if (channel == NotificationChannel.SIGNAL) USERNAME else ERROR_USERNAME
        // 通知本文の中の @ は [DiscordText] で無害化済み。さらに allowed_mentions で、本人以外は鳴らさない
        val allowed = if (channel == NotificationChannel.SIGNAL) mention.allowedMentionsJson() else MentionPolicy.NONE.allowedMentionsJson()
        return """{"content":${JsonText.quote(content)},"username":${JsonText.quote(username)},"allowed_mentions":$allowed}"""
    }

    companion object {
        const val USERNAME = "JRA EV Bot"
        const val ERROR_USERNAME = "JRA EV Bot (error)"
    }
}

/** 送信 JSON の文字列エスケープ（Jackson を使わない最小実装）。制御文字・引用符・バックスラッシュ・孤立サロゲートを安全にする。 */
internal object JsonText {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> sb.append("\\ufffd")
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }
}
