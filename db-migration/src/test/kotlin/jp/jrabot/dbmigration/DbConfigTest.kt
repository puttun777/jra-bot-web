package jp.jrabot.dbmigration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DbConfigTest {
    private val env = mapOf("DB_URL" to "jdbc:mysql://10.0.0.5:3306/jrabot", "DB_USER" to "migrate", "DB_PASSWORD" to "s3cret-pw")

    @Test
    fun `環境変数から作る`() {
        val config = assertIs<ConfigResult.Ok>(DbConfig.fromEnv(env)).config
        assertEquals("migrate", config.user)
        assertEquals("s3cret-pw", config.password)
    }

    @Test
    fun `必須の環境変数が無ければ不正で、欠けた名前だけを示す`() {
        val result = assertIs<ConfigResult.Invalid>(DbConfig.fromEnv(env - "DB_PASSWORD" - "DB_URL"))
        assertTrue("DB_URL" in result.message && "DB_PASSWORD" in result.message && "DB_USER" !in result.message, result.message)
    }

    @Test
    fun `空のパスワードは許すが未設定は許さない`() {
        assertIs<ConfigResult.Ok>(DbConfig.fromEnv(env + ("DB_PASSWORD" to "")))
        assertIs<ConfigResult.Invalid>(DbConfig.fromEnv(env - "DB_PASSWORD"))
    }

    @Test
    fun `MySQL 以外の URL は不正`() {
        assertIs<ConfigResult.Invalid>(DbConfig.fromEnv(env + ("DB_URL" to "jdbc:postgresql://h/db")))
        assertIs<ConfigResult.Invalid>(DbConfig.fromEnv(env + ("DB_URL" to "mysql://h/db")))
    }

    @Test
    fun `connectTimeout が無い URL にだけ 10 秒を足す`() {
        assertEquals("jdbc:mysql://h:3306/db?connectTimeout=10000", DbConfig("jdbc:mysql://h:3306/db", "u", "p").effectiveUrl())
        assertEquals("jdbc:mysql://h/db?sslMode=VERIFY_CA&connectTimeout=10000", DbConfig("jdbc:mysql://h/db?sslMode=VERIFY_CA", "u", "p").effectiveUrl())
        assertEquals("jdbc:mysql://h/db?connectTimeout=500", DbConfig("jdbc:mysql://h/db?connectTimeout=500", "u", "p").effectiveUrl())
    }

    @Test
    fun `toString にパスワードも URL も出ない`() {
        val config = DbConfig("jdbc:mysql://h/db?password=urlpw", "migrate", "s3cret-pw")
        assertFalse("s3cret-pw" in config.toString() || "urlpw" in config.toString() || "h/db" in config.toString(), config.toString())
    }

    @Test
    fun `URL に埋め込まれたパスワードも秘密として扱う`() {
        val config = DbConfig("jdbc:mysql://h/db?user=a&password=urlpw&x=1", "migrate", "s3cret-pw")
        assertEquals(setOf("s3cret-pw", "urlpw"), config.secrets().toSet())
    }

    @Test
    fun `Redactor は完全一致と password=形式を伏せる`() {
        val text = "Access denied for 's3cret-pw' url=jdbc:mysql://h/db?password=urlpw&a=b PWD=zzz, passwd=q"
        val redacted = Redactor.redact(text, listOf("s3cret-pw", "urlpw"))
        for (leak in listOf("s3cret-pw", "urlpw", "zzz", "=q")) assertFalse(leak in redacted, "$leak が残っている: $redacted")
        assertTrue("a=b" in redacted, redacted)
    }

    @Test
    fun `短いパスワードが他の語の一部でも長い秘密を先に伏せる`() {
        assertEquals("*** and ***", Redactor.redact("abcdef and abc", listOf("abc", "abcdef")))
    }
}
