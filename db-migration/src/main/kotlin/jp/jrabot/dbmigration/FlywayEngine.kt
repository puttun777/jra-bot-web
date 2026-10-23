package jp.jrabot.dbmigration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState

/**
 * Flyway による実装。設定は CONTRACTS §4.2 で固定した値だけで、呼び出し側から変えられない
 * （`validateOnMigrate=true`、`outOfOrder=false`、`baselineOnMigrate=false`、`cleanDisabled=true`、`group=true`）。
 * 接続のたびにセッションの時刻を UTC にする（CONTRACTS §8.1）。`repair` は呼ばない（失敗は人が判断する）。
 */
class FlywayEngine(
    private val config: DbConfig,
    private val locations: List<String> = DEFAULT_LOCATIONS,
) : MigrationEngine {
    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(config.effectiveUrl(), config.user, config.password)
            .locations(*locations.toTypedArray())
            .validateOnMigrate(true)
            .outOfOrder(false)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .group(true)
            .connectRetries(0)
            .initSql("SET time_zone = '+00:00'")
            // 未適用は不整合ではない（validate は 10 で返す）。適用済みより新しい版を持つ DB（旧イメージでの検証）も許す。
            .ignoreMigrationPatterns("*:pending", "*:future")
            .load()

    override fun info(): InfoReport = report(flyway().info().all().toList())

    override fun validate(): ValidateReport {
        val flyway = flyway()
        val result = flyway.validateWithResult()
        val errors =
            result.invalidMigrations
                .map { "${it.version.orEmpty()} ${it.description.orEmpty()}: ${it.errorDetails?.errorMessage.orEmpty()}".trim() }
                .toMutableList()
        if (!result.validationSuccessful && errors.isEmpty()) errors += result.errorDetails?.errorMessage ?: "検証に失敗しました"
        val info = report(flyway.info().all().toList())
        return ValidateReport(valid = result.validationSuccessful && info.failed.isEmpty(), info = info, errors = errors)
    }

    override fun migrate(): MigrateReport {
        val flyway = flyway()
        val result = flyway.migrate()
        check(result.success) { "マイグレーションが成功扱いになりませんでした" }
        val applied = result.migrations.map { label(it.version, it.description) }
        return MigrateReport(applied = applied, info = report(flyway.info().all().toList()))
    }

    private fun report(all: List<MigrationInfo>): InfoReport {
        val versioned = all.filter { it.version != null }
        val current =
            versioned
                .filter { it.state.isApplied && !it.state.isFailed }
                .maxByOrNull { it.version }
        val latest = versioned.filter { it.state.isResolved }.maxByOrNull { it.version }
        return InfoReport(
            current = current?.version?.toString(),
            latest = latest?.version?.toString(),
            pending = all.filter { it.state == MigrationState.PENDING || it.state == MigrationState.OUTDATED }.map { label(it) }.distinct(),
            failed = all.filter { it.state.isFailed }.map { label(it) },
        )
    }

    private fun label(info: MigrationInfo): String = label(info.version?.toString(), info.description)

    private fun label(
        version: String?,
        description: String?,
    ): String = if (version.isNullOrEmpty()) "R__${description.orEmpty()}" else version

    companion object {
        val DEFAULT_LOCATIONS = listOf("classpath:db/migration")
    }
}
