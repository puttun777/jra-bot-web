package jp.jrabot.dashboard.api

import jakarta.servlet.http.HttpServletRequest
import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.CalendarOverrideService
import jp.jrabot.domain.calendar.CreateOverrideOutcome
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.calendar.OverrideRevokeResult
import jp.jrabot.domain.calendar.OverrideScope
import jp.jrabot.domain.calendar.OverrideValue
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

// 管理 API（R20）。**管理者だけ**（[ApiAuthFilter]: `/api/v1/admin` 以下は読取りも ADMIN）。書込みは CSRF 対策（[jp.jrabot.dashboard.guard.CsrfGuardFilter]）と運用モードのガード
// （[jp.jrabot.dashboard.guard.AdminWriteGuardFilter]。NORMAL 以外は 409）を通った後だけ。**すべての書込みを監査記録（audit_log）に残す**。テナントは本人の行から決まる。

data class CreateOverrideRequest(
    val scope: String?,
    val date: String?,
    val raceId: String?,
    val action: String?,
    val postAt: String?,
    val postponedTo: String?,
    val reason: String?,
    val validUntil: String?,
)

data class OverrideDto(
    val id: Long?,
    val scope: String,
    val date: LocalDate,
    val raceId: String?,
    val action: String,
    val value: String?,
    val reason: String,
    val createdBy: String,
    val createdAt: Instant,
    val validUntil: Instant?,
    val revokedAt: Instant?,
    val revokedBy: String?,
)

@RestController
@RequestMapping("/api/v1/admin")
class AdminApi(
    private val clock: Clock,
    private val service: CalendarOverrideService,
    private val reader: CalendarOverrideReader,
    private val audit: AuditLogWriter,
    /** 変更と監査記録を**同じトランザクション**にする（監査を書けなければ変更も取り消す）。 */
    private val tx: TransactionOperations,
) {
    @GetMapping("/overrides")
    fun list(
        request: HttpServletRequest,
        @RequestParam(required = false) date: String?,
    ): ResponseEntity<List<OverrideDto>> {
        val p = ApiAuthFilter.principalOf(request)
        val d = parseDate(date) ?: clock.today()
        return ok(HttpStatus.OK, reader.listByDate(p.tenantId, d).map(::dto))
    }

    @PostMapping("/overrides")
    fun create(
        request: HttpServletRequest,
        @RequestBody body: CreateOverrideRequest,
    ): ResponseEntity<OverrideDto> {
        val p = ApiAuthFilter.principalOf(request)
        val scope = enumOf<OverrideScope>(body.scope)
        val action = enumOf<OverrideAction>(body.action)
        val date = parseDate(body.date) ?: throw bad()
        val raceId = body.raceId?.let { RaceId.parseOrNull(it) ?: throw bad() }
        val reason = body.reason?.trim().orEmpty()
        if (reason.isEmpty() || reason.length > 500) throw bad()
        val value =
            when (action) {
                OverrideAction.SET_POST_TIME -> OverrideValue.PostAt(parseInstant(body.postAt) ?: throw bad())
                OverrideAction.POSTPONE_TO -> OverrideValue.PostponedTo(parseDate(body.postponedTo) ?: throw bad())
                else -> null
            }
        // 変更と監査記録を 1 つのトランザクションで行う（service/repository は同じ TransactionOperations に参加する）。監査に失敗すれば例外 → 変更も取り消される
        val created =
            try {
                tx.execute {
                    val r = service.create(p.tenantId, scope, date, raceId, action, value, reason, p.email.value, parseInstant(body.validUntil))
                    if (r is CreateOverrideOutcome.Created) record(request, "CALENDAR_OVERRIDE_CREATE", r.override.id, null, dto(r.override), reason)
                    r
                }
            } catch (_: IllegalArgumentException) {
                throw bad() // ドメインの検証（scope と action の組合せ・日付の整合など）。メッセージは出さない
            } catch (e: AuditFailure) {
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "audit_failed", e)
            }
        return when (created) {
            is CreateOverrideOutcome.Created -> {
                ok(HttpStatus.CREATED, dto(created.override))
            }

            CreateOverrideOutcome.Duplicate -> {
                throw ResponseStatusException(HttpStatus.CONFLICT, "duplicate")
            }

            CreateOverrideOutcome.RaceNotFound -> {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "race_not_found")
            }
        }
    }

    @PostMapping("/overrides/{id}/revoke")
    fun revoke(
        request: HttpServletRequest,
        @PathVariable id: Long,
    ): ResponseEntity<OverrideDto> {
        val p = ApiAuthFilter.principalOf(request)
        if (id < 1) throw bad()
        val result =
            try {
                tx.execute {
                    val before = reader.find(p.tenantId, id)?.let(::dto)
                    val r = service.revoke(p.tenantId, id, p.email.value)
                    if (r is OverrideRevokeResult.Revoked) record(request, "CALENDAR_OVERRIDE_REVOKE", id, before, dto(r.override), null)
                    r
                }
            } catch (e: AuditFailure) {
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "audit_failed", e)
            }
        return when (result) {
            is OverrideRevokeResult.Revoked -> ok(HttpStatus.OK, dto(result.override))
            OverrideRevokeResult.AlreadyRevoked -> throw ResponseStatusException(HttpStatus.CONFLICT, "already_revoked")
            OverrideRevokeResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "not_found")
        }
    }

    /** 監査記録。変更と**同じトランザクションの中**で書く。書けなければ [AuditFailure]（呼び出し側のトランザクションが取り消す）。 */
    private fun record(
        request: HttpServletRequest,
        action: String,
        id: Long?,
        before: OverrideDto?,
        after: OverrideDto,
        reason: String?,
    ) {
        val p = ApiAuthFilter.principalOf(request)
        try {
            audit.append(
                AuditEntry(
                    p.tenantId,
                    p.email.value,
                    action,
                    "calendar_override",
                    id?.toString(),
                    before?.let(::json),
                    json(after),
                    reason,
                    UUID.randomUUID().toString(),
                ),
                clock.now(),
            )
        } catch (e: Exception) {
            log.error("AUDIT_FAILED action={} target={} error={}（変更は取り消される）", action, id, e.javaClass.simpleName)
            throw AuditFailure(e)
        }
    }

    private class AuditFailure(
        cause: Exception,
    ) : RuntimeException("audit failed", cause)

    /** 監査に残す JSON。秘密を含まない値だけ（enum・日付・レース ID・設定した値・期限・取消者）。値は JSON 文字列として安全にエスケープする。 */
    private fun json(d: OverrideDto): String {
        fun q(v: Any?): String = if (v == null) "null" else "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "{" +
            listOf(
                "\"id\":${d.id}",
                "\"scope\":${q(d.scope)}",
                "\"date\":${q(d.date)}",
                "\"raceId\":${q(d.raceId)}",
                "\"action\":${q(d.action)}",
                "\"value\":${q(d.value)}",
                "\"validUntil\":${q(d.validUntil)}",
                "\"revokedAt\":${q(d.revokedAt)}",
                "\"revokedBy\":${q(d.revokedBy)}",
            ).joinToString(",") +
            "}"
    }

    private fun dto(o: CalendarOverride) =
        OverrideDto(
            o.id,
            o.scope.name,
            o.raceDate,
            o.raceId?.value,
            o.action.name,
            o.value?.toJson(),
            o.reason,
            o.createdBy,
            o.createdAt,
            o.validUntil,
            o.revokedAt,
            o.revokedBy,
        )

    private inline fun <reified E : Enum<E>> enumOf(s: String?): E = enumValues<E>().firstOrNull { it.name == s } ?: throw bad()

    private fun parseDate(s: String?): LocalDate? =
        if (s.isNullOrBlank()) {
            null
        } else {
            try {
                LocalDate.parse(s)
            } catch (_: DateTimeParseException) {
                throw bad()
            }
        }

    private fun parseInstant(s: String?): Instant? =
        if (s.isNullOrBlank()) {
            null
        } else {
            try {
                Instant.parse(s)
            } catch (_: DateTimeParseException) {
                throw bad()
            }
        }

    private fun bad() = ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request")

    private fun <T : Any> ok(
        status: HttpStatus,
        body: T,
    ): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)

    companion object {
        private val log = LoggerFactory.getLogger(AdminApi::class.java)
    }
}
