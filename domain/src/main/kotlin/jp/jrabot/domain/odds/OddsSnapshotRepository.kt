package jp.jrabot.domain.odds

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId

/** オッズの読取り。すべての操作が [TenantId] と [RaceId] を必須とし、レースをまたぐ全件取得は提供しない（最大の増加源のため）。 */
interface OddsSnapshotReader {
    /** 観測時刻が最も新しいスナップショット。無ければ null。 */
    fun latest(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): OddsSnapshot?

    /** 観測時刻の昇順。無ければ空。 */
    fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<OddsSnapshot>
}

/** オッズの書込み（ingest・移行）。 */
interface OddsSnapshotWriter {
    /**
     * 自然キー `(tenantId, raceId, observedAt, market, 馬番)` の upsert。冪等: 同じ観測を再送しても行は増えず、
     * 同じ内容なら [UpsertOutcome.UNCHANGED]。取込の batch 単位の冪等（`batchId` / `record_hash`）は :ingest（T3.4）。
     */
    fun upsert(snapshot: OddsSnapshot): UpsertOutcome
}

interface OddsSnapshotRepository :
    OddsSnapshotReader,
    OddsSnapshotWriter
