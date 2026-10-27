package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunWriter
import jp.jrabot.domain.bot.CheckpointResult
import jp.jrabot.domain.bot.HeartbeatResult
import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.bot.TransitionResult
import jp.jrabot.domain.time.Clock
import java.time.Instant

/** リースを今も持っているか。 */
sealed interface LeaseStatus {
    data object Held : LeaseStatus

    /** 失った（期限切れ・奪取・終了・別の所有者）。**以後、この run は何も書かずに止まる**。[detail] は固定の語彙。 */
    data class Lost(
        val detail: String,
    ) : LeaseStatus
}

/**
 * 自分の `bot_run` 行（リース）の保持。heartbeat・checkpoint・状態遷移の結果から**行の版**（`version`）を追い、楽観ロックの衝突（同じ所有者の
 * heartbeat との競合）は読み直して再試行する。奪取された旧実行・期限切れの実行は DB が拒否するので、ここは結果を [LeaseStatus.Lost] に畳むだけ
 * （メモリ上のロックで二重起動を防ぐのではない: 最終防壁は DB の UNIQUE と行ロック）。スレッドセーフではない（ループ 1 本から使う）。
 */
class LeaseKeeper(
    private val runs: BotRunWriter,
    private val owner: String,
    private val clock: Clock,
    private val lease: LeaseSettings,
    initial: BotRun,
) {
    var run: BotRun = initial
        private set

    private var lastHeartbeatAt: Instant = initial.heartbeatAt ?: initial.startedAt

    fun heartbeatIfDue(): LeaseStatus {
        val now = clock.now()
        return if (java.time.Duration.between(lastHeartbeatAt, now) >= lease.heartbeatInterval) heartbeat() else LeaseStatus.Held
    }

    fun heartbeat(): LeaseStatus {
        val now = clock.now()
        return when (val r = runs.heartbeat(run.tenantId, run.runId, owner, now, lease.duration)) {
            is HeartbeatResult.Renewed -> {
                run = r.run
                lastHeartbeatAt = now
                LeaseStatus.Held
            }

            is HeartbeatResult.Expired -> {
                LeaseStatus.Lost("LEASE_EXPIRED")
            }

            is HeartbeatResult.Lost -> {
                LeaseStatus.Lost("LEASE_LOST")
            }

            HeartbeatResult.NotFound -> {
                LeaseStatus.Lost("RUN_NOT_FOUND")
            }
        }
    }

    fun saveCheckpoint(json: String): LeaseStatus {
        repeat(CONFLICT_RETRIES) {
            when (val r = runs.saveCheckpoint(run.tenantId, run.runId, owner, run.version, json, clock.now())) {
                is CheckpointResult.Saved -> {
                    run = r.run
                    return LeaseStatus.Held
                }

                is CheckpointResult.Conflict -> {
                    run = r.run
                }

                is CheckpointResult.Expired -> {
                    return LeaseStatus.Lost("LEASE_EXPIRED")
                }

                is CheckpointResult.Lost -> {
                    return LeaseStatus.Lost("LEASE_LOST")
                }

                CheckpointResult.NotFound -> {
                    return LeaseStatus.Lost("RUN_NOT_FOUND")
                }
            }
        }
        return LeaseStatus.Lost("CHECKPOINT_CONFLICT")
    }

    /** 状態を進める。成功したら [run] を更新する。衝突は読み直して再試行する。 */
    fun transition(
        to: BotRunState,
        reason: String?,
        checkpoint: String?,
    ): LeaseStatus {
        repeat(CONFLICT_RETRIES) {
            when (val r = runs.transition(run.tenantId, run.runId, owner, to, clock.now(), reason, checkpoint)) {
                is TransitionResult.Done -> {
                    run = r.run
                    return LeaseStatus.Held
                }

                is TransitionResult.Conflict -> {
                    run = r.run
                }

                is TransitionResult.Lost -> {
                    return LeaseStatus.Lost("LEASE_LOST")
                }

                is TransitionResult.InvalidTransition -> {
                    return LeaseStatus.Lost("INVALID_TRANSITION_${r.from}_TO_${r.to}")
                }

                TransitionResult.NotFound -> {
                    return LeaseStatus.Lost("RUN_NOT_FOUND")
                }
            }
        }
        return LeaseStatus.Lost("TRANSITION_CONFLICT")
    }

    private companion object {
        const val CONFLICT_RETRIES = 3
    }
}
