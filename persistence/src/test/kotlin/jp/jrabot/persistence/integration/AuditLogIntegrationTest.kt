package jp.jrabot.persistence.integration

import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 監査記録（`app` ユーザー）。追記だけができ、UPDATE・DELETE は権限が無いこと、テナントが記録されること。 */
@Tag("integration")
class AuditLogIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "app", clock = MutableClock(Instant.parse("2026-10-24T01:00:00Z")), poolMax = 2)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    @BeforeEach
    fun reset() {
        db.exec("DELETE FROM audit_log")
    }

    @Test
    fun `追記でき 誰が何をいつどのテナントでしたかが残り UPDATE と DELETE はできない`() {
        val writer = h.bean(AuditLogWriter::class.java)
        writer.append(
            AuditEntry(TenantId.of(1), "me@example.com", "CALENDAR_OVERRIDE_CREATE", "calendar_override", "7", null, """{"id":7}""", "テスト", "req-1"),
            Instant.parse("2026-10-24T01:00:00.5Z"),
        )
        assertEquals(
            1,
            db.count(
                "SELECT COUNT(*) FROM audit_log WHERE tenant_id = 1 AND actor = 'me@example.com' AND action = 'CALENDAR_OVERRIDE_CREATE' AND target_id = '7'",
            ),
        )
        assertEquals("USER", db.scalar("SELECT actor_type FROM audit_log"))
        assertEquals("req-1", db.scalar("SELECT request_id FROM audit_log"))
        assertFailsWith<Exception> { h.dataSource.connection.use { it.createStatement().executeUpdate("UPDATE audit_log SET reason = 'x'") } }
        assertFailsWith<Exception> { h.dataSource.connection.use { it.createStatement().executeUpdate("DELETE FROM audit_log") } }
        assertEquals(1, db.count("SELECT COUNT(*) FROM audit_log"))
    }
}
