package jp.jrabot.dashboard.api

import jakarta.servlet.http.HttpServletRequest
import jp.jrabot.domain.agent.AgentAdminStore
import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.AgentRevokeResult
import jp.jrabot.domain.agent.AgentSummary
import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.domain.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/** エージェントの 1 行（R20）。資格情報（トークン・ハッシュ・署名鍵）は含めない。 */
data class AgentDto(
    val agentId: String,
    val name: String,
    val tokenExpiresAt: Instant,
    val status: String,
    val createdAt: Instant,
    val createdBy: String?,
    val revokedAt: Instant?,
    val revokedBy: String?,
)

data class RevokeAgentRequest(
    /** 必須（監査記録の理由。1〜500 文字）。 */
    val reason: String?,
)

/**
 * Windows 取得エージェントの一覧と失効（R20。D-270。CONTRACTS §3.3「失効・盗難時: 管理者 API で失効 → 以後 403」）。
 * `/api/v1/admin` の下: 読取りも ADMIN だけ（[ApiAuthFilter]）、POST は CSRF の印（CsrfGuardFilter）と運用モード NORMAL（AdminWriteGuardFilter）が要る。
 * 失効と監査記録は**同じトランザクション**（監査を書けなければ失効も取り消す）。再有効化・発行は持たない。ingest は要求ごとに DB から読むので、次の要求から 403。
 */
@RestController
@RequestMapping("/api/v1/admin")
class AgentAdminApi(
    private val clock: Clock,
    private val agents: AgentAdminStore,
    private val audit: AuditLogWriter,
    private val tx: TransactionOperations,
) {
    @GetMapping("/agents")
    fun list(request: HttpServletRequest): ResponseEntity<List<AgentDto>> {
        val p = ApiAuthFilter.principalOf(request)
        return ok(HttpStatus.OK, agents.list(p.tenantId).map(::dto))
    }

    @PostMapping("/agents/{agentId}/revoke")
    fun revoke(
        request: HttpServletRequest,
        @PathVariable agentId: String,
        @RequestBody body: RevokeAgentRequest,
    ): ResponseEntity<AgentDto> {
        val p = ApiAuthFilter.principalOf(request)
        val id = AgentId.ofOrNull(agentId) ?: throw bad()
        val reason = body.reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 && it.none(Char::isISOControl) } ?: throw bad()
        val result =
            try {
                tx.execute {
                    val before = agents.find(p.tenantId, id)
                    val r = agents.revoke(p.tenantId, id, p.email.value, clock.now())
                    if (r is AgentRevokeResult.Revoked) record(request, id, before, r.agent, reason)
                    r
                }
            } catch (e: AuditFailure) {
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "audit_failed", e)
            } catch (_: UnexpectedRollbackException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "already_revoked")
            }
        return when (result) {
            is AgentRevokeResult.Revoked -> {
                log.info("AGENT_REVOKED agent={}", id.value) // 資格情報・理由の本文は出さない（理由は監査記録だけ）
                ok(HttpStatus.OK, dto(result.agent))
            }

            AgentRevokeResult.AlreadyRevoked -> {
                throw ResponseStatusException(HttpStatus.CONFLICT, "already_revoked")
            }

            AgentRevokeResult.NotFound -> {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "not_found")
            }
        }
    }

    /** 監査記録。失効と同じトランザクションの中で書く。書けなければ [AuditFailure]（失効も取り消される）。 */
    private fun record(
        request: HttpServletRequest,
        id: AgentId,
        before: AgentSummary?,
        after: AgentSummary,
        reason: String,
    ) {
        val p = ApiAuthFilter.principalOf(request)
        try {
            audit.append(
                AuditEntry(
                    p.tenantId,
                    p.email.value,
                    "AGENT_REVOKE",
                    "agent",
                    id.value,
                    before?.let(::json),
                    json(after),
                    reason,
                    UUID.randomUUID().toString(),
                ),
                clock.now(),
            )
        } catch (e: Exception) {
            log.error("AUDIT_FAILED action=AGENT_REVOKE target={} error={}（失効は取り消される）", id.value, e.javaClass.simpleName)
            throw AuditFailure(e)
        }
    }

    private class AuditFailure(
        cause: Exception,
    ) : RuntimeException("audit failed", cause)

    private fun bad() = ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request")

    private fun <T : Any> ok(
        status: HttpStatus,
        body: T,
    ): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    companion object {
        private val log = LoggerFactory.getLogger(AgentAdminApi::class.java)

        fun dto(a: AgentSummary) = AgentDto(a.agentId.value, a.name, a.tokenExpiresAt, a.status.name, a.createdAt, a.createdBy, a.revokedAt, a.revokedBy)

        /** 監査に残す JSON（資格情報を含まない値だけ。文字列は JSON としてエスケープ）。 */
        fun json(a: AgentSummary): String {
            fun q(v: Any?): String =
                if (v == null) {
                    "null"
                } else {
                    "\"" +
                        v
                            .toString()
                            .flatMap { c ->
                                when {
                                    c == '"' -> listOf('\\', '"')
                                    c == '\\' -> listOf('\\', '\\')
                                    c.isISOControl() -> String.format("\\u%04x", c.code).toList()
                                    else -> listOf(c)
                                }
                            }.joinToString("") + "\""
                }
            return "{\"agent_id\":${q(
                a.agentId.value,
            )},\"name\":${q(a.name)},\"status\":${q(a.status.name)},\"revoked_at\":${q(a.revokedAt)},\"revoked_by\":${q(a.revokedBy)}}"
        }
    }
}
