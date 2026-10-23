package jp.jrabot.persistence.repository

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.calendar.ScheduleFetchFact
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ingest.AgentCredential
import jp.jrabot.domain.ingest.AgentCredentialStore
import jp.jrabot.domain.ingest.AgentHeartbeatStore
import jp.jrabot.domain.ingest.AgentStatus
import jp.jrabot.domain.ingest.Backfill
import jp.jrabot.domain.ingest.BackfillRequestResult
import jp.jrabot.domain.ingest.BackfillStatus
import jp.jrabot.domain.ingest.BackfillStore
import jp.jrabot.domain.ingest.BatchClaim
import jp.jrabot.domain.ingest.BatchCounts
import jp.jrabot.domain.ingest.ClaimResult
import jp.jrabot.domain.ingest.EncryptedSigningKey
import jp.jrabot.domain.ingest.FetchEnsure
import jp.jrabot.domain.ingest.FetchOrigin
import jp.jrabot.domain.ingest.FetchRecord
import jp.jrabot.domain.ingest.FetchStatus
import jp.jrabot.domain.ingest.FetchStore
import jp.jrabot.domain.ingest.HeartbeatReport
import jp.jrabot.domain.ingest.IngestBatchStore
import jp.jrabot.domain.ingest.IngestCursorStore
import jp.jrabot.domain.ingest.IngestEnvelope
import jp.jrabot.domain.ingest.IngestGapStore
import jp.jrabot.domain.ingest.IssuedAgentCredential
import jp.jrabot.domain.ingest.NewBackfill
import jp.jrabot.domain.ingest.NonceStore
import jp.jrabot.domain.ingest.ParseStatus
import jp.jrabot.domain.ingest.RawRecordRow
import jp.jrabot.domain.ingest.RawRecordStore
import jp.jrabot.domain.ingest.UnitOfWork
import jp.jrabot.domain.odds.ComboOddsRow
import jp.jrabot.domain.odds.ComboOddsSnapshot
import jp.jrabot.domain.odds.ComboOddsSnapshotRepository
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.AgentDao
import jp.jrabot.persistence.dao.AgentEntity
import jp.jrabot.persistence.dao.AgentHeartbeatDao
import jp.jrabot.persistence.dao.AgentHeartbeatEntity
import jp.jrabot.persistence.dao.AgentNonceDao
import jp.jrabot.persistence.dao.AgentNonceEntity
import jp.jrabot.persistence.dao.ComboOddsRowEntity
import jp.jrabot.persistence.dao.ComboOddsSnapshotDao
import jp.jrabot.persistence.dao.IngestBackfillDao
import jp.jrabot.persistence.dao.IngestBackfillEntity
import jp.jrabot.persistence.dao.IngestBatchDao
import jp.jrabot.persistence.dao.IngestBatchEntity
import jp.jrabot.persistence.dao.IngestCursorDao
import jp.jrabot.persistence.dao.IngestCursorEntity
import jp.jrabot.persistence.dao.IngestFetchDao
import jp.jrabot.persistence.dao.IngestFetchEntity
import jp.jrabot.persistence.dao.IngestGapDao
import jp.jrabot.persistence.dao.IngestGapEntity
import jp.jrabot.persistence.dao.IngestRawDao
import jp.jrabot.persistence.dao.IngestRawEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit

/** エージェントの登録（発行した資格情報の保存）。ingest ユーザーは `agent` に INSERT できる（R__grants.sql）。 */
interface AgentRegistrar {
    fun register(
        tenantId: TenantId,
        name: String,
        issued: IssuedAgentCredential,
        tokenExpiresAt: Instant,
        createdBy: String?,
        now: Instant,
    )
}

internal class AgentRepositoryImpl(
    private val dao: AgentDao,
) : AgentCredentialStore {
    override fun findByAgentId(agentId: AgentId): AgentCredential? =
        dao.selectByAgentId(agentId.value)?.let { e ->
            val key = if (e.signKeyEnc != null && e.signKeyKid != null) EncryptedSigningKey(e.signKeyKid, e.signKeyEnc) else null
            AgentCredential(
                TenantId.of(e.tenantId),
                AgentId.of(e.agentId),
                enumFromDb<AgentStatus>(e.status, "agent.status"),
                e.tokenExpiresAt.toInstantUtc(),
                key,
            )
        }
}

internal class AgentRegistrarImpl(
    private val dao: AgentDao,
    private val tx: TransactionOperations,
) : AgentRegistrar {
    override fun register(
        tenantId: TenantId,
        name: String,
        issued: IssuedAgentCredential,
        tokenExpiresAt: Instant,
        createdBy: String?,
        now: Instant,
    ) {
        tx.inTransaction {
            dao.insert(
                AgentEntity(
                    tenantId.value,
                    issued.agentId.value,
                    name.take(100),
                    issued.tokenHash,
                    issued.signingKey.blob,
                    issued.signingKey.kid,
                    tokenExpiresAt.toDbTime(),
                    AgentStatus.ACTIVE.name,
                    now.toDbTime(),
                    createdBy,
                    null,
                    null,
                ),
            )
        }
    }
}

internal class NonceStoreImpl(
    private val dao: AgentNonceDao,
    private val tx: TransactionOperations,
) : NonceStore {
    override fun consume(
        tenantId: TenantId,
        agentId: AgentId,
        nonce: String,
        expiresAt: Instant,
    ): Boolean =
        tx.inTransaction {
            try {
                dao.insert(AgentNonceEntity(tenantId.value, agentId.value, nonce, expiresAt.toDbTime()))
                true
            } catch (e: RuntimeException) {
                if (e.isDuplicateOn("agent_nonce", "PRIMARY")) false else throw e
            }
        }

    override fun purgeExpired(
        before: Instant,
        limit: Int,
    ): Int = tx.inTransaction { dao.deleteExpired(before.toDbTime(), limit) }
}

internal class AgentHeartbeatStoreImpl(
    private val dao: AgentHeartbeatDao,
    private val tx: TransactionOperations,
) : AgentHeartbeatStore {
    override fun upsert(
        tenantId: TenantId,
        agentId: AgentId,
        report: HeartbeatReport,
        now: Instant,
    ) {
        tx.inTransaction {
            dao.upsert(
                AgentHeartbeatEntity(
                    tenantId.value,
                    agentId.value,
                    now.toDbTime(),
                    report.agentVersion,
                    report.clockSkewMs,
                    report.queueDepth,
                    report.oldestQueuedAt.toDbTimeOrNull(),
                    report.ackedTsJson,
                    report.lastJvResultJson,
                    report.diskFreeMb,
                    report.lastError,
                    report.localMode,
                ),
            )
        }
    }
}

internal class IngestBatchStoreImpl(
    private val dao: IngestBatchDao,
    private val tx: TransactionOperations,
) : IngestBatchStore {
    override fun claim(claim: BatchClaim): ClaimResult {
        val env = claim.envelope
        val tenant = claim.tenantId.value
        return tx.inTransactionRetryingDeadlock {
            val token = claim.receivedAt.toDbTime()
            val inserted =
                dao
                    .insertIfAbsent(
                        IngestBatchEntity(
                            tenant,
                            env.batchId,
                            env.agentId.value,
                            env.batchClass.code,
                            env.dataspec,
                            env.option,
                            env.fromtime,
                            env.lastFileTimestamp,
                            env.eof,
                            env.observedAt.toDbTime(),
                            token,
                            env.payloadSha256,
                            env.records.size,
                            0,
                            0,
                            "PROCESSING",
                            null,
                            env.fetch?.fetchId,
                            env.fetch?.seq,
                            null,
                        ),
                    ).count == 1
            if (inserted) return@inTransactionRetryingDeadlock ClaimResult.Claimed(token.toInstantUtc())
            // 既に UNIQUE のどれかがある。同じ batchId の行があれば X ロックして判定する（同時に来た再送は、ここで直列化される）。
            // 無ければ、重複したのは取得セッション内の同じ連番（別の batchId が持っている）
            val row =
                dao.selectByIdForUpdate(tenant, env.batchId)
                    ?: return@inTransactionRetryingDeadlock ClaimResult.FetchSeqTaken
            if (row.payloadSha256 != env.payloadSha256 || row.agentId != env.agentId.value) return@inTransactionRetryingDeadlock ClaimResult.PayloadMismatch
            when {
                row.status == "COMPLETED" -> {
                    ClaimResult.AlreadyCompleted(row.acceptedCount, row.duplicateCount, checkNotNull(row.completedAt).toInstantUtc())
                }

                row.status == "FAILED" || (row.status == "PROCESSING" && !row.receivedAt.isAfter(claim.staleBefore.toDbTime())) -> {
                    // 新しいトークンは必ず古いトークンより大きくする（古い処理の完了を無効にするため）
                    val bumped = row.receivedAt.plus(1, ChronoUnit.MICROS)
                    val newToken = if (token.isAfter(bumped)) token else bumped
                    if (dao.takeover(tenant, env.batchId, row.status, row.receivedAt, newToken) == 1) {
                        ClaimResult.Claimed(newToken.toInstantUtc())
                    } else {
                        ClaimResult.InProgress
                    }
                }

                else -> {
                    ClaimResult.InProgress
                }
            }
        }
    }

    override fun complete(
        tenantId: TenantId,
        batchId: String,
        token: Instant,
        counts: BatchCounts,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.complete(tenantId.value, batchId, token.toDbTime(), counts.accepted, counts.duplicates, now.toDbTime()) == 1 }

    override fun fail(
        tenantId: TenantId,
        batchId: String,
        token: Instant,
        reason: String,
    ): Boolean = tx.inTransaction { dao.fail(tenantId.value, batchId, token.toDbTime(), reason) == 1 }
}

internal class RawRecordStoreImpl(
    private val dao: IngestRawDao,
    private val tx: TransactionOperations,
) : RawRecordStore {
    override fun insertIfAbsent(row: RawRecordRow): Boolean =
        tx.inTransaction {
            dao
                .insertIfAbsent(
                    IngestRawEntity(
                        row.tenantId.value,
                        row.recordHash,
                        row.batchId,
                        row.seq.toLong(),
                        row.dataspec,
                        row.recordId,
                        row.payload,
                        row.receivedAt.toDbTime(),
                        row.parseStatus.name,
                        row.parseError,
                    ),
                ).count == 1
        }
}

internal class FetchStoreImpl(
    private val dao: IngestFetchDao,
    private val tx: TransactionOperations,
) : FetchStore {
    override fun ensure(
        tenantId: TenantId,
        envelope: IngestEnvelope,
        now: Instant,
    ): FetchEnsure {
        val f = requireNotNull(envelope.fetch) { "fetch の無い封筒" }
        val t = now.toDbTime()
        return tx.inTransactionRetryingDeadlock {
            dao.insertIfAbsent(
                IngestFetchEntity(
                    tenantId.value,
                    f.fetchId,
                    envelope.agentId.value,
                    envelope.dataspec,
                    envelope.option,
                    envelope.fromtime,
                    f.origin.name,
                    f.backfillId,
                    f.targetFrom,
                    f.targetTo,
                    null,
                    FetchStatus.IN_PROGRESS.name,
                    null,
                    0,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    t,
                    t,
                    null,
                ),
            )
            val e = checkNotNull(dao.selectById(tenantId.value, f.fetchId)) { "取得セッションが読めない" }
            val same =
                e.agentId == envelope.agentId.value && e.dataspec == envelope.dataspec && e.optionNo == envelope.option && e.fromtime == envelope.fromtime &&
                    e.origin == f.origin.name && e.backfillId == f.backfillId && e.targetFrom == f.targetFrom && e.targetTo == f.targetTo
            if (same) FetchEnsure.Ready else FetchEnsure.Mismatch("FETCH_MISMATCH")
        }
    }

    override fun peek(
        tenantId: TenantId,
        fetchId: String,
    ): FetchRecord? = dao.selectById(tenantId.value, fetchId)?.toDomain()

    override fun lockForUpdate(
        tenantId: TenantId,
        fetchId: String,
    ): FetchRecord? = dao.selectByIdForUpdate(tenantId.value, fetchId)?.toDomain()

    override fun save(fetch: FetchRecord) {
        val n =
            tx.inTransaction {
                dao
                    .update(
                        IngestFetchEntity(
                            fetch.tenantId.value,
                            fetch.fetchId,
                            fetch.agentId.value,
                            fetch.dataspec,
                            fetch.option,
                            fetch.fromtime,
                            fetch.origin.name,
                            fetch.backfillId,
                            fetch.targetFrom,
                            fetch.targetTo,
                            fetch.lastFileTimestamp,
                            fetch.status.name,
                            fetch.failReason,
                            fetch.nextSeq,
                            fetch.recordCount,
                            fetch.unparsedCount,
                            fetch.failedParseCount,
                            fetch.maxRaceDate,
                            fetch.coveredFrom,
                            fetch.coveredTo,
                            fetch.startedAt.toDbTime(),
                            fetch.lastBatchAt.toDbTime(),
                            fetch.completedAt.toDbTimeOrNull(),
                        ),
                    ).count
            }
        check(n == 1) { "取得セッションの更新件数が 1 ではない: $n" }
    }

    private fun IngestFetchEntity.toDomain() =
        FetchRecord(
            TenantId.of(tenantId),
            fetchId,
            AgentId.of(agentId),
            dataspec,
            optionNo,
            fromtime,
            enumFromDb<FetchOrigin>(origin, "ingest_fetch.origin"),
            backfillId,
            targetFrom,
            targetTo,
            lastFileTimestamp,
            enumFromDb<FetchStatus>(status, "ingest_fetch.status"),
            failReason,
            nextSeq,
            recordCount,
            unparsedCount,
            failedParseCount,
            maxRaceDate,
            coveredFrom,
            coveredTo,
            startedAt.toInstantUtc(),
            lastBatchAt.toInstantUtc(),
            completedAt.toInstantUtcOrNull(),
        )
}

/** 取得の事実（YSCH の完全な取得が日付を覆う最新の完了時刻）。DB に事実が無ければ null = 「取得していない」（予定なしの確認にしない）。 */
internal class DbScheduleFetchFactReader(
    private val dao: IngestFetchDao,
) : ScheduleFetchFactReader {
    override fun latestSuccess(
        tenantId: TenantId,
        date: java.time.LocalDate,
    ): ScheduleFetchFact? = dao.selectLatestFactAt(tenantId.value, "YSCH", date)?.let { ScheduleFetchFact(date, it.toInstantUtc()) }
}

internal class IngestCursorStoreImpl(
    private val dao: IngestCursorDao,
    private val tx: TransactionOperations,
) : IngestCursorStore {
    override fun advance(
        tenantId: TenantId,
        dataType: String,
        lastFileTimestamp: String,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.advance(IngestCursorEntity(tenantId.value, dataType, lastFileTimestamp, now.toDbTime())).count > 0 }

    override fun find(
        tenantId: TenantId,
        dataType: String,
    ): String? = dao.selectById(tenantId.value, dataType)?.lastFileTimestamp
}

internal class IngestGapStoreImpl(
    private val dao: IngestGapDao,
    private val tx: TransactionOperations,
) : IngestGapStore {
    override fun openFetchFailure(
        tenantId: TenantId,
        agentId: AgentId,
        dataspec: String,
        gapFrom: Instant,
        detectedAt: Instant,
        detail: String,
    ): Boolean =
        tx.inTransaction {
            dao
                .insertIfAbsent(
                    IngestGapEntity(
                        null,
                        tenantId.value,
                        agentId.value,
                        dataspec,
                        "FETCH_FAILURE",
                        gapFrom.toDbTime(),
                        null,
                        detectedAt.toDbTime(),
                        null,
                        detail.take(500),
                    ),
                ).count == 1
        }

    override fun resolve(
        tenantId: TenantId,
        gapId: Long,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.resolve(tenantId.value, gapId, now.toDbTime()) == 1 }
}

internal class BackfillStoreImpl(
    private val dao: IngestBackfillDao,
    private val tx: TransactionOperations,
) : BackfillStore {
    override fun request(
        tenantId: TenantId,
        request: NewBackfill,
        now: Instant,
    ): BackfillRequestResult {
        val t = now.toDbTime()
        return tx.inTransactionRetryingDeadlock {
            val inserted =
                try {
                    dao.insert(
                        IngestBackfillEntity(
                            null,
                            tenantId.value,
                            request.dataspec,
                            request.option,
                            request.fromtime,
                            request.targetFrom,
                            request.targetTo,
                            request.gapId,
                            BackfillStatus.REQUESTED.name,
                            null,
                            t,
                            request.requestedBy,
                            t,
                            null,
                            null,
                        ),
                    )
                    true
                } catch (e: RuntimeException) {
                    if (!e.isDuplicateOn("ingest_backfill", "uq_ingest_backfill_active")) throw e
                    false
                }
            val row = checkNotNull(dao.selectActiveByKey(tenantId.value, request.dataspec, request.option ?: -1, request.fromtime)) { "要求が読めない" }
            if (inserted) BackfillRequestResult.Created(checkNotNull(row.id)) else BackfillRequestResult.AlreadyActive(checkNotNull(row.id))
        }
    }

    override fun find(
        tenantId: TenantId,
        id: Long,
    ): Backfill? = dao.selectById(tenantId.value, id)?.toDomain()

    override fun listActive(tenantId: TenantId): List<Backfill> = dao.selectActive(tenantId.value).map { it.toDomain() }

    override fun attachFetch(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        now: Instant,
        takeoverBefore: Instant,
    ): Boolean = tx.inTransaction { dao.attachFetch(tenantId.value, id, fetchId, now.toDbTime(), takeoverBefore.toDbTime()) == 1 }

    override fun complete(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.complete(tenantId.value, id, fetchId, now.toDbTime()) == 1 }

    override fun releaseFetch(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        reason: String,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.releaseFetch(tenantId.value, id, fetchId, reason.take(100), now.toDbTime()) == 1 }

    override fun cancel(
        tenantId: TenantId,
        id: Long,
        now: Instant,
    ): Boolean = tx.inTransaction { dao.cancel(tenantId.value, id, now.toDbTime()) == 1 }

    private fun IngestBackfillEntity.toDomain() =
        Backfill(
            checkNotNull(id),
            TenantId.of(tenantId),
            dataspec,
            optionNo,
            fromtime,
            targetFrom,
            targetTo,
            gapId,
            enumFromDb<BackfillStatus>(status, "ingest_backfill.status"),
            fetchId,
            requestedAt.toInstantUtc(),
            requestedBy,
            updatedAt.toInstantUtc(),
            completedAt.toInstantUtcOrNull(),
            failReason,
        )
}

internal class ComboOddsSnapshotRepositoryImpl(
    private val dao: ComboOddsSnapshotDao,
    private val tx: TransactionOperations,
    private val clock: Clock,
) : ComboOddsSnapshotRepository {
    override fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<ComboOddsSnapshot> =
        dao
            .selectHistory(tenantId.value, raceId.value, market.code)
            .groupBy { it.collectedAt }
            .values
            .map { rows ->
                ComboOddsSnapshot(
                    tenantId,
                    raceId,
                    market,
                    rows.first().collectedAt.toInstantUtc(),
                    rows.map { ComboOddsRow(it.comboKey, Odds.of(it.odds), it.oddsHigh?.let(Odds::of), it.popularity) },
                    rows.first().source,
                )
            }

    override fun upsert(snapshot: ComboOddsSnapshot): UpsertOutcome {
        val at = snapshot.collectedAt.toDbTime()
        val created = clock.now().toDbTime()
        val rows =
            snapshot.rows.map {
                ComboOddsRowEntity(
                    snapshot.tenantId.value,
                    snapshot.raceId.value,
                    snapshot.market.code,
                    at,
                    it.comboKey,
                    it.odds.value,
                    it.oddsHigh?.value,
                    it.popularity,
                    snapshot.source,
                    null,
                    created,
                )
            }
        val outcomes = tx.inTransaction { dao.upsertAll(rows).counts }.map { upsertOutcomeOf(it) }
        return when {
            UpsertOutcome.UPDATED in outcomes -> UpsertOutcome.UPDATED
            UpsertOutcome.CREATED in outcomes -> UpsertOutcome.CREATED
            else -> UpsertOutcome.UNCHANGED
        }
    }
}

/**
 * バッチ 1 件の処理を 1 つのトランザクション（READ COMMITTED）で行う。READ COMMITTED にするのは、楽観ロックの競合で読み直したとき
 * 他のトランザクションがコミットした最新の行が見えるようにするため（REPEATABLE READ では同じスナップショットを読み続けて再判定できない）。
 * デッドロック（1213）は**全体を再実行**する（[UnitOfWork] の契約: ブロックは冪等）。既にトランザクション内なら参加する（再試行しない）。
 */
internal class SpringUnitOfWork(
    transactionManager: PlatformTransactionManager,
) : UnitOfWork {
    private val readCommitted =
        TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }

    override fun <T> run(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                var result: Any? = null
                readCommitted.executeWithoutResult { result = block() }

                @Suppress("UNCHECKED_CAST")
                return result as T
            } catch (e: RuntimeException) {
                if (!e.isDeadlock() || attempt >= DEADLOCK_ATTEMPTS ||
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                ) {
                    throw e
                }
                Thread.sleep(10L * attempt)
                attempt++
            }
        }
    }
}
