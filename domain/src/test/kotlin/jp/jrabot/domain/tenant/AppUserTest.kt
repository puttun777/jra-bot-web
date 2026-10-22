package jp.jrabot.domain.tenant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUserTest {
    private fun user(
        role: AppUserRole,
        enabled: Boolean,
    ) = AppUser(TenantId.of(1), EmailAddress.of("u@example.com"), role, enabled)

    @Test
    fun `管理者として扱えるのは有効な ADMIN だけ`() {
        assertTrue(user(AppUserRole.ADMIN, true).isActiveAdmin)
        assertFalse(user(AppUserRole.ADMIN, false).isActiveAdmin)
        assertFalse(user(AppUserRole.VIEWER, true).isActiveAdmin)
    }
}
