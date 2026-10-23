package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.Update
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime

/** `race_schedule`。`races` への FK は持たない（開催予定は結果の取込より先に存在する）。`version` は [BotRunEntity] と同じ明示的な楽観ロック。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_schedule")
internal data class RaceScheduleEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    val raceDate: LocalDate,
    val venueCode: String,
    val raceNo: Int,
    val scheduledPostAt: LocalDateTime?,
    val postTimeUnknown: Boolean,
    val status: String,
    val dataKubun: String,
    val sourceVersion: String,
    val raceName: String?,
    val lastChangedAt: LocalDateTime,
    val version: Int,
)

@Dao
internal interface RaceScheduleDao {
    @Select
    fun selectById(
        tenantId: Long,
        raceId: String,
    ): RaceScheduleEntity?

    @Select
    fun selectByDate(
        tenantId: Long,
        date: LocalDate,
    ): List<RaceScheduleEntity>

    /** 主キーの重複は 1062（PRIMARY）。 */
    @Insert(sqlFile = true)
    fun insert(entity: RaceScheduleEntity): Result<RaceScheduleEntity>

    @Update(sqlFile = true)
    fun updateIfVersion(entity: RaceScheduleEntity): Result<RaceScheduleEntity>
}
