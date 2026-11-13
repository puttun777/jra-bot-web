package jp.jrabot.bot

import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.ops.Workload
import jp.jrabot.domain.ops.WorkloadStatus
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AWS 構成の起動まわり（D-282・D-283）: 起動目的の柵（閲覧用の起動では bot-run・calendar-check・通知が始まらない）、spool への起動依頼、
 * 設定の検証、calendar-plan の引数。DB・Spring には触れない（`execute` が呼ばれた回数で確かめる）。
 */
class AwsLaunchTest {
    private class Mode(
        private val mode: OpsMode,
        private val w: WorkloadStatus,
    ) : ModeProvider {
        override fun current(): OpsMode = mode

        override fun workloads(): WorkloadStatus = w
    }

    private fun launcher(
        provider: ModeProvider,
        executed: AtomicInteger,
        log: RecordingLogger = RecordingLogger(),
    ) = BotLauncher(modeProviderFactory = { provider }, execute = {
        executed.incrementAndGet()
        JobResult(JobExit.OK, "EXECUTED")
    }, log = log)

    private val view = WorkloadStatus.Specified(raceday = false, batch = false)
    private val raceday = WorkloadStatus.Specified(raceday = true, batch = false)
    private val batch = WorkloadStatus.Specified(raceday = false, batch = true)

    private fun gated(vararg extra: Pair<String, String?>) = baseEnv("/dev/null", "OPS_WORKLOAD_GATE" to "required", *extra)

    @Test
    fun `閲覧用の起動（raceday=false）では bot-run・calendar-check・reconcile・retention を始めず終了コード 0（DB にも進まない）`() {
        for (task in listOf("bot-run", "calendar-check", "reconcile", "retention")) {
            val n = AtomicInteger()
            val log = RecordingLogger()
            assertEquals(0, launcher(Mode(OpsMode.NORMAL, view), n, log).run(args(task), gated()), task)
            assertEquals(0, n.get(), "$task は実行されない")
            assertTrue(log.text().contains("reason=WORKLOAD_RACEDAY_DISABLED"), log.text())
        }
    }

    @Test
    fun `重い処理は batch のときだけ、開催日の処理は raceday のときだけ。calendar-plan は閲覧用でも動く`() {
        val n = AtomicInteger()
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, raceday), n).run(args("bot-run"), gated()))
        assertEquals(1, n.get())
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, raceday), n).run(args("backtest"), gated()))
        assertEquals(1, n.get(), "開催日の起動中に backtest を始めない（メモリ）")
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, batch), n).run(args("backtest"), gated()))
        assertEquals(2, n.get())
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, batch), n).run(args("bot-run"), gated()))
        assertEquals(2, n.get())
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, view), n).run(args("calendar-plan"), gated()))
        assertEquals(3, n.get())
    }

    @Test
    fun `柵が有効なのに workloads が無い・確認できないときは 75（許可に倒さない）。柵が無効（GCP）なら従来どおり`() {
        val n = AtomicInteger()
        assertEquals(75, launcher(Mode(OpsMode.NORMAL, WorkloadStatus.Unspecified), n).run(args("bot-run"), gated()))
        assertEquals(75, launcher(Mode(OpsMode.NORMAL, WorkloadStatus.Unknown), n).run(args("calendar-check"), gated()))
        assertEquals(0, n.get())
        assertEquals(0, launcher(Mode(OpsMode.NORMAL, WorkloadStatus.Unspecified), n).run(args("bot-run"), baseEnv("/dev/null")))
        assertEquals(1, n.get())
        assertEquals(0, launcher(Mode(OpsMode.STOPPED, raceday), n).run(args("bot-run"), gated()))
        assertEquals(1, n.get(), "モードの判定が先（STOPPED は 0 で何もしない）")
    }

    @Test
    fun `WorkloadGate の判定表`() {
        assertEquals(JobGate.Proceed, WorkloadGate.decide(Workload.RACEDAY, view, required = false))
        assertEquals(JobGate.Proceed, WorkloadGate.decide(Workload.ANY, WorkloadStatus.Unknown, required = true))
        assertIs<JobGate.Skip>(WorkloadGate.decide(Workload.BATCH, raceday, required = true))
        assertEquals("WORKLOAD_BATCH_DISABLED", (WorkloadGate.decide(Workload.BATCH, raceday, required = true) as JobGate.Skip).result.reason)
    }

    @Test
    fun `設定（JOB_LAUNCHER=spool は JOB_SPOOL_DIR が必須で、本番の calendar-check でも RUN_JOBS が要らない。不正な値は 2）`() {
        val env = baseEnv("/dev/null", "APP_ENV" to "prod", "JOB_LAUNCHER" to "spool", "JOB_SPOOL_DIR" to "/var/spool/jrabot")
        val s = BotSettings.from(env, BotTask.CALENDAR_CHECK)
        assertEquals(Path.of("/var/spool/jrabot"), s.spoolDir)
        assertEquals(null, s.runJobs)
        assertFailsWith<IllegalStateException> { BotSettings.from(env + ("JOB_SPOOL_DIR" to "relative/dir"), BotTask.CALENDAR_CHECK) }
        assertFailsWith<IllegalStateException> { BotSettings.from(env - "JOB_SPOOL_DIR", BotTask.CALENDAR_CHECK) }
        assertFailsWith<IllegalStateException> { BotSettings.from(env + ("JOB_LAUNCHER" to "docker"), BotTask.CALENDAR_CHECK) }
        assertFailsWith<IllegalStateException> { BotSettings.from(env + ("OPS_WORKLOAD_GATE" to "yes"), BotTask.CALENDAR_CHECK) }
        assertTrue(BotSettings.from(env + ("OPS_WORKLOAD_GATE" to "required"), BotTask.CALENDAR_CHECK).workloadGate)
        // GCP の本番は従来どおり RUN_JOBS が必須
        assertFailsWith<IllegalStateException> { BotSettings.from(env - "JOB_LAUNCHER", BotTask.CALENDAR_CHECK) }
        // export-dataset は s3:// の保管先を受け付ける
        assertEquals("s3://jrabot-models", BotSettings.from(env + ("MODEL_STORE_URI" to "s3://jrabot-models"), BotTask.EXPORT_DATASET).modelStoreUri)
    }

    @Test
    fun `spool への起動依頼は 1 件 1 ファイルで、書きかけを見せない（一時ファイルは残らない）`(
        @TempDir dir: Path,
    ) {
        val l = SpoolJobLauncher(dir, clock = { Instant.parse("2026-11-15T00:30:00Z") })
        val r = l.launch(JobLaunchRequest(TenantId.of(1), LaunchableJob.BOT_RUN, mapOf("trigger" to "SCHEDULER", "date" to "2026-11-15")))
        val id = assertIs<LaunchResult.Launched>(r).executionId
        val files = dir.listDirectoryEntries()
        assertEquals(listOf("$id.json"), files.map { it.fileName.toString() })
        assertEquals(
            """{"schema_version":1,"job":"bot-run","tenant_id":1,"params":{"date":"2026-11-15","trigger":"SCHEDULER"},"requested_at":"2026-11-15T00:30:00Z"}""",
            files.single().readText(),
        )
        assertEquals(LaunchResult.Failed("JOB_UNSUPPORTED", transient = false), l.launch(JobLaunchRequest(TenantId.of(1), LaunchableJob.ML_TRAIN, emptyMap())))
        val gone = SpoolJobLauncher(dir.resolve("missing"))
        val failed = assertIs<LaunchResult.Failed>(gone.launch(JobLaunchRequest(TenantId.of(1), LaunchableJob.BOT_RUN, emptyMap())))
        assertTrue(failed.transient)
        assertEquals(1, Files.list(dir).use { it.count() })
    }

    @Test
    fun `calendar-plan の引数（--out は正規化された絶対パスの json、--days は 1〜60）`() {
        val ok =
            assertIs<ParsedArgs.Valid>(BotArgsParser.parse(arrayOf("--task=calendar-plan", "--tenant-id=1", "--out=/var/spool/jrabot/plan.json", "--days=30")))
        assertEquals(PlanArgs(30, Path.of("/var/spool/jrabot/plan.json")), ok.command.plan)
        assertEquals(
            PlanArgs.DEFAULT_DAYS,
            assertNotNull((BotArgsParser.parse(arrayOf("--task=calendar-plan", "--tenant-id=1", "--out=/x/p.json")) as ParsedArgs.Valid).command.plan).days,
        )
        for (bad in listOf("--out=relative.json", "--out=/x/../p.json", "--out=/x/p.txt", "--days=0", "--days=61", "--date=2026-01-01")) {
            val more = if (bad.startsWith("--out")) arrayOf(bad) else arrayOf("--out=/x/p.json", bad)
            assertIs<ParsedArgs.Invalid>(BotArgsParser.parse(arrayOf("--task=calendar-plan", "--tenant-id=1", *more)), bad)
        }
        assertIs<ParsedArgs.Invalid>(BotArgsParser.parse(arrayOf("--task=calendar-plan", "--tenant-id=1")))
    }
}
