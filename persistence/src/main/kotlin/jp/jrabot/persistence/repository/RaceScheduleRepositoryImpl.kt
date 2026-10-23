package jp.jrabot.persistence.repository

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.repository.UpdateResult
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.RaceScheduleDao
import jp.jrabot.persistence.dao.RaceScheduleEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.LocalDate

/** `race_schedule_event` の追記（同じトランザクションでの変更履歴）は T3.3 でこのクラスを拡張する。 */
internal class RaceScheduleRepositoryImpl(
    private val dao: RaceScheduleDao,
    private val tx: TransactionOperations,
) : RaceScheduleRepository {
    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): RaceSchedule? = dao.selectById(tenantId.value, raceId.value)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceSchedule> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    /** 主キー `(tenant_id, race_id)` の重複（1062・PRIMARY）だけを [InsertOutcome.ALREADY_EXISTS] にする。既存の行は変更しない。 */
    override fun insertIfAbsent(schedule: RaceSchedule): InsertOutcome {
        require(schedule.version == 0) { "新規の version は 0: ${schedule.version}" }
        return try {
            dao.insert(schedule.toEntity())
            InsertOutcome.INSERTED
        } catch (e: RuntimeException) {
            if (e.isDuplicateOn("race_schedule", "PRIMARY")) InsertOutcome.ALREADY_EXISTS else throw e
        }
    }

    /** 楽観ロック更新（[BotRunRepositoryImpl.update] と同じ規則）。更新対象は予定時刻・状態・データ区分・版・レース名・最終変更。 */
    override fun update(schedule: RaceSchedule): UpdateResult<RaceSchedule> {
        val entity = schedule.toEntity()
        return tx.inTransaction {
            val count = dao.updateIfVersion(entity).count
            val current = dao.selectById(entity.tenantId, entity.raceId)
            when {
                count == 1 -> UpdateResult.Updated(checkNotNull(current).toDomain())
                current == null -> UpdateResult.NotFound
                else -> UpdateResult.Conflict
            }
        }
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
