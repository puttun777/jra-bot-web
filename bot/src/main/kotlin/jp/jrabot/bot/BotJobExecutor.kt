package jp.jrabot.bot

import jp.jrabot.persistence.schema.SchemaCheck
import jp.jrabot.persistence.schema.SchemaFailure
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * NORMAL と判定された Job の実行（CONTRACTS §4.3・§9）。**ここで初めて DB に触れる**（スキーマ版の確認。Flyway は実行しない）。
 *
 * | スキーマ版の確認 | 結果 |
 * |---|---|
 * | 必要な版以上（新しいのは許容） | タスクを実行 |
 * | 足りない（履歴なしを含む） | 終了コード 1（`SCHEMA_INSUFFICIENT`）。タスクは実行しない |
 * | DB に接続できない（一時障害） | 終了コード 75（`DB_UNAVAILABLE`） |
 * | 認証失敗・DB/権限の誤り・想定外 | 終了コード 1（`DB_AUTH_FAILED` / `DB_ERROR`）。**75 にしない**（再試行しても直らない） |
 */
class BotJobExecutor(
    private val command: BotCommand,
    private val schemaCheck: () -> SchemaCheck,
    private val handler: BotTaskHandler,
    private val log: Logger = LoggerFactory.getLogger(BotJobExecutor::class.java),
) {
    constructor(command: BotCommand, verifier: SchemaVersionVerifier, handler: BotTaskHandler) : this(command, verifier::check, handler)

    fun execute(): JobResult {
        log.info("JOB_PHASE schema-check task={}", command.task.cliName)
        return when (val check = schemaCheck()) {
            is SchemaCheck.Satisfied -> runTask()
            is SchemaCheck.Insufficient -> JobResult(JobExit.FAILURE, "SCHEMA_INSUFFICIENT")
            SchemaCheck.Unreachable -> JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE")
            is SchemaCheck.Failed -> JobResult(JobExit.FAILURE, if (check.reason == SchemaFailure.AUTHENTICATION) "DB_AUTH_FAILED" else "DB_ERROR")
        }
    }

    private fun runTask(): JobResult =
        try {
            handler.execute(command)
        } catch (e: Exception) {
            log.error("task failed: task={} error={}", command.task.cliName, e.javaClass.simpleName)
            JobResult(JobExit.FAILURE, "TASK_FAILED")
        }
}
