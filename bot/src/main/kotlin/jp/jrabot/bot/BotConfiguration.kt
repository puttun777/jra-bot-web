package jp.jrabot.bot

import jp.jrabot.bot.discord.DiscordNotifier
import jp.jrabot.bot.discord.DiscordSettings
import jp.jrabot.bot.discord.JdkWebhookTransport
import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.calendar.CalendarCheckService
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.Notifier
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.ComboOddsSnapshotRepository
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.persistence.PersistenceConfiguration
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.schema.AppBuildInfo
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import javax.sql.DataSource

/**
 * :bot の Bean 構成（T2.8）。この構成は**モード確認を通った後**（[BotLauncher]）にだけ読み込まれる。DataSource は遅延
 * （[DataSourceFactory.createLazy]）で、コンテキストの初期化では DB に接続しない。[BotRuntime] は起動前に登録される。
 * Flyway は使わない（マイグレーションは :db-migration だけ）。
 */
@Configuration(proxyBeanMethods = false)
@Import(PersistenceConfiguration::class)
class BotConfiguration {
    /** 時計・起動基盤・取得の事実は、テストが同名の型の Bean を先に登録すれば差し替えられる（本番の配線は下の既定）。 */
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = SystemClock

    @Bean
    fun modeProvider(runtime: BotRuntime): ModeProvider = runtime.modeProvider

    @Bean
    fun appBuildInfo(runtime: BotRuntime): AppBuildInfo = AppBuildInfo.load(runtime.settings.releaseIdOverride)

    @Bean
    fun dataSource(runtime: BotRuntime): DataSource = DataSourceFactory.createLazy(runtime.settings.db, "bot-${runtime.command.task.cliName}")

    @Bean
    fun schemaVersionVerifier(
        dataSource: DataSource,
        info: AppBuildInfo,
    ): SchemaVersionVerifier = SchemaVersionVerifier(dataSource, info.requiredSchemaVersion)

    /**
     * 起動基盤の**本番の配線は未設定（fail closed）**: [UnconfiguredJobLauncher] は起動できたことにしない。GCP の実装（Cloud Run Jobs）は T5 系で置き換える。
     * テスト用の Fake はテストコードにだけある。
     */
    @Bean
    @ConditionalOnMissingBean(JobLauncher::class)
    fun jobLauncher(): JobLauncher = UnconfiguredJobLauncher()

    // 取得の事実（ScheduleFetchFactReader）は :persistence の `scheduleFetchFactReader`（DB の ingest_fetch。T3.4）。
    // DB に事実が無い日は null（= 行の無い日は「不明」のまま。開催なしを確認したことにしない）。

    /** 停止要求の旗。SIGTERM のハンドラの設置は [BotRunTaskHandler]（T3.8）。立っている間は新しいサイクルも通知の新規送信も始めない。 */
    @Bean
    fun stopFlag(): StopFlag = StopFlag()

    /**
     * Discord 通知（T3.7）。宛先は `DISCORD_WEBHOOK_URL` / `DISCORD_ERROR_WEBHOOK_URL`（`bot-run` のときだけ読む。未設定の宛先へは送らない）。
     * 実際の送信は bot-run（T3.8）が [SignalNotificationDispatcher] / [ErrorReporter] を呼んだときだけで、**他のタスクは通知を送らない**。
     */
    @Bean
    @ConditionalOnMissingBean(Notifier::class)
    fun notifier(
        runtime: BotRuntime,
        stop: StopFlag,
    ): Notifier {
        val d = runtime.settings.discord ?: DiscordSettings.NONE
        return DiscordNotifier(d.signalUrl, d.errorUrl, d.mention, JdkWebhookTransport(), stopRequested = stop::isRequested)
    }

    @Bean
    fun signalNotificationDispatcher(
        runtime: BotRuntime,
        signals: SignalRepository,
        notifier: Notifier,
        clock: Clock,
        stop: StopFlag,
    ): SignalNotificationDispatcher =
        SignalNotificationDispatcher(
            signals,
            notifier,
            runtime.modeProvider,
            clock,
            (runtime.settings.discord?.mention ?: MentionPolicy.NONE),
            stopRequested = stop::isRequested,
        )

    @Bean
    fun errorReporter(
        notifier: Notifier,
        clock: Clock,
    ): ErrorReporter = ErrorReporter(notifier, clock)

    /**
     * タスクごとの実装。**calendar-check（T3.3）と export-dataset（T3.6-B）が実装済み**で、他（bot-run・reconcile・retention）は未実装のスタブ
     * （[StubTaskHandler]。T3.8 以降で差し替える）。
     */
    @Bean
    fun botTaskHandler(
        runtime: BotRuntime,
        clock: Clock,
        days: RaceDayRepository,
        schedules: RaceScheduleRepository,
        overrides: CalendarOverrideRepository,
        runs: BotRunRepository,
        facts: ScheduleFetchFactReader,
        launcher: JobLauncher,
        features: FeatureSource,
        datasets: DatasetSource,
        odds: OddsSnapshotRepository,
        combos: ComboOddsSnapshotRepository,
        races: RaceRepository,
        evaluations: RaceEvaluationWriter,
        entries: RaceEntryReader,
        signals: SignalRepository,
        registry: ModelRegistryReader,
        dispatcher: SignalNotificationDispatcher,
        errors: ErrorReporter,
        stop: StopFlag,
    ): BotTaskHandler =
        when (runtime.command.task) {
            BotTask.CALENDAR_CHECK -> {
                val settings = checkNotNull(runtime.settings.calendar) { "calendar-check の設定が無い（BotSettings が検証しているはず）" }
                CalendarCheckTaskHandler(CalendarCheckService(clock, runtime.modeProvider, days, schedules, overrides, facts, runs, launcher, settings))
            }

            BotTask.EXPORT_DATASET -> {
                val uri = checkNotNull(runtime.settings.modelStoreUri) { "export-dataset の保管先が無い（BotSettings が検証しているはず）" }
                ExportDatasetTaskHandler(features, datasets, DatasetStoreFactory.create(uri), clock)
            }

            BotTask.BOT_RUN -> {
                val settings = checkNotNull(runtime.settings.botRun) { "bot-run の設定が無い（BotSettings が検証しているはず）" }
                BotRunTaskHandler(
                    orchestratorFactory = {
                        BotRunWiring.orchestrator(
                            settings, clock, runtime.modeProvider, runs, days, schedules, overrides, facts,
                            odds, combos, races, evaluations, entries, signals, registry, features, dispatcher, errors, stop,
                        )
                    },
                    clock = clock,
                    stop = stop,
                    signals = SignalInstaller.SIGTERM,
                )
            }

            else -> {
                StubTaskHandler(runtime.settings.appEnv)
            }
        }

    @Bean
    fun botJobExecutor(
        runtime: BotRuntime,
        verifier: SchemaVersionVerifier,
        handler: BotTaskHandler,
    ): BotJobExecutor = BotJobExecutor(runtime.command, verifier, handler)
}
