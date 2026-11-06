package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.Update
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDateTime

/** 管理画面のエージェントの行（R20。D-270）。資格情報の列（token_hash・sign_key_*）は読まない（app の列単位の SELECT に無い）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "agent")
internal data class AgentSummaryEntity(
    @Id val tenantId: Long,
    @Id val agentId: String,
    val name: String,
    val tokenExpiresAt: LocalDateTime,
    val status: String,
    val createdAt: LocalDateTime,
    val createdBy: String?,
    val revokedAt: LocalDateTime?,
    val revokedBy: String?,
)

/** エージェントの一覧と失効（dashboard の app ユーザー: 列単位の SELECT と status・revoked_* の UPDATE だけ）。すべての SQL がテナントの条件を持つ。 */
@Dao
internal interface AgentAdminDao {
    @Select
    fun selectByTenant(tenantId: Long): List<AgentSummaryEntity>

    @Select
    fun selectOne(
        tenantId: Long,
        agentId: String,
    ): AgentSummaryEntity?

    /** ACTIVE のときだけ REVOKED にする（更新件数 1 = 失効した、0 = 既に失効・無い）。 */
    @Update(sqlFile = true)
    fun revoke(
        tenantId: Long,
        agentId: String,
        revokedAt: LocalDateTime,
        revokedBy: String,
    ): Int
}
