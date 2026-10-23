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
import java.time.LocalDateTime

/** `signals`。EV・Kelly の内訳の列（kelly_raw など）は Stage 3 で追加するまでマップしない。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "signals")
internal data class SignalEntity(
    @Id val id: Long,
    val tenantId: Long,
    val raceId: String,
    val market: String,
    val umaban: Int,
    val observedAt: LocalDateTime,
    val odds: Double,
    val prob: Double,
    val ev: Double,
    val isPaper: Boolean,
    val stake: Double,
    val probSource: String?,
    val notifyState: String,
    val notifyUpdatedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

@Dao
internal interface SignalDao {
    @Select
    fun selectById(
        tenantId: Long,
        id: Long,
    ): SignalEntity?

    @Select
    fun selectByNaturalKey(
        tenantId: Long,
        raceId: String,
        market: String,
        umaban: Int,
        observedAt: LocalDateTime,
    ): SignalEntity?

    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<SignalEntity>

    @Select
    fun selectUnsent(
        tenantId: Long,
        limit: Int,
    ): List<SignalEntity>

    /** `id` は AUTO_INCREMENT なので挿入しない（`entity.id` は無視される）。自然キーの重複は 1062（uq_signals_natural）。 */
    @Insert(sqlFile = true)
    fun insert(entity: SignalEntity): Result<SignalEntity>

    /** `UNSENT → CLAIMED`。件数 1 のときだけ勝ち。 */
    @Update(sqlFile = true)
    fun claim(entity: SignalEntity): Result<SignalEntity>

    /** `CLAIMED → SENT`。 */
    @Update(sqlFile = true)
    fun markSent(entity: SignalEntity): Result<SignalEntity>

    /** `CLAIMED → SEND_UNKNOWN`。 */
    @Update(sqlFile = true)
    fun markSendUnknown(entity: SignalEntity): Result<SignalEntity>
}
