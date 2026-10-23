package jp.jrabot.domain.ingest

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class BackfillStatus(
    val active: Boolean,
) {
    REQUESTED(true),
    IN_PROGRESS(true),
    COMPLETED(false),
    FAILED(false),
    CANCELLED(false),
}

/**
 * バックフィル要求（過去期間の再取得）。**要求と進捗の状態だけ**を持つ。実際に JVOpen で過去データを取得するのはエージェント（T3.5）で、
 * 取得したバッチは通常の ingest（`fetch.origin=BACKFILL`・`fetch.backfillId`）で届く。重複は `record_hash` で吸収され、通知は出さない
 * （RACE_CALENDAR_DESIGN §9: BACKFILL では通知しない）。速報のオッズは過去時点を再構成できないので、蓄積系（S）の dataspec だけが対象。
 */
data class Backfill(
    val id: Long,
    val tenantId: TenantId,
    val dataspec: String,
    val option: Int?,
    val fromtime: String,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val gapId: Long?,
    val status: BackfillStatus,
    val fetchId: String?,
    val requestedAt: Instant,
    val requestedBy: String,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val failReason: String?,
)

class NewBackfill(
    val dataspec: String,
    val option: Int?,
    val fromtime: String,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val gapId: Long?,
    val requestedBy: String,
)

sealed interface BackfillRequestResult {
    data class Created(
        val id: Long,
    ) : BackfillRequestResult

    /** 同じ要求（種別・option・fromtime）が実行中 = 重複登録しない。 */
    data class AlreadyActive(
        val id: Long,
    ) : BackfillRequestResult
}

interface BackfillStore {
    fun request(
        tenantId: TenantId,
        request: NewBackfill,
        now: Instant,
    ): BackfillRequestResult

    fun find(
        tenantId: TenantId,
        id: Long,
    ): Backfill?

    /** 実行中（REQUESTED・IN_PROGRESS）の要求。要求の古い順。 */
    fun listActive(tenantId: TenantId): List<Backfill>

    /** 取得セッションを紐づけて IN_PROGRESS にする。別の取得セッションが進行中（[takeoverBefore] より新しい）なら false。 */
    fun attachFetch(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        now: Instant,
        takeoverBefore: Instant,
    ): Boolean

    fun complete(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        now: Instant,
    ): Boolean

    /** 取得セッションが失敗した。要求は REQUESTED に戻して再実行できるようにする（再開可能）。 */
    fun releaseFetch(
        tenantId: TenantId,
        id: Long,
        fetchId: String,
        reason: String,
        now: Instant,
    ): Boolean

    fun cancel(
        tenantId: TenantId,
        id: Long,
        now: Instant,
    ): Boolean
}

class InvalidBackfillRequest(
    message: String,
) : IllegalArgumentException(message)

class BackfillService(
    private val store: BackfillStore,
    private val clock: Clock,
    private val maxSpan: Duration = Duration.ofDays(400),
) {
    /** 検証して登録する（冪等: 実行中の同じ要求があればそれを返す）。承認・認可は呼び出し側（管理操作）が済ませている前提。 */
    fun request(
        tenantId: TenantId,
        request: NewBackfill,
    ): BackfillRequestResult {
        if (SupportedDataSpecs.classOf(request.dataspec) != BatchClass.STORED) {
            throw InvalidBackfillRequest("バックフィルできるのは蓄積系（S）の dataspec だけ: ${request.dataspec}（速報オッズは過去時点を再構成できない）")
        }
        if (request.option != null && request.option !in 0..9) throw InvalidBackfillRequest("option が範囲外")
        if (!EnvelopeValidator.isJvTimestamp(request.fromtime)) throw InvalidBackfillRequest("fromtime は yyyyMMddHHmmss")
        if ((request.targetFrom == null) != (request.targetTo == null)) throw InvalidBackfillRequest("対象範囲は両方指定する")
        val from = request.targetFrom
        val to = request.targetTo
        if (from != null && to != null) {
            if (to.isBefore(from)) throw InvalidBackfillRequest("対象範囲の終わりが始まりより前")
            if (Duration.ofDays(to.toEpochDay() - from.toEpochDay()) > maxSpan) throw InvalidBackfillRequest("対象範囲が長すぎる")
        }
        if (request.requestedBy.isBlank() || request.requestedBy.length > 254) throw InvalidBackfillRequest("requestedBy が不正")
        return store.request(tenantId, request, clock.now())
    }
}
