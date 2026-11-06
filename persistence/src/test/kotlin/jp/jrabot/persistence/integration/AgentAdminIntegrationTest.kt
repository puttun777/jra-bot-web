package jp.jrabot.persistence.integration

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.AgentRevokeResult
import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * エージェントの一覧と失効（R20。D-270）を **dashboard の `app` ユーザー**で通す（列単位の SELECT〔資格情報の列なし〕と status・revoked_* の UPDATE だけで足りること）。
 * 失効は ACTIVE のときだけ（条件付き更新）、他テナントは見えず変えられない。失効の後、ingest の資格情報の読取りでも REVOKED になる。合成データだけ。
 */
@Tag("integration")
class AgentAdminIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val app = RepositoryHarness(db, user = "app")
        private val ingest = RepositoryHarness(db, user = "ingest")

        @JvmStatic
        @AfterAll
        fun close() {
            app.close()
            ingest.close()
        }
    }

    private val at = Instant.parse("2026-10-24T01:00:00.123456Z")

    @BeforeEach
    fun reset() {
        db.resetData()
        db.exec("DELETE FROM agent_nonce")
        db.exec("DELETE FROM agent_heartbeat")
        db.exec("DELETE FROM agent")
        db.exec("INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (2, 'second', '2026-10-24 00:00:00.000000')")
        for ((tenant, id, created) in listOf(Triple(1, "pc_a", "2026-10-01"), Triple(1, "pc_b", "2026-10-02"), Triple(2, "pc_other", "2026-10-03"))) {
            db.exec(
                "INSERT INTO agent (tenant_id, agent_id, name, token_hash, token_expires_at, created_at, created_by) VALUES " +
                    "($tenant, '$id', 'PC $id', '${"a".repeat(64)}', '2027-01-01 00:00:00.000000', '$created 00:00:00.000000', 'me@example.com')",
            )
        }
    }

    @Test
    fun `app ユーザーで自分のテナントの一覧を読める（ACTIVE を先頭に新しい順。他テナントは出ない）`() {
        assertEquals(listOf("pc_b", "pc_a"), app.agentAdmin.list(T1).map { it.agentId.value })
        assertEquals(listOf("pc_other"), app.agentAdmin.list(TenantId.of(2)).map { it.agentId.value })
        assertNull(app.agentAdmin.find(T1, AgentId.ofOrNull("pc_other")!!), "他テナントのエージェントは見えない")
    }

    @Test
    fun `失効は ACTIVE のときだけで 二重は AlreadyRevoked・他テナントと無いものは NotFound・ingest の読取りでも REVOKED`() {
        val pcA = AgentId.ofOrNull("pc_a")!!
        val r = assertIs<AgentRevokeResult.Revoked>(app.agentAdmin.revoke(T1, pcA, "me@example.com", at))
        assertEquals(AgentStatus.REVOKED, r.agent.status)
        assertEquals(Instant.parse("2026-10-24T01:00:00.123456Z"), r.agent.revokedAt)
        assertEquals("me@example.com", r.agent.revokedBy)
        assertEquals(AgentRevokeResult.AlreadyRevoked, app.agentAdmin.revoke(T1, pcA, "other@example.com", at.plusSeconds(60)))
        assertEquals("me@example.com", app.agentAdmin.find(T1, pcA)!!.revokedBy, "二重の失効は最初の記録を変えない")
        assertEquals(AgentRevokeResult.NotFound, app.agentAdmin.revoke(T1, AgentId.ofOrNull("pc_other")!!, "me@example.com", at))
        assertEquals(AgentRevokeResult.NotFound, app.agentAdmin.revoke(T1, AgentId.ofOrNull("nope")!!, "me@example.com", at))
        assertEquals("ACTIVE", db.scalar("SELECT status FROM agent WHERE agent_id = 'pc_other'"), "他テナントの行は変わらない")
        assertEquals(AgentStatus.REVOKED, ingest.agentCredentials.findByAgentId(pcA)!!.status, "ingest は次の要求から失効を見る（キャッシュしない）")
    }
}
