package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.GeneratedValue
import org.seasar.doma.GenerationType
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.Update
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime

/** `calendar_override`（手動補正）。`value` は JSON 列で文字列のまま読み書きする。内容の更新はせず、取消（`revoked_*`）だけを書き換える。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "calendar_override")
internal data class CalendarOverrideEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long?,
    val tenantId: Long,
    val scope: String,
    val raceDate: LocalDate,
    val raceId: String?,
    val action: String,
    val value: String?,
    val reason: String,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val validUntil: LocalDateTime?,
    val revokedAt: LocalDateTime?,
    val revokedBy: String?,
)

@Dao
internal interface CalendarOverrideDao {
    @Select
    fun selectById(
        tenantId: Long,
        id: Long,
    ): CalendarOverrideEntity?

    @Select
    fun selectByDate(
        tenantId: Long,
        raceDate: LocalDate,
    ): List<CalendarOverrideEntity>

    /** 自動生成の INSERT（`id` は AUTO_INCREMENT で、挿入後の値が返る）。テナントは常に `entity.tenantId`。 */
    @Insert
    fun insert(entity: CalendarOverrideEntity): Result<CalendarOverrideEntity>

    /** 未取消の行だけを取り消す（`revoked_at IS NULL`。件数 1 のときだけ取消した）。 */
    @Update(sqlFile = true)
    fun revoke(entity: CalendarOverrideEntity): Result<CalendarOverrideEntity>
}
