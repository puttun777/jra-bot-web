package jp.jrabot.persistence.integration

import jp.jrabot.domain.tenant.AppUser
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
class AppUserRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun seed() {
        db.exec("DELETE FROM app_user")
        db.exec(
            """INSERT INTO app_user (tenant_id, email, role, enabled, created_at, updated_at) VALUES
              (1, 'alice@example.com', 'ADMIN', 1, NOW(6), NOW(6)),
              (1, 'bob@example.com', 'VIEWER', 1, NOW(6), NOW(6)),
              (1, 'disabled@example.com', 'ADMIN', 0, NOW(6), NOW(6)),
              (2, 'carol@example.com', 'VIEWER', 1, NOW(6), NOW(6))""",
        )
    }

    @Test
    fun `findByEmail は完全一致の 1 件を、そのテナントと role・enabled つきで返す`() {
        assertEquals(
            AppUser(T1, EmailAddress.of("alice@example.com"), AppUserRole.ADMIN, true),
            h.appUsers.findByEmail(EmailAddress.of("alice@example.com")),
        )
        assertEquals(T2, h.appUsers.findByEmail(EmailAddress.of("carol@example.com"))?.tenantId)
        assertEquals(AppUserRole.VIEWER, h.appUsers.findByEmail(EmailAddress.of("bob@example.com"))?.role)
    }

    @Test
    fun `入力の大文字・前後の空白は EmailAddress が正規化して引ける`() {
        assertEquals(T1, h.appUsers.findByEmail(EmailAddress.of("  Alice@Example.COM "))?.tenantId)
    }

    @Test
    fun `無効な利用者も返す（判断は呼び出し側）。isActiveAdmin は false`() {
        val user = checkNotNull(h.appUsers.findByEmail(EmailAddress.of("disabled@example.com")))
        assertFalse(user.enabled)
        assertFalse(user.isActiveAdmin)
        assertTrue(checkNotNull(h.appUsers.findByEmail(EmailAddress.of("alice@example.com"))).isActiveAdmin)
    }

    @Test
    fun `部分一致・ワイルドカードでは引けない`() {
        assertNull(h.appUsers.findByEmail(EmailAddress.of("alice@example.co")))
        assertNull(h.appUsers.findByEmail(EmailAddress.of("lice@example.com")))
        assertNull(h.appUsers.findByEmail(EmailAddress.of("%@example.com")))
        assertNull(h.appUsers.findByEmail(EmailAddress.of("a_ice@example.com")))
        assertNull(h.appUsers.findByEmail(EmailAddress.of("nobody@example.com")))
    }

    @Test
    fun `listByTenant は自分のテナントの利用者だけをメール順に返す`() {
        assertEquals(listOf("alice@example.com", "bob@example.com", "disabled@example.com"), h.appUsers.listByTenant(T1).map { it.email.value })
        assertEquals(listOf("carol@example.com"), h.appUsers.listByTenant(T2).map { it.email.value })
        assertEquals(emptyList(), h.appUsers.listByTenant(TenantId.of(99)))
    }

    @Test
    fun `メールは全体で一意（別テナントに同じメールは入らない）で、大文字を含む値も入らない`() {
        assertSqlError(MySqlError.DUPLICATE_ENTRY) {
            db.exec(
                "INSERT INTO app_user (tenant_id, email, role, enabled, created_at, updated_at) VALUES (2, 'alice@example.com', 'VIEWER', 1, NOW(6), NOW(6))",
            )
        }
        assertSqlError(MySqlError.CHECK_VIOLATED) {
            db.exec(
                "INSERT INTO app_user (tenant_id, email, role, enabled, created_at, updated_at) VALUES (2, 'Upper@example.com', 'VIEWER', 1, NOW(6), NOW(6))",
            )
        }
    }
}
