package jp.jrabot.persistence.repository

import jp.jrabot.domain.retention.RetentionStore
import jp.jrabot.domain.retention.RetentionTarget
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.RetentionDao
import jp.jrabot.persistence.mapping.toDbTime
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant

/** 件数は読取り、削除は 1 回ごとに独立したトランザクション（長いロックを避ける。冪等）。 */
internal class RetentionStoreImpl(
    private val dao: RetentionDao,
    private val tx: TransactionOperations,
) : RetentionStore {
    override fun count(
        tenantId: TenantId,
        target: RetentionTarget,
        before: Instant,
    ): Long {
        val t = before.toDbTime()
        return when (target) {
            RetentionTarget.COMBO_ODDS -> dao.countComboOdds(tenantId.value, t)
            RetentionTarget.INGEST_RAW -> dao.countIngestRaw(tenantId.value, t)
            RetentionTarget.SCHEDULE_EVENTS -> dao.countScheduleEvents(tenantId.value, t)
            RetentionTarget.BOT_RUNS -> dao.countBotRuns(tenantId.value, t)
        }
    }

    override fun delete(
        tenantId: TenantId,
        target: RetentionTarget,
        before: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..100_000) { "limit は 1〜100000" }
        val t = before.toDbTime()
        return tx.inTransaction {
            when (target) {
                RetentionTarget.COMBO_ODDS -> dao.deleteComboOdds(tenantId.value, t, limit)
                RetentionTarget.INGEST_RAW -> dao.deleteIngestRaw(tenantId.value, t, limit)
                RetentionTarget.SCHEDULE_EVENTS -> dao.deleteScheduleEvents(tenantId.value, t, limit)
                RetentionTarget.BOT_RUNS -> dao.deleteBotRuns(tenantId.value, t, limit)
            }
        }
    }
}
