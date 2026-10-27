package jp.jrabot.bot.discord

import jp.jrabot.bot.RecordingLogger
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.notify.ContentSplitter
import jp.jrabot.domain.notify.DeliveryResult
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.Notification
import jp.jrabot.domain.notify.NotificationChannel
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fake HTTP サーバ（127.0.0.1）との実通信で、配送の安全規則を検証する。実 Discord へは接続しない。待機は記録するだけで眠らない。 */
class DiscordNotifierFakeServerTest {
    private val server = FakeWebhookServer()
    private val sleeps = CopyOnWriteArrayList<Duration>()
    private val log = RecordingLogger()
    private val tenant = TenantId.of(1)

    @AfterEach
    fun tearDown() = server.close()

    private fun notifier(
        mention: MentionPolicy = MentionPolicy.NONE,
        transport: WebhookTransport = JdkWebhookTransport(Duration.ofSeconds(2), Duration.ofSeconds(2)),
        url: DiscordWebhookUrl? = server.url(),
        policy: DiscordRetryPolicy = DiscordRetryPolicy(),
        stop: () -> Boolean = { false },
        sleeper: Sleeper = Sleeper { sleeps += it },
    ) = DiscordNotifier(url, url, mention, transport, sleeper, policy, stop, log)

    private fun signal(body: String = "本文") = Notification(tenant, NotificationChannel.SIGNAL, "件名", body)

    private fun send(
        body: String = "本文",
        n: DiscordNotifier = notifier(),
    ) = n.send(signal(body))

    @Test
    fun `2xx は DELIVERED で JSON の形と許可メンションが正しい`() {
        val r = send("📢 馬番3 \"引用\" \\ 改行\nタブ\t", notifier(MentionPolicy.forUser("123456789012345678")))
        assertEquals(DeliveryResult.DELIVERED, r)
        val req = server.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/api/webhooks/123456789012345678/fake-token_123", req.path)
        assertTrue(req.contentType.orEmpty().startsWith("application/json"))
        assertTrue(req.userAgent.orEmpty().startsWith("JRA-EV-Bot"))
        val json = req.json()
        assertEquals("📢 馬番3 \"引用\" \\ 改行\nタブ\t", req.content())
        assertEquals("JRA EV Bot", (json["username"] as JsonString).value)
        val allowed = json["allowed_mentions"] as JsonObject
        assertEquals(JsonArray(), allowed["parse"])
        assertEquals(JsonArray(JsonString("123456789012345678")), allowed["users"])
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `メンションなしなら allowed_mentions の parse は空で users は無い`() {
        send()
        val allowed = server.requests.single().json()["allowed_mentions"] as JsonObject
        assertEquals(JsonArray(), allowed["parse"])
        assertEquals(null, allowed["users"])
    }

    @Test
    fun `エラー用チャンネルは本人メンションも付けない`() {
        val n = notifier(MentionPolicy.forUser("123456789012345678"))
        assertEquals(DeliveryResult.DELIVERED, n.send(Notification(null, NotificationChannel.ERROR, "e", "本文")))
        val json = server.requests.single().json()
        assertEquals("JRA EV Bot (error)", (json["username"] as JsonString).value)
        assertEquals(null, (json["allowed_mentions"] as JsonObject)["users"])
    }

    @Test
    fun `4xx は確実に拒否 再試行せず NOT_SENT`() {
        for (status in listOf(400, 401, 403, 404)) {
            server.requests.clear()
            server.script(Reply.Status(status, """{"message":"x","code":0}"""))
            assertEquals(DeliveryResult.NOT_SENT, send(), "status=$status")
            assertEquals(1, server.requests.size, "再試行しない: $status")
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `5xx は結果不明 再送しない`() {
        for (status in listOf(500, 502, 503, 504)) {
            server.requests.clear()
            server.script(Reply.Status(status, "upstream error"))
            assertEquals(DeliveryResult.UNKNOWN, send(), "status=$status")
            assertEquals(1, server.requests.size, "二重通知を避けるため再送しない: $status")
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `想定外のステータス 3xx はリダイレクトを辿らず UNKNOWN`() {
        server.script(Reply.Status(302, "", mapOf("Location" to "http://127.0.0.1:${server.port}/elsewhere")))
        assertEquals(DeliveryResult.UNKNOWN, send())
        assertEquals(1, server.requests.size, "Location へ行かない（SSRF 対策）")
    }

    @Test
    fun `429 は本文の retry_after を優先して待ち 再試行して成功`() {
        server.script(
            Reply.Status(
                429,
                """{"message":"You are being rate limited.","retry_after":1.5,"global":false}""",
                mapOf(
                    "Retry-After" to "9",
                    "X-RateLimit-Scope" to "user",
                ),
            ),
        )
        assertEquals(DeliveryResult.DELIVERED, send())
        assertEquals(2, server.requests.size)
        assertEquals(listOf(Duration.ofMillis(1500)), sleeps.toList())
        assertTrue(log.text().contains("scope=user"))
    }

    @Test
    fun `429 は Retry-After ヘッダ X-RateLimit-Reset-After 既定値の順に使い 0_5 秒未満は 0_5 秒に丸める`() {
        server.script(
            Reply.Status(429, "", mapOf("Retry-After" to "3")),
            Reply.Status(429, "<html>", mapOf("X-RateLimit-Reset-After" to "0.1")),
            Reply.Status(429, ""),
        )
        assertEquals(DeliveryResult.DELIVERED, send())
        assertEquals(listOf(Duration.ofSeconds(3), Duration.ofMillis(500), Duration.ofSeconds(1)), sleeps.toList())
    }

    @Test
    fun `429 が続いても最大試行回数で止まる 無限リトライしない`() {
        server.script(*Array(10) { Reply.Status(429, """{"retry_after":2}""") })
        assertEquals(DeliveryResult.NOT_SENT, send())
        assertEquals(5, server.requests.size)
        assertEquals(4, sleeps.size)
    }

    @Test
    fun `429 で上限を超える待機を求められたら待たずにあきらめる`() {
        server.script(Reply.Status(429, """{"retry_after":120}"""))
        assertEquals(DeliveryResult.NOT_SENT, send())
        assertEquals(1, server.requests.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `要求のタイムアウトは UNKNOWN 再送しない`() {
        server.script(Reply.Stall(2500))
        val n = notifier(transport = JdkWebhookTransport(Duration.ofSeconds(2), Duration.ofMillis(300)))
        assertEquals(DeliveryResult.UNKNOWN, send(n = n))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `接続の切断 応答なしは UNKNOWN 再送しない`() {
        for (readRequest in listOf(false, true)) {
            DroppingServer(readRequest).use { drop ->
                val n = notifier(url = drop.url())
                assertEquals(DeliveryResult.UNKNOWN, n.send(signal()), "readRequest=$readRequest")
                assertEquals(1, drop.accepted.get(), "再送しない")
            }
        }
        // Fake サーバがヘッダ無しで閉じる場合
        server.script(Reply.Drop)
        assertEquals(DeliveryResult.UNKNOWN, send())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `接続拒否は受け取られていないので指数バックオフで再試行し NOT_SENT`() {
        val closedPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val n = notifier(url = DiscordWebhookUrl.forLocalTest("http://127.0.0.1:$closedPort/api/webhooks/1/a"))
        assertEquals(DeliveryResult.NOT_SENT, n.send(signal()))
        assertEquals(listOf(1L, 2L, 4L, 8L), sleeps.map { it.seconds })
    }

    @Test
    fun `長文は分割して順に送り 連結すると元に戻る 各 POST は上限内`() {
        val body = (1..300).joinToString("\n") { "    - 01-%02d odds=12.3（推定） p=3.45%% EV=12.3%% 🐴🎯".format(it % 100) }
        assertEquals(DeliveryResult.DELIVERED, send(body))
        assertTrue(server.requests.size >= 3)
        assertEquals(body, server.requests.joinToString("") { it.content() })
        assertTrue(server.requests.all { it.content().length <= ContentSplitter.SAFE_CHUNK_LIMIT })
    }

    @Test
    fun `途中のチャンクで失敗したら UNKNOWN 全体を再送しない`() {
        val body = "a".repeat(1900) + "b".repeat(1900) + "c".repeat(1900)
        for (failure in listOf(Reply.Status(500), Reply.Status(400), Reply.Status(403), Reply.Status(429, """{"retry_after":120}"""), Reply.Stall(2500))) {
            server.requests.clear()
            server.script(Reply.Status(204), failure)
            val n = notifier(transport = JdkWebhookTransport(Duration.ofSeconds(2), Duration.ofMillis(300)))
            assertEquals(DeliveryResult.UNKNOWN, send(body, n), "2 通目の失敗 $failure は一部送信済みなので UNKNOWN")
            assertEquals(2, server.requests.size, "3 通目は送らない・1 通目を再送しない: $failure")
        }
    }

    @Test
    fun `1 通目が確実に拒否されたら NOT_SENT`() {
        server.script(Reply.Status(400))
        assertEquals(DeliveryResult.NOT_SENT, send("a".repeat(1900) + "b".repeat(1900)))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `途中のチャンクの 429 は待って再試行し 全チャンクが成功すれば DELIVERED`() {
        server.script(Reply.Status(204), Reply.Status(429, """{"retry_after":2}"""), Reply.Status(204))
        assertEquals(DeliveryResult.DELIVERED, send("a".repeat(1900) + "b".repeat(1900)))
        assertEquals(3, server.requests.size)
        assertEquals("b".repeat(1900), server.requests[1].content())
        assertEquals("b".repeat(1900), server.requests[2].content(), "429 のチャンクだけを再送")
        assertEquals(listOf(Duration.ofSeconds(2)), sleeps.toList())
    }

    @Test
    fun `停止要求では新しい送信を始めない 送信済みがあれば UNKNOWN`() {
        var stop = false
        val n = notifier(stop = { stop })
        stop = true
        assertEquals(DeliveryResult.NOT_SENT, n.send(signal()))
        assertTrue(server.requests.isEmpty())
        // 1 通目の後に停止
        val seen =
            java.util.concurrent.atomic
                .AtomicInteger()
        val n2 = notifier(stop = { seen.getAndIncrement() >= 1 })
        assertEquals(DeliveryResult.UNKNOWN, n2.send(signal("a".repeat(1900) + "b".repeat(1900))))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `待機中の中断 SIGTERM では以降を送らない`() {
        server.script(Reply.Status(429, """{"retry_after":2}"""))
        val interrupting = Sleeper { throw InterruptedException() }
        try {
            assertEquals(DeliveryResult.NOT_SENT, send(n = notifier(sleeper = interrupting)), "何も届いていない")
            assertEquals(1, server.requests.size)
        } finally {
            Thread.interrupted() // 後始末: 中断フラグを戻す
        }
        server.requests.clear()
        server.script(Reply.Status(204), Reply.Status(429, """{"retry_after":2}"""))
        try {
            assertEquals(DeliveryResult.UNKNOWN, send("a".repeat(1900) + "b".repeat(1900), notifier(sleeper = interrupting)), "1 通目は届いている")
            assertEquals(2, server.requests.size)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `宛先が未設定なら canDeliver が false で 送らない`() {
        val n = DiscordNotifier(null, server.url(), MentionPolicy.NONE, JdkWebhookTransport(), log = log)
        assertFalse(n.canDeliver(NotificationChannel.SIGNAL))
        assertTrue(n.canDeliver(NotificationChannel.ERROR))
        assertEquals(DeliveryResult.NOT_SENT, n.send(signal()))
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `ログに URL トークン 本文 ヘッダを出さない`() {
        server.script(
            Reply.Status(429, """{"retry_after":1,"message":"RESPONSE-BODY-SECRET"}""", mapOf("Retry-After" to "1")),
            Reply.Status(500, "RESPONSE-BODY-SECRET"),
        )
        send("SECRET-NOTIFICATION-BODY 馬名")
        val text = log.text()
        assertTrue(text.isNotBlank())
        for (secret in listOf(
            "fake-token_123",
            "123456789012345678",
            "127.0.0.1",
            "SECRET-NOTIFICATION-BODY",
            "RESPONSE-BODY-SECRET",
            "api/webhooks",
            "http",
        )) {
            assertFalse(text.contains(secret), "ログに出てはいけない: $secret\n$text")
        }
    }

    @Test
    fun `例外メッセージ経由でも URL を漏らさない`() {
        val closedPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        notifier(url = DiscordWebhookUrl.forLocalTest("http://127.0.0.1:$closedPort/api/webhooks/1/TOKEN-XYZ")).send(signal())
        assertFalse(log.text().contains("TOKEN-XYZ") || log.text().contains(closedPort.toString()))
    }

    @Test
    fun `JSON の制御文字 孤立サロゲート 引用符を安全に符号化する`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\ufffd\\u2028\"", JsonText.quote("a\"b\\c\n\u0001\uD800 "))
        assertEquals("\"🐴\"", JsonText.quote("🐴"))
    }
}
