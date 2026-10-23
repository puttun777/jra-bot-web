package jp.jrabot.persistence.repository

import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.RaceDayDao
import jp.jrabot.persistence.dao.RaceDayEntity
import jp.jrabot.persistence.dao.ScheduleEventDao
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.time.LocalDate

/** `race_day` の更新と、日単位の履歴（DAY_STATUS・POSTPONED）の追記を 1 つのトランザクションで行う。 */
internal class RaceDayRepositoryImpl(
    private val dao: RaceDayDao,
    events: ScheduleEventDao,
    private val tx: TransactionOperations,
) : RaceDayRepository {
    private val store = ScheduleEventStore(events)

    override fun find(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
    ): RaceDay? = dao.selectById(tenantId.value, date, venue.code)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceDay> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    override fun insertIfAbsent(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay> {
        require(day.version == 0) { "新規の version は 0: ${day.version}" }
        requireDayEvent(day, event)
        return duplicateAware {
            tx.inTransaction {
                if (store.exists(event)) return@inTransaction ChangeResult.Duplicate
                try {
                    dao.insert(day.toEntity())
                } catch (e: RuntimeException) {
                    if (e.isDuplicateOn("race_day", "PRIMARY")) return@inTransaction ChangeResult.AlreadyExists
                    throw e
                }
                val stored = store.insert(event)
                ChangeResult.Applied(checkNotNull(dao.selectById(day.tenantId.value, day.raceDate, day.venue.code)).toDomain(), stored)
            }
        }
    }

    override fun update(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay> {
        requireDayEvent(day, event)
        val entity = day.toEntity()
        return duplicateAware {
            tx.inTransaction {
                if (store.exists(event)) return@inTransaction ChangeResult.Duplicate
                if (dao.updateIfVersion(entity).count != 1) {
                    val current = dao.selectById(entity.tenantId, entity.raceDate, entity.venueCode)
                    return@inTransaction if (current == null) ChangeResult.NotFound else ChangeResult.Conflict
                }
                val stored = store.insert(event)
                ChangeResult.Applied(checkNotNull(dao.selectById(entity.tenantId, entity.raceDate, entity.venueCode)).toDomain(), stored)
            }
        }
    }

    override fun confirm(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        observedAt: Instant,
    ): Boolean =
        tx.inTransaction {
            val probe = RaceDayEntity(tenantId.value, date, venue.code, 1, 1, "", null, observedAt.toDbTime(), observedAt.toDbTime(), 0)
            if (dao.advanceConfirmed(probe).count == 1) return@inTransaction true
            dao.selectById(tenantId.value, date, venue.code) != null
        }

    private fun requireDayEvent(
        day: RaceDay,
        event: ScheduleEvent,
    ) {
        require(event.tenantId == day.tenantId && event.raceDate == day.raceDate && event.raceId == null) { "履歴がこの開催日のもの（日単位）ではない" }
        require(event.type == ScheduleEventType.DAY_STATUS || event.type == ScheduleEventType.POSTPONED) { "開催日の履歴の種類は DAY_STATUS か POSTPONED: ${event.type}" }
    }

    private fun <T> duplicateAware(block: () -> ChangeResult<T>): ChangeResult<T> =
        try {
            block()
        } catch (e: RuntimeException) {
            if (ScheduleEventStore.isDuplicateEvent(e)) ChangeResult.Duplicate else throw e
        }

    private fun RaceDay.toEntity() =
        RaceDayEntity(
            tenantId = tenantId.value,
            raceDate = raceDate,
            venueCode = venue.code,
            kai = kai,
            nichime = nichime,
            status = status.name,
            replacedByDate = replacedByDate,
            firstSeenAt = firstSeenAt.toDbTime(),
            lastConfirmedAt = lastConfirmedAt.toDbTimeOrNull(),
            version = version,
        )
}

internal fun RaceDayEntity.toDomain(): RaceDay =
    RaceDay(
        tenantId = TenantId.of(tenantId),
        raceDate = raceDate,
        venue = Venue.fromCode(venueCode),
        kai = kai,
        nichime = nichime,
        status = enumFromDb<RaceDayStatus>(status, "race_day.status"),
        replacedByDate = replacedByDate,
        firstSeenAt = firstSeenAt.toInstantUtc(),
        lastConfirmedAt = lastConfirmedAt.toInstantUtcOrNull(),
        version = version,
    )
