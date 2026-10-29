package jp.jrabot.persistence.repository

import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationRecord
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.botrun.RecordedEvaluation
import jp.jrabot.domain.botrun.TimingNotice
import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.RaceEvaluationDao
import jp.jrabot.persistence.dao.RaceEvaluationEntity
import jp.jrabot.persistence.dao.RaceHorseNameDao
import jp.jrabot.persistence.dao.RaceTimingNoticeDao
import jp.jrabot.persistence.dao.RaceTimingNoticeEntity
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc
import org.springframework.transaction.support.TransactionOperations

/** 判定の記録（`race_evaluations`）。冪等な追記だけ（更新・削除は T3.9 の結果反映が別に行う）。 */
internal class RaceEvaluationRepositoryImpl(
    private val dao: RaceEvaluationDao,
    private val clock: Clock,
    private val tx: TransactionOperations,
) : RaceEvaluationWriter {
    override fun record(record: RaceEvaluationRecord): RecordedEvaluation {
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
                // 旧 `signal_sent` は『この評価の判定が notify（買い目あり）か skip か』（旧 UI の「通知のみ」フィルタ: decision=notify/skip）で、Discord の配送結果ではない。
                // 意味は変えない（移行データと同じ）。実際の通知状態の正は `signals.notify_state`（UNSENT/CLAIMED/SENT/SEND_UNKNOWN）。
                signalSent = record.signalCount > 0,
                createdAt = clock.now().toDbTime(),
            )
        // 新方式の行の一意性は DB の UNIQUE（V5 の生成列 uq_native）が守る。ON DUPLICATE KEY で既存なら何も変えない。分離レベルは REPEATABLE READ に固定（DataSourceFactory）。
        // 同時挿入のギャップロックによるデッドロック（1213）は、冪等なのでトランザクションごと再試行する
        return tx.inTransactionRetryingDeadlock {
            val inserted = dao.insertIfAbsent(entity).count == 1
            val id = checkNotNull(dao.selectNativeId(entity.tenantId, entity.raceId, entity.snapshotAt)) { "判定の記録の行が読めない（自然キー）" }
            RecordedEvaluation(if (inserted) InsertOutcome.INSERTED else InsertOutcome.ALREADY_EXISTS, id)
        }
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

/**
 * 通知タイミングの確定（`race_timing_notice`）。追記専用: 確定した行は更新・削除しない。同じ `(tenant_id, race_id, timing_centi)` は PK が拒否する。
 * [claim] は 1 つのトランザクションで全タイミングを試し、新しく確定できたものだけを返す（通常の INSERT。主キーの重複〈1062〉は確定済みとして扱う。
 * bot に UPDATE 権限が無いので `ON DUPLICATE KEY UPDATE` は使えない）。デッドロックは冪等なので再試行する。
 */
internal class TimingNoticeStoreImpl(
    private val dao: RaceTimingNoticeDao,
    private val tx: TransactionOperations,
) : TimingNoticeStore {
    override fun claim(notices: List<TimingNotice>): Set<Int> {
        if (notices.isEmpty()) return emptySet()
        return tx.inTransactionRetryingDeadlock {
            val claimed = linkedSetOf<Int>()
            for (n in notices) {
                try {
                    dao.insertIfAbsent(n.toEntity())
                    claimed += n.timingCenti
                } catch (e: RuntimeException) {
                    // 主キーの重複 = 確定済み。他のエラー（FK・CHECK・権限・接続）は握りつぶさず、そのまま投げる
                    if (!e.isDuplicateOn("race_timing_notice", "PRIMARY")) throw e
                }
            }
            claimed
        }
    }

    override fun list(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<TimingNotice> = dao.selectByRace(tenantId.value, raceId.value).map { it.toDomain() }

    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
        timingCenti: Int,
    ): TimingNotice? = dao.selectOne(tenantId.value, raceId.value, timingCenti.toLong())?.toDomain()

    private fun TimingNotice.toEntity() =
        RaceTimingNoticeEntity(
            tenantId.value,
            raceId.value,
            timingCenti.toLong(),
            runId,
            evaluationId,
            oddsObservedAt.toDbTime(),
            noticeSchema,
            noticeJson,
            noticeSha256,
            firedAt.toDbTime(),
        )

    private fun RaceTimingNoticeEntity.toDomain() =
        TimingNotice(
            TenantId.of(tenantId),
            RaceId.parse(raceId),
            Math.toIntExact(timingCenti),
            runId,
            evaluationId,
            oddsObservedAt.toInstantUtc(),
            noticeSchema,
            noticeJson,
            noticeSha256,
            firedAt.toInstantUtc(),
        )
}
