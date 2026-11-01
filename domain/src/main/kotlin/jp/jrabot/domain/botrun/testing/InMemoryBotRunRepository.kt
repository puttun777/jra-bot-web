package jp.jrabot.domain.botrun.testing

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunStateMachine
import jp.jrabot.domain.bot.CheckpointResult
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.bot.TransitionResult
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * [BotRunRepository] のメモリ実装（**テスト用の偽物**。`:persistence` の DB 実装と同じ規則を写す: その日に実行中の行は 1 つ、期限切れのリースの奪取、
 * 所有者・状態・期限・版の検証）。DB の UNIQUE・行ロックそのものの検証ではない（それは MySQL の統合テスト `BotRunAcquireContentionTest`）。
 * 1 つの monitor で直列化して、DB の原子性を写す。[failNext] で次の呼び出しに障害を注入できる。
 */
class InMemoryBotRunRepository : BotRunRepository {
    private val rows = LinkedHashMap<Pair<TenantId, RunId>, BotRun>()

    /** 次の操作で投げる例外（1 回だけ）。DB 障害の再現。 */
    @Volatile
    var failNext: RuntimeException? = null

    private fun maybeFail() {
        failNext?.let {
            failNext = null
            throw it
        }
    }

    @Synchronized
    fun all(): List<BotRun> = rows.values.toList()

    /** 試験用: 保存済みの checkpoint を書き換える（欠損・破損の再現）。 */
    @Synchronized
    fun rewriteCheckpoints(f: (BotRun) -> String?) {
        rows.replaceAll { _, r -> r.copy(checkpoint = f(r)) }
    }

    @Synchronized
    override fun find(
        tenantId: TenantId,
        runId: RunId,
    ): BotRun? = rows[tenantId to runId]

    @Synchronized
    override fun findActive(
        tenantId: TenantId,
        runDate: LocalDate,
    ): BotRun? = rows.values.firstOrNull { it.tenantId == tenantId && it.runDate == runDate && it.state.holdsActiveSlot }

    @Synchronized
    override fun listByDate(
        tenantId: TenantId,
        runDate: LocalDate,
    ): List<BotRun> {
        maybeFail()
        return rows.values.filter { it.tenantId == tenantId && it.runDate == runDate }.sortedByDescending { it.startedAt }
    }

    @Synchronized
    override fun listRecent(
        tenantId: TenantId,
        limit: Int,
    ): List<BotRun> =
        rows.values
            .filter { it.tenantId == tenantId }
            .sortedByDescending { it.startedAt }
            .take(limit)

    @Synchronized
    override fun acquire(request: LeaseRequest): AcquireResult {
        maybeFail()
        val active = findActive(request.tenantId, request.runDate)
        var takenOver: BotRun? = null
        if (active != null) {
            val expiresAt = active.leaseExpiresAt
            if (expiresAt == null || request.now.isBefore(expiresAt)) return AcquireResult.AlreadyActive(active.runId, expiresAt)
            val aborted =
                active.copy(
                    state = BotRunState.ABORTED,
                    finishedAt = maxOf(request.now, active.startedAt),
                    exitReason = "LEASE_EXPIRED_TAKEN_OVER",
                    version = active.version + 1,
                )
            rows[active.tenantId to active.runId] = aborted
            takenOver = aborted
        }
        val run =
            BotRun(
                tenantId = request.tenantId,
                runId = request.runId,
                runDate = request.runDate,
                trigger = request.trigger,
                state = BotRunState.STARTING,
                leaseOwner = request.owner,
                leaseExpiresAt = request.now.plus(request.leaseDuration),
                heartbeatAt = request.now,
                checkpoint = null,
                startedAt = request.now,
                finishedAt = null,
                exitReason = null,
            )
        rows[run.tenantId to run.runId] = run
        return AcquireResult.Acquired(run, takenOver)
    }

    private enum class Holder { Valid, Expired, Lost }

    private fun holder(
        row: BotRun,
        owner: String,
        now: Instant,
    ): Holder {
        if (!row.state.holdsActiveSlot || row.leaseOwner != owner) return Holder.Lost
        val expiresAt = row.leaseExpiresAt ?: return Holder.Expired
        return if (now.isBefore(expiresAt)) Holder.Valid else Holder.Expired
    }

    @Synchronized
    override fun heartbeat(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        now: Instant,
        leaseDuration: Duration,
    ): HeartbeatResult {
        maybeFail()
        val row = rows[tenantId to runId] ?: return HeartbeatResult.NotFound
        return when (holder(row, owner, now)) {
            Holder.Lost -> {
                HeartbeatResult.Lost(row)
            }

            Holder.Expired -> {
                HeartbeatResult.Expired(row)
            }

            Holder.Valid -> {
                val renewed =
                    row.copy(heartbeatAt = now, leaseExpiresAt = maxOf(row.leaseExpiresAt!!, now.plus(leaseDuration)), version = row.version + 1)
                rows[tenantId to runId] = renewed
                HeartbeatResult.Renewed(renewed)
            }
        }
    }

    @Synchronized
    override fun transition(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        to: BotRunState,
        now: Instant,
        exitReason: String?,
        checkpoint: String?,
    ): TransitionResult {
        maybeFail()
        val row = rows[tenantId to runId] ?: return TransitionResult.NotFound
        val terminal = BotRunStateMachine.isTerminal(to)
        when (holder(row, owner, now)) {
            Holder.Lost -> return TransitionResult.Lost(row)
            Holder.Expired -> if (!terminal) return TransitionResult.Lost(row)
            Holder.Valid -> Unit
        }
        if (!BotRunStateMachine.canTransition(row.state, to)) return TransitionResult.InvalidTransition(row.state, to)
        val next =
            row.copy(
                state = to,
                finishedAt = if (terminal) maxOf(now, row.startedAt) else null,
                exitReason = exitReason ?: row.exitReason,
                checkpoint = checkpoint ?: row.checkpoint,
                version = row.version + 1,
            )
        rows[tenantId to runId] = next
        return TransitionResult.Done(next)
    }

    @Synchronized
    override fun saveCheckpoint(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        expectedVersion: Int,
        checkpoint: String,
        now: Instant,
    ): CheckpointResult {
        maybeFail()
        val row = rows[tenantId to runId] ?: return CheckpointResult.NotFound
        when (holder(row, owner, now)) {
            Holder.Lost -> return CheckpointResult.Lost(row)
            Holder.Expired -> return CheckpointResult.Expired(row)
            Holder.Valid -> Unit
        }
        if (row.version != expectedVersion) return CheckpointResult.Conflict(row)
        val next = row.copy(checkpoint = checkpoint, version = row.version + 1)
        rows[tenantId to runId] = next
        return CheckpointResult.Saved(next)
    }
}
