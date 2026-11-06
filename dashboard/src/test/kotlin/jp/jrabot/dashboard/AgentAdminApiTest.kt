package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.AgentAdminApi
import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.api.RevokeAgentRequest
import jp.jrabot.domain.agent.AgentAdminStore
import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.AgentRevokeResult
import jp.jrabot.domain.agent.AgentSummary
import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.Principal
import jp.jrabot.domain.tenant.TenantId
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** エージェントの一覧と失効（R20。D-270）: 失効は主体のテナントだけ・理由は必須・監査記録と同じトランザクション・資格情報を返さない。 */
class AgentAdminApiTest {
    private val t1 = TenantId.of(1)
    private val now = Instant.parse("2026-10-24T01:00:00Z")
    private val clock = ManualClock(now)

    private fun agent(
        tenant: TenantId,
        id: String,
    ) = AgentSummary(
        tenant,
        AgentId.ofOrNull(id)!!,
        "PC-$id",
        now.plusSeconds(86_400),
        AgentStatus.ACTIVE,
        now.minusSeconds(3600),
        "me@example.com",
        null,
        null,
    )

    private val store =
        object : AgentAdminStore {
            val rows = mutableListOf(agent(t1, "pc-1"), agent(TenantId.of(2), "pc-other"))

            override fun list(tenantId: TenantId) = rows.filter { it.tenantId == tenantId }

            override fun find(
                tenantId: TenantId,
                agentId: AgentId,
            ) = rows.firstOrNull { it.tenantId == tenantId && it.agentId == agentId }

            override fun revoke(
                tenantId: TenantId,
                agentId: AgentId,
                by: String,
                at: Instant,
            ): AgentRevokeResult {
                val i = rows.indexOfFirst { it.tenantId == tenantId && it.agentId == agentId }
                if (i < 0) return AgentRevokeResult.NotFound
                if (rows[i].status == AgentStatus.REVOKED) return AgentRevokeResult.AlreadyRevoked
                rows[i] = rows[i].copy(status = AgentStatus.REVOKED, revokedAt = at, revokedBy = by)
                return AgentRevokeResult.Revoked(rows[i])
            }
        }

    /** 例外で終わったら行を開始時点に戻す（ロールバックの確認用）。 */
    private val tx =
        object : TransactionOperations {
            override fun <T : Any?> execute(action: TransactionCallback<T>): T {
                val snapshot = store.rows.toList()
                try {
                    return action.doInTransaction(SimpleTransactionStatus())
                } catch (e: Throwable) {
                    store.rows.clear()
                    store.rows.addAll(snapshot)
                    throw e
                }
            }
        }
    private val audits = mutableListOf<AuditEntry>()
    private var auditFails = false
    private val api = AgentAdminApi(clock, store, AuditLogWriter { e, _ -> if (auditFails) error("db down") else audits += e }, tx)

    private fun req(tenant: TenantId = t1) =
        MockHttpServletRequest().also {
            it.setAttribute(ApiAuthFilter.PRINCIPAL_ATTRIBUTE, Principal(tenant, EmailAddress.of("me@example.com"), AppUserRole.ADMIN))
        }

    private fun status(f: () -> Unit): Int = assertFailsWith<ResponseStatusException> { f() }.statusCode.value()

    @Test
    fun `一覧は主体のテナントだけで no-store`() {
        val r = api.list(req())
        assertEquals("no-store", r.headers.cacheControl)
        assertEquals(listOf("pc-1"), assertNotNull(r.body).map { it.agentId })
    }

    @Test
    fun `失効すると 失効者は本人・時刻は時計で 監査記録に理由と前後の状態が残る（資格情報は含まない）`() {
        val r = api.revoke(req(), "pc-1", RevokeAgentRequest("  PC を紛失  "))
        val dto = assertNotNull(r.body)
        assertEquals("REVOKED", dto.status)
        assertEquals("me@example.com", dto.revokedBy)
        assertEquals(now, dto.revokedAt)
        val a = audits.single()
        assertEquals("AGENT_REVOKE", a.action)
        assertEquals("agent", a.targetType)
        assertEquals("pc-1", a.targetId)
        assertEquals("PC を紛失", a.reason)
        assertTrue(a.beforeJson!!.contains("\"status\":\"ACTIVE\"") && a.afterJson!!.contains("\"status\":\"REVOKED\""))
    }

    @Test
    fun `二重の失効は 409・他テナントや無いエージェントは 404・不正な ID と理由は 400 で 何も記録しない`() {
        api.revoke(req(), "pc-1", RevokeAgentRequest("紛失"))
        audits.clear()
        assertEquals(409, status { api.revoke(req(), "pc-1", RevokeAgentRequest("再度")) })
        assertEquals(404, status { api.revoke(req(), "pc-other", RevokeAgentRequest("他テナント")) })
        assertEquals(404, status { api.revoke(req(), "nope", RevokeAgentRequest("無い")) })
        assertEquals(400, status { api.revoke(req(), "bad id!", RevokeAgentRequest("x")) })
        for (bad in listOf(null, "", "   ", "x".repeat(501), "改行\nあり")) assertEquals(400, status { api.revoke(req(), "pc-1", RevokeAgentRequest(bad)) })
        assertTrue(audits.isEmpty())
        assertEquals(AgentStatus.ACTIVE, store.rows.single { it.agentId.value == "pc-other" }.status, "他テナントのエージェントは変わらない")
    }

    @Test
    fun `テナントは主体から決まる（テナント 2 の管理者はテナント 1 のエージェントを見られず失効できない）`() {
        val t2 = TenantId.of(2)
        assertEquals(listOf("pc-other"), assertNotNull(api.list(req(t2)).body).map { it.agentId })
        assertEquals(404, status { api.revoke(req(t2), "pc-1", RevokeAgentRequest("他テナント")) })
        assertEquals(AgentStatus.ACTIVE, store.rows.single { it.agentId.value == "pc-1" }.status)
        val r = api.revoke(req(t2), "pc-other", RevokeAgentRequest("自テナント"))
        assertEquals("REVOKED", assertNotNull(r.body).status)
        assertEquals(t2, audits.single().tenantId)
    }

    @Test
    fun `監査記録を書けなければ 500 で失効も取り消す`() {
        auditFails = true
        assertEquals(500, status { api.revoke(req(), "pc-1", RevokeAgentRequest("紛失")) })
        assertEquals(AgentStatus.ACTIVE, store.rows.single { it.agentId.value == "pc-1" }.status)
    }

    @Test
    fun `監査の JSON は引用符・バックスラッシュ・制御文字をエスケープする`() {
        val odd = agent(t1, "pc-2").copy(name = "a\"b\\c\u0001")
        val json = AgentAdminApi.json(odd)
        assertTrue(json.contains("\"name\":\"a\\\"b\\\\c\\u0001\""), json)
    }
}
