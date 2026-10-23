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

/**
 * `bot_run`。`version` は Doma の `@Version` ではなく**明示的な SQL**（`WHERE version = ?` と `version = version + 1`）で管理する。
 * 競合（他の更新が先）と不在を区別したい（domain の `UpdateResult`）ので、更新件数 0 の後に読み直して判定する（D-070）。
 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "bot_run")
internal data class BotRunEntity(
    @Id val tenantId: Long,
    @Id val runId: String,
    val runDate: LocalDate,
    val triggerType: String,
    val state: String,
    val leaseOwner: String?,
    val leaseExpiresAt: LocalDateTime?,
    val heartbeatAt: LocalDateTime?,
    val activeSlot: Int?,
    val checkpoint: String?,
    val exitReason: String?,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val version: Int,
)

@Dao
internal interface BotRunDao {
    @Select
    fun selectById(
        tenantId: Long,
        runId: String,
    ): BotRunEntity?

    /** `active_slot = 1`（排他を持つ実行）。 */
    @Select
    fun selectActive(
        tenantId: Long,
        runDate: LocalDate,
    ): BotRunEntity?

    /** 主キーで**行ロック**つきで読む（heartbeat・状態遷移・checkpoint・奪取の検証と更新を原子的にする）。 */
    @Select
    fun selectByIdForUpdate(
        tenantId: Long,
        runId: String,
    ): BotRunEntity?

    @Select
    fun selectByDate(
        tenantId: Long,
        runDate: LocalDate,
    ): List<BotRunEntity>

    @Select
    fun selectRecent(
        tenantId: Long,
        limit: Int,
    ): List<BotRunEntity>

    @Insert(sqlFile = true)
    fun insert(entity: BotRunEntity): Result<BotRunEntity>

    /** `entity.version` が保存値と等しい行だけを更新し、`version` を 1 増やす（所有者・状態の検証は呼び出し側が行ロックの下で行う）。 */
    @Update(sqlFile = true)
    fun updateIfVersion(entity: BotRunEntity): Result<BotRunEntity>
}
