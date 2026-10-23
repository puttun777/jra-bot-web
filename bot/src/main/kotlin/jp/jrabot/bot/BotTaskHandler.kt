package jp.jrabot.bot

import jp.jrabot.domain.ops.AppEnv
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 1 つの Job タスクの実行（スキーマ版の確認が済んだ後に呼ばれる）。実装は冪等にし（CONTRACTS §9.2）、二重実行の防止は
 * 業務側のリース（`bot_run` の UNIQUE。bot-run のみ、Stage 3）に任せる。起動の仕組み（[BotLauncher]）は排他をしない
 * ＝再実行・同時実行が起き得る前提で、将来の二重実行防止と矛盾しない。
 */
fun interface BotTaskHandler {
    fun execute(command: BotCommand): JobResult
}

/**
 * **業務処理は未実装のスタブ**（T2.8）。Job の仕組み（引数 → モード → スキーマ版 → 実行 → 終了コード）が繋がることだけを確かめる。
 * 運用の Job の完成として扱わない。
 *
 * 本番(`APP_ENV=prod`)で未実装の処理が正常終了に見えないよう、**終了コード 1・理由 `TASK_NOT_IMPLEMENTED`** で終える（通知される）。
 * 成功(0)として終えるのはローカル(`APP_ENV=local`)だけ（開発・テストでスタブの配線を確かめるため）。
 */
class StubTaskHandler(
    private val appEnv: AppEnv,
    private val log: Logger = LoggerFactory.getLogger(StubTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        log.warn("TASK_NOT_IMPLEMENTED task={} tenant={}: この Job の業務処理は未実装のスタブです（何も処理していません）", command.task.cliName, command.tenantId.value)
        return if (appEnv == AppEnv.LOCAL) {
            JobResult(JobExit.OK, "STUB_LOCAL_OK")
        } else {
            JobResult(JobExit.FAILURE, "TASK_NOT_IMPLEMENTED")
        }
    }
}
