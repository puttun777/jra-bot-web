package jp.jrabot.bot

import jp.jrabot.domain.botrun.ReconcileReport
import jp.jrabot.domain.botrun.ReconcileService
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * `--task=reconcile`（T3.9）。取り込み済みの結果から、結果待ちの締切までに終えられなかった開催日・異常終了で残った開催日を回復する（[ReconcileService]）。
 * 終了コード: 処理した（対象が無い場合を含む）= 0（`RECONCILED`）、DB に接続できない = 75（`DB_UNAVAILABLE`）。通知は送らない。
 */
class ReconcileTaskHandler(
    private val service: ReconcileService,
    private val clock: jp.jrabot.domain.time.Clock,
    private val log: Logger = LoggerFactory.getLogger(ReconcileTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val anchor = command.date ?: clock.today()
        val report: ReconcileReport =
            try {
                service.reconcile(command.tenantId, anchor)
            } catch (e: Exception) {
                if (DbFailures.isConnectionFailure(e)) {
                    log.error("RECONCILE db-unavailable tenant={} error={}", command.tenantId.value, e.javaClass.simpleName)
                    return JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE")
                }
                throw e
            }
        for (d in report.days) log.info("RECONCILE_DAY tenant={} date={} outcome={} unsettled={}", report.tenantId.value, d.date, d.outcome, d.unsettled)
        log.info("RECONCILE_DONE tenant={} days={} completed={}", report.tenantId.value, report.days.size, report.completed)
        return JobResult(JobExit.OK, "RECONCILED")
    }
}
