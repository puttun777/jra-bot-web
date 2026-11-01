package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * 1 つのトランザクション（READ COMMITTED）で [block] を実行する。例外が出れば**すべて巻き戻す**。デッドロックは実装が全体を再実行する
 * （[block] は冪等でなければならない）。バッチ 1 件の「生レコード保存 + 解析結果の反映 + 完了の印」を不可分にするために使う（途中失敗で不完全なデータを確定しない）。
 */
interface UnitOfWork {
    fun <T> run(block: () -> T): T
}

enum class ParseStatus { PENDING, PARSED, FAILED }

class RawRecordRow(
    val tenantId: TenantId,
    val recordHash: String,
    val batchId: String,
    val seq: Int,
    val dataspec: String,
    val recordId: String,
    val payload: ByteArray,
    val receivedAt: Instant,
    val parseStatus: ParseStatus,
    val parseError: String?,
)

interface RawRecordStore {
    /** `(tenant, record_hash)` が無いときだけ保存して true。既にあれば何も変えず false（重複の吸収）。 */
    fun insertIfAbsent(row: RawRecordRow): Boolean

    /**
     * 保留（[ParseStatus.PENDING]）の行の結果を、再解析・再適用の結果で更新する（PARSED / FAILED）。**PENDING の行だけ**を更新し、確定済みの行は変えない
     * （更新できたら true）。同じレコードの再送・保留の再適用で、依存するデータが揃ったのに PENDING のまま残るのを防ぐ。
     */
    fun settlePending(
        tenantId: TenantId,
        recordHash: String,
        status: ParseStatus,
        error: String?,
    ): Boolean

    /**
     * 依存するデータ待ち（[ParseStatus.PENDING] で、理由が [reasons] のどれか）の行を、**新しい順**に最大 [limit] 件。[after] を渡すと、その位置より後（古い側）から
     * 返す（キーセットのページング: 再適用に失敗して PENDING のまま残る行があっても、同じ行を返し続けず、全件を漏れなく走査できる）。
     * 再適用に必要な、バッチの種別・観測時刻を付ける。テナントの行だけ。
     */
    fun listPending(
        tenantId: TenantId,
        reasons: List<String>,
        limit: Int,
        after: PendingCursor? = null,
    ): List<PendingRawRecord>
}

/** [RawRecordStore.listPending] のページング位置（`ingest_raw` の `(received_at, batch_id, seq)`。新しい順の並びのキー）。 */
data class PendingCursor(
    val receivedAt: Instant,
    val batchId: String,
    val seq: Int,
)

/** 保留中の生レコード（再解析・再適用用）。[observedAt] は取り込んだバッチの観測時刻（オッズのスナップショットの時刻になる）。 */
class PendingRawRecord(
    val recordHash: String,
    val batchId: String,
    val batchClass: BatchClass,
    val observedAt: Instant,
    val dataspec: String,
    val recordId: String,
    val payload: ByteArray,
    /** ページング用の位置（この行の `received_at`・`seq`。`batchId` は上にある）。 */
    val receivedAt: Instant,
    val seq: Int,
) {
    fun cursor() = PendingCursor(receivedAt, batchId, seq)
}

class BatchClaim(
    val tenantId: TenantId,
    val envelope: IngestEnvelope,
    val receivedAt: Instant,
    /** この時刻以前に始まった PROCESSING は放置された処理とみなして引き継げる。 */
    val staleBefore: Instant,
)

sealed interface ClaimResult {
    /** 処理権を得た。[token] は完了・失敗の印を付けるときに必要（引き継がれた古い処理の完了を無効にする）。 */
    data class Claimed(
        val token: Instant,
    ) : ClaimResult

    /** 同じ内容で完了済み = 重複の ACK。 */
    data class AlreadyCompleted(
        val accepted: Int,
        val duplicates: Int,
        val completedAt: Instant,
    ) : ClaimResult

    /** 別のリクエストが処理中。 */
    data object InProgress : ClaimResult

    /** 同じ `batchId` で内容が違う（再利用。不正）。別のエージェントが使った `batchId` も同じ扱い。 */
    data object PayloadMismatch : ClaimResult

    /** 取得セッション内の同じ連番を別の `batchId` が持っている。 */
    data object FetchSeqTaken : ClaimResult
}

class BatchCounts(
    val accepted: Int,
    val duplicates: Int,
)

interface IngestBatchStore {
    /** `(tenant, batch_id)` の INSERT（PK）で処理権を得る。競合は DB の UNIQUE が決める（アプリ内の排他に頼らない）。 */
    fun claim(claim: BatchClaim): ClaimResult

    /** [token] の持ち主のときだけ COMPLETED にする。引き継がれた後などで持ち主でなければ false（呼び出し側は巻き戻す）。 */
    fun complete(
        tenantId: TenantId,
        batchId: String,
        token: Instant,
        counts: BatchCounts,
        now: Instant,
    ): Boolean

    /** [token] の持ち主のときだけ FAILED にする（再送で引き継げる）。理由に本文・秘密を入れない。 */
    fun fail(
        tenantId: TenantId,
        batchId: String,
        token: Instant,
        reason: String,
    ): Boolean
}

enum class FetchStatus { IN_PROGRESS, SUCCEEDED, FAILED }

/** `ingest_fetch` の 1 行（取得セッション）。 */
data class FetchRecord(
    val tenantId: TenantId,
    val fetchId: String,
    val agentId: AgentId,
    val dataspec: String,
    val option: Int?,
    val fromtime: String?,
    val origin: FetchOrigin,
    val backfillId: Long?,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val lastFileTimestamp: String?,
    val status: FetchStatus,
    val failReason: String?,
    val nextSeq: Int,
    val recordCount: Long,
    val unparsedCount: Int,
    val failedParseCount: Int,
    val maxRaceDate: LocalDate?,
    val coveredFrom: LocalDate?,
    val coveredTo: LocalDate?,
    val startedAt: Instant,
    val lastBatchAt: Instant,
    val completedAt: Instant?,
)

sealed interface FetchEnsure {
    data object Ready : FetchEnsure

    /** 既存の取得セッションと属性（エージェント・種別・範囲など）が食い違う / バックフィル要求と合わない。 */
    data class Mismatch(
        val code: String,
    ) : FetchEnsure
}

interface FetchStore {
    /** 無ければ IN_PROGRESS で作り、あれば属性が同じか検証する。 */
    fun ensure(
        tenantId: TenantId,
        envelope: IngestEnvelope,
        now: Instant,
    ): FetchEnsure

    /** ロックなしの読取り（先走りのバッチを、バッチの行を作らずに断るための事前確認。判定の正本は [lockForUpdate] の後）。 */
    fun peek(
        tenantId: TenantId,
        fetchId: String,
    ): FetchRecord?

    /** 行ロック（SELECT … FOR UPDATE）。同じ取得セッションのバッチを直列化する。 */
    fun lockForUpdate(
        tenantId: TenantId,
        fetchId: String,
    ): FetchRecord?

    fun save(fetch: FetchRecord)
}

interface IngestCursorStore {
    /** 前進だけ（保存値以下なら変えず false）。`dataType` は [IngestCursors.dataType]。 */
    fun advance(
        tenantId: TenantId,
        dataType: String,
        lastFileTimestamp: String,
        now: Instant,
    ): Boolean

    fun find(
        tenantId: TenantId,
        dataType: String,
    ): String?
}

object IngestCursors {
    fun dataType(
        dataspec: String,
        option: Int?,
    ): String = if (option == null) dataspec else "$dataspec:$option"
}

interface IngestGapStore {
    /** 取得失敗の取りこぼし区間を（まだ無ければ）開く。冪等。 */
    fun openFetchFailure(
        tenantId: TenantId,
        agentId: AgentId,
        dataspec: String,
        gapFrom: Instant,
        detectedAt: Instant,
        detail: String,
    ): Boolean

    /** バックフィルの完了で区間を解消する（既に解消済みなら false）。 */
    fun resolve(
        tenantId: TenantId,
        gapId: Long,
        now: Instant,
    ): Boolean
}
