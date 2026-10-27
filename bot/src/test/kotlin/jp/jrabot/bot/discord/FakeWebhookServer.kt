package jp.jrabot.bot.discord

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 受信した 1 リクエスト。 */
class Recorded(
    val method: String,
    val path: String,
    val contentType: String?,
    val userAgent: String?,
    val body: String,
) {
    fun json(): JsonObject = StrictJson.parse(body) as JsonObject

    fun content(): String = (json()["content"] as JsonString).value
}

/** 台本の 1 回分の応答。 */
sealed interface Reply {
    data class Status(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) : Reply

    /** 応答を返さず [millis] 待つ（要求タイムアウトの再現）。 */
    data class Stall(
        val millis: Long,
    ) : Reply

    /** 本文を読んでから、応答を返さず接続を閉じる。 */
    data object Drop : Reply
}

/**
 * ローカルだけで動く Fake の Discord Webhook サーバ（127.0.0.1・空きポート）。実 Discord へは一切つながらない。
 * 台本（[script]）を先頭から 1 つずつ消費し、尽きたら 204 を返す。
 */
class FakeWebhookServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<Recorded>()
    private val script = java.util.concurrent.ConcurrentLinkedQueue<Reply>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val port: Int get() = server.address.port

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun script(vararg replies: Reply) {
        script.addAll(replies)
    }

    fun url(token: String = "fake-token_123"): DiscordWebhookUrl =
        DiscordWebhookUrl.forLocalTest("http://127.0.0.1:$port/api/webhooks/123456789012345678/$token")

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
        requests +=
            Recorded(ex.requestMethod, ex.requestURI.rawPath, ex.requestHeaders.getFirst("Content-Type"), ex.requestHeaders.getFirst("User-Agent"), body)
        when (val reply = script.poll() ?: Reply.Status(204)) {
            is Reply.Status -> {
                reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val bytes = reply.body.toByteArray()
                if (reply.status == 204 || bytes.isEmpty()) {
                    ex.sendResponseHeaders(reply.status, -1)
                } else {
                    ex.sendResponseHeaders(reply.status, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                ex.close()
            }

            is Reply.Stall -> {
                try {
                    Thread.sleep(reply.millis)
                    ex.sendResponseHeaders(204, -1)
                } catch (e: Exception) {
                    // クライアントがすでに切断している
                }
                ex.close()
            }

            Reply.Drop -> {
                ex.close()
            } // ヘッダを送らずに閉じる
        }
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

/** 接続を受けた直後（`readRequest` なら要求を読んだ後）に何も返さず閉じる、生のソケットサーバ（接続切断の再現）。 */
class DroppingServer(
    private val readRequest: Boolean,
) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val accepted =
        java.util.concurrent.atomic
            .AtomicInteger()
    private val thread =
        Thread {
            try {
                while (true) {
                    val s = socket.accept()
                    accepted.incrementAndGet()
                    if (readRequest) {
                        val input = s.getInputStream()
                        val buf = ByteArray(4096)
                        s.soTimeout = 500
                        try {
                            input.read(buf)
                        } catch (e: java.net.SocketTimeoutException) {
                            // 読めた分だけ
                        }
                    }
                    s.close()
                }
            } catch (e: java.io.IOException) {
                // close() で終了
            }
        }.apply {
            isDaemon = true
            start()
        }

    fun url(): DiscordWebhookUrl = DiscordWebhookUrl.forLocalTest("http://127.0.0.1:$port/api/webhooks/123456789012345678/drop-token")

    override fun close() {
        socket.close()
        thread.join(2000)
    }
}
