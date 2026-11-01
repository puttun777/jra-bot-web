package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.GeneratedValue
import org.seasar.doma.GenerationType
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Table
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDateTime

/** `audit_log`（追記専用。`app` ユーザーには INSERT だけを付与: R__grants.sql）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "audit_log")
internal data class AuditLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long?,
    val tenantId: Long,
    val occurredAt: LocalDateTime,
    val actorType: String,
    val actor: String,
    val action: String,
    val targetType: String,
    val targetId: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String?,
    val requestId: String?,
)

@Dao
internal interface AuditLogDao {
    @Insert
    fun insert(entity: AuditLogEntity): Result<AuditLogEntity>
}
