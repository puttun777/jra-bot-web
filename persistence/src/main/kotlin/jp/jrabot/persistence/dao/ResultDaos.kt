package jp.jrabot.persistence.dao

import org.seasar.doma.BatchInsert
import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.BatchResult
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDateTime

/** `results` の 1 行（主キーは tenant_id・race_id・umaban）。`payout`（旧の単勝倍率）は T3.9 では書かない（払戻は `payouts`）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "results")
internal data class ResultRowEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val umaban: Int,
    val finish: Int?,
    val payout: Double?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Dao
internal interface ResultDao {
    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<ResultRowEntity>

    /** 行ごとの更新件数: 1 = 挿入、2 = 更新、0 = 同じ内容（`useAffectedRows=true` が前提）。 */
    @BatchInsert(sqlFile = true)
    fun upsertAll(rows: List<ResultRowEntity>): BatchResult<ResultRowEntity>
}

/** `payouts` の 1 行（主キーは tenant_id・race_id・market・combo_key）。`payout` は 100 円あたりの円。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "payouts")
internal data class PayoutRowEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val market: String,
    @Id val comboKey: String,
    val payout: Double,
    val isProvisional: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Dao
internal interface PayoutDao {
    @Select
    fun selectByRaceMarket(
        tenantId: Long,
        raceId: String,
        market: String,
    ): List<PayoutRowEntity>

    /** 確定の行を速報で上書きしない。行ごとの更新件数: 1 = 挿入、2 = 更新、0 = 変更なし。 */
    @BatchInsert(sqlFile = true)
    fun upsertAll(rows: List<PayoutRowEntity>): BatchResult<PayoutRowEntity>
}
