package jp.jrabot.domain.ingest

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId

/** 保存済みの払戻 1 行。 */
data class StoredPayout(
    val comboKey: String,
    val payout: Double,
    val provisional: Boolean,
)

/**
 * 着順の保存先（`results`）。**取込（ingest）が書き、決済（bot）は読むだけ**（権限もこの分担: R__grants.sql）。同じ馬番は上書き（着順は事実の訂正があり得る）。
 * 戻り値は行ごとの結果の集約: 1 行でも作成/更新があれば [UpsertOutcome.CREATED]/[UpsertOutcome.UPDATED]、すべて同じなら [UpsertOutcome.UNCHANGED]。
 */
interface ResultStore {
    fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        entries: List<ResultEntry>,
    ): UpsertOutcome

    fun list(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<ResultEntry>
}

/**
 * 払戻の保存先（`payouts`）。**確定（`is_provisional = 0`）の行を速報で上書きしない**。確定→確定で値が違えば上書き（JRA の訂正）。
 * 速報→確定は確定に置き換える。
 */
interface PayoutStore {
    fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
        rows: List<PayoutRow>,
        provisional: Boolean,
    ): UpsertOutcome

    fun list(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<StoredPayout>
}
