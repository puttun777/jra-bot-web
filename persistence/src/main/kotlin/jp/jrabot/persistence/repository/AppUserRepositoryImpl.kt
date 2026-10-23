package jp.jrabot.persistence.repository

import jp.jrabot.domain.tenant.AppUser
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.AppUserDao
import jp.jrabot.persistence.dao.AppUserEntity
import jp.jrabot.persistence.mapping.enumFromDb

internal class AppUserRepositoryImpl(
    private val dao: AppUserDao,
) : AppUserRepository {
    /** `email` は [EmailAddress] が正規化済み（小文字）。DB は大文字小文字を区別する完全一致（`utf8mb4_0900_bin`）で全体に一意。 */
    override fun findByEmail(email: EmailAddress): AppUser? = dao.selectByEmail(email.value)?.toDomain()

    override fun listByTenant(tenantId: TenantId): List<AppUser> = dao.selectByTenant(tenantId.value).map { it.toDomain() }
}

internal fun AppUserEntity.toDomain(): AppUser =
    AppUser(
        tenantId = TenantId.of(tenantId),
        email = checkNotNull(EmailAddress.ofOrNull(email)) { "app_user.email が EmailAddress として不正" },
        role = enumFromDb<AppUserRole>(role, "app_user.role"),
        enabled = enabled,
    )
