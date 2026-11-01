package jp.jrabot.dashboard

import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.performance.PerformanceReadService
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.signal.SignalReader
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.HumanAuthorizer
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.persistence.PersistenceConfiguration
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.schema.AppBuildInfo
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * :dashboard の Bean 構成（T2.8）。DB は [DataSourceFactory.createLazy]（起動時に接続しない）、運用モードは
 * `OpsModeProviders.fromEnvironment`（`gs://` は GCS、ローカルファイルは `APP_ENV=local` のときだけ。本番で設定が足りなければ起動失敗で、
 * ローカル実装へ黙ってフォールバックしない）。Flyway は使わない（マイグレーションは :db-migration だけ）。
 */
@Configuration(proxyBeanMethods = false)
@Import(PersistenceConfiguration::class)
class DashboardConfiguration {
    @Bean
    fun clock(): Clock = SystemClock

    @Bean
    fun dashboardSettings(env: Environment): DashboardSettings = DashboardSettings.from { env.getProperty(it) }

    @Bean
    fun appBuildInfo(settings: DashboardSettings): AppBuildInfo = AppBuildInfo.load(settings.releaseIdOverride)

    @Bean
    fun dataSource(settings: DashboardSettings): DataSource = DataSourceFactory.createLazy(settings.db, "dashboard")

    @Bean
    fun modeProvider(
        settings: DashboardSettings,
        clock: Clock,
    ): InspectableModeProvider = OpsModeProviders.fromEnvironment(settings.opsEnv, clock)

    @Bean
    fun schemaVersionVerifier(
        dataSource: DataSource,
        info: AppBuildInfo,
    ): SchemaVersionVerifier = SchemaVersionVerifier(dataSource, info.requiredSchemaVersion)

    @Bean
    fun humanAuthorizer(
        settings: DashboardSettings,
        users: AppUserRepository,
    ): HumanAuthorizer =
        HumanAuthorizer(settings.allowedUsers, settings.adminUsers, users, listOfNotNull(EmailAddress.ofOrNull(settings.opsServiceAccountEmail)))

    /**
     * IAP の JWT の検証器。**1 つの Bean を 2 つの用途で使う**: 人の利用者（`/api`: [PersonVerifier]）と内部 API の呼び出し元（`/internal`: [InternalCallerVerifier]。
     * aud=IAP_AUDIENCE、email=OPS_SA_EMAIL）。型の違う 2 つの Bean に分けると、同じ具象型が 2 つ見つかって注入が曖昧になり、**本番の起動に失敗する**
     * （試験は @Primary の差し替えで隠れていた: DashboardDefaultWiringTest が防ぐ）。テストは [GoogleSignedTokenVerifier] の Bean を @Primary で差し替える。
     */
    @Bean
    fun iapVerifier(
        settings: DashboardSettings,
        clock: Clock,
    ): GoogleSignedTokenVerifier = GoogleSignedTokenVerifier.iap(settings.iapAudience, settings.opsServiceAccountEmail, clock)

    /** 紙上成績の読取り（保存せず毎回計算: D-234）。`app` ユーザーの SELECT だけで動く。 */
    @Bean
    fun performanceReadService(
        schedules: RaceScheduleReader,
        signals: SignalReader,
        timings: TimingNoticeStore,
        results: ResultStore,
        payouts: PayoutStore,
    ): PerformanceReadService = PerformanceReadService(schedules, signals, timings, results, payouts)
}
