package jp.jrabot.dbmigration

import java.io.PrintStream

/**
 * `db-migration --mode=info|validate|migrate`（CONTRACTS §4.2）。
 * 標準出力には**機械可読の JSON を 1 行**だけ出し、診断（エラーの詳細）は標準エラー出力へ分ける。
 * 秘密（パスワード）はどちらにも出さない。終了コードは [ExitCodes]。
 */
class Cli(
    private val engineFactory: (DbConfig) -> MigrationEngine = { FlywayEngine(it) },
) {
    fun run(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val mode =
            when (val parsed = CliArgs.parse(args)) {
                is ParsedArgs.Invalid -> return usageError(err, parsed.message)
                is ParsedArgs.Ok -> parsed.mode
            }
        val config =
            when (val result = DbConfig.fromEnv(env)) {
                is ConfigResult.Invalid -> return usageError(err, result.message)
                is ConfigResult.Ok -> result.config
            }
        return try {
            execute(mode, engineFactory(config), out)
        } catch (e: Exception) {
            val temporary = ErrorClassifier.isTemporary(e)
            err.println("db-migration: ${if (temporary) "一時的な失敗" else "失敗"} (${mode.argValue}): ${describe(e, config)}")
            out.println(Json.obj("mode" to mode.argValue, "error" to if (temporary) "unavailable" else "failed"))
            if (temporary) ExitCodes.TEMPORARY_FAILURE else ExitCodes.FAILURE
        } finally {
            out.flush()
            err.flush()
        }
    }

    private fun execute(
        mode: Mode,
        engine: MigrationEngine,
        out: PrintStream,
    ): Int =
        when (mode) {
            Mode.INFO -> {
                out.println(infoJson(engine.info()))
                ExitCodes.OK
            }

            Mode.VALIDATE -> {
                val report = engine.validate()
                out.println(validateJson(report))
                when {
                    !report.valid -> ExitCodes.FAILURE
                    report.info.pending.isNotEmpty() -> ExitCodes.PENDING
                    else -> ExitCodes.OK
                }
            }

            Mode.MIGRATE -> {
                val report = engine.migrate()
                out.println(migrateJson(report))
                ExitCodes.OK
            }
        }

    private fun usageError(
        err: PrintStream,
        message: String,
    ): Int {
        err.println("db-migration: $message")
        err.println(CliArgs.USAGE)
        err.flush()
        return ExitCodes.USAGE
    }

    /** 例外の連鎖を `クラス名: メッセージ` で最大 4 件。秘密は伏せる。スタックトレースは出さない。 */
    private fun describe(
        error: Throwable,
        config: DbConfig,
    ): String =
        ErrorClassifier
            .causeChain(error)
            .take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().lineSequence().firstOrNull().orEmpty()}" }
            .let { Redactor.redact(it, config.secrets()) }

    companion object {
        fun infoJson(info: InfoReport): String =
            Json.obj("current" to info.current, "pending" to info.pending, "failed" to info.failed, "latest" to info.latest)

        fun validateJson(report: ValidateReport): String =
            Json.obj(
                "valid" to report.valid,
                "current" to report.info.current,
                "pending" to report.info.pending,
                "failed" to report.info.failed,
                "latest" to report.info.latest,
                "errors" to report.errors,
            )

        fun migrateJson(report: MigrateReport): String =
            Json.obj(
                "success" to true,
                "applied" to report.applied,
                "current" to report.info.current,
                "pending" to report.info.pending,
                "failed" to report.info.failed,
                "latest" to report.info.latest,
            )
    }
}
