package jp.jrabot.bot

import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.persistence.PersistenceConfiguration
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.schema.AppBuildInfo
import jp.jrabot.persistence.schema.SchemaVersionVerifier
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
    @Bean
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

    /** Stage 3 で、タスクごとの実装に差し替える。現在はすべてスタブ（[StubTaskHandler]）。 */
    @Bean
    fun botTaskHandler(runtime: BotRuntime): BotTaskHandler = StubTaskHandler(runtime.settings.appEnv)

    @Bean
    fun botJobExecutor(
        runtime: BotRuntime,
        verifier: SchemaVersionVerifier,
        handler: BotTaskHandler,
    ): BotJobExecutor = BotJobExecutor(runtime.command, verifier, handler)
}
