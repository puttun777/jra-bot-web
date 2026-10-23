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

/** `odds_snapshots` の 1 行（主キーは tenant_id・race_id・market・observed_at・umaban。D-064）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "odds_snapshots")
internal data class OddsRowEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val market: String,
    @Id val observedAt: LocalDateTime,
    @Id val umaban: Int,
    val odds: Double,
    val popularity: Int?,
)

@Dao
internal interface OddsSnapshotDao {
    /** 観測時刻が最新のスナップショットの全行（馬番昇順）。 */
    @Select
    fun selectLatest(
        tenantId: Long,
        raceId: String,
        market: String,
    ): List<OddsRowEntity>

    /** 観測時刻・馬番の昇順。 */
    @Select
    fun selectHistory(
        tenantId: Long,
        raceId: String,
        market: String,
    ): List<OddsRowEntity>

    /** 行ごとの更新件数: 1 = 挿入、2 = 更新、0 = 同じ内容（`useAffectedRows=true` が前提）。 */
    @BatchInsert(sqlFile = true)
    fun upsertAll(rows: List<OddsRowEntity>): BatchResult<OddsRowEntity>
}
