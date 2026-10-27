package jp.jrabot.bot.discord

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException

/** 1 回の POST の結果。**『相手が受け取ったかもしれないか』で分ける**（再送してよいかの根拠）。 */
sealed interface PostOutcome {
    /** HTTP 応答を受け取った。 */
    data class Response(
        val status: Int,
        val retryAfterHeader: String?,
        val resetAfterHeader: String?,
        val scopeHeader: String?,
        val body: String,
    ) : PostOutcome

    /** リクエストの本文を送る前に失敗した（接続拒否・名前解決・接続タイムアウト・TLS ハンドシェイク）。相手は受け取っていない。 */
    data class NotSent(
        val cause: String,
    ) : PostOutcome

    /** 送ったかもしれない（要求のタイムアウト・切断・応答の途中終了・中断）。相手が受け取った可能性があり、再送すると二重になり得る。 */
    data class Unknown(
        val cause: String,
    ) : PostOutcome
}

/** 1 回の POST だけを行う口（リトライは [DiscordNotifier]）。テストでは Fake サーバへ実際に送る [JdkWebhookTransport] か、台本どおりに返す実装を使う。 */
fun interface WebhookTransport {
    fun post(
        uri: URI,
        jsonBody: String,
    ): PostOutcome
}

/**
 * JDK 標準の `java.net.http.HttpClient`。追加の依存を持たない。リダイレクトは辿らない（SSRF 対策）。HTTP/1.1。
 * 接続・要求にタイムアウトを必ず付ける（無制限に待たない）。応答本文は [MAX_BODY_BYTES] まで読む。ログに URL・本文を出さない。
 */
class JdkWebhookTransport(
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val requestTimeout: Duration = Duration.ofSeconds(10),
) : WebhookTransport {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    override fun post(
        uri: URI,
        jsonBody: String,
    ): PostOutcome {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, Charsets.UTF_8))
                .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body = response.body().use { it.readNBytes(MAX_BODY_BYTES).toString(Charsets.UTF_8) }
            PostOutcome.Response(
                status = response.statusCode(),
                retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
                resetAfterHeader = response.headers().firstValue("X-RateLimit-Reset-After").orElse(null),
                scopeHeader = response.headers().firstValue("X-RateLimit-Scope").orElse(null),
                body = body,
            )
        } catch (e: HttpConnectTimeoutException) {
            PostOutcome.NotSent("connect-timeout")
        } catch (e: HttpTimeoutException) {
            PostOutcome.Unknown("request-timeout")
        } catch (e: ConnectException) {
            PostOutcome.NotSent("connect-failed")
        } catch (e: SSLHandshakeException) {
            PostOutcome.NotSent("tls-handshake-failed")
        } catch (e: IOException) {
            PostOutcome.Unknown("io-error:${e.javaClass.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            PostOutcome.Unknown("interrupted")
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val USER_AGENT = "JRA-EV-Bot (webhook-notifier, 1)"
    }
}
