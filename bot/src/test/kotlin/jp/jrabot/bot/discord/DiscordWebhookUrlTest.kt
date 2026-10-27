package jp.jrabot.bot.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordWebhookUrlTest {
    private val ok = "https://discord.com/api/webhooks/123456789012345678/AbC-dEf_123"

    @Test
    fun `正規の Webhook URL を受け付ける`() {
        for (u in listOf(
            ok,
            "https://ptb.discord.com/api/webhooks/1/a",
            "https://canary.discord.com/api/webhooks/1/a",
            "https://discordapp.com/api/webhooks/1/a",
            "https://DISCORD.com:443/api/webhooks/1/a",
        )) {
            assertEquals("DiscordWebhookUrl(<redacted>)", DiscordWebhookUrl.parse(u).toString())
        }
    }

    @Test
    fun `任意の外部 URL と内部ネットワーク宛は拒否する SSRF`() {
        val bad =
            listOf(
                "",
                "   ",
                "not a url",
                "http://discord.com/api/webhooks/1/a",
                "ftp://discord.com/api/webhooks/1/a",
                "https://evil.example.com/api/webhooks/1/a",
                "https://discord.com.evil.example.com/api/webhooks/1/a",
                "https://evil.com/discord.com/api/webhooks/1/a",
                "https://discord.com@evil.example.com/api/webhooks/1/a",
                "https://user:pw@discord.com/api/webhooks/1/a",
                "https://notdiscord.com/api/webhooks/1/a",
                "https://127.0.0.1/api/webhooks/1/a",
                "https://localhost/api/webhooks/1/a",
                "https://[::1]/api/webhooks/1/a",
                "https://169.254.169.254/api/webhooks/1/a",
                "https://10.0.0.1/api/webhooks/1/a",
                "https://192.168.0.1/api/webhooks/1/a",
                "https://metadata.google.internal/api/webhooks/1/a",
                "https://discord.com:8443/api/webhooks/1/a",
                "https://discord.com/api/webhooks/1/a?wait=true",
                "https://discord.com/api/webhooks/1/a#frag",
                "https://discord.com/api/webhooks/abc/a",
                "https://discord.com/api/webhooks/1/",
                "https://discord.com/api/webhooks/1",
                "https://discord.com/other/path",
                "https://discord.com/api/webhooks/1/a/b",
                "https://discord.com/api/webhooks/1/a%2f..",
                "https://discord.com/api/webhooks/../1/a",
                "https://discord.com/api/webhooks/1/a b",
                "https://discord.com/api/webhooks/1/a\n",
                " https://discord.com/api/webhooks/1/a",
                "https://discord.com/api/webhooks/1/" + "a".repeat(600),
                "https://discord.com/api/v10/webhooks/1/a",
                "https://2130706433/api/webhooks/1/a",
                "https://0x7f000001/api/webhooks/1/a",
            )
        for (u in bad) assertFailsWith<IllegalArgumentException>("拒否されるべき: $u") { DiscordWebhookUrl.parse(u) }
    }

    @Test
    fun `エラーメッセージと toString にトークンを出さない`() {
        val e = assertFailsWith<IllegalArgumentException> { DiscordWebhookUrl.parse("https://evil.example.com/api/webhooks/123456789012345678/SECRETTOKEN") }
        assertFalse(e.message.orEmpty().contains("SECRETTOKEN") || e.message.orEmpty().contains("evil"))
        assertFalse(DiscordWebhookUrl.parse(ok).toString().contains("AbC"))
    }

    @Test
    fun `テスト用はループバックの http だけ 本番の parse では通らない`() {
        val u = DiscordWebhookUrl.forLocalTest("http://127.0.0.1:8080/api/webhooks/1/a")
        assertEquals(8080, u.uri.port)
        DiscordWebhookUrl.forLocalTest("http://localhost:8080/api/webhooks/1/a")
        for (bad in listOf(
            "https://127.0.0.1:8080/api/webhooks/1/a",
            "http://127.0.0.1/api/webhooks/1/a",
            "http://10.0.0.1:8080/api/webhooks/1/a",
            "http://evil.example.com:80/api/webhooks/1/a",
            "http://127.0.0.1:8080/other",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { DiscordWebhookUrl.forLocalTest(bad) }
        }
        assertFailsWith<IllegalArgumentException> { DiscordWebhookUrl.parse("http://127.0.0.1:8080/api/webhooks/1/a") }
        assertTrue(true)
    }
}
