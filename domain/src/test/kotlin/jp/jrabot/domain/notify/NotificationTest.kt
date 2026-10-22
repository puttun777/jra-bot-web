package jp.jrabot.domain.notify

import jp.jrabot.domain.tenant.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NotificationTest {
    @Test
    fun `SIGNAL にはテナントが必要で ERROR には不要`() {
        assertFailsWith<IllegalArgumentException> { Notification(null, NotificationChannel.SIGNAL, "件名", "本文") }
        assertEquals(TenantId.of(1), Notification(TenantId.of(1), NotificationChannel.SIGNAL, "件名", "本文").tenant)
        assertNull(Notification(null, NotificationChannel.ERROR, "bot-run が exit 75", "モード UNKNOWN").tenant)
    }

    @Test
    fun `件名と本文を検査する`() {
        for (subject in listOf("", "  ", "x".repeat(201))) {
            assertFailsWith<IllegalArgumentException> { Notification(null, NotificationChannel.ERROR, subject, "本文") }
        }
        assertFailsWith<IllegalArgumentException> { Notification(null, NotificationChannel.ERROR, "件名", " ") }
    }

    @Test
    fun `送れたか不明を NOT_SENT と区別する`() {
        assertEquals(listOf("DELIVERED", "NOT_SENT", "UNKNOWN"), DeliveryResult.entries.map { it.name })
    }
}
