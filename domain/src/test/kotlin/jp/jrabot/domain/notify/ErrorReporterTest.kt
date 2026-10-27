package jp.jrabot.domain.notify

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorReporterTest {
    private class RecordingNotifier(
        var result: () -> DeliveryResult = { DeliveryResult.DELIVERED },
        var configured: Boolean = true,
    ) : Notifier {
        val sent = mutableListOf<Notification>()

        override fun canDeliver(channel: NotificationChannel) = configured

        override fun send(notification: Notification): DeliveryResult {
            sent += notification
            return result()
        }
    }

    private val clock = FixedClock()
    private val notifier = RecordingNotifier()
    private val reporter = ErrorReporter(notifier, clock, Duration.ofMinutes(5))

    @Test
    fun `エラーチャンネルへテナントなしで送る`() {
        assertEquals(ErrorReporter.Outcome.SENT, reporter.report("race processing error", "IllegalStateException: x", "stack"))
        val n = notifier.sent.single()
        assertEquals(NotificationChannel.ERROR, n.channel)
        assertNull(n.tenant)
        assertTrue(n.body.startsWith("【JRA EV Bot エラー】\n**race processing error**\n") && n.body.contains("```text\nstack\n```"))
    }

    @Test
    fun `宛先が未設定なら何もしない`() {
        notifier.configured = false
        assertEquals(ErrorReporter.Outcome.NOT_CONFIGURED, reporter.report("t", "m"))
        assertTrue(notifier.sent.isEmpty())
    }

    @Test
    fun `同じ内容はクールダウン中は送らず 経過後に送る`() {
        assertEquals(ErrorReporter.Outcome.SENT, reporter.report("t", "m"))
        assertEquals(ErrorReporter.Outcome.COOLDOWN, reporter.report("t", "m"))
        assertEquals(ErrorReporter.Outcome.SENT, reporter.report("t", "別のメッセージ"))
        clock.instant = clock.instant.plus(Duration.ofMinutes(6))
        assertEquals(ErrorReporter.Outcome.SENT, reporter.report("t", "m"))
        assertEquals(3, notifier.sent.size)
    }

    @Test
    fun `送信に失敗しても例外にせず 失敗をさらに通知し直さない`() {
        notifier.result = { DeliveryResult.UNKNOWN }
        assertEquals(ErrorReporter.Outcome.NOT_DELIVERED, reporter.report("t", "m"))
        assertEquals(1, notifier.sent.size, "失敗を同じ宛先へ再通知しない")
        val throwing = ErrorReporter(RecordingNotifier({ throw IllegalStateException("boom") }), clock)
        assertEquals(ErrorReporter.Outcome.NOT_DELIVERED, throwing.report("t", "m"))
    }

    @Test
    fun `報告の最中の再入は捨てる 無限再帰しない`() {
        lateinit var self: ErrorReporter
        var nested: ErrorReporter.Outcome? = null
        val n =
            RecordingNotifier({
                nested = self.report("nested", "again")
                DeliveryResult.UNKNOWN
            })
        self = ErrorReporter(n, clock)
        self.report("outer", "m")
        assertEquals(ErrorReporter.Outcome.REENTRANT, nested)
        assertEquals(1, n.sent.size)
    }

    @Test
    fun `Webhook URL と認証情報と秘密鍵を本文から除く`() {
        val url = "https://discord.com/api/webhooks/123456789012345678/AbCdEf_secret-token"
        val msg =
            "failed POST $url Authorization: Bearer abcdefghijklmnop password=hunter2 api_key: sk-123 " +
                "-----" + "BEGIN PRIVATE KEY" + "-----\nMIIBVQIBADANBg\n-----" + "END PRIVATE KEY" + "-----"
        reporter.report("title $url", msg, "detail token=zzzz1234 $url")
        val body = notifier.sent.single().body + notifier.sent.single().subject
        for (secret in listOf("AbCdEf_secret-token", "123456789012345678/", "abcdefghijklmnop", "hunter2", "sk-123", "MIIBVQIBADANBg", "zzzz1234")) {
            assertFalse(body.contains(secret), "含まれてはいけない: $secret")
        }
        assertTrue(body.contains("[redacted"))
    }

    @Test
    fun `長さを切り詰めメンションを無害化する`() {
        reporter.report("t".repeat(500), "m".repeat(5000) + "@everyone", "d".repeat(5000))
        val n = notifier.sent.single()
        assertTrue(n.body.length <= 1900 && n.subject.length <= 200)
        assertTrue(n.body.contains("...(truncated)"))
        reporter.report("@here", "<@&99>")
        assertFalse(
            notifier.sent
                .last()
                .body
                .contains("@here"),
        )
    }

    @Test
    fun `SecretRedactor 単体`() {
        assertEquals("ok", SecretRedactor.redact("ok"))
        assertEquals("[redacted-webhook-url]", SecretRedactor.redact("https://discordapp.com/api/v10/webhooks/1/abc"))
    }
}
