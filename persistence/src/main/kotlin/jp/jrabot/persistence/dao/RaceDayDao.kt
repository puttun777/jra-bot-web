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

/** `race_day`（場ごとの開催日）。`version` は [BotRunEntity] と同じ明示的な楽観ロック。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_day")
internal data class RaceDayEntity(
    @Id val tenantId: Long,
    @Id val raceDate: LocalDate,
    @Id val venueCode: String,
    val kai: Int,
    val nichime: Int,
    val status: String,
    val replacedByDate: LocalDate?,
    val firstSeenAt: LocalDateTime,
    val lastConfirmedAt: LocalDateTime?,
    val version: Int,
)

@Dao
internal interface RaceDayDao {
    @Select
    fun selectById(
        tenantId: Long,
        raceDate: LocalDate,
        venueCode: String,
    ): RaceDayEntity?

    @Select
    fun selectByDate(
        tenantId: Long,
        raceDate: LocalDate,
    ): List<RaceDayEntity>

    /** 主キーの重複は 1062（PRIMARY）。 */
    @Insert(sqlFile = true)
    fun insert(entity: RaceDayEntity): Result<RaceDayEntity>

    @Update(sqlFile = true)
    fun updateIfVersion(entity: RaceDayEntity): Result<RaceDayEntity>

    /** `entity.lastConfirmedAt` が保存値より新しいときだけ前進させる（`version` も進む）。 */
    @Update(sqlFile = true)
    fun advanceConfirmed(entity: RaceDayEntity): Result<RaceDayEntity>
}
