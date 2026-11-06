package jp.jrabot.bot

import jp.jrabot.cloudgcp.ops.OpsModeConfigurationException
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Job 起動の順序と終了コード（CONTRACTS §5.3・§9）。モードの確認までに DB・Spring のコンテキストが作られないことは、
 * `execute`（= Spring の起動と DB の確認）が呼ばれない回数で確かめる。
 */
class BotLauncherTest {
    private class Harness(
        mode: OpsMode = OpsMode.NORMAL,
        factoryFails: Boolean = false,
        private val executeResult: () -> JobResult = { JobResult(JobExit.OK, "EXECUTED") },
    ) {
        val provider = FixedMode(mode)
        val factoryCalls = AtomicInteger()
        val executeCalls = AtomicInteger()
        var runtime: BotRuntime? = null
        val log = RecordingLogger()
        val launcher =
            BotLauncher(
                modeProviderFactory = {
                    factoryCalls.incrementAndGet()
                    if (factoryFails) throw OpsModeConfigurationException("OPS_MODE_SOURCE が未設定")
                    provider as ModeProvider
                },
                execute = {
                    executeCalls.incrementAndGet()
                    runtime = it
                    executeResult()
                },
                log = log,
            )
    }

    private val env = baseEnv("/dev/null")

    @Test
    fun `calendar-check の設定（監視ウィンドウ・鮮度）が無ければ終了コード 2 で、DB にもモードの確認にも進まない（既定値を置かない）`() {
        for (name in listOf("CALENDAR_WATCH_START_MINUTES", "CALENDAR_PRESTART_MINUTES", "CALENDAR_SETTLE_DEADLINE_MINUTES", "CALENDAR_STALE_AFTER_MINUTES")) {
            val h = Harness(OpsMode.NORMAL) { JobResult(JobExit.OK, "EXECUTED") }
            assertEquals(2, h.launcher.run(args("calendar-check"), baseEnv("/dev/null", name to null)), name)
            assertEquals(0, h.executeCalls.get(), name)
            assertEquals(0, h.provider.calls.get(), "設定の検証はモード確認の前")
            assertTrue(h.log.text().contains("INVALID_CONFIGURATION") && h.log.text().contains(name), h.log.text())
        }
        // 他のタスクは calendar の設定が無くても動く
        val other = Harness(OpsMode.NORMAL) { JobResult(JobExit.OK, "EXECUTED") }
        assertEquals(0, other.launcher.run(args("retention"), baseEnv("/dev/null", "CALENDAR_WATCH_START_MINUTES" to null)))
        // reconcile は締切の計算に監視ウィンドウの設定が必要（T3.9）
        val reconcile = Harness(OpsMode.NORMAL) { JobResult(JobExit.OK, "EXECUTED") }
        assertEquals(2, reconcile.launcher.run(args("reconcile"), baseEnv("/dev/null", "CALENDAR_SETTLE_DEADLINE_MINUTES" to null)))
    }

    @Test
    fun `NORMAL ならスキーマ確認とタスクの実行へ進み、その結果の終了コードを返す`() {
        val h = Harness(OpsMode.NORMAL) { JobResult(JobExit.OK, "EXECUTED") }
        assertEquals(0, h.launcher.run(args("calendar-check"), env))
        assertEquals(1, h.executeCalls.get())
        assertEquals(BotTask.CALENDAR_CHECK, h.runtime!!.command.task)
        assertEquals(
            1L,
            h.runtime!!
                .command.tenantId.value,
        )
        assertEquals(1, h.provider.calls.get(), "モードは Job の開始時に 1 回だけ確認する")
        assertTrue(h.log.text().contains("JOB_RESULT task=calendar-check tenant=1 exit=0 reason=EXECUTED"), h.log.text())
    }

    @Test
    fun `STARTING STOPPING STOPPED は何もせず終了コード 0 で、実行（DB・Spring）に進まない`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val h = Harness(m)
            assertEquals(0, h.launcher.run(args("reconcile"), env), "$m")
            assertEquals(0, h.executeCalls.get(), "$m で実行に進んだ")
            assertTrue(h.log.text().contains("exit=0 reason=MODE_${m.name}"), h.log.text())
        }
    }

    @Test
    fun `UNKNOWN は終了コード 75 で、NORMAL 扱いにせず実行に進まない`() {
        val h = Harness(OpsMode.UNKNOWN)
        assertEquals(75, h.launcher.run(args("retention"), env))
        assertEquals(0, h.executeCalls.get())
        assertTrue(h.log.text().contains("exit=75 reason=MODE_UNKNOWN"), h.log.text())
    }

    @Test
    fun `全タスクで、非 NORMAL のモードでは実行に進まない`() {
        for (t in BotTask.entries) {
            for ((m, expected) in listOf(OpsMode.STOPPED to 0, OpsMode.UNKNOWN to 75)) {
                val h = Harness(m)
                assertEquals(expected, h.launcher.run(args(t.cliName), env), "${t.cliName} $m")
                assertEquals(0, h.executeCalls.get(), "${t.cliName} $m")
            }
        }
    }

    @Test
    fun `引数が不正なら終了コード 2 で、設定もモードも見ない`() {
        for (bad in listOf(
            arrayOf(),
            arrayOf("--tenant-id=1"),
            args("nope"),
            arrayOf("--task=reconcile"),
            args("reconcile", "0"),
            args("reconcile", "1", "--bogus=1"),
        )) {
            val h = Harness(OpsMode.NORMAL)
            assertEquals(2, h.launcher.run(bad, env), bad.joinToString(" "))
            assertEquals(0, h.factoryCalls.get())
            assertEquals(0, h.provider.calls.get())
            assertEquals(0, h.executeCalls.get())
        }
    }

    @Test
    fun `必須の環境変数が足りない・不正なら終了コード 2 で、モードも見ない`() {
        for (key in listOf("APP_ENV", "DB_URL", "DB_USER", "DB_PASSWORD")) {
            val h = Harness(OpsMode.NORMAL)
            assertEquals(2, h.launcher.run(args(), baseEnv("/dev/null", key to null)), key)
            assertEquals(0, h.provider.calls.get(), key)
            assertEquals(0, h.executeCalls.get(), key)
            assertTrue(h.log.text().contains(key), "$key が欠けていることがログで分かる: ${h.log.text()}")
        }
        for ((key, value) in listOf("APP_ENV" to "production", "DB_POOL_MAX" to "0", "DB_POOL_MAX" to "x", "DB_URL" to "postgres://x")) {
            val h = Harness(OpsMode.NORMAL)
            assertEquals(2, h.launcher.run(args(), baseEnv("/dev/null", key to value)), "$key=$value")
            assertEquals(0, h.executeCalls.get())
        }
    }

    @Test
    fun `OPS_MODE_SOURCE の設定不正は終了コード 2 で、実行に進まない`() {
        val h = Harness(factoryFails = true)
        assertEquals(2, h.launcher.run(args(), env))
        assertEquals(0, h.executeCalls.get())
    }

    @Test
    fun `本番(APP_ENV=prod)では bot 以外の DB ユーザー(migrate など)では実行しない`() {
        for (user in listOf("migrate", "app", "ingest", "root")) {
            val h = Harness(OpsMode.NORMAL)
            assertEquals(2, h.launcher.run(args(), baseEnv("gs://synthetic/ops.json", "APP_ENV" to "prod", "DB_USER" to user)), user)
            assertEquals(0, h.executeCalls.get())
        }
        val ok = Harness(OpsMode.NORMAL)
        assertEquals(
            0,
            ok.launcher.run(
                args(),
                baseEnv(
                    "gs://synthetic/ops.json",
                    "APP_ENV" to "prod",
                    "DB_USER" to "bot",
                    "RUN_JOBS_PROJECT_ID" to "jra-bot-test1",
                    "RUN_JOBS_REGION" to "asia-northeast1",
                ),
            ),
        )
    }

    @Test
    fun `本番の calendar-check は Cloud Run Jobs の起動先が無ければ 2 で実行しない ローカルは任意`() {
        val h = Harness(OpsMode.NORMAL)
        assertEquals(2, h.launcher.run(args(), baseEnv("gs://synthetic/ops.json", "APP_ENV" to "prod", "DB_USER" to "bot")))
        assertEquals(0, h.executeCalls.get())
        assertEquals(0, Harness(OpsMode.NORMAL).launcher.run(args(), baseEnv("/dev/null")))
        // 本番でも calendar-check 以外は起動先を要求しない
        assertEquals(0, Harness(OpsMode.NORMAL).launcher.run(args("retention"), baseEnv("gs://synthetic/ops.json", "APP_ENV" to "prod", "DB_USER" to "bot")))
    }

    @Test
    fun `起動先の設定は形式を検査し 片方だけの指定を拒否する`() {
        fun parse(vararg kv: Pair<String, String>) = RunJobsSettings.fromEnv(mapOf(*kv), jp.jrabot.domain.ops.AppEnv.LOCAL)
        assertEquals(null, parse())
        val ok = parse("RUN_JOBS_PROJECT_ID" to "jra-bot-test1", "RUN_JOBS_REGION" to "asia-northeast1")!!
        assertEquals("jra-bot-bot-run", ok.jobNames().getValue(jp.jrabot.domain.bot.LaunchableJob.BOT_RUN))
        assertEquals("jra-bot-calendar-check", ok.jobNames().getValue(jp.jrabot.domain.bot.LaunchableJob.CALENDAR_CHECK))
        kotlin.test.assertFailsWith<IllegalStateException> { parse("RUN_JOBS_PROJECT_ID" to "jra-bot-test1") }
        kotlin.test.assertFailsWith<IllegalStateException> { parse("RUN_JOBS_PROJECT_ID" to "BAD_ID", "RUN_JOBS_REGION" to "asia-northeast1") }
        kotlin.test.assertFailsWith<IllegalStateException> { parse("RUN_JOBS_PROJECT_ID" to "jra-bot-test1", "RUN_JOBS_REGION" to "x") }
    }

    @Test
    fun `プール上限は task の既定値で、DB_POOL_MAX で上書きできる`() {
        val cases = mapOf("bot-run" to 4, "calendar-check" to 1, "reconcile" to 1, "retention" to 1, "export-dataset" to 2, "backtest" to 1)
        for ((task, expected) in cases) {
            val h = Harness()
            h.launcher.run(args(task), env)
            assertEquals(
                expected,
                h.runtime!!
                    .settings.db.poolMax,
                task,
            )
        }
        val h = Harness()
        h.launcher.run(args("bot-run"), baseEnv("/dev/null", "DB_POOL_MAX" to "2"))
        assertEquals(
            2,
            h.runtime!!
                .settings.db.poolMax,
        )
    }

    @Test
    fun `想定外の例外は 1（75 にしない）で、例外のメッセージ(接続情報を含み得る)をログに出さない`() {
        val h = Harness(executeResult = { throw IllegalStateException("jdbc:mysql://secret-host/db password=hunter2") })
        assertEquals(1, h.launcher.run(args(), env))
        assertTrue(h.log.text().contains("reason=UNEXPECTED_ERROR error=IllegalStateException"), h.log.text())
        assertFalse(h.log.text().contains("hunter2") || h.log.text().contains("secret-host"))
    }

    @Test
    fun `ログにパスワードや DB の URL を出さない`() {
        val h = Harness()
        h.launcher.run(args(), env)
        assertFalse(h.log.text().contains("synthetic-password-never-logged"))
        assertFalse(h.log.text().contains("jdbc:mysql"))
    }

    @Test
    fun `同じ引数で繰り返し実行しても起動の仕組みは状態を持たない（二重実行の防止は業務側のリース）`() {
        val h = Harness()
        repeat(3) { assertEquals(0, h.launcher.run(args("bot-run"), env)) }
        assertEquals(3, h.executeCalls.get())
    }
}
