package jp.jrabot.domain.tenant

enum class AppUserRole { VIEWER, ADMIN }

/**
 * 人の利用者（共有表 `app_user`）。認可は「`ALLOWED_USERS` に含まれ、かつ有効な行がある」場合だけ（CONTRACTS §11.1、deny by default）。
 * `ROLE_OPS`（ops SA）は人ではないのでここには含めない。
 */
data class AppUser(
    override val tenantId: TenantId,
    val email: EmailAddress,
    val role: AppUserRole,
    val enabled: Boolean,
) : TenantScoped {
    /** 有効で、かつ管理者のときだけ true。無効な行は管理者でも false。 */
    val isActiveAdmin: Boolean get() = enabled && role == AppUserRole.ADMIN
}
