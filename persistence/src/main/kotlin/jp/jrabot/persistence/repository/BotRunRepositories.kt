package jp.jrabot.persistence.repository

import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationRecord
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.RaceEvaluationDao
import jp.jrabot.persistence.dao.RaceEvaluationEntity
import jp.jrabot.persistence.dao.RaceHorseNameDao
import jp.jrabot.persistence.mapping.toDbTime

/** 判定の記録（`race_evaluations`）。冪等な追記だけ（更新・削除は T3.9 の結果反映が別に行う）。 */
internal class RaceEvaluationRepositoryImpl(
    private val dao: RaceEvaluationDao,
    private val clock: Clock,
) : RaceEvaluationWriter {
    override fun insertIfAbsent(record: RaceEvaluationRecord): InsertOutcome {
        val entity =
            RaceEvaluationEntity(
                id = 0,
                tenantId = record.tenantId.value,
                raceId = record.raceId.value,
                snapshotAt = record.snapshotAt.toDbTime(),
                modelTypeUsed = record.modelTypeUsed,
                modelId = record.modelId,
                fallbackReason = record.fallbackReason,
                betTypes = record.betTypesJson,
                priorityMode = record.priorityMode,
                evThreshold = record.evThreshold,
                winOddsMax = record.winOddsMax,
                winProbMin = record.winProbMin,
                winRatioMin = record.winRatioMin,
                bestEv = record.bestEv,
                rejectionSummaryJson = record.rejectionSummaryJson,
                signalSent = record.signalCount > 0,
                createdAt = clock.now().toDbTime(),
            )
        return if (dao.insertIfAbsent(entity).count == 1) InsertOutcome.INSERTED else InsertOutcome.ALREADY_EXISTS
    }
}

/** 出走表の馬名（`race_horses.bamei`）の読取り。すべての SQL が `tenant_id` を持つ。 */
internal class RaceEntryReaderImpl(
    private val dao: RaceHorseNameDao,
) : RaceEntryReader {
    override fun horseNames(
        tenantId: TenantId,
        raceId: RaceId,
    ): Map<Int, String> =
        dao
            .selectNames(tenantId.value, raceId.value)
            .mapNotNull { r -> r.bamei?.let { r.umaban to it } }
            .toMap()
}
