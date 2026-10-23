package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class OverrideScope { DAY, RACE }

/**
 * 手動補正の操作（RACE_CALENDAR_DESIGN §7）。[scopes] はその操作を付けられる対象。
 * 優先順位は [EffectiveCalendar] が決める（DENY_RUN > CANCEL・MARK_NO_RACE > ALLOW_RUN・MARK_RACE_DAY・SET_POST_TIME・POSTPONE_TO > 元の予定）。
 */
enum class OverrideAction(
    val scopes: Set<OverrideScope>,
) {
    MARK_RACE_DAY(setOf(OverrideScope.DAY)),
    MARK_NO_RACE(setOf(OverrideScope.DAY)),
    CANCEL(setOf(OverrideScope.DAY, OverrideScope.RACE)),
    SET_POST_TIME(setOf(OverrideScope.RACE)),
    POSTPONE_TO(setOf(OverrideScope.DAY)),
    ALLOW_RUN(setOf(OverrideScope.DAY)),
    DENY_RUN(setOf(OverrideScope.DAY)),
}

/** 手動補正の値（`calendar_override.value` の JSON）。形は操作ごとに 1 つ。 */
sealed interface OverrideValue {
    fun toJson(): String

    /** [OverrideAction.SET_POST_TIME]: 発走時刻（UTC の瞬間）。JSON は `{"post_at":"2026-11-01T06:35:00Z"}`。 */
    data class PostAt(
        val at: Instant,
    ) : OverrideValue {
        override fun toJson(): String = EventJson.obj("post_at" to at.toString())
    }

    /** [OverrideAction.POSTPONE_TO]: 延期先の開催日（JST の暦日）。JSON は `{"to":"2026-11-02"}`。 */
    data class PostponedTo(
        val date: LocalDate,
    ) : OverrideValue {
        override fun toJson(): String = EventJson.obj("to" to date.toString())
    }

    companion object {
        private val POST_AT = Regex("""\{\s*"post_at"\s*:\s*"([^"\\]+)"\s*}""")
        private val TO = Regex("""\{\s*"to"\s*:\s*"([^"\\]+)"\s*}""")

        /** DB の JSON（MySQL は空白を整形して返す）を読む。形が違えば [IllegalStateException]（黙って無視しない）。 */
        fun parse(
            action: OverrideAction,
            json: String?,
        ): OverrideValue? =
            when (action) {
                OverrideAction.SET_POST_TIME -> {
                    val text = json?.let { POST_AT.matchEntire(it.trim())?.groupValues?.get(1) } ?: throw IllegalStateException("SET_POST_TIME の value が不正")
                    try {
                        PostAt(Instant.parse(text))
                    } catch (_: DateTimeParseException) {
                        throw IllegalStateException("SET_POST_TIME の value の時刻が不正")
                    }
                }

                OverrideAction.POSTPONE_TO -> {
                    val text = json?.let { TO.matchEntire(it.trim())?.groupValues?.get(1) } ?: throw IllegalStateException("POSTPONE_TO の value が不正")
                    try {
                        PostponedTo(LocalDate.parse(text))
                    } catch (_: DateTimeException) {
                        throw IllegalStateException("POSTPONE_TO の value の日付が不正")
                    }
                }

                else -> {
                    check(json == null) { "$action は value を持たない" }
                    null
                }
            }
    }
}

/**
 * 手動補正（`calendar_override`）。監査情報（[reason]・[createdBy]・[createdAt]）は必須で、失効は [validUntil]（期限）か取消（[revokedAt]・[revokedBy]）。
 * 補正は**追記と取消だけ**で、内容を書き換えない（履歴が監査の根拠）。
 *
 * 適用中か（[isActiveAt]）: 取消されておらず、期限（[validUntil]。ちょうどの時刻は期限切れ）が来ていない。
 */
data class CalendarOverride(
    override val tenantId: TenantId,
    val id: Long?,
    val scope: OverrideScope,
    val raceDate: LocalDate,
    val raceId: RaceId?,
    val action: OverrideAction,
    val value: OverrideValue?,
    val reason: String,
    val createdBy: String,
    val createdAt: Instant,
    val validUntil: Instant? = null,
    val revokedAt: Instant? = null,
    val revokedBy: String? = null,
) : TenantScoped {
    init {
        require(id == null || id >= 1) { "id は 1 以上" }
        require((scope == OverrideScope.RACE) == (raceId != null)) { "RACE の補正だけが raceId を持つ" }
        require(raceId == null || raceId.date == raceDate) { "raceDate が raceId の日付と一致しない" }
        require(scope in action.scopes) { "$action は $scope には使えない" }
        when (action) {
            OverrideAction.SET_POST_TIME -> {
                val v = value
                require(v is OverrideValue.PostAt) { "SET_POST_TIME は PostAt が必須" }
                require(v.at.atZone(Zones.JST).toLocalDate() == raceDate) { "発走時刻の日付（JST）が開催日と違う" }
            }

            OverrideAction.POSTPONE_TO -> {
                val v = value
                require(v is OverrideValue.PostponedTo) { "POSTPONE_TO は延期先の日付が必須" }
                require(v.date.isAfter(raceDate)) { "延期先は開催日より後" }
            }

            else -> {
                require(value == null) { "$action は value を持たない" }
            }
        }
        require(reason.isNotBlank() && reason.length <= 500) { "reason は 1〜500 文字（空白のみは不可）" }
        require(validCreator(createdBy)) { "createdBy は 1〜254 文字の制御文字を含まない文字列" }
        require(validUntil == null || validUntil.isAfter(createdAt)) { "validUntil は createdAt より後" }
        require((revokedAt == null) == (revokedBy == null)) { "revokedAt と revokedBy は両方ある・両方ない" }
        require(revokedBy == null || validCreator(revokedBy)) { "revokedBy が不正" }
        require(revokedAt == null || !revokedAt.isBefore(createdAt)) { "revokedAt は createdAt 以降" }
    }

    fun isActiveAt(now: Instant): Boolean = revokedAt == null && (validUntil == null || now.isBefore(validUntil))

    private fun validCreator(s: String) = s.isNotBlank() && s.length <= 254 && s.none { it.isISOControl() }
}

sealed interface OverrideCreateResult {
    data class Created(
        val override: CalendarOverride,
        val event: ScheduleEvent,
    ) : OverrideCreateResult

    /** 同じ補正（同じ作成者・同じ内容・同じ時刻）の履歴が既にある。何も追加していない。 */
    data object Duplicate : OverrideCreateResult
}

sealed interface OverrideRevokeResult {
    data class Revoked(
        val override: CalendarOverride,
        val event: ScheduleEvent,
    ) : OverrideRevokeResult

    data object AlreadyRevoked : OverrideRevokeResult

    /** 無い（別テナントの補正を含む）。 */
    data object NotFound : OverrideRevokeResult
}

interface CalendarOverrideReader {
    fun find(
        tenantId: TenantId,
        id: Long,
    ): CalendarOverride?

    /** その日の補正（取消・期限切れを含む全件。適用中かは [CalendarOverride.isActiveAt]）。作成の昇順（`id` の昇順）。 */
    fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<CalendarOverride>
}

/** 補正の追記と取消。**どちらも履歴（MANUAL_OVERRIDE・MANUAL_REVOKE）の追記と同じトランザクション**。 */
interface CalendarOverrideWriter {
    fun create(
        override: CalendarOverride,
        event: ScheduleEvent,
    ): OverrideCreateResult

    /** 未取消のものだけを取り消す（同時に 2 回取り消しても履歴は 1 行）。 */
    fun revoke(
        tenantId: TenantId,
        id: Long,
        revokedBy: String,
        revokedAt: Instant,
        event: ScheduleEvent,
    ): OverrideRevokeResult
}

interface CalendarOverrideRepository :
    CalendarOverrideReader,
    CalendarOverrideWriter

sealed interface CreateOverrideOutcome {
    data class Created(
        val override: CalendarOverride,
    ) : CreateOverrideOutcome

    data object Duplicate : CreateOverrideOutcome

    /** RACE の補正の対象レースが予定に無い。 */
    data object RaceNotFound : CreateOverrideOutcome
}

/**
 * 手動補正のアプリケーションサービス（Domain・Persistence の範囲。**認証・認可はしない**。管理 API・画面は Stage 4 で、
 * 管理者の認可（ADMIN ロール・監査ログ）を行ってからこのサービスを呼ぶ。この層を直接公開する入口は作らない）。
 */
class CalendarOverrideService(
    private val overrides: CalendarOverrideRepository,
    private val races: RaceScheduleReader,
    private val clock: Clock,
) {
    fun create(
        tenantId: TenantId,
        scope: OverrideScope,
        raceDate: LocalDate,
        raceId: RaceId?,
        action: OverrideAction,
        value: OverrideValue?,
        reason: String,
        createdBy: String,
        validUntil: Instant? = null,
    ): CreateOverrideOutcome {
        val now = clock.now()
        val override = CalendarOverride(tenantId, null, scope, raceDate, raceId, action, value, reason.trim(), createdBy, now, validUntil)
        if (raceId != null && races.find(tenantId, raceId) == null) return CreateOverrideOutcome.RaceNotFound
        val event =
            ScheduleEvent.create(
                tenantId = tenantId,
                raceId = raceId,
                raceDate = raceDate,
                type = ScheduleEventType.MANUAL_OVERRIDE,
                oldValue = null,
                newValue =
                    EventJson.obj(
                        "action" to action.name,
                        "scope" to scope.name,
                        "value" to value?.toJson(),
                        "valid_until" to validUntil?.toString(),
                        "created_by" to createdBy,
                        "reason" to override.reason,
                    ),
                source = ScheduleEventSource.MANUAL,
                observedAt = now,
                receivedAt = now,
                dedupGroup = "override:$now",
            )
        return when (val r = overrides.create(override, event)) {
            is OverrideCreateResult.Created -> CreateOverrideOutcome.Created(r.override)
            OverrideCreateResult.Duplicate -> CreateOverrideOutcome.Duplicate
        }
    }

    fun revoke(
        tenantId: TenantId,
        id: Long,
        revokedBy: String,
    ): OverrideRevokeResult {
        require(revokedBy.isNotBlank() && revokedBy.length <= 254 && revokedBy.none { it.isISOControl() }) { "revokedBy は 1〜254 文字の制御文字を含まない文字列" }
        val current = overrides.find(tenantId, id) ?: return OverrideRevokeResult.NotFound
        if (current.revokedAt != null) return OverrideRevokeResult.AlreadyRevoked
        val now = clock.now()
        val event =
            ScheduleEvent.create(
                tenantId = tenantId,
                raceId = current.raceId,
                raceDate = current.raceDate,
                type = ScheduleEventType.MANUAL_REVOKE,
                oldValue = EventJson.obj("override_id" to id, "action" to current.action.name),
                newValue = EventJson.obj("revoked_by" to revokedBy),
                source = ScheduleEventSource.MANUAL,
                observedAt = now,
                receivedAt = now,
                dedupGroup = "revoke:$id",
            )
        return overrides.revoke(tenantId, id, revokedBy, now, event)
    }
}
