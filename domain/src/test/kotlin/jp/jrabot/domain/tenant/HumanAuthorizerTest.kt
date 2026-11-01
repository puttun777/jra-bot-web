package jp.jrabot.domain.tenant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HumanAuthorizerTest {
    private val me = EmailAddress.of("Me@Example.com")
    private val other = EmailAddress.of("other@example.com")

    private class Repo(
        private val rows: List<AppUser>,
    ) : AppUserRepository {
        override fun findByEmail(email: EmailAddress) = rows.firstOrNull { it.email == email }

        override fun listByTenant(tenantId: TenantId) = rows.filter { it.tenantId == tenantId }
    }

    private fun user(
        email: EmailAddress,
        role: AppUserRole = AppUserRole.VIEWER,
        enabled: Boolean = true,
        tenant: Long = 1,
    ) = AppUser(TenantId.of(tenant), email, role, enabled)

    private fun auth(
        allowed: List<EmailAddress>,
        admins: List<EmailAddress> = emptyList(),
        vararg rows: AppUser,
    ) = HumanAuthorizer(allowed, admins, Repo(rows.toList()))

    @Test
    fun `許可リストと有効な行がそろえば認め メールは大文字小文字・空白を無視し テナントは行から決まる`() {
        val a = auth(listOf(me), emptyList(), user(me, tenant = 7))
        val d = a.authorize("  ME@example.COM ") as AuthDecision.Allowed
        assertEquals(TenantId.of(7), d.principal.tenantId)
        assertEquals(AppUserRole.VIEWER, d.principal.role)
        assertTrue(!d.principal.isAdmin)
    }

    @Test
    fun `許可リストが空なら全員拒否`() {
        val a = auth(emptyList(), emptyList(), user(me))
        assertEquals(AuthDecision.Denied(DenyReason.NOT_ALLOWED), a.authorize("me@example.com"))
    }

    @Test
    fun `許可リストに無い 行が無い 無効な行 は拒否`() {
        val a = auth(listOf(me, other), emptyList(), user(me, enabled = false))
        assertEquals(AuthDecision.Denied(DenyReason.NO_ACTIVE_USER), a.authorize("me@example.com"))
        assertEquals(AuthDecision.Denied(DenyReason.NO_ACTIVE_USER), a.authorize("other@example.com"))
        assertEquals(AuthDecision.Denied(DenyReason.NOT_ALLOWED), a.authorize("stranger@example.com"))
    }

    @Test
    fun `ADMIN の行は ADMIN_USERS にも載っているときだけ 載っていなければ降格せず拒否`() {
        val row = user(me, AppUserRole.ADMIN)
        val ok = auth(listOf(me), listOf(me), row).authorize("me@example.com") as AuthDecision.Allowed
        assertTrue(ok.principal.isAdmin)
        assertEquals(AuthDecision.Denied(DenyReason.ADMIN_NOT_LISTED), auth(listOf(me), emptyList(), row).authorize("me@example.com"))
    }

    @Test
    fun `ADMIN_USERS に載っていても 行が VIEWER なら閲覧者のまま`() {
        val d = auth(listOf(me), listOf(me), user(me, AppUserRole.VIEWER)).authorize("me@example.com") as AuthDecision.Allowed
        assertTrue(!d.principal.isAdmin)
    }

    @Test
    fun `ops のサービスアカウントは 許可リストに入っていても人としては拒否`() {
        val ops = EmailAddress.of("ops@proj.iam.gserviceaccount.com")
        val a = HumanAuthorizer(listOf(me, ops), emptyList(), Repo(listOf(user(me), user(ops))), listOf(ops))
        assertEquals(AuthDecision.Denied(DenyReason.NOT_A_PERSON), a.authorize("ops@proj.iam.gserviceaccount.com"))
        assertTrue(a.authorize("me@example.com") is AuthDecision.Allowed)
    }

    @Test
    fun `不正なメールは拒否`() {
        val a = auth(listOf(me), emptyList(), user(me))
        for (bad in listOf("", "   ", "no-at", "a@b@c", "a b@c.com")) assertEquals(AuthDecision.Denied(DenyReason.INVALID_EMAIL), a.authorize(bad))
    }

    @Test
    fun `環境変数の一覧の読み取り 空は空集合 不正は変数名だけの例外`() {
        assertEquals(emptyList(), HumanAuthorizer.parseList("ALLOWED_USERS", null))
        assertEquals(emptyList(), HumanAuthorizer.parseList("ALLOWED_USERS", " , "))
        assertEquals(listOf(me, other), HumanAuthorizer.parseList("ALLOWED_USERS", "me@example.com, OTHER@example.com"))
        val e = assertFailsWith<IllegalStateException> { HumanAuthorizer.parseList("ADMIN_USERS", "ok@example.com,secret-bad") }
        assertTrue("ADMIN_USERS" in e.message.orEmpty() && "secret-bad" !in e.message.orEmpty())
    }
}
