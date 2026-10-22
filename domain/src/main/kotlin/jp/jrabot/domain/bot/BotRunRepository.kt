package jp.jrabot.domain.bot

import jp.jrabot.domain.repository.UpdateResult
import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate

interface BotRunReader {
    fun find(
        tenantId: TenantId,
        runId: RunId,
    ): BotRun?

    /** その日に排他（`active_slot = 1`）を持つ実行。無ければ null。 */
    fun findActive(
        tenantId: TenantId,
        runDate: LocalDate,
    ): BotRun?

    /** 開始の新しい順に最大 [limit] 件（1〜200）。 */
    fun listRecent(
        tenantId: TenantId,
        limit: Int,
    ): List<BotRun>
}

sealed interface StartResult {
    /** 排他を取得して開始した。 */
    data class Started(
        val run: BotRun,
    ) : StartResult

    /** 同じ `(tenantId, runDate)` で既に実行中。[activeRunId] がその実行。二重起動は起動しないのが正しい結果（異常ではない）。 */
    data class AlreadyActive(
        val activeRunId: RunId,
    ) : StartResult
}

/** bot の実行の書込み。二重実行の最終防壁は DB の UNIQUE 制約（アプリ内のチェックだけに頼らない）。 */
interface BotRunWriter {
    /**
     * 排他（その日に実行中の行は 1 つだけ）を原子的に取得して挿入する。[BotRun.state] は [BotRunState.holdsActiveSlot] が true でなければならない。
     * 期限切れリースの奪取（前の実行を ABORTED にする）は T3.3 で、このポートに操作を足す。
     */
    fun tryStart(run: BotRun): StartResult

    /** 楽観ロック更新（heartbeat・checkpoint・状態遷移）。`run.version` が保存値と一致するときだけ成功する。 */
    fun update(run: BotRun): UpdateResult<BotRun>
}

interface BotRunRepository :
    BotRunReader,
    BotRunWriter
