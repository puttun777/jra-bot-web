package jp.jrabot.bot

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 実際の JVM プロセスとして起動して終了コードを確かめる（`exitProcess` の対応づけ）。実 GCP・実 DB には接続しない:
 * モードはローカルの合成ファイル、DB の接続先は接続を受け付けないポート。非 NORMAL では DB に触れないので、DB が無くても結果が変わらない。
 */
class BotProcessTest {
    private val dir = Files.createTempDirectory("bot-process")

    private class Outcome(
        val exit: Int,
        val output: String,
    )

    private fun launch(
        args: Array<String>,
        env: Map<String, String>,
    ): Outcome {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "jp.jrabot.bot.BotApplicationKt", *args)
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH") ?: "")
            put("LC_ALL", "C.UTF-8")
            putAll(env)
        }
        val out = File.createTempFile("bot-out", ".txt", dir.toFile())
        val process = builder.redirectErrorStream(true).redirectOutput(out).start()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "プロセスが終わらない")
        return Outcome(process.exitValue(), out.readText())
    }

    private fun ops(mode: String) = writeOpsState(Files.createTempDirectory(dir, "ops"), mode).toString()

    @Test
    fun `不正な task は終了コード 2`() {
        val r = launch(args("no-such-task"), baseEnv(ops("NORMAL")))
        assertEquals(2, r.exit, r.output)
        assertTrue(r.output.contains("INVALID_ARGUMENTS"), r.output)
        assertFalse(r.output.contains("JOB_PHASE"), "Spring/DB の段階に進んだ: ${r.output}")
    }

    @Test
    fun `task 未指定は終了コード 2`() {
        assertEquals(2, launch(arrayOf("--tenant-id=1"), baseEnv(ops("NORMAL"))).exit)
        assertEquals(2, launch(emptyArray(), baseEnv(ops("NORMAL"))).exit)
    }

    @Test
    fun `STOPPED は終了コード 0 で、Spring も DB も使わない`() {
        for (t in BotTask.entries) {
            val r = launch(args(t.cliName), baseEnv(ops("STOPPED")))
            assertEquals(0, r.exit, "${t.cliName}: ${r.output}")
            assertTrue(r.output.contains("reason=MODE_STOPPED"), r.output)
            assertFalse(r.output.contains("JOB_PHASE") || r.output.contains("HikariPool"), "Spring/DB が動いた: ${r.output}")
        }
    }

    @Test
    fun `STARTING と STOPPING も終了コード 0`() {
        assertEquals(0, launch(args("reconcile"), baseEnv(ops("STARTING"))).exit)
        assertEquals(0, launch(args("reconcile"), baseEnv(ops("STOPPING"))).exit)
    }

    @Test
    fun `UNKNOWN(正本を読めない)は終了コード 75`() {
        val missing = dir.resolve("no-such-ops-state.json").toString()
        val r = launch(args("calendar-check"), baseEnv(missing))
        assertEquals(75, r.exit, r.output)
        assertTrue(r.output.contains("reason=MODE_UNKNOWN"), r.output)
        assertFalse(r.output.contains("JOB_PHASE") || r.output.contains("HikariPool"), "Spring/DB の段階に進んだ: ${r.output}")
    }

    @Test
    fun `UNKNOWN(JSON が壊れている)も 75`() {
        val bad = Files.writeString(dir.resolve("broken.json"), "{ not json").toString()
        assertEquals(75, launch(args("calendar-check"), baseEnv(bad)).exit)
    }

    @Test
    fun `環境変数が足りなければ終了コード 2（パスワードを出さない）`() {
        val r = launch(args(), baseEnv(ops("NORMAL"), "DB_URL" to null))
        assertEquals(2, r.exit, r.output)
        assertFalse(r.output.contains("synthetic-password-never-logged"), r.output)
        assertEquals(2, launch(args(), baseEnv(ops("NORMAL"), "OPS_MODE_SOURCE" to null)).exit)
    }

    @Test
    fun `本番でローカルファイルのモードは使えず終了コード 2（ローカル実装へ黙ってフォールバックしない）`() {
        val r = launch(args(), baseEnv(ops("STOPPED"), "APP_ENV" to "prod", "DB_USER" to "bot"))
        assertEquals(2, r.exit, r.output)
    }

    @Test
    fun `NORMAL で DB に接続できなければ 75(DB 不達)`() {
        val r = launch(args("calendar-check"), baseEnv(ops("NORMAL")))
        assertEquals(75, r.exit, r.output)
        assertTrue(
            r.output.contains("JOB_PHASE spring-context-start") && r.output.contains("JOB_PHASE schema-check"),
            "NORMAL では Spring と DB 確認に進む: ${r.output}",
        )
        assertTrue(r.output.contains("reason=DB_UNAVAILABLE"), r.output)
        assertFalse(r.output.contains("synthetic-password-never-logged"), r.output)
    }
}
