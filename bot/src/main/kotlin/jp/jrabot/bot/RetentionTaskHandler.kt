package jp.jrabot.bot

import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.retention.RetentionPolicy
import jp.jrabot.domain.retention.RetentionService
import jp.jrabot.domain.retention.RetentionTarget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * retention の設定（`RETENTION_*`）。**すべて任意**: 保持日数の既定は DATA_MIGRATION_DESIGN §9.2 の案、`RETENTION_EXECUTE` の既定は **false（ドライラン）**。
 * 実データを削除するには本人の承認の上で `RETENTION_EXECUTE=true` を明示する（`--dry-run` を付ければ常にドライラン）。
 */
data class RetentionSettings(
    val policy: RetentionPolicy,
    val execute: Boolean,
) {
    companion object {
        private val NAMES =
            mapOf(
                RetentionTarget.COMBO_ODDS to "RETENTION_COMBO_ODDS_DAYS",
                RetentionTarget.INGEST_RAW to "RETENTION_INGEST_RAW_DAYS",
                RetentionTarget.SCHEDULE_EVENTS to "RETENTION_SCHEDULE_EVENT_DAYS",
                RetentionTarget.BOT_RUNS to "RETENTION_BOT_RUN_DAYS",
            )

        fun fromEnv(env: Map<String, String>): RetentionSettings {
            val defaults = RetentionPolicy().days
            val days =
                NAMES.entries.associate { (target, name) ->
                    val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() }
                    val v =
                        if (raw == null) {
                            defaults.getValue(target)
                        } else {
                            raw.toLongOrNull() ?: throw IllegalStateException("$name は整数で指定してください")
                        }
                    check(
                        v in RetentionPolicy.MIN_DAYS..RetentionPolicy.MAX_DAYS,
                    ) { "$name は ${RetentionPolicy.MIN_DAYS}〜${RetentionPolicy.MAX_DAYS} の範囲で指定してください" }
                    target to v
                }
            val execute =
                when (env["RETENTION_EXECUTE"]?.trim()?.lowercase()) {
                    null, "", "false" -> false
                    "true" -> true
                    else -> throw IllegalStateException("RETENTION_EXECUTE は true / false のいずれかです")
                }
            return RetentionSettings(RetentionPolicy(days), execute)
        }
    }
}

/**
 * `--task=retention`（F14/F16）。保持期間を過ぎた再生成可能な記録を消す（対象は [RetentionTarget]）。**既定はドライラン**（件数を数えるだけ）。
 * 終了コード: 完了（ドライラン含む）0（`RETENTION_DRY_RUN` / `RETENTION_DONE`）、上限や停止で打ち切り 0（`RETENTION_TRUNCATED`。次の実行で続きを消す）、
 * DB に接続できない 75（`DB_UNAVAILABLE`）。SIGTERM は区切りで止まる。
 */
class RetentionTaskHandler(
    private val settings: RetentionSettings,
    private val serviceFactory: (shouldStop: () -> Boolean) -> RetentionService,
    private val stop: StopFlag,
    private val signals: SignalInstaller,
    private val log: Logger = LoggerFactory.getLogger(RetentionTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val execute = settings.execute && !command.dryRun
        val restore = signals.install { stop.request() }
        val report =
            try {
                serviceFactory(stop::isRequested).run(command.tenantId, execute)
            } catch (e: Exception) {
                if (DbFailures.isConnectionFailure(e)) {
                    log.error("RETENTION db-unavailable tenant={} error={}", command.tenantId.value, e.javaClass.simpleName)
                    return JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE")
                }
                throw e
            } finally {
                restore.run()
            }
        for (l in report.lines) {
            log.info(
                "RETENTION_TARGET tenant={} table={} cutoff={} eligible={} deleted={} truncated={} dryRun={}",
                report.tenantId.value,
                l.target.table,
                l.cutoff,
                l.eligible,
                l.deleted,
                l.truncated,
                report.dryRun,
            )
        }
        return when {
            report.dryRun -> JobResult(JobExit.OK, "RETENTION_DRY_RUN")
            report.lines.any { it.truncated } -> JobResult(JobExit.OK, "RETENTION_TRUNCATED")
            else -> JobResult(JobExit.OK, "RETENTION_DONE")
        }
    }
}
