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
        val created =
            try {
                service.create(p.tenantId, scope, date, raceId, action, value, reason, p.email.value, parseInstant(body.validUntil))
            } catch (_: IllegalArgumentException) {
                throw bad() // ドメインの検証（scope と action の組合せ・日付の整合など）。メッセージは出さない
            }
        return when (created) {
            is CreateOverrideOutcome.Created -> {
                val out = dto(created.override)
                record(request, "CALENDAR_OVERRIDE_CREATE", out.id, null, out, reason)
                ok(HttpStatus.CREATED, out)
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
        val before = reader.find(p.tenantId, id)?.let(::dto)
        return when (val r = service.revoke(p.tenantId, id, p.email.value)) {
            is OverrideRevokeResult.Revoked -> {
                val out = dto(r.override)
                record(request, "CALENDAR_OVERRIDE_REVOKE", id, before, out, null)
                ok(HttpStatus.OK, out)
            }

            OverrideRevokeResult.AlreadyRevoked -> {
                throw ResponseStatusException(HttpStatus.CONFLICT, "already_revoked")
            }

            OverrideRevokeResult.NotFound -> {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "not_found")
            }
        }
    }

    /** 監査記録。**変更の後**に書く。書けなかった場合は成功にせず 500 を返す（変更は済んでいる = ログに残して運用者が確認する）。 */
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
            log.error("AUDIT_FAILED action={} target={} error={}（変更は適用済み。監査記録を確認すること）", action, id, e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "audit_failed")
        }
    }

    private fun json(d: OverrideDto): String =
        "{" +
            listOf(
                "\"id\":${d.id}",
                "\"scope\":\"${d.scope}\"",
                "\"date\":\"${d.date}\"",
                "\"raceId\":${d.raceId?.let { "\"$it\"" }}",
                "\"action\":\"${d.action}\"",
                "\"revokedAt\":${d.revokedAt?.let { "\"$it\"" }}",
            ).joinToString(",") + "}"

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
