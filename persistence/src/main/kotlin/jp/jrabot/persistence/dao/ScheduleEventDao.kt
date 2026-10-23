package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime

/** `race_schedule_event`（追記専用）。`old_value`・`new_value` は JSON 列で、文字列のまま読み書きする。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_schedule_event")
internal data class ScheduleEventEntity(
    @Id val id: Long,
    val tenantId: Long,
    val raceId: String?,
    val raceDate: LocalDate,
    val eventType: String,
    val oldValue: String?,
    val newValue: String?,
    val source: String,
    val observedAt: LocalDateTime,
    val receivedAt: LocalDateTime,
    val batchId: String?,
    val dedupHash: String,
)

/** 更新・削除の操作は持たない（追記専用。保持期間による削除は retention Job の別経路）。 */
@Dao
internal interface ScheduleEventDao {
    @Select
    fun selectByHash(
        tenantId: Long,
        dedupHash: String,
    ): ScheduleEventEntity?

    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<ScheduleEventEntity>

    @Select
    fun selectByDate(
        tenantId: Long,
        raceDate: LocalDate,
    ): List<ScheduleEventEntity>

    /** `id` は AUTO_INCREMENT なので挿入しない。`(tenant_id, dedup_hash)` の重複は 1062（uq_race_schedule_event_dedup）。 */
    @Insert(sqlFile = true)
    fun insert(entity: ScheduleEventEntity): Result<ScheduleEventEntity>
}
