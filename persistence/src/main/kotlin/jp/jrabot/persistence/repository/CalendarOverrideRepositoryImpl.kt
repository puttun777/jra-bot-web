package jp.jrabot.persistence.repository

import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.OverrideAction
import jp.jrabot.domain.calendar.OverrideCreateResult
import jp.jrabot.domain.calendar.OverrideRevokeResult
import jp.jrabot.domain.calendar.OverrideScope
import jp.jrabot.domain.calendar.OverrideValue
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.CalendarOverrideDao
import jp.jrabot.persistence.dao.CalendarOverrideEntity
import jp.jrabot.persistence.dao.ScheduleEventDao
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.time.LocalDate

/**
 * `calendar_override`（手動補正）。補正の追記・取消は、履歴（MANUAL_OVERRIDE・MANUAL_REVOKE）の追記と**同じトランザクション**。
 * 内容は書き換えず、取消だけが更新（`revoked_*`。未取消の行だけ。同時に取り消しても勝者は 1 つで履歴も 1 行）。
 * 書込みには `app` ユーザー（INSERT・UPDATE の権限）が必要。`bot` は読取りだけ（権限で拒否される）。
 */
internal class CalendarOverrideRepositoryImpl(
    private val dao: CalendarOverrideDao,
    events: ScheduleEventDao,
    private val tx: TransactionOperations,
) : CalendarOverrideRepository {
    private val store = ScheduleEventStore(events)

    override fun find(
        tenantId: TenantId,
        id: Long,
    ): CalendarOverride? = dao.selectById(tenantId.value, id)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<CalendarOverride> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    override fun create(
        override: CalendarOverride,
        event: ScheduleEvent,
    ): OverrideCreateResult {
        require(override.id == null && override.revokedAt == null) { "新規の補正は id なし・未取消" }
        require(event.type == ScheduleEventType.MANUAL_OVERRIDE) { "履歴の種類は MANUAL_OVERRIDE: ${event.type}" }
        requireSame(override, event)
        return try {
            tx.inTransaction {
                if (store.exists(event)) return@inTransaction OverrideCreateResult.Duplicate
                val saved = dao.insert(override.toEntity()).entity
                val stored = store.insert(event)
                OverrideCreateResult.Created(checkNotNull(dao.selectById(saved.tenantId, checkNotNull(saved.id))).toDomain(), stored)
            }
        } catch (e: RuntimeException) {
            if (ScheduleEventStore.isDuplicateEvent(e)) OverrideCreateResult.Duplicate else throw e
        }
    }

    override fun revoke(
        tenantId: TenantId,
        id: Long,
        revokedBy: String,
        revokedAt: Instant,
        event: ScheduleEvent,
    ): OverrideRevokeResult {
        require(event.type == ScheduleEventType.MANUAL_REVOKE && event.tenantId == tenantId) { "履歴が MANUAL_REVOKE・同じテナントではない" }
        return try {
            tx.inTransaction {
                val current = dao.selectById(tenantId.value, id) ?: return@inTransaction OverrideRevokeResult.NotFound
                requireSame(current.toDomain(), event)
                if (current.revokedAt != null) return@inTransaction OverrideRevokeResult.AlreadyRevoked
                val probe = current.copy(revokedAt = revokedAt.toDbTime(), revokedBy = revokedBy)
                if (dao.revoke(probe).count != 1) return@inTransaction OverrideRevokeResult.AlreadyRevoked
                if (store.exists(event)) throw IllegalStateException("取消の履歴が既にある（未取消の補正に対して）")
                val stored = store.insert(event)
                OverrideRevokeResult.Revoked(checkNotNull(dao.selectById(tenantId.value, id)).toDomain(), stored)
            }
        } catch (e: RuntimeException) {
            if (ScheduleEventStore.isDuplicateEvent(e)) OverrideRevokeResult.AlreadyRevoked else throw e
        }
    }

    /** 履歴が補正と同じ対象（テナント・日付・レース）のものか。 */
    private fun requireSame(
        override: CalendarOverride,
        event: ScheduleEvent,
    ) {
        require(event.tenantId == override.tenantId && event.raceDate == override.raceDate && event.raceId == override.raceId) { "履歴が補正の対象（テナント・日付・レース）と一致しない" }
    }

    private fun CalendarOverride.toEntity() =
        CalendarOverrideEntity(
            id = id,
            tenantId = tenantId.value,
            scope = scope.name,
            raceDate = raceDate,
            raceId = raceId?.value,
            action = action.name,
            value = value?.toJson(),
            reason = reason,
            createdBy = createdBy,
            createdAt = createdAt.toDbTime(),
            validUntil = validUntil.toDbTimeOrNull(),
            revokedAt = revokedAt.toDbTimeOrNull(),
            revokedBy = revokedBy,
        )
}

internal fun CalendarOverrideEntity.toDomain(): CalendarOverride {
    val action = enumFromDb<OverrideAction>(action, "calendar_override.action")
    return CalendarOverride(
        tenantId = TenantId.of(tenantId),
        id = id,
        scope = enumFromDb<OverrideScope>(scope, "calendar_override.scope"),
        raceDate = raceDate,
        raceId = raceId?.let { RaceId.parse(it) },
        action = action,
        value = OverrideValue.parse(action, value),
        reason = reason,
        createdBy = createdBy,
        createdAt = createdAt.toInstantUtc(),
        validUntil = validUntil.toInstantUtcOrNull(),
        revokedAt = revokedAt.toInstantUtcOrNull(),
        revokedBy = revokedBy,
    )
}
