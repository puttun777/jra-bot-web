package jp.jrabot.persistence.repository

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.bot.StartResult
import jp.jrabot.domain.repository.UpdateResult
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.BotRunDao
import jp.jrabot.persistence.dao.BotRunEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDate

internal class BotRunRepositoryImpl(
    private val dao: BotRunDao,
    private val tx: TransactionOperations,
) : BotRunRepository {
    override fun find(
        tenantId: TenantId,
        runId: RunId,
    ): BotRun? = dao.selectById(tenantId.value, runId.value)?.toDomain()

    override fun findActive(
        tenantId: TenantId,
        runDate: LocalDate,
    ): BotRun? = dao.selectActive(tenantId.value, runDate)?.toDomain()

    override fun listRecent(
        tenantId: TenantId,
        limit: Int,
    ): List<BotRun> {
        require(limit in 1..MAX_RECENT) { "limit は 1〜$MAX_RECENT: $limit" }
        return dao.selectRecent(tenantId.value, limit).map { it.toDomain() }
    }

    /**
     * 排他は DB の `UNIQUE(tenant_id, run_date, active_slot)` が最終防壁（アプリ内のロックは使わない）。
     * 挿入が **この UNIQUE 制約**（1062・`uq_bot_run_active`）で拒否されたときだけ [StartResult.AlreadyActive] にする。
     * 主キー（run_id）の重複や FK・CHECK 違反などの他のエラーはそのまま投げる。
     */
    override fun tryStart(run: BotRun): StartResult {
        require(run.state.holdsActiveSlot) { "tryStart の state は実行中の状態(STARTING/RUNNING/DRAINING): ${run.state}" }
        require(run.version == 0) { "新規の実行の version は 0: ${run.version}" }
        val entity = run.toEntity()
        return tx.inTransaction {
            repeat(START_ATTEMPTS) {
                try {
                    dao.insert(entity)
                    return@inTransaction StartResult.Started(checkNotNull(dao.selectById(entity.tenantId, entity.runId)).toDomain())
                } catch (e: RuntimeException) {
                    if (!e.isDuplicateOn("bot_run", ACTIVE_KEY)) throw e
                }
                // 勝った実行の ID を返す。読めなければ（勝った実行が直後に終わった）もう一度挿入を試みる。
                dao.selectActive(entity.tenantId, entity.runDate)?.let { return@inTransaction StartResult.AlreadyActive(RunId.parse(it.runId)) }
            }
            error("排他の取得に失敗した（重複と判定されたが有効な実行が読めない）。呼び出し側のトランザクションが長すぎないか確認する")
        }
    }

    /**
     * 楽観ロック更新。`run.version` が保存値と一致するときだけ更新して `version` を 1 進める。
     * 0 件のときは読み直して、行が無ければ [UpdateResult.NotFound]（別テナントの run_id も含む）、あれば [UpdateResult.Conflict]。
     * 実行中の状態へ戻す更新が、同じ日の別の実行の排他と衝突したとき（1062・`uq_bot_run_active`）も [UpdateResult.Conflict]。
     * 変更できるのは状態・リース・heartbeat・checkpoint・exitReason・finishedAt。実行中でない状態では `active_slot` を NULL にして排他を解放する。
     */
    override fun update(run: BotRun): UpdateResult<BotRun> {
        val entity = run.toEntity()
        return tx.inTransaction {
            val count =
                try {
                    dao.updateIfVersion(entity).count
                } catch (e: RuntimeException) {
                    if (e.isDuplicateOn("bot_run", ACTIVE_KEY)) return@inTransaction UpdateResult.Conflict
                    throw e
                }
            val current = dao.selectById(entity.tenantId, entity.runId)
            when {
                count == 1 -> UpdateResult.Updated(checkNotNull(current).toDomain())
                current == null -> UpdateResult.NotFound
                else -> UpdateResult.Conflict
            }
        }
    }

    private fun BotRun.toEntity(): BotRunEntity =
        BotRunEntity(
            tenantId = tenantId.value,
            runId = runId.value,
            runDate = runDate,
            triggerType = trigger.name,
            state = state.name,
            leaseOwner = leaseOwner,
            leaseExpiresAt = leaseExpiresAt.toDbTimeOrNull(),
            heartbeatAt = heartbeatAt.toDbTimeOrNull(),
            activeSlot = if (state.holdsActiveSlot) ACTIVE_SLOT else null,
            checkpoint = checkpoint,
            exitReason = exitReason,
            startedAt = startedAt.toDbTime(),
            finishedAt = finishedAt.toDbTimeOrNull(),
            version = version,
        )

    private companion object {
        const val ACTIVE_KEY = "uq_bot_run_active"
        const val ACTIVE_SLOT = 1
        const val MAX_RECENT = 200
        const val START_ATTEMPTS = 3
    }
}

internal fun BotRunEntity.toDomain(): BotRun =
    BotRun(
        tenantId = TenantId.of(tenantId),
        runId = RunId.parse(runId),
        runDate = runDate,
        trigger = enumFromDb<BotRunTrigger>(triggerType, "bot_run.trigger_type"),
        state = enumFromDb<BotRunState>(state, "bot_run.state"),
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt.toInstantUtcOrNull(),
        heartbeatAt = heartbeatAt.toInstantUtcOrNull(),
        checkpoint = checkpoint,
        startedAt = startedAt.toInstantUtc(),
        finishedAt = finishedAt.toInstantUtcOrNull(),
        exitReason = exitReason,
        version = version,
    )
