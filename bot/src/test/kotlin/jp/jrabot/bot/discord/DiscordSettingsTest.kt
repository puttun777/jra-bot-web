package jp.jrabot.bot.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiscordSettingsTest {
    private val url = "https://discord.com/api/webhooks/123456789012345678/AbC-dEf_123"

    @Test
    fun `すべて任意で 未設定なら宛先なし`() {
        val s = DiscordSettings.fromEnv(emptyMap())
        assertNull(s.signalUrl)
        assertNull(s.errorUrl)
        assertNull(s.mention.prefix)
        val blank = DiscordSettings.fromEnv(mapOf("DISCORD_WEBHOOK_URL" to "  ", "DISCORD_ERROR_WEBHOOK_URL" to "", "DISCORD_MENTION_USER_ID" to " "))
        assertNull(blank.signalUrl)
    }

    @Test
    fun `設定を読む`() {
        val s = DiscordSettings.fromEnv(mapOf("DISCORD_WEBHOOK_URL" to url, "DISCORD_ERROR_WEBHOOK_URL" to " $url ", "DISCORD_MENTION_USER_ID" to "123456789012345678"))
        assertNotNull(s.signalUrl)
        assertNotNull(s.errorUrl)
        assertEquals("<@123456789012345678>", s.mention.prefix)
    }

    @Test
    fun `不正な値は変数名だけのメッセージで拒否 値を出さない`() {
        val e1 = assertFailsWith<IllegalArgumentException> { DiscordSettings.fromEnv(mapOf("DISCORD_WEBHOOK_URL" to "https://evil.example.com/SECRETTOKEN")) }
        assertEquals("DISCORD_WEBHOOK_URL が不正です（Discord の Webhook URL ではありません）", e1.message)
        val e2 = assertFailsWith<IllegalArgumentException> { DiscordSettings.fromEnv(mapOf("DISCORD_ERROR_WEBHOOK_URL" to "http://discord.com/api/webhooks/1/SECRETTOKEN")) }
        assertFalse(e2.message.orEmpty().contains("SECRETTOKEN"))
        val e3 = assertFailsWith<IllegalArgumentException> { DiscordSettings.fromEnv(mapOf("DISCORD_MENTION_USER_ID" to "@everyone")) }
        assertFalse(e3.message.orEmpty().contains("everyone"))
    }

    @Test
    fun `toString は URL を含まない`() {
        val s = DiscordSettings.fromEnv(mapOf("DISCORD_WEBHOOK_URL" to url, "DISCORD_MENTION_USER_ID" to "123456789012345678"))
        assertFalse(s.toString().contains("AbC") || s.toString().contains("1234567890"))
    }

    @Test
    fun `停止要求の旗`() {
        val f = StopFlag()
        assertFalse(f.isRequested())
        f.request()
        assertEquals(true, f.isRequested())
    }
}
