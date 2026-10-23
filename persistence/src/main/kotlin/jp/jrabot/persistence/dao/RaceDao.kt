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
import java.time.LocalTime

/** `races`。コース条件のコード列（track_type_cd など）は T3.6 で追加するまでマップしない（upsert でも触れない）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "races")
internal data class RaceEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    val raceDate: LocalDate,
    val venueCode: String,
    val raceNo: Int,
    val kai: Int?,
    val nichime: Int?,
    val postTime: LocalTime?,
    val postTimeUnknown: Boolean,
    val name: String?,
    val distanceM: Int?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

@Dao
internal interface RaceDao {
    @Select
    fun selectById(
        tenantId: Long,
        raceId: String,
    ): RaceEntity?

    @Select
    fun selectByDate(
        tenantId: Long,
        date: LocalDate,
    ): List<RaceEntity>

    /** 更新件数: 1 = 挿入、2 = 内容が変わって更新、0 = 同じ内容（`useAffectedRows=true` が前提）。 */
    @Insert(sqlFile = true)
    fun upsert(entity: RaceEntity): Result<RaceEntity>
}
