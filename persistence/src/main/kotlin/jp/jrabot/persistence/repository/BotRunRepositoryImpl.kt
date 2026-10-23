package jp.jrabot.persistence.repository

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunStateMachine
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.CheckpointResult
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.bot.TransitionResult
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.dao.BotRunDao
import jp.jrabot.persistence.dao.BotRunEntity
import jp.jrabot.persistence.mapping.enumFromDb
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toDbTimeOrNull
import jp.jrabot.persistence.mapping.toInstantUtc
import jp.jrabot.persistence.mapping.toInstantUtcOrNull
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * bot-run の排他（DB リース）。**排他の最終防壁は DB**: UNIQUE `(tenant_id, run_date, active_slot)`（同時に実行中の行は 1 つ）、
 * 行ロック（`SELECT … FOR UPDATE`）の下での検証と更新、`version` の楽観ロック。アプリ内の mutex・キャッシュは使わない。
 *
 * - 取得（[acquire]）: ①まず INSERT（勝てば排他を取得）。② UNIQUE で拒否されたら、**別のトランザクション**で稼働中の行を（主キーで）行ロックして読み、
 *   リースが有効なら [AcquireResult.AlreadyActive]、期限切れ（`now >= lease_expires_at`）なら同じトランザクションで旧行を ABORTED にして
 *   新しい行を挿入する（奪取）。同時に複数が奪取しても、行ロックで直列化され勝者は 1 つ（敗者は新しい有効なリースを見て AlreadyActive）。
 *   決着しなければ有限回で [AcquireResult.Contended]（成功扱いにしない）。デッドロック（1213）はこの操作に限り、トランザクション単位で有限回だけ再試行する。
 * - heartbeat・状態遷移・checkpoint: 行ロックの下で「所有者・状態・リース期限」を検証してから `version` つきで更新する。
 *   奪取された旧実行（行は ABORTED・`active_slot` NULL）は、どの操作も拒否される（新しい実行の行には触れない: run_id が違う）。
 * - 呼び出し側のトランザクションの中で呼ばない（取得は 2 つの短いトランザクションに分かれている前提）。
 */
internal class BotRunRepositoryImpl(
    private val dao: BotRunDao,
    private val tx: TransactionOperations,
) : BotRunRepository {
    override fun find(
        tenantId: TenantId,
        runId: RunId,
    ): BotRun? = dao.selectById(tenantId.value, runId.value)?.toDomain()

    override fun findActive(
        tenantId: TenantId,
        runDate: LocalDate,
    ): BotRun? = dao.selectActive(tenantId.value, runDate)?.toDomain()

    override fun listByDate(
        tenantId: TenantId,
        runDate: LocalDate,
    ): List<BotRun> = dao.selectByDate(tenantId.value, runDate).map { it.toDomain() }

    override fun listRecent(
        tenantId: TenantId,
        limit: Int,
    ): List<BotRun> {
        require(limit in 1..MAX_RECENT) { "limit は 1〜$MAX_RECENT: $limit" }
        return dao.selectRecent(tenantId.value, limit).map { it.toDomain() }
    }

    override fun acquire(request: LeaseRequest): AcquireResult {
        val entity = request.toEntity()
        repeat(ACQUIRE_ATTEMPTS) {
            // ① 排他の取得を試みる（勝てばそのまま開始）。UNIQUE（uq_bot_run_active）で拒否されたときだけ ② へ
            val inserted =
                tx.inTransactionRetryingDeadlock {
                    try {
                        dao.insert(entity)
                        true
                    } catch (e: RuntimeException) {
                        if (!e.isDuplicateOn("bot_run", ACTIVE_KEY)) throw e
                        false
                    }
                }
            if (inserted) return AcquireResult.Acquired(checkNotNull(dao.selectById(entity.tenantId, entity.runId)).toDomain(), null)
            // ② 稼働中の行を調べる（有効なリース → AlreadyActive、期限切れ → 奪取）
            val step =
                try {
                    tx.inTransactionRetryingDeadlock { inspectActive(request, entity) }
                } catch (_: RetryAcquire) {
                    Step.Retry
                }
            when (step) {
                is Step.Done -> return step.result
                Step.Retry -> Unit
            }
        }
        return AcquireResult.Contended
    }

    private sealed interface Step {
        data class Done(
            val result: AcquireResult,
        ) : Step

        data object Retry : Step
    }

    private fun inspectActive(
        request: LeaseRequest,
        newRun: BotRunEntity,
    ): Step {
        // ロックの順序は常に「主キー → 副インデックス」にそろえる（heartbeat・遷移と同じ）。副インデックス(uq_bot_run_active)で先にロックすると、
        // 主キーから先にロックして `active_slot` を更新する遷移と順序が逆になり、デッドロックする（統合テストで確認）。
        // そこで、まずロックなしで稼働中の run_id を知り、主キーで行ロックしてから、まだ稼働中かを確かめる。
        val peek = dao.selectActive(request.tenantId.value, request.runDate) ?: return Step.Retry
        val active = dao.selectByIdForUpdate(peek.tenantId, peek.runId) ?: return Step.Retry
        if (active.activeSlot != ACTIVE_SLOT) return Step.Retry
        val expiresAt = active.leaseExpiresAt?.toInstantUtc()
        // 期限が無い（リースの情報が無い）行は、期限切れと証明できないので奪取しない（排他を優先する）
        if (expiresAt == null || request.now.isBefore(expiresAt)) {
            return Step.Done(AcquireResult.AlreadyActive(RunId.parse(active.runId), expiresAt))
        }
        val aborted =
            active.copy(
                state = BotRunState.ABORTED.name,
                activeSlot = null,
                finishedAt = maxOf(request.now, active.startedAt.toInstantUtc()).toDbTime(),
                exitReason = TAKEN_OVER,
            )
        if (dao.updateIfVersion(aborted).count != 1) return Step.Retry
        try {
            dao.insert(newRun)
        } catch (e: RuntimeException) {
            // 奪取の直後の挿入が UNIQUE で拒否されることは通常ない。起きたら全体を巻き戻して読み直す（旧行の ABORTED も戻る）
            if (e.isDuplicateOn("bot_run", ACTIVE_KEY)) throw RetryAcquire(e)
            throw e
        }
        val previous = checkNotNull(dao.selectById(active.tenantId, active.runId)).toDomain()
        return Step.Done(AcquireResult.Acquired(checkNotNull(dao.selectById(newRun.tenantId, newRun.runId)).toDomain(), previous))
    }

    /** 奪取の挿入が衝突したことを呼び出し元（[acquire] のループ）へ伝える内部の例外。トランザクションを巻き戻すために投げる。 */
    private class RetryAcquire(
        cause: Throwable,
    ) : RuntimeException(cause)

    override fun heartbeat(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        now: Instant,
        leaseDuration: Duration,
    ): HeartbeatResult {
        require(leaseDuration.isPositive) { "リース期限は正" }
        return tx.inTransaction {
            val row = dao.selectByIdForUpdate(tenantId.value, runId.value) ?: return@inTransaction HeartbeatResult.NotFound
            when (val check = checkHolder(row, owner, now)) {
                Holder.Lost -> {
                    HeartbeatResult.Lost(row.toDomain())
                }

                Holder.Expired -> {
                    HeartbeatResult.Expired(row.toDomain())
                }

                Holder.Valid -> {
                    val current = row.leaseExpiresAt!!.toInstantUtc()
                    val renewed = row.copy(heartbeatAt = now.toDbTime(), leaseExpiresAt = maxOf(current, now.plus(leaseDuration)).toDbTime())
                    check(dao.updateIfVersion(renewed).count == 1) { "行ロックの下の更新が入らない（$check）" }
                    HeartbeatResult.Renewed(checkNotNull(dao.selectById(row.tenantId, row.runId)).toDomain())
                }
            }
        }
    }

    override fun transition(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        to: BotRunState,
        now: Instant,
        exitReason: String?,
        checkpoint: String?,
    ): TransitionResult {
        require(exitReason == null || exitReason.length <= 200) { "exitReason は 200 文字以下" }
        require(checkpoint == null || checkpoint.length <= BotRun.MAX_CHECKPOINT_LENGTH) { "checkpoint が長すぎる" }
        return tx.inTransaction {
            val row = dao.selectByIdForUpdate(tenantId.value, runId.value) ?: return@inTransaction TransitionResult.NotFound
            val terminal = BotRunStateMachine.isTerminal(to)
            val from = enumFromDb<BotRunState>(row.state, "bot_run.state")
            // 終了の状態へは、期限切れでも所有者なら行える（失効した実行が後始末を記録できる）。実行中の状態へ進むには有効なリースが必要
            when (checkHolder(row, owner, now)) {
                Holder.Lost -> return@inTransaction TransitionResult.Lost(row.toDomain())
                Holder.Expired -> if (!terminal) return@inTransaction TransitionResult.Lost(row.toDomain())
                Holder.Valid -> Unit
            }
            if (!BotRunStateMachine.canTransition(from, to)) return@inTransaction TransitionResult.InvalidTransition(from, to)
            val next =
                row.copy(
                    state = to.name,
                    activeSlot = if (to.holdsActiveSlot) ACTIVE_SLOT else null,
                    finishedAt = if (terminal) maxOf(now, row.startedAt.toInstantUtc()).toDbTime() else null,
                    exitReason = exitReason ?: row.exitReason,
                    checkpoint = checkpoint ?: row.checkpoint,
                )
            check(dao.updateIfVersion(next).count == 1) { "行ロックの下の更新が入らない" }
            TransitionResult.Done(checkNotNull(dao.selectById(row.tenantId, row.runId)).toDomain())
        }
    }

    override fun saveCheckpoint(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        expectedVersion: Int,
        checkpoint: String,
        now: Instant,
    ): CheckpointResult {
        require(checkpoint.length <= BotRun.MAX_CHECKPOINT_LENGTH) { "checkpoint が長すぎる" }
        require(checkpoint.trim().let { it.startsWith("{") && it.endsWith("}") }) { "checkpoint は JSON オブジェクト" }
        return tx.inTransaction {
            val row = dao.selectByIdForUpdate(tenantId.value, runId.value) ?: return@inTransaction CheckpointResult.NotFound
            when (checkHolder(row, owner, now)) {
                Holder.Lost -> return@inTransaction CheckpointResult.Lost(row.toDomain())
                Holder.Expired -> return@inTransaction CheckpointResult.Expired(row.toDomain())
                Holder.Valid -> Unit
            }
            if (row.version != expectedVersion) return@inTransaction CheckpointResult.Conflict(row.toDomain())
            check(dao.updateIfVersion(row.copy(checkpoint = checkpoint)).count == 1) { "行ロックの下の更新が入らない" }
            CheckpointResult.Saved(checkNotNull(dao.selectById(row.tenantId, row.runId)).toDomain())
        }
    }

    private enum class Holder { Valid, Expired, Lost }

    /** [owner] がこの行の実行中の所有者か。実行中でない・別の所有者は [Holder.Lost]、期限（`now >= lease_expires_at`）切れは [Holder.Expired]。 */
    private fun checkHolder(
        row: BotRunEntity,
        owner: String,
        now: Instant,
    ): Holder {
        if (row.activeSlot != ACTIVE_SLOT || row.leaseOwner != owner) return Holder.Lost
        val expiresAt = row.leaseExpiresAt?.toInstantUtc() ?: return Holder.Expired
        return if (now.isBefore(expiresAt)) Holder.Valid else Holder.Expired
    }

    private fun LeaseRequest.toEntity(): BotRunEntity =
        BotRunEntity(
            tenantId = tenantId.value,
            runId = runId.value,
            runDate = runDate,
            triggerType = trigger.name,
            state = BotRunState.STARTING.name,
            leaseOwner = owner,
            leaseExpiresAt = now.plus(leaseDuration).toDbTimeOrNull(),
            heartbeatAt = now.toDbTime(),
            activeSlot = ACTIVE_SLOT,
            checkpoint = null,
            exitReason = null,
            startedAt = now.toDbTime(),
            finishedAt = null,
            version = 0,
        )

    private companion object {
        const val ACTIVE_KEY = "uq_bot_run_active"
        const val ACTIVE_SLOT = 1
        const val MAX_RECENT = 200
        const val ACQUIRE_ATTEMPTS = 4
        const val TAKEN_OVER = "LEASE_EXPIRED_TAKEN_OVER"
    }
}

internal fun BotRunEntity.toDomain(): BotRun =
    BotRun(
        tenantId = TenantId.of(tenantId),
        runId = RunId.parse(runId),
        runDate = runDate,
        trigger = enumFromDb<BotRunTrigger>(triggerType, "bot_run.trigger_type"),
        state = enumFromDb<BotRunState>(state, "bot_run.state"),
        leaseOwner = leaseOwner,
        leaseExpiresAt = leaseExpiresAt.toInstantUtcOrNull(),
        heartbeatAt = heartbeatAt.toInstantUtcOrNull(),
        checkpoint = checkpoint,
        startedAt = startedAt.toInstantUtc(),
        finishedAt = finishedAt.toInstantUtcOrNull(),
        exitReason = exitReason,
        version = version,
    )
