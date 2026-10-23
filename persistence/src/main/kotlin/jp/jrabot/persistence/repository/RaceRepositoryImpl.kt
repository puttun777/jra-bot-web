package jp.jrabot.persistence.repository

import jp.jrabot.domain.race.Race
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.RaceDao
import jp.jrabot.persistence.dao.RaceEntity
import jp.jrabot.persistence.mapping.toDbTime
import java.time.LocalDate

internal class RaceRepositoryImpl(
    private val dao: RaceDao,
    private val clock: Clock,
) : RaceRepository {
    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): Race? = dao.selectById(tenantId.value, raceId.value)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<Race> = dao.selectByDate(tenantId.value, date).map { it.toDomain() }

    /** 1 文の `INSERT … ON DUPLICATE KEY UPDATE`（原子的）。発走時刻が null なら「不明」として保存する。 */
    override fun upsert(race: Race): UpsertOutcome = upsertOutcomeOf(dao.upsert(race.toEntity()).count)

    private fun Race.toEntity(): RaceEntity {
        val now = clock.now().toDbTime()
        return RaceEntity(
            tenantId = tenantId.value,
            raceId = id.value,
            raceDate = id.date,
            venueCode = id.venue.code,
            raceNo = id.raceNo,
            kai = kai,
            nichime = nichime,
            postTime = postTime,
            postTimeUnknown = postTime == null,
            name = name,
            distanceM = distanceMeters,
            createdAt = now,
            updatedAt = now,
        )
    }
}

internal fun RaceEntity.toDomain(): Race =
    Race(
        tenantId = TenantId.of(tenantId),
        id = RaceId.parse(raceId),
        kai = kai,
        nichime = nichime,
        postTime = postTime,
        name = name,
        distanceMeters = distanceM,
    )
