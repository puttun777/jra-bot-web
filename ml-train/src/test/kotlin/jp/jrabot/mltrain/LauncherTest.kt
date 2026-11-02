package jp.jrabot.mltrain

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.mltrain.job.ExitCode
import jp.jrabot.mltrain.job.JobLog
import jp.jrabot.mltrain.job.UsageError
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** CLI の順序と終了コード（CONTRACTS §9.1）。運用モードは DB に接続する前に判定し、非 NORMAL・UNKNOWN では DB に触れない。 */
class LauncherTest {
    private val dir = Files.createTempDirectory("launcher")
    private val cfg = dir.resolve("train.json").also { Files.writeString(it, """{"kind":"calibration"}""") }.toString()
    private val store = Files.createDirectories(dir.resolve("store"))
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private fun env(over: Map<String, String> = emptyMap()) =
        mapOf(
            "APP_ENV" to "local",
            "OPS_MODE_SOURCE" to "file:///unused",
            "MODEL_STORE_URI" to store.toUri().toString(),
            "MODEL_SIGNING_KEY" to key,
            "DB_URL" to "jdbc:mysql://127.0.0.1:1/jrabot",
            "DB_USER" to "mltrain",
            "DB_PASSWORD" to "x",
        ) + over

    private fun run(
        args: List<String>,
        e: Map<String, String>,
        mode: OpsMode = OpsMode.NORMAL,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val l =
            Launcher(
                log = JobLog(PrintStream(out, true, Charsets.UTF_8)),
                modeProviderFactory = { { mode } },
                dataSourceFactory = { fail("DB に接続してはいけない") },
            )
        return l.run(args, e) to out.toString(Charsets.UTF_8)
    }

    private val train = listOf("train", "--tenant-id", "1", "--dataset-id", "ds-1", "--config", cfg)

    private fun reason(log: String): String = Regex("\"reason\":\"([A-Z_0-9]+)\"").find(log.lines().last { "JOB_RESULT" in it })?.groupValues?.get(1) ?: ""

    @Test
    fun `usage errors exit 2 without touching anything`() {
        for (
        args in
        listOf(
            emptyList(),
            listOf("nonsense"),
            listOf("train"),
            listOf("train", "--tenant-id", "0", "--dataset-id", "d", "--config", cfg),
            listOf("train", "--tenant-id", "1", "--dataset-id", "../d", "--config", cfg),
            listOf("train", "--tenant-id", "1", "--dataset-id", "d", "--config", cfg, "--unknown", "x"),
            listOf("train", "--tenant-id", "1", "--tenant-id", "1", "--dataset-id", "d", "--config", cfg),
            listOf("train", "--tenant-id", "1", "--dataset-id", "d", "--config", "/nonexistent.json"),
        )
        ) {
            assertEquals(ExitCode.USAGE, run(args, env()).first, args.toString())
        }
    }

    @Test
    fun `configuration is validated before the mode and the db`() {
        for ((over, want) in listOf(
            mapOf("MODEL_SIGNING_KEY" to "") to "MODEL_SIGNING_KEY_MISSING",
            mapOf("APP_ENV" to "staging") to "APP_ENV_INVALID",
            mapOf("MODEL_STORE_URI" to "") to "MODEL_STORE_URI_MISSING",
            mapOf("MODEL_STORE_URI" to "http://x") to "MODEL_STORE_URI_INVALID",
            mapOf("MODEL_STORE_URI" to "file:///nonexistent-dir-xyz") to "MODEL_STORE_URI_INVALID",
            mapOf("DB_PASSWORD" to "") to "DB_ENV_MISSING",
            mapOf("APP_ENV" to "prod", "DB_USER" to "bot", "OPS_MODE_SOURCE" to "gs://b/o") to "DB_USER_INVALID",
            mapOf("MODEL_VERIFY_PUBKEY" to Base64.getEncoder().encodeToString(ByteArray(32))) to "SIGNING_KEY_NOT_TRUSTED",
        )) {
            val (code, log) = run(train, env(over))
            assertEquals(ExitCode.USAGE, code, "$over: $log")
            assertEquals(want, reason(log), over.toString())
        }
    }

    @Test
    fun `non normal modes skip with exit 0 and unknown exits 75, both before any db connection`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val (code, log) = run(train, env(), m)
            assertEquals(0 to "MODE_${m.name}", code to reason(log))
        }
        val (code, log) = run(train, env(), OpsMode.UNKNOWN)
        assertEquals(75 to "MODE_UNKNOWN", code to reason(log))
    }

    @Test
    fun `gs store uris are accepted without network access at configuration time`() {
        val (code, log) = run(train, env(mapOf("MODEL_STORE_URI" to "gs://fake-bucket/prefix")), OpsMode.STOPPED)
        assertEquals(0 to "MODE_STOPPED", code to reason(log))
        assertEquals("MODEL_STORE_URI_INVALID", assertFailsWith<UsageError> { Launcher.openStore("gs://UPPER/x", "prod") }.reason)
        assertEquals("MODEL_STORE_URI_INVALID", assertFailsWith<UsageError> { Launcher.openStore(dir.toString(), "prod") }.reason, "bare path は local だけ")
    }

    @Test
    fun `db errors are classified with the old vocabulary`() {
        val transient = Launcher.classifyDbError(RuntimeException(SQLTransientConnectionException("x", "08S01")))
        assertEquals("DB_UNAVAILABLE" to ExitCode.TEMPFAIL, transient.reason to transient.exitCode)
        val auth = Launcher.classifyDbError(RuntimeException(SQLException("denied", "42000", 1142)))
        assertEquals("DB_AUTH_FAILED" to ExitCode.FAILURE, auth.reason to auth.exitCode)
        val lock = Launcher.classifyDbError(RuntimeException(SQLException("deadlock", "40001", 1213)))
        assertEquals(ExitCode.TEMPFAIL, lock.exitCode)
        val other = Launcher.classifyDbError(IllegalStateException("bug"))
        assertEquals("DB_ERROR" to ExitCode.FAILURE, other.reason to other.exitCode)
    }

    @Test
    fun `the log never contains the signing key`() {
        val (_, log) = run(train, env(), OpsMode.STOPPED)
        assertTrue(key !in log)
    }
}
