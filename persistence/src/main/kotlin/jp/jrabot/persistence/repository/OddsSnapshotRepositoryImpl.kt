package jp.jrabot.persistence.repository

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.OddsRowEntity
import jp.jrabot.persistence.dao.OddsSnapshotDao
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc
import org.springframework.transaction.support.TransactionOperations

internal class OddsSnapshotRepositoryImpl(
    private val dao: OddsSnapshotDao,
    private val tx: TransactionOperations,
) : OddsSnapshotRepository {
    override fun latest(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): OddsSnapshot? = dao.selectLatest(tenantId.value, raceId.value, market.code).takeIf { it.isNotEmpty() }?.toSnapshot()

    override fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<OddsSnapshot> =
        dao
            .selectHistory(tenantId.value, raceId.value, market.code)
            .groupBy { it.observedAt }
            .values
            .map { it.toSnapshot() }

    /**
     * スナップショット全体を 1 つのトランザクションで書く（途中で失敗したら 1 行も残らない）。行ごとの結果から、
     * 1 行でも内容が変わって更新されたら [UpsertOutcome.UPDATED]、そうでなく 1 行でも挿入されたら [UpsertOutcome.CREATED]、
     * 全行が同じ内容なら [UpsertOutcome.UNCHANGED]。親のレースが（このテナントに）無ければ FK 違反で失敗する。
     */
    override fun upsert(snapshot: OddsSnapshot): UpsertOutcome {
        val observedAt = snapshot.observedAt.toDbTime()
        val rows =
            snapshot.entries.map {
                OddsRowEntity(
                    tenantId = snapshot.tenantId.value,
                    raceId = snapshot.raceId.value,
                    market = snapshot.market.code,
                    observedAt = observedAt,
                    umaban = it.umaban.value,
                    odds = it.odds.value,
                    popularity = it.popularity,
                )
            }
        val counts = tx.inTransaction { dao.upsertAll(rows).counts }
        val outcomes = counts.map { upsertOutcomeOf(it) }
        return when {
            UpsertOutcome.UPDATED in outcomes -> UpsertOutcome.UPDATED
            UpsertOutcome.CREATED in outcomes -> UpsertOutcome.CREATED
            else -> UpsertOutcome.UNCHANGED
        }
    }

    /** 同じ観測時刻の行（呼び出し側が [OddsRowEntity.observedAt] でまとめたもの）から [OddsSnapshot] を作る。 */
    private fun List<OddsRowEntity>.toSnapshot(): OddsSnapshot {
        val first = first()
        return OddsSnapshot(
            tenantId = TenantId.of(first.tenantId),
            raceId = RaceId.parse(first.raceId),
            market = Market.fromCodeOrNull(first.market) ?: throw IllegalStateException("odds_snapshots.market '${first.market}' がドメインに無い"),
            observedAt = first.observedAt.toInstantUtc(),
            entries =
                sortedBy { it.umaban }.map {
                    OddsEntry(HorseNumber.of(it.umaban), Odds.of(it.odds), it.popularity)
                },
        )
    }
}
