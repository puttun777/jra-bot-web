package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDateTime

/** `race_evaluations`（判定の記録。T3.8 の bot-run が書く）。`id` は AUTO_INCREMENT なので挿入しない（`entity.id` は無視される）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_evaluations")
internal data class RaceEvaluationEntity(
    @Id val id: Long,
    val tenantId: Long,
    val raceId: String,
    val snapshotAt: LocalDateTime,
    val modelTypeUsed: String?,
    val modelId: String?,
    val fallbackReason: String?,
    val betTypes: String?,
    val priorityMode: String?,
    val evThreshold: Double?,
    val winOddsMax: Double?,
    val winProbMin: Double?,
    val winRatioMin: Double?,
    val bestEv: Double?,
    val rejectionSummaryJson: String?,
    val signalSent: Boolean,
    val createdAt: LocalDateTime,
)

@Dao
internal interface RaceEvaluationDao {
    /**
     * 自然キー `(tenant_id, race_id, snapshot_at)` が無いときだけ挿入する（`INSERT … SELECT … WHERE NOT EXISTS`）。件数 1 = 挿入、0 = 既存。
     * 旧テーブルには UNIQUE が無い（移行データに重複があり得る: D-066）ので、同時挿入の防止は bot-run のリース（`bot_run` の UNIQUE）に依存する。
     */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: RaceEvaluationEntity): Result<RaceEvaluationEntity>

    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<RaceEvaluationEntity>
}

/** 出走表の馬名（`race_horses.bamei`）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
internal data class RaceHorseNameRow(
    val umaban: Int,
    val bamei: String?,
)

@Dao
internal interface RaceHorseNameDao {
    @Select
    fun selectNames(
        tenantId: Long,
        raceId: String,
    ): List<RaceHorseNameRow>
}
