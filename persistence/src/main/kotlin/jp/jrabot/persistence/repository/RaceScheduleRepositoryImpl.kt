package jp.jrabot.persistence.repository

import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.RaceScheduleDao
import jp.jrabot.persistence.dao.RaceScheduleEntity
import jp.jrabot.persistence.dao.ScheduleEventDao
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDate

/**
 * `race_schedule` の更新と `race_schedule_event` の追記を **1 つのトランザクション**で行う（どちらか一方だけが残ることはない）。
 * 同じ履歴（`dedup_hash`）が既にあれば何も変更せず [ChangeResult.Duplicate]（挿入・更新は残さない）。
 * これらの操作は自分のトランザクションを持つ（呼び出し元が外側のトランザクションに入れると、Duplicate 時の巻き戻しが外側にも及ぶので、外側に入れない）。
 */
internal class RaceScheduleRepositoryImpl(
    private val dao: RaceScheduleDao,
    events: ScheduleEventDao,
    private val tx: TransactionOperations,
) : RaceScheduleRepository {
    private val store = ScheduleEventStore(events)

    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): RaceSchedule? = dao.selectById(tenantId.value, raceId.value)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceSchedule> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    /** 主キー `(tenant_id, race_id)` の重複（1062・PRIMARY）だけを [ChangeResult.AlreadyExists] にする。既存の行は変更しない。 */
    override fun insertIfAbsent(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule> {
        require(schedule.version == 0) { "新規の version は 0: ${schedule.version}" }
        requireMatching(schedule, event)
        return duplicateAware {
            tx.inTransaction {
                if (store.exists(event)) return@inTransaction ChangeResult.Duplicate
                try {
                    dao.insert(schedule.toEntity())
                } catch (e: RuntimeException) {
                    if (e.isDuplicateOn("race_schedule", "PRIMARY")) return@inTransaction ChangeResult.AlreadyExists
                    throw e
                }
                val stored = store.insert(event)
                ChangeResult.Applied(checkNotNull(dao.selectById(schedule.tenantId.value, schedule.raceId.value)).toDomain(), stored)
            }
        }
    }

    /** 楽観ロック更新（[BotRunRepositoryImpl] と同じ規則）。更新対象は予定時刻・状態・データ区分・版・レース名・最終変更。更新が入らなければ履歴も追記しない。 */
    override fun update(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule> {
        requireMatching(schedule, event)
        val entity = schedule.toEntity()
        return duplicateAware {
            tx.inTransaction {
                if (store.exists(event)) return@inTransaction ChangeResult.Duplicate
                if (dao.updateIfVersion(entity).count != 1) {
                    val current = dao.selectById(entity.tenantId, entity.raceId)
                    return@inTransaction if (current == null) ChangeResult.NotFound else ChangeResult.Conflict
                }
                val stored = store.insert(event)
                ChangeResult.Applied(checkNotNull(dao.selectById(entity.tenantId, entity.raceId)).toDomain(), stored)
            }
        }
    }

    private fun requireMatching(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ) {
        require(event.tenantId == schedule.tenantId && event.raceId == schedule.raceId) { "履歴がこの予定（テナント・レース）のものではない" }
    }

    private fun duplicateAware(block: () -> ChangeResult<RaceSchedule>): ChangeResult<RaceSchedule> =
        try {
            block()
        } catch (e: RuntimeException) {
            if (ScheduleEventStore.isDuplicateEvent(e)) ChangeResult.Duplicate else throw e
        }

    private fun RaceSchedule.toEntity(): RaceScheduleEntity =
        RaceScheduleEntity(
            tenantId = tenantId.value,
            raceId = raceId.value,
            raceDate = raceId.date,
            venueCode = raceId.venue.code,
            raceNo = raceId.raceNo,
            scheduledPostAt = scheduledPostAt.toDbTimeOrNull(),
            postTimeUnknown = postTimeUnknown,
            status = status.name,
            dataKubun = dataKubun.toString(),
            sourceVersion = sourceVersion,
            raceName = raceName,
            lastChangedAt = lastChangedAt.toDbTime(),
            version = version,
        )
}

internal fun RaceScheduleEntity.toDomain(): RaceSchedule =
    RaceSchedule(
        tenantId = TenantId.of(tenantId),
        raceId = RaceId.parse(raceId),
        scheduledPostAt = scheduledPostAt.toInstantUtcOrNull(),
        postTimeUnknown = postTimeUnknown,
        status = enumFromDb<RaceScheduleStatus>(status, "race_schedule.status"),
        dataKubun = dataKubun.single(),
        sourceVersion = sourceVersion,
        raceName = raceName,
        lastChangedAt = lastChangedAt.toInstantUtc(),
        version = version,
    )
