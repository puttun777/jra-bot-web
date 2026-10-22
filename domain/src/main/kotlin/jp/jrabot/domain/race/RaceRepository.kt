package jp.jrabot.domain.race

import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate

/**
 * レースの読取り（dashboard・bot が使う）。すべての操作が [TenantId] を必須とし、他テナントのレースは返らない。
 * 戻り値はドメインのモデルで、DB の Entity は含まない。
 */
interface RaceReader {
    /** 見つからなければ null。 */
    fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): Race?

    /** 開催日（JST の暦日）のレースを、場コード・レース番号の昇順で返す。無ければ空。 */
    fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<Race>
}

/** レースの更新（ingest・移行が使う）。対象のテナントは引数の [Race.tenantId]。 */
interface RaceWriter {
    /**
     * `(tenantId, raceId)` を自然キーとする upsert（最後の書込みが勝つ）。冪等: 同じ内容の再実行は [UpsertOutcome.UNCHANGED]。
     * 取込の新旧判定（source_version の比較）は呼び出し側（RaceSchedule）で行う。
     */
    fun upsert(race: Race): UpsertOutcome
}

interface RaceRepository :
    RaceReader,
    RaceWriter
