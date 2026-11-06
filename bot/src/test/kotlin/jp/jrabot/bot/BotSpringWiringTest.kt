package jp.jrabot.bot

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** NORMAL と判定された後の Spring のコンテキスト（DI・Doma・DataSource）。DB は接続を受け付けないポート。実 DB・実 GCP には接続しない。 */
class BotSpringWiringTest {
    private val ops = writeOpsState(Files.createTempDirectory("bot-wiring"))

    private fun runtime(
        task: String = "bot-run",
        mode: ModeProvider = FixedMode(OpsMode.NORMAL),
    ): BotRuntime {
        val command = (BotArgsParser.parse(args(task)) as ParsedArgs.Valid).command
        return BotRuntime(command, BotSettings.from(baseEnv(ops.toString()), command.task), mode)
    }

    private fun start(rt: BotRuntime): ConfigurableApplicationContext =
        SpringApplicationBuilder(BotApplication::class.java)
            .web(WebApplicationType.NONE)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { it.beanFactory.registerSingleton("botRuntime", rt) })
            .run()

    @Test
    fun `DI が解決され、Repository・ModeProvider・スキーマ検証が Bean になる（DataSource と Doma が接続される）`() {
        val mode = FixedMode(OpsMode.NORMAL)
        start(runtime(mode = mode)).use { ctx ->
            for (type in listOf(
                RaceRepository::class.java,
                OddsSnapshotRepository::class.java,
                BotRunRepository::class.java,
                AppUserRepository::class.java,
                RaceScheduleRepository::class.java,
                SignalRepository::class.java,
                RaceDayRepository::class.java,
                ScheduleEventRepository::class.java,
                CalendarOverrideRepository::class.java,
            )) {
                assertNotNull(ctx.getBean(type), type.simpleName)
            }
            assertSame(mode, ctx.getBean(ModeProvider::class.java), "モード確認に使ったものと同じインスタンス")
            assertEquals("5", ctx.getBean(SchemaVersionVerifier::class.java).required.toString())
            assertTrue(ctx.getBeansOfType(Class.forName("org.seasar.doma.jdbc.Config")).isNotEmpty())
            assertNotNull(ctx.getBean(BotJobExecutor::class.java))
        }
    }

    @Test
    fun `タスクごとのハンドラ（calendar-check・export-dataset・bot-run が実装済み、reconcile・retention も実装済み。スタブは残っていない）と、本番の起動基盤・取得の事実の配線は fail closed`() {
        start(runtime("calendar-check")).use { ctx ->
            assertIs<CalendarCheckTaskHandler>(ctx.getBean(BotTaskHandler::class.java))
            assertIs<UnconfiguredJobLauncher>(ctx.getBean(JobLauncher::class.java), "起動基盤の本番の配線は未設定（fail closed）")
            assertEquals(
                "DbScheduleFetchFactReader",
                ctx.getBean(ScheduleFetchFactReader::class.java)::class.simpleName,
                "取得の事実は DB の ingest_fetch から読む（合成の FixedScheduleFetchFacts や、常に null の NoScheduleFetchFacts ではない）",
            )
        }
        start(runtime("retention")).use { ctx -> assertIs<RetentionTaskHandler>(ctx.getBean(BotTaskHandler::class.java), "retention") }
        start(runtime("reconcile")).use { ctx -> assertIs<ReconcileTaskHandler>(ctx.getBean(BotTaskHandler::class.java)) }
        start(runtime("bot-run")).use { ctx -> assertIs<BotRunTaskHandler>(ctx.getBean(BotTaskHandler::class.java)) }
        start(runtime("export-dataset")).use { ctx -> assertIs<ExportDatasetTaskHandler>(ctx.getBean(BotTaskHandler::class.java)) }
        start(runtime("backtest")).use { ctx -> assertIs<BacktestTaskHandler>(ctx.getBean(BotTaskHandler::class.java)) }
    }

    @Test
    fun `Discord 通知の Bean が配線され、設定が無ければ宛先なし（送らない）`() {
        start(runtime()).use { ctx ->
            val notifier = ctx.getBean(jp.jrabot.domain.notify.Notifier::class.java)
            assertIs<jp.jrabot.bot.discord.DiscordNotifier>(notifier)
            assertFalse(notifier.canDeliver(jp.jrabot.domain.notify.NotificationChannel.SIGNAL))
            assertFalse(notifier.canDeliver(jp.jrabot.domain.notify.NotificationChannel.ERROR))
            assertNotNull(ctx.getBean(jp.jrabot.domain.notify.SignalNotificationDispatcher::class.java))
            assertNotNull(ctx.getBean(jp.jrabot.domain.notify.ErrorReporter::class.java))
            assertFalse(ctx.getBean(jp.jrabot.bot.discord.StopFlag::class.java).isRequested())
        }
    }

    @Test
    fun `Discord の URL が設定されていれば bot-run の Notifier は送信可能になる（送信はしない）`() {
        val env = baseEnv(ops.toString()) + ("DISCORD_WEBHOOK_URL" to "https://discord.com/api/webhooks/123456789012345678/AbC-dEf_123")
        val command = (BotArgsParser.parse(args("bot-run")) as ParsedArgs.Valid).command
        val rt = BotRuntime(command, BotSettings.from(env, command.task), FixedMode(OpsMode.NORMAL))
        start(rt).use { ctx ->
            val notifier = ctx.getBean(jp.jrabot.domain.notify.Notifier::class.java)
            assertTrue(notifier.canDeliver(jp.jrabot.domain.notify.NotificationChannel.SIGNAL))
            assertFalse(notifier.canDeliver(jp.jrabot.domain.notify.NotificationChannel.ERROR))
        }
    }

    @Test
    fun `コンテキストの初期化では DB に接続しない（プールは最初の接続まで起動しない）`() {
        start(runtime()).use { ctx ->
            val ds = ctx.getBean(javax.sql.DataSource::class.java) as HikariDataSource
            assertNull(ds.hikariPoolMXBean, "初期化でプールが接続を試みている")
            assertEquals(4, ds.maximumPoolSize, "bot-run のプール既定値は 4")
            assertEquals("bot", ds.username)
        }
    }

    @Test
    fun `Flyway は組み込まれていない`() {
        start(runtime()).use { ctx ->
            assertFalse(ctx.beanDefinitionNames.any { it.contains("flyway", ignoreCase = true) })
            assertTrue(runCatching { Class.forName("org.flywaydb.core.Flyway") }.isFailure)
        }
    }

    @Test
    fun `実際の Spring の実行(NORMAL、DB なし)は DB 不達の 75、スタブは動かない`() {
        val r = SpringJobExecutor.run(runtime("calendar-check"))
        assertEquals(JobResult(75, "DB_UNAVAILABLE"), r)
    }

    @Test
    fun `プール上限の既定値は task ごと`() {
        for ((task, max) in mapOf("calendar-check" to 1, "export-dataset" to 2)) {
            start(runtime(task)).use { ctx -> assertEquals(max, (ctx.getBean(javax.sql.DataSource::class.java) as HikariDataSource).maximumPoolSize, task) }
        }
    }
}
