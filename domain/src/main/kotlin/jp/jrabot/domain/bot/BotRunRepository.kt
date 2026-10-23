package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

interface BotRunReader {
    fun find(
        tenantId: TenantId,
        runId: RunId,
    ): BotRun?

    /** その日に排他（`active_slot = 1`）を持つ実行。無ければ null。 */
    fun findActive(
        tenantId: TenantId,
        runDate: LocalDate,
    ): BotRun?

    /** その日の実行（開始の新しい順。終了したものを含む）。再開時に直前の checkpoint を探すのに使う。 */
    fun listByDate(
        tenantId: TenantId,
        runDate: LocalDate,
    ): List<BotRun>

    /** 開始の新しい順に最大 [limit] 件（1〜200）。 */
    fun listRecent(
        tenantId: TenantId,
        limit: Int,
    ): List<BotRun>
}

/**
 * bot の実行の書込み。**二重実行の最終防壁は DB**（UNIQUE `(tenant_id, run_date, active_slot)` と行ロック・条件付き UPDATE。アプリ内のロックは使わない）。
 * 実行の所有者を検証しない更新（汎用の更新）は提供しない: 奪取された旧実行は、状態・所有者・版の条件で必ず拒否される。
 */
interface BotRunWriter {
    /**
     * 排他（その日に実行中の行は 1 つだけ）を取得して新しい実行（STARTING）を開始する。リースの期限が切れた実行が残っていれば、
     * 同じトランザクションでそれを ABORTED にして奪取する（期限ちょうども奪取できる）。有効なリースがあれば [AcquireResult.AlreadyActive]。
     * 同時に複数が取得を試みても勝者は 1 つ。決着しなければ [AcquireResult.Contended]（成功ではない）。
     */
    fun acquire(request: LeaseRequest): AcquireResult

    /** 所有者 [owner] が自分のリースを [leaseDuration] だけ延長する（期限は短くならない）。奪取後・期限切れ後の更新は拒否される。 */
    fun heartbeat(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        now: Instant,
        leaseDuration: Duration,
    ): HeartbeatResult

    /**
     * 状態を [to] へ進める（[BotRunStateMachine] の表に従う）。終了の状態では排他（`active_slot`）を解放し `finished_at` を記録する
     * （リースの解除はこれ。旧実行は新しい実行を解除できない）。終了の状態への遷移は期限切れでも所有者なら行える。
     * [checkpoint] を渡すと同時に保存する（SIGTERM の INTERRUPTED など）。
     */
    fun transition(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        to: BotRunState,
        now: Instant,
        exitReason: String? = null,
        checkpoint: String? = null,
    ): TransitionResult

    /** checkpoint（JSON オブジェクトの文字列）を保存する。[expectedVersion] は読んだ行の版（heartbeat との競合を [CheckpointResult.Conflict] で検出する）。 */
    fun saveCheckpoint(
        tenantId: TenantId,
        runId: RunId,
        owner: String,
        expectedVersion: Int,
        checkpoint: String,
        now: Instant,
    ): CheckpointResult
}

interface BotRunRepository :
    BotRunReader,
    BotRunWriter
