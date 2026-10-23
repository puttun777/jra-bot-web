package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.entity.NamingType

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "app_user")
internal data class AppUserEntity(
    @Id val tenantId: Long,
    @Id val email: String,
    val role: String,
    val enabled: Boolean,
)

@Dao
internal interface AppUserDao {
    /**
     * ログイン時のテナント解決だけに使う。**テナント条件を持たない唯一の SQL**（email は全体で UNIQUE: D-063）。
     * 静的検査（SqlTemplateTenantGuardTest）の許可リストに、このファイルだけが載っている。
     */
    @Select
    fun selectByEmail(email: String): AppUserEntity?

    @Select
    fun selectByTenant(tenantId: Long): List<AppUserEntity>
}
