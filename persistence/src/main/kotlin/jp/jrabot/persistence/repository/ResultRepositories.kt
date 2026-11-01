package jp.jrabot.persistence.repository

import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.PayoutDao
import jp.jrabot.persistence.dao.PayoutRowEntity
import jp.jrabot.persistence.dao.ResultDao
import jp.jrabot.persistence.dao.ResultRowEntity
import jp.jrabot.persistence.mapping.toDbTime
import org.springframework.transaction.support.TransactionOperations

private fun aggregate(counts: List<Int>): UpsertOutcome {
    val outcomes = counts.map { upsertOutcomeOf(it) }
    return when {
        UpsertOutcome.UPDATED in outcomes -> UpsertOutcome.UPDATED
        UpsertOutcome.CREATED in outcomes -> UpsertOutcome.CREATED
        else -> UpsertOutcome.UNCHANGED
    }
}

/** `results`。親のレースが（このテナントに）無ければ FK 違反で失敗する（呼び出し側が先にレースの有無を確認する）。 */
internal class ResultStoreImpl(
    private val dao: ResultDao,
    private val tx: TransactionOperations,
    private val clock: Clock,
) : ResultStore {
    override fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        entries: List<ResultEntry>,
    ): UpsertOutcome {
        val now = clock.now().toDbTime()
        val rows = entries.sortedBy { it.umaban }.map { ResultRowEntity(tenantId.value, raceId.value, it.umaban, it.finish, null, now, now) }
        return aggregate(tx.inTransaction { dao.upsertAll(rows).counts }.toList())
    }

    override fun list(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<ResultEntry> = dao.selectByRace(tenantId.value, raceId.value).map { ResultEntry(it.umaban, it.finish) }
}

/** `payouts`。確定の行を速報で上書きしない（SQL の条件付き更新）。 */
internal class PayoutStoreImpl(
    private val dao: PayoutDao,
    private val tx: TransactionOperations,
    private val clock: Clock,
) : PayoutStore {
    override fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
        rows: List<PayoutRow>,
        provisional: Boolean,
    ): UpsertOutcome {
        val now = clock.now().toDbTime()
        val entities =
            rows.sortedBy { it.comboKey }.map {
                PayoutRowEntity(
                    tenantId.value,
                    raceId.value,
                    market.code,
                    it.comboKey,
                    it.payout,
                    provisional,
                    now,
                    now,
                )
            }
        return aggregate(tx.inTransaction { dao.upsertAll(entities).counts }.toList())
    }

    override fun list(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<StoredPayout> = dao.selectByRaceMarket(tenantId.value, raceId.value, market.code).map { StoredPayout(it.comboKey, it.payout, it.isProvisional) }
}
