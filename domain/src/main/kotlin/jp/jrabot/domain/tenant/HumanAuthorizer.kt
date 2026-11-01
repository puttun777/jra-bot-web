package jp.jrabot.domain.tenant

/** 認可された利用者。[tenantId] は `app_user` の行から決まる（リクエストの値では決めない）。 */
data class Principal(
    val tenantId: TenantId,
    val email: EmailAddress,
    val role: AppUserRole,
) {
    val isAdmin: Boolean get() = role == AppUserRole.ADMIN
}

/** 拒否の理由（固定語彙。メールアドレス等の値を含めない）。 */
enum class DenyReason { INVALID_EMAIL, NOT_A_PERSON, NOT_ALLOWED, NO_ACTIVE_USER, ADMIN_NOT_LISTED }

sealed interface AuthDecision {
    data class Allowed(
        val principal: Principal,
    ) : AuthDecision

    data class Denied(
        val reason: DenyReason,
    ) : AuthDecision
}

/**
 * 人の認可（CONTRACTS §11.1、deny by default）。署名済みトークンで確認できたメールアドレスが、
 * ① `ALLOWED_USERS` に含まれ、② `app_user` に**有効な行**があり、③ ロールが ADMIN なら `ADMIN_USERS` にも含まれる、の**すべてを満たす**ときだけ認める。
 * どれかを満たさなければ拒否（ADMIN の行なのに `ADMIN_USERS` に無い場合は閲覧者に降格せず拒否する = 設定の食い違いを黙って緩めない）。
 * `ALLOWED_USERS` が空なら全員拒否。テナントは行から決まり、複数テナントに同じメールがあることは DB の一意制約（email は全体で UNIQUE: D-063）で無い。
 */
class HumanAuthorizer(
    allowedUsers: Collection<EmailAddress>,
    adminUsers: Collection<EmailAddress>,
    private val users: AppUserRepository,
    /** 人ではない主体（ops 用サービスアカウント）。設定の誤りで `ALLOWED_USERS` に入っても人としては常に拒否する（多層防御。レビュー A3）。 */
    nonPersons: Collection<EmailAddress> = emptyList(),
) {
    private val notPersons = nonPersons.toSet()
    private val allowed = allowedUsers.toSet()
    private val admins = adminUsers.toSet()

    fun authorize(rawEmail: String): AuthDecision {
        val email = EmailAddress.ofOrNull(rawEmail) ?: return AuthDecision.Denied(DenyReason.INVALID_EMAIL)
        if (email in notPersons) return AuthDecision.Denied(DenyReason.NOT_A_PERSON)
        if (email !in allowed) return AuthDecision.Denied(DenyReason.NOT_ALLOWED)
        val user = users.findByEmail(email)
        if (user == null || !user.enabled) return AuthDecision.Denied(DenyReason.NO_ACTIVE_USER)
        if (user.role == AppUserRole.ADMIN && email !in admins) return AuthDecision.Denied(DenyReason.ADMIN_NOT_LISTED)
        return AuthDecision.Allowed(Principal(user.tenantId, email, user.role))
    }

    companion object {
        /** 環境変数のカンマ区切りを読む。空・未設定は空集合（= 全拒否）。1 つでも不正なメールがあれば例外（変数名だけを含める）。 */
        fun parseList(
            name: String,
            raw: String?,
        ): List<EmailAddress> =
            raw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { EmailAddress.ofOrNull(it) ?: throw IllegalStateException("$name にメールアドレスとして不正な値があります") }
                .orEmpty()
    }
}
