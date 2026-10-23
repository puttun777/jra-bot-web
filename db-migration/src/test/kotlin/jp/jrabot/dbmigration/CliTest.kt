package jp.jrabot.dbmigration

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 終了コードの契約（CONTRACTS §4.2）を、フェイクのエンジンで検証する（Docker 不要）。 */
class CliTest {
    private val password = "s3cret-pw"
    private val env = mapOf("DB_URL" to "jdbc:mysql://10.0.0.5:3306/jrabot", "DB_USER" to "migrate", "DB_PASSWORD" to password)

    private class Result(
        val code: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        args: List<String>,
        env: Map<String, String> = this.env,
        engine: MigrationEngine = FakeEngine(),
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli { engine }.run(args, env, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private class FakeEngine(
        var info: InfoReport = InfoReport(current = "2", latest = "2", pending = emptyList(), failed = emptyList()),
        var valid: Boolean = true,
        var errors: List<String> = emptyList(),
        var applied: List<String> = emptyList(),
        var failure: Exception? = null,
    ) : MigrationEngine {
        override fun info(): InfoReport = failure?.let { throw it } ?: info

        override fun validate(): ValidateReport = failure?.let { throw it } ?: ValidateReport(valid, info, errors)

        override fun migrate(): MigrateReport = failure?.let { throw it } ?: MigrateReport(applied, info)
    }

    private val pending = InfoReport(current = "1", latest = "2", pending = listOf("2", "R__grants"), failed = emptyList())
    private val link = SQLException("Communications link failure", "08S01", 0)

    private fun assertSingleJsonLine(stdout: String) {
        assertEquals(1, stdout.trimEnd('\n').lines().size, "標準出力は JSON 1 行: <$stdout>")
        assertTrue(stdout.startsWith("{") && stdout.trimEnd().endsWith("}"), stdout)
    }

    // ---- 共通: 引数・設定の誤りは 2。標準出力には何も出さない ----
    @Test
    fun `モード未指定は 2 で標準出力は空`() {
        val r = run(emptyList())
        assertEquals(2, r.code)
        assertEquals("", r.stdout)
        assertTrue("--mode" in r.stderr)
    }

    @Test
    fun `不正なモードや余分な引数は 2`() {
        for (args in listOf(listOf("--mode=repair"), listOf("--mode=info", "x"), listOf("info"), listOf("--mode=migrate", "--mode=migrate"))) {
            val r = run(args)
            assertEquals(2, r.code, "args=$args")
            assertEquals("", r.stdout)
        }
    }

    @Test
    fun `DB の環境変数が無ければ 2`() {
        val r = run(listOf("--mode=info"), env = emptyMap())
        assertEquals(2, r.code)
        assertEquals("", r.stdout)
        assertTrue("DB_URL" in r.stderr)
    }

    // ---- info ----
    @Test
    fun `info は 0 で契約の JSON を出す`() {
        val r = run(listOf("--mode=info"), engine = FakeEngine(info = InfoReport("2", "3", listOf("3"), emptyList())))
        assertEquals(0, r.code)
        assertEquals("""{"current":"2","pending":["3"],"failed":[],"latest":"3"}""" + "\n", r.stdout)
    }

    @Test
    fun `info は未適用や失敗があっても 0`() {
        val r = run(listOf("--mode=info"), engine = FakeEngine(info = InfoReport("1", "2", listOf("2"), listOf("2"))))
        assertEquals(0, r.code)
        assertTrue(""""failed":["2"]""" in r.stdout)
    }

    @Test
    fun `info は DB に接続できなければ 75`() {
        val r = run(listOf("--mode=info"), engine = FakeEngine(failure = RuntimeException("Unable to obtain connection", link)))
        assertEquals(75, r.code)
        assertEquals("""{"mode":"info","error":"unavailable"}""" + "\n", r.stdout)
    }

    // ---- validate ----
    @Test
    fun `validate は整合していて未適用が無ければ 0`() {
        val r = run(listOf("--mode=validate"))
        assertEquals(0, r.code)
        assertSingleJsonLine(r.stdout)
        assertTrue(""""valid":true""" in r.stdout)
    }

    @Test
    fun `validate は整合していて未適用があれば 10`() {
        val r = run(listOf("--mode=validate"), engine = FakeEngine(info = pending))
        assertEquals(10, r.code)
        assertSingleJsonLine(r.stdout)
        assertTrue(""""valid":true""" in r.stdout && """"pending":["2","R__grants"]""" in r.stdout, r.stdout)
    }

    @Test
    fun `validate は不整合なら 1`() {
        val r = run(listOf("--mode=validate"), engine = FakeEngine(valid = false, errors = listOf("1 baseline: checksum mismatch")))
        assertEquals(1, r.code)
        assertTrue(""""valid":false""" in r.stdout && "checksum mismatch" in r.stdout, r.stdout)
    }

    @Test
    fun `validate は不整合と未適用が重なっても 1（不整合を優先）`() {
        assertEquals(1, run(listOf("--mode=validate"), engine = FakeEngine(info = pending, valid = false)).code)
    }

    @Test
    fun `validate は DB に接続できなければ 75`() {
        val r = run(listOf("--mode=validate"), engine = FakeEngine(failure = RuntimeException("x", link)))
        assertEquals(75, r.code)
        assertEquals("""{"mode":"validate","error":"unavailable"}""" + "\n", r.stdout)
    }

    @Test
    fun `validate は接続以外の例外なら 1`() {
        assertEquals(1, run(listOf("--mode=validate"), engine = FakeEngine(failure = IllegalStateException("bug"))).code)
    }

    // ---- migrate ----
    @Test
    fun `migrate は成功なら 0 で適用した版を出す`() {
        val r = run(listOf("--mode=migrate"), engine = FakeEngine(applied = listOf("1", "2", "R__grants")))
        assertEquals(0, r.code)
        assertSingleJsonLine(r.stdout)
        assertTrue(""""success":true""" in r.stdout && """"applied":["1","2","R__grants"]""" in r.stdout, r.stdout)
    }

    @Test
    fun `migrate は何も適用しなくても 0（再実行は安全）`() {
        val r = run(listOf("--mode=migrate"))
        assertEquals(0, r.code)
        assertTrue(""""applied":[]""" in r.stdout)
    }

    @Test
    fun `migrate が失敗したら 1 で、自動の修復はしない`() {
        val r = run(listOf("--mode=migrate"), engine = FakeEngine(failure = IllegalStateException("Migration V2 failed")))
        assertEquals(1, r.code)
        assertEquals("""{"mode":"migrate","error":"failed"}""" + "\n", r.stdout)
        assertTrue("Migration V2 failed" in r.stderr)
    }

    @Test
    fun `migrate は DB に接続できなければ 75`() {
        val r = run(listOf("--mode=migrate"), engine = FakeEngine(failure = RuntimeException("x", link)))
        assertEquals(75, r.code)
        assertEquals("""{"mode":"migrate","error":"unavailable"}""" + "\n", r.stdout)
    }

    // ---- 秘密 ----
    @Test
    fun `エラーの詳細にパスワードが含まれても標準出力にも標準エラーにも出さない`() {
        val failure = SQLException("Access denied for user 'migrate' (using password: $password) url=jdbc:mysql://h/db?password=$password", "28000", 1045)
        for (mode in listOf("info", "validate", "migrate")) {
            val r = run(listOf("--mode=$mode"), engine = FakeEngine(failure = failure))
            assertEquals(1, r.code, "認証失敗は一時的ではない: $mode")
            assertFalse(password in r.stdout, r.stdout)
            assertFalse(password in r.stderr, r.stderr)
            assertTrue("***" in r.stderr, r.stderr)
        }
    }

    @Test
    fun `標準エラーはスタックトレースを出さず、詳細は 1 例外につき 1 行`() {
        val r = run(listOf("--mode=migrate"), engine = FakeEngine(failure = RuntimeException("line1\nline2 at secret.Frame")))
        assertFalse("at secret.Frame" in r.stderr)
        assertEquals(
            1,
            r.stderr
                .trimEnd('\n')
                .lines()
                .size,
            r.stderr,
        )
    }
}
