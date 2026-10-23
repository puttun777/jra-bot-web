package jp.jrabot.persistence.repository

import jp.jrabot.domain.calendar.AppendResult
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.ScheduleEventDao
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDate

/** 追記専用（読取りと [append] だけ）。重複は [AppendResult.Duplicate]（冪等）。 */
internal class ScheduleEventRepositoryImpl(
    private val dao: ScheduleEventDao,
    private val tx: TransactionOperations,
) : ScheduleEventRepository {
    private val store = ScheduleEventStore(dao)

    override fun eventsOfRace(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<ScheduleEvent> = dao.selectByRace(tenantId.value, raceId.value).map { it.toDomain() }

    override fun eventsOfDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<ScheduleEvent> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    override fun append(event: ScheduleEvent): AppendResult =
        try {
            tx.inTransaction {
                if (store.exists(event)) {
                    AppendResult.Duplicate
                } else {
                    AppendResult.Appended(store.insert(event))
                }
            }
        } catch (e: RuntimeException) {
            if (ScheduleEventStore.isDuplicateEvent(e)) AppendResult.Duplicate else throw e
        }
}
