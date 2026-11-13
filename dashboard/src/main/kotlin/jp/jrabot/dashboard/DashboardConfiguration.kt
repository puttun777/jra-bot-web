package jp.jrabot.dashboard

import jp.jrabot.cloudaws.auth.CognitoIdTokenVerifier
import jp.jrabot.cloudaws.ops.S3OpsStateSources
import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.ops.GcsOpsStateSources
import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.CalendarOverrideService
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
import jp.jrabot.platform.RuntimePlatform
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.PersonVerifier
import jp.jrabot.platform.auth.StaticTokenVerifier
import jp.jrabot.platform.auth.TokenHeader
import jp.jrabot.platform.ops.OpsModeProviders
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
    ): InspectableModeProvider = OpsModeProviders.fromEnvironment(settings.opsEnv, clock, listOf(GcsOpsStateSources, S3OpsStateSources))

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
        HumanAuthorizer(
            settings.allowedUsers,
            settings.adminUsers,
            users,
            if (settings.platform == RuntimePlatform.GCP) listOfNotNull(EmailAddress.ofOrNull(settings.opsServiceAccountEmail)) else emptyList(),
        )

    /**
     * IAP の JWT の検証器。**1 つの Bean を 2 つの用途で使う**: 人の利用者（`/api`: [PersonVerifier]）と内部 API の呼び出し元（`/internal`: [InternalCallerVerifier]。
     * aud=IAP_AUDIENCE、email=OPS_SA_EMAIL）。型の違う 2 つの Bean に分けると、同じ具象型が 2 つ見つかって注入が曖昧になり、**本番の起動に失敗する**
     * （試験は @Primary の差し替えで隠れていた: DashboardDefaultWiringTest が防ぐ）。テストは [GoogleSignedTokenVerifier] の Bean を @Primary で差し替える。
     */
    @Bean
    @ConditionalOnProperty(name = [RuntimePlatform.ENV], havingValue = "gcp", matchIfMissing = true)
    fun iapVerifier(
        settings: DashboardSettings,
        clock: Clock,
    ): GoogleSignedTokenVerifier = GoogleSignedTokenVerifier.iap(settings.iapAudience, settings.opsServiceAccountEmail, clock)

    /**
     * AWS（D-281）: 人は Cognito の ID トークン（前段の oauth2-proxy が `Authorization: Bearer` で渡す。**署名を検証する**）、`/internal` は
     * 同じホストの運用エージェントのトークン。型の違う 2 つの Bean（どちらも片方のインターフェースだけを実装する）なので注入は曖昧にならない。
     */
    @Bean
    @ConditionalOnProperty(name = [RuntimePlatform.ENV], havingValue = "aws")
    fun cognitoPersonVerifier(
        settings: DashboardSettings,
        clock: Clock,
    ): PersonVerifier {
        val c = checkNotNull(settings.cognito) { "COGNITO_* が無い（DashboardSettings が検証しているはず）" }
        return CognitoIdTokenVerifier(c.issuer, c.clientId, clock)
    }

    @Bean
    @ConditionalOnProperty(name = [RuntimePlatform.ENV], havingValue = "aws")
    fun opsTokenVerifier(settings: DashboardSettings): InternalCallerVerifier =
        StaticTokenVerifier.fromFile(checkNotNull(settings.internalOpsTokenFile) { "INTERNAL_OPS_TOKEN_FILE が無い（DashboardSettings が検証しているはず）" })

    /** 人・内部 API のトークンを読むヘッダー（GCP: IAP の JWT、AWS: `Authorization: Bearer`）。 */
    @Bean
    fun authHeaders(settings: DashboardSettings): AuthHeaders =
        when (settings.platform) {
            RuntimePlatform.GCP -> AuthHeaders(TokenHeader.IAP_ASSERTION, TokenHeader.IAP_ASSERTION)
            RuntimePlatform.AWS -> AuthHeaders(TokenHeader.AUTHORIZATION_BEARER, TokenHeader.AUTHORIZATION_BEARER)
        }

    /** 紙上成績の読取り（保存せず毎回計算: D-234）。`app` ユーザーの SELECT だけで動く。 */
    @Bean
    fun performanceReadService(
        schedules: RaceScheduleReader,
        signals: SignalReader,
        timings: TimingNoticeStore,
        results: ResultStore,
        payouts: PayoutStore,
    ): PerformanceReadService = PerformanceReadService(schedules, signals, timings, results, payouts)

    /** 手動補正（管理 API が管理者の認可・監査記録の後に呼ぶ。ドメインのサービスは認可をしない）。 */
    @Bean
    fun calendarOverrideService(
        overrides: CalendarOverrideRepository,
        schedules: RaceScheduleReader,
        clock: Clock,
    ): CalendarOverrideService = CalendarOverrideService(overrides, schedules, clock)
}

/** 人（`/api`）と内部 API（`/internal`）のトークンの取り出し元（D-281）。 */
data class AuthHeaders(
    val person: TokenHeader,
    val internal: TokenHeader,
)
