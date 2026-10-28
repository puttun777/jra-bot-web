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
import org.springframework.transaction.support.TransactionOperations

/** 判定の記録（`race_evaluations`）。冪等な追記だけ（更新・削除は T3.9 の結果反映が別に行う）。 */
internal class RaceEvaluationRepositoryImpl(
    private val dao: RaceEvaluationDao,
    private val clock: Clock,
    private val tx: TransactionOperations,
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
                // 旧 `signal_sent` は『この評価の判定が notify（買い目あり）か skip か』（旧 UI の「通知のみ」フィルタ: decision=notify/skip）で、Discord の配送結果ではない。
                // 意味は変えない（移行データと同じ）。実際の通知状態の正は `signals.notify_state`（UNSENT/CLAIMED/SENT/SEND_UNKNOWN）。
                signalSent = record.signalCount > 0,
                createdAt = clock.now().toDbTime(),
            )
        // UNIQUE が無い（旧データの重複を許す: D-066）ので、重複の防止は『分離レベルを REPEATABLE READ に固定（DataSourceFactory）+ NOT EXISTS の INSERT…SELECT』
        // （同時に入れようとすると片方がデッドロックで巻き戻る）に依存する。冪等なので、デッドロックはトランザクションごと再試行する（再試行後は ALREADY_EXISTS になる）。
        // 親の races 行の FOR UPDATE は使わない: bot ユーザーに races の UPDATE 権限が無い（最小権限）。UNIQUE の追加は DB 変更の承認後（docs/AUDIT_FIX_DESIGN.md）
        return tx.inTransactionRetryingDeadlock {
            if (dao.insertIfAbsent(entity).count == 1) InsertOutcome.INSERTED else InsertOutcome.ALREADY_EXISTS
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
