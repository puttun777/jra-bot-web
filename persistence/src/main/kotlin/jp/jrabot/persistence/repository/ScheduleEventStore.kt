package jp.jrabot.persistence.repository

import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.ScheduleEventDao
import jp.jrabot.persistence.dao.ScheduleEventEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc

/**
 * `race_schedule_event` への追記の共通部品（予定・開催日・手動補正の各 Repository が、自分のトランザクションの中で使う）。
 * 更新・削除は持たない。重複（`(tenant_id, dedup_hash)`）は挿入が 1062（`uq_race_schedule_event_dedup`）で失敗する。
 */
internal class ScheduleEventStore(
    private val dao: ScheduleEventDao,
) {
    fun exists(event: ScheduleEvent): Boolean = dao.selectByHash(event.tenantId.value, event.dedupHash) != null

    /** 追記して `id` つきの履歴を返す。重複なら例外（呼び出し側が [isDuplicateEvent] で判定する）。 */
    fun insert(event: ScheduleEvent): ScheduleEvent {
        dao.insert(event.toEntity())
        return checkNotNull(dao.selectByHash(event.tenantId.value, event.dedupHash)) { "追記したイベントが読めない" }.toDomain()
    }

    companion object {
        const val DEDUP_KEY = "uq_race_schedule_event_dedup"

        fun isDuplicateEvent(e: Throwable): Boolean = e.isDuplicateOn("race_schedule_event", DEDUP_KEY)
    }
}

internal fun ScheduleEvent.toEntity(): ScheduleEventEntity =
    ScheduleEventEntity(
        id = 0,
        tenantId = tenantId.value,
        raceId = raceId?.value,
        raceDate = raceDate,
        eventType = type.name,
        oldValue = oldValue,
        newValue = newValue,
        source = source.dbValue,
        observedAt = observedAt.toDbTime(),
        receivedAt = receivedAt.toDbTime(),
        batchId = batchId,
        dedupHash = dedupHash,
    )

internal fun ScheduleEventEntity.toDomain(): ScheduleEvent =
    ScheduleEvent(
        tenantId = TenantId.of(tenantId),
        id = id,
        raceId = raceId?.let { RaceId.parse(it) },
        raceDate = raceDate,
        type = enumFromDb<ScheduleEventType>(eventType, "race_schedule_event.event_type"),
        oldValue = oldValue,
        newValue = newValue,
        source = ScheduleEventSource.fromDbValue(source),
        observedAt = observedAt.toInstantUtc(),
        receivedAt = receivedAt.toInstantUtc(),
        batchId = batchId,
        dedupHash = dedupHash,
    )
