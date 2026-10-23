package jp.jrabot.bot

import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.calendar.CalendarCheckService
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.NoScheduleFetchFacts
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.race.RaceScheduleRepository
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

    /** 取得の事実は T3.4/T3.5 の実装まで存在しない（行の無い日は「不明」のまま。開催なしを確認したことにしない）。 */
    @Bean
    @ConditionalOnMissingBean(ScheduleFetchFactReader::class)
    fun scheduleFetchFacts(): ScheduleFetchFactReader = NoScheduleFetchFacts

    /**
     * タスクごとの実装。**calendar-check（T3.3）だけが実装済み**で、他（bot-run・reconcile・retention・export-dataset）は未実装のスタブ
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
    ): BotTaskHandler =
        when (runtime.command.task) {
            BotTask.CALENDAR_CHECK -> {
                val settings = checkNotNull(runtime.settings.calendar) { "calendar-check の設定が無い（BotSettings が検証しているはず）" }
                CalendarCheckTaskHandler(CalendarCheckService(clock, runtime.modeProvider, days, schedules, overrides, facts, runs, launcher, settings))
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
