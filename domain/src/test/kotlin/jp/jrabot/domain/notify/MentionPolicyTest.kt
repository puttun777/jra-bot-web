package jp.jrabot.domain.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentionPolicyTest {
    @Test
    fun `未設定は常にメンションなしで parse は空`() {
        for (raw in listOf(null, "", "   ")) {
            val p = MentionPolicy.fromSetting(raw)
            assertNull(p.prefix)
            assertEquals("""{"parse":[]}""", p.allowedMentionsJson())
        }
        assertEquals("""{"parse":[]}""", MentionPolicy.NONE.allowedMentionsJson())
    }

    @Test
    fun `本人の ID だけを個別に許す`() {
        val p = MentionPolicy.forUser("123456789012345678")
        assertEquals("<@123456789012345678>", p.prefix)
        assertEquals("""{"parse":[],"users":["123456789012345678"]}""", p.allowedMentionsJson())
        assertFalse(p.allowedMentionsJson().contains("everyone"))
        assertFalse(p.allowedMentionsJson().contains("roles"))
    }

    @Test
    fun `不正な ID は拒否し値をメッセージに出さない`() {
        for (bad in listOf(
            "abc",
            "1234",
            "12345678901234567890123",
            "＠１２３４５６７８９０１２３４５６７８",
            "123456789012345678 ",
            "@everyone",
            "<@123456789012345678>",
            "12345678901234567a",
            "-12345678901234567",
        )) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { MentionPolicy.forUser(bad) }
            assertFalse(e.message.orEmpty().contains(bad.trim()) && bad.length > 3, "値をメッセージに含めない")
        }
        // fromSetting は前後の空白だけ取り除く
        assertEquals("<@123456789012345678>", MentionPolicy.fromSetting(" 123456789012345678 ").prefix)
        assertFailsWith<IllegalArgumentException> { MentionPolicy.fromSetting("not-an-id") }
    }

    @Test
    fun `toString に ID を出さない`() {
        assertFalse(MentionPolicy.forUser("123456789012345678").toString().contains("1234567890"))
    }

    @Test
    fun `動的な文字列の at マークを無害化する`() {
        assertEquals("馬名", DiscordText.neutralizeMentions("馬名"))
        val out = DiscordText.neutralizeMentions("@everyone @here <@123456789012345678> <@&99>")
        assertFalse(out.contains("@everyone") || out.contains("@here") || out.contains("<@1") || out.contains("<@&"))
        assertTrue(out.replace("​", "") == "@everyone @here <@123456789012345678> <@&99>", "ゼロ幅スペース以外は変わらない")
    }
}
