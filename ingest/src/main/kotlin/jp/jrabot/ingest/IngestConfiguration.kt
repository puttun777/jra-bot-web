package jp.jrabot.ingest

import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.ops.InspectableModeProvider
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
 * :ingest の Bean 構成（T2.8）。DB は [DataSourceFactory.createLazy]（起動時に接続しない）、運用モードは
 * `OpsModeProviders.fromEnvironment`（`gs://` は GCS、ローカルファイルは `APP_ENV=local` のときだけ。本番で設定が足りなければ起動失敗で、
 * ローカル実装へ黙ってフォールバックしない）。Flyway は使わない（マイグレーションは :db-migration だけ）。
 */
@Configuration(proxyBeanMethods = false)
@Import(PersistenceConfiguration::class)
class IngestConfiguration {
    @Bean
    fun clock(): Clock = SystemClock

    @Bean
    fun ingestSettings(env: Environment): IngestSettings = IngestSettings.from { env.getProperty(it) }

    @Bean
    fun appBuildInfo(settings: IngestSettings): AppBuildInfo = AppBuildInfo.load(settings.releaseIdOverride)

    @Bean
    fun dataSource(settings: IngestSettings): DataSource = DataSourceFactory.createLazy(settings.db, "ingest")

    @Bean
    fun modeProvider(
        settings: IngestSettings,
        clock: Clock,
    ): InspectableModeProvider = OpsModeProviders.fromEnvironment(settings.opsEnv, clock)

    @Bean
    fun schemaVersionVerifier(
        dataSource: DataSource,
        info: AppBuildInfo,
    ): SchemaVersionVerifier = SchemaVersionVerifier(dataSource, info.requiredSchemaVersion)

    /** `/internal` の呼び出し元の検証（CONTRACTS §11.2: Google ID トークン、aud=INTERNAL_ID_TOKEN_AUDIENCE、email=OPS_SA_EMAIL）。 */
    @Bean
    fun internalCallerVerifier(
        settings: IngestSettings,
        clock: Clock,
    ): InternalCallerVerifier = GoogleSignedTokenVerifier.googleIdToken(settings.internalAudience, settings.opsServiceAccountEmail, clock)
}
