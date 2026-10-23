package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * bot-run の状態遷移表（RACE_CALENDAR_DESIGN §6.3 と CONTRACTS §9.2）。許可する辺だけを列挙し、逆方向・終端からの遷移は拒否する。
 *
 * - STARTING → RUNNING（リース取得と開催検証 OK）、ABORTED（モードが STOPPED/不明・開催検証 NG）、FAILED、INTERRUPTED（SIGTERM）。
 * - RUNNING → DRAINING（全レース完了・締切・停止検知）、FAILED、STOPPED_BY_MODE、INTERRUPTED（SIGTERM）、ABORTED（最大稼働の超過）。
 * - DRAINING → SUCCEEDED、FAILED（未反映あり）、STOPPED_BY_MODE、INTERRUPTED、ABORTED。
 * - SUCCEEDED・FAILED・ABORTED・STOPPED_BY_MODE・INTERRUPTED は終端で、`active_slot` を解放している（DDL の CHECK と一致）。
 *
 * 期限切れリースの奪取で前の実行を ABORTED にするのも、実行中の 3 状態から ABORTED への辺。
 * 業務の遷移（いつ DRAINING にするか等）は T3.8 の bot-run が決める。ここは「その遷移が許されるか」だけ。
 */
object BotRunStateMachine {
    private val allowed: Map<BotRunState, Set<BotRunState>> =
        mapOf(
            BotRunState.STARTING to setOf(BotRunState.RUNNING, BotRunState.ABORTED, BotRunState.FAILED, BotRunState.INTERRUPTED),
            BotRunState.RUNNING to
                setOf(BotRunState.DRAINING, BotRunState.FAILED, BotRunState.STOPPED_BY_MODE, BotRunState.INTERRUPTED, BotRunState.ABORTED),
            BotRunState.DRAINING to
                setOf(BotRunState.SUCCEEDED, BotRunState.FAILED, BotRunState.STOPPED_BY_MODE, BotRunState.INTERRUPTED, BotRunState.ABORTED),
            BotRunState.SUCCEEDED to emptySet(),
            BotRunState.FAILED to emptySet(),
            BotRunState.ABORTED to emptySet(),
            BotRunState.STOPPED_BY_MODE to emptySet(),
            BotRunState.INTERRUPTED to emptySet(),
        )

    fun canTransition(
        from: BotRunState,
        to: BotRunState,
    ): Boolean = to in allowed.getValue(from)

    fun isTerminal(state: BotRunState): Boolean = !state.holdsActiveSlot
}

/**
 * リースの設定。**既定値を持たない**（設計の案は heartbeat 60 秒・期限 5 分だが、運用値は T3.8 で確定。D-122）。
 * [heartbeatInterval] は [duration] より短くなければならない（期限の前に少なくとも 1 回は更新できる）。
 */
data class LeaseSettings(
    val duration: Duration,
    val heartbeatInterval: Duration,
) {
    init {
        require(duration.isPositive) { "リース期限は正: $duration" }
        require(heartbeatInterval.isPositive && heartbeatInterval < duration) { "heartbeat の間隔は正で、リース期限より短い" }
    }
}

/**
 * bot-run の排他（リース）の取得依頼。[runId] はこの実行の新しい ID、[owner] は実行ホストの識別（コンテナ ID・execution ID など。1〜100 文字）。
 * 時刻 [now] は呼び出し側の [jp.jrabot.domain.time.Clock]（プロセス間の時計のずれはリース期限に比べて十分小さい前提。NTP）。
 */
data class LeaseRequest(
    override val tenantId: TenantId,
    val runId: RunId,
    val runDate: LocalDate,
    val trigger: BotRunTrigger,
    val owner: String,
    val now: Instant,
    val leaseDuration: Duration,
) : TenantScoped {
    init {
        require(owner.isNotBlank() && owner.length <= 100) { "owner は 1〜100 文字" }
        require(leaseDuration.isPositive) { "リース期限は正" }
    }
}

sealed interface AcquireResult {
    /**
     * 排他を取得して [run]（STARTING）を開始した。[takenOver] は、この取得で奪取した**期限切れの前の実行**（ABORTED にした行。
     * 無ければ null）。新しい実行は DB の checkpoint から状態を再導出して続ける（前の実行の checkpoint は [takenOver] にある）。
     */
    data class Acquired(
        val run: BotRun,
        val takenOver: BotRun?,
    ) : AcquireResult

    /** 有効なリースを持つ実行が既にある。起動しないのが正しい結果（異常ではない）。 */
    data class AlreadyActive(
        val activeRunId: RunId,
        val leaseExpiresAt: Instant?,
    ) : AcquireResult

    /**
     * 競合が続いて決着しなかった（試行の上限）。**取得できていない**ので業務処理をしてはいけない。一時的な失敗（Job は exit 75）。
     */
    data object Contended : AcquireResult
}

sealed interface HeartbeatResult {
    data class Renewed(
        val run: BotRun,
    ) : HeartbeatResult

    /** リースの期限が切れていた（期限ちょうども切れ）。この実行は失効したものとして止まる（再開できない）。[run] は現在の行。 */
    data class Expired(
        val run: BotRun,
    ) : HeartbeatResult

    /** この所有者の実行中の行ではない（奪取された・終了した・別の所有者）。止まる。[run] は現在の行。 */
    data class Lost(
        val run: BotRun,
    ) : HeartbeatResult

    data object NotFound : HeartbeatResult
}

sealed interface TransitionResult {
    data class Done(
        val run: BotRun,
    ) : TransitionResult

    /** 遷移表に無い変更（読んだ状態 [from] から [to] へは進めない）。何も変更していない。 */
    data class InvalidTransition(
        val from: BotRunState,
        val to: BotRunState,
    ) : TransitionResult

    /** 奪取された・終了した・別の所有者・期限切れ（実行中の状態への遷移のとき）。止まる。 */
    data class Lost(
        val run: BotRun,
    ) : TransitionResult

    /** 同じ所有者の別の更新が先に入った。読み直して再判断する。 */
    data class Conflict(
        val run: BotRun,
    ) : TransitionResult

    data object NotFound : TransitionResult
}

sealed interface CheckpointResult {
    data class Saved(
        val run: BotRun,
    ) : CheckpointResult

    /** `version` の不一致（heartbeat などが先に入った）。**保存されていない**。読み直して再試行する。 */
    data class Conflict(
        val run: BotRun,
    ) : CheckpointResult

    data class Expired(
        val run: BotRun,
    ) : CheckpointResult

    data class Lost(
        val run: BotRun,
    ) : CheckpointResult

    data object NotFound : CheckpointResult
}
