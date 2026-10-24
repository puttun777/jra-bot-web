package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.calendar.EventJson
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.calendar.ScheduleEventWriter
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 認証済みの呼び出し元。テナントは認証したエージェントの行から決まる（封筒の値ではない）。 */
data class IngestCaller(
    val tenantId: TenantId,
    val agentId: AgentId,
)

data class IngestPolicy(
    /** PROCESSING のまま放置されたとみなす時間（これを過ぎた再送は処理を引き継ぐ。それまでは 409）。1 バッチ（≤200 レコード）の処理時間より十分長くする。 */
    val claimStaleAfter: Duration = Duration.ofSeconds(120),
    /** 進行中のバックフィルの取得セッションを引き継げるまでの無通信時間。 */
    val backfillTakeoverAfter: Duration = Duration.ofHours(1),
)

class FetchProgress(
    val fetchId: String,
    val status: FetchStatus,
    val nextSeq: Int,
)

class CursorAck(
    val dataType: String,
    val lastFileTimestamp: String,
)

/** 200 の応答の中身（CONTRACTS §3.4: accepted, duplicates, ackTs, serverTime ＋ 追加項目。D-134）。 */
class IngestAck(
    val accepted: Int,
    val duplicates: Int,
    val quarantined: Int,
    val unsupported: Int,
    val ackTs: Instant,
    /** 同じ内容で既に完了していたバッチへの再送（duplicate=true）。数は最初の処理のもの。 */
    val duplicateBatch: Boolean,
    val fetch: FetchProgress?,
    val cursor: CursorAck?,
)

sealed interface IngestOutcome {
    data class Accepted(
        val ack: IngestAck,
    ) : IngestOutcome

    data class Rejected(
        val rejection: IngestRejection,
    ) : IngestOutcome
}

private class BatchRejectedException(
    val rejection: IngestRejection,
) : RuntimeException(rejection.code, null, false, false)

private class ClaimLostException : RuntimeException("処理権を失った", null, false, false)

/**
 * ingest バッチの処理（CONTRACTS §3.4・JVLINK_DESIGN §5）。
 *
 * 1. **claim**: `ingest_batch` への INSERT（PK）で処理権を得る。競合の判定は DB の UNIQUE が行う（アプリ内の排他には頼らない）。
 *    同じ内容で完了済み → 重複 ACK、処理中 → 409、内容違い → 422、失敗・放置 → 引き継ぎ。
 * 2. **処理**: 1 つのトランザクションで 生レコード保存（`record_hash` で重複吸収）→ 解析 → 適用 → 取得セッションの進行 → 完了の印。
 *    どこかで失敗すれば**全部巻き戻る**（不完全なデータを確定しない）。ACK 直前に切断されても、再送は重複 ACK になる。
 * 3. **ACK 後にだけ**カーソルを進める（取得セッションが EOF まで連続して読了したときだけ）。
 */
class BatchIngestor(
    private val batches: IngestBatchStore,
    private val raw: RawRecordStore,
    private val fetches: FetchStore,
    private val cursors: IngestCursorStore,
    private val backfills: BackfillStore,
    private val gaps: IngestGapStore,
    private val events: ScheduleEventWriter,
    private val parsers: RecordParsers,
    private val applier: ObservationApplier,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val policy: IngestPolicy = IngestPolicy(),
) {
    fun ingest(
        caller: IngestCaller,
        env: IngestEnvelope,
    ): IngestOutcome {
        require(env.agentId == caller.agentId) { "封筒のエージェントが認証したエージェントと違う（検証済みのはず）" }
        val now = clock.now()
        val tenantId = caller.tenantId
        val observedAt = minOf(env.observedAt, now) // 時計のずれで未来の時刻を保存しない

        env.fetch?.let { fetch ->
            fetch.backfillId?.let { id ->
                val b = backfills.find(tenantId, id) ?: return reject(422, "BACKFILL_NOT_FOUND")
                if (b.dataspec != env.dataspec || b.option != env.option) return reject(422, "BACKFILL_MISMATCH")
                if (!b.status.active) return reject(422, "BACKFILL_CLOSED")
            }
            when (val e = fetches.ensure(tenantId, env, now)) {
                FetchEnsure.Ready -> Unit
                is FetchEnsure.Mismatch -> return reject(422, e.code)
            }
            // 手前のバッチがまだ届いていない先走りは、バッチの行を作らずに断る（行が連番を占有して、別の batchId での再送を妨げないように）。
            // 完了済みのバッチの再送（重複 ACK）は連番が手前なので、ここでは止まらない。確定的な判定は処理の中の行ロックの後
            val current = fetches.peek(tenantId, fetch.fetchId)
            if (current != null && current.status == FetchStatus.IN_PROGRESS && fetch.seq > current.nextSeq) return reject(409, "FETCH_SEQ_AHEAD")
        }

        val token =
            when (val c = batches.claim(BatchClaim(tenantId, env, now, now.minus(policy.claimStaleAfter)))) {
                is ClaimResult.Claimed -> {
                    c.token
                }

                is ClaimResult.AlreadyCompleted -> {
                    // 重複 ACK にも取得セッションの状態とカーソルを付ける（D-144。追加のみ）。ACK の応答が失われて再送されたとき、
                    // 完了を確認できずに acked_ts が止まり続けるのを防ぐ。カーソルは「サーバの保存値がこの取得の値以上」のときだけ
                    val f = env.fetch?.let { fetches.peek(tenantId, it.fetchId) }
                    val progress = f?.let { FetchProgress(it.fetchId, it.status, it.nextSeq) }
                    val cursor = f?.let { confirmedCursor(tenantId, it) }
                    return IngestOutcome.Accepted(
                        IngestAck(c.accepted, c.duplicates, 0, 0, c.completedAt, duplicateBatch = true, fetch = progress, cursor = cursor),
                    )
                }

                ClaimResult.InProgress -> {
                    return reject(409, "BATCH_IN_PROGRESS")
                }

                ClaimResult.PayloadMismatch -> {
                    return reject(422, "BATCH_ID_REUSED")
                }

                ClaimResult.FetchSeqTaken -> {
                    return reject(422, "FETCH_SEQ_REUSED")
                }
            }

        return try {
            IngestOutcome.Accepted(uow.run { process(caller, env, token, now, observedAt) })
        } catch (e: BatchRejectedException) {
            releaseQuietly(tenantId, env.batchId, token, e.rejection.code)
            IngestOutcome.Rejected(e.rejection)
        } catch (_: ClaimLostException) {
            IngestOutcome.Rejected(IngestRejection(409, "BATCH_IN_PROGRESS"))
        } catch (e: RuntimeException) {
            releaseQuietly(tenantId, env.batchId, token, "ERROR:${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun reject(
        status: Int,
        code: String,
    ) = IngestOutcome.Rejected(IngestRejection(status, code))

    private fun releaseQuietly(
        tenantId: TenantId,
        batchId: String,
        token: Instant,
        reason: String,
    ) {
        try {
            batches.fail(tenantId, batchId, token, reason.take(200))
        } catch (_: RuntimeException) {
            // 失敗の印を付けられなくても、PROCESSING は claimStaleAfter 後に引き継げる
        }
    }

    private fun process(
        caller: IngestCaller,
        env: IngestEnvelope,
        token: Instant,
        now: Instant,
        observedAt: Instant,
    ): IngestAck {
        val tenantId = caller.tenantId
        val fetchInfo = env.fetch
        val fetch =
            fetchInfo?.let {
                val f = fetches.lockForUpdate(tenantId, it.fetchId) ?: throw IllegalStateException("取得セッションの行が無い")
                when {
                    f.status != FetchStatus.IN_PROGRESS -> throw BatchRejectedException(IngestRejection(422, "FETCH_CLOSED"))
                    it.seq < f.nextSeq -> throw BatchRejectedException(IngestRejection(422, "FETCH_SEQ_REUSED"))
                    it.seq > f.nextSeq -> throw BatchRejectedException(IngestRejection(409, "FETCH_SEQ_AHEAD")) // 手前のバッチを待つ
                }
                if (it.backfillId != null && it.seq == 0 &&
                    !backfills.attachFetch(tenantId, it.backfillId, it.fetchId, now, now.minus(policy.backfillTakeoverAfter))
                ) {
                    throw BatchRejectedException(IngestRejection(409, "BACKFILL_BUSY"))
                }
                f
            }

        val parseCtx = ParseContext(env.dataspec, env.batchClass, observedAt)
        val applyCtx = ApplyContext(observedAt, env.batchId, env.dataspec)
        var accepted = 0
        var duplicates = 0
        var quarantined = 0
        var unsupported = 0
        var maxRaceDate: LocalDate? = null
        env.records.forEachIndexed { index, rec ->
            var status = ParseStatus.PARSED
            var error: String? = null
            when (val parsed = parsers.parse(parseCtx, rec)) {
                is ParseOutcome.Unsupported -> {
                    status = ParseStatus.PENDING
                    error = parsed.reason
                    unsupported++
                }

                is ParseOutcome.Malformed -> {
                    status = ParseStatus.FAILED
                    error = parsed.reason
                    quarantined++
                }

                is ParseOutcome.Parsed -> {
                    for (obs in parsed.observations) {
                        if (obs is ScheduleDayObservation) maxRaceDate = listOfNotNull(maxRaceDate, obs.date).max()
                        when (val r = applier.apply(tenantId, obs, applyCtx)) {
                            is ApplyOutcome.Quarantined -> {
                                status = ParseStatus.FAILED
                                error = r.reason
                            }

                            is ApplyOutcome.Deferred -> {
                                if (status == ParseStatus.PARSED) {
                                    status = ParseStatus.PENDING
                                    error = r.reason
                                }
                            }

                            else -> {}
                        }
                    }
                    if (status == ParseStatus.FAILED) {
                        quarantined++
                    } else if (status == ParseStatus.PENDING) {
                        unsupported++
                    }
                }
            }
            val hash = Hashing.recordHash(env.dataspec, rec.id, env.batchClass, env.observedAt, rec.bytesSha256)
            val inserted =
                raw.insertIfAbsent(
                    RawRecordRow(tenantId, hash, env.batchId, index, env.dataspec, rec.id, rec.bytes, now, status, error?.take(200)),
                )
            if (inserted) accepted++ else duplicates++
        }

        if (!batches.complete(tenantId, env.batchId, token, BatchCounts(accepted, duplicates), now)) throw ClaimLostException()

        var progress: FetchProgress? = null
        var cursorAck: CursorAck? = null
        if (fetch != null) {
            val stats = BatchStats(env.records.size, unsupported, quarantined, maxRaceDate)
            val updated = FetchPolicy.advance(fetch, env, stats, now)
            fetches.save(updated)
            progress = FetchProgress(updated.fetchId, updated.status, updated.nextSeq)
            if (updated.status != FetchStatus.IN_PROGRESS) cursorAck = finishFetch(caller, updated, now)
        }
        return IngestAck(accepted, duplicates, quarantined, unsupported, now, duplicateBatch = false, fetch = progress, cursor = cursorAck)
    }

    /** サーバのカーソルがこの取得の値以上なら、その値を返す（前進したか既に同値以上かを区別しない。後戻りはしない）。LIVE の成功だけ。 */
    private fun confirmedCursor(
        tenantId: TenantId,
        f: FetchRecord,
    ): CursorAck? {
        if (f.status != FetchStatus.SUCCEEDED || f.origin != FetchOrigin.LIVE) return null
        val last = f.lastFileTimestamp ?: return null
        val type = IngestCursors.dataType(f.dataspec, f.option)
        val stored = cursors.find(tenantId, type) ?: return null
        return if (stored >= last) CursorAck(type, last) else null
    }

    /** 取得セッションが終わったときの後始末。カーソルは **SUCCEEDED の LIVE だけ**進める（バックフィルは過去を埋めるだけで、現在位置は動かさない）。 */
    private fun finishFetch(
        caller: IngestCaller,
        f: FetchRecord,
        now: Instant,
    ): CursorAck? {
        val tenantId = caller.tenantId
        if (f.status == FetchStatus.SUCCEEDED) {
            if (f.origin == FetchOrigin.BACKFILL) {
                val id = checkNotNull(f.backfillId)
                if (backfills.complete(tenantId, id, f.fetchId, now)) {
                    backfills.find(tenantId, id)?.gapId?.let { gaps.resolve(tenantId, it, now) }
                }
                return null
            }
            val last = f.lastFileTimestamp ?: return null
            val type = IngestCursors.dataType(f.dataspec, f.option)
            cursors.advance(tenantId, type, last, now)
            return confirmedCursor(tenantId, f)
        }
        // FAILED
        val reason = f.failReason ?: "FAILED"
        if (f.backfillId != null) backfills.releaseFetch(tenantId, f.backfillId, f.fetchId, reason, now)
        gaps.openFetchFailure(tenantId, f.agentId, f.dataspec, gapStart(f), now, "fetch ${f.fetchId}: $reason")
        if (f.dataspec == FetchPolicy.FACT_DATASPEC) {
            val date = f.targetFrom ?: now.atZone(Zones.JST).toLocalDate()
            events.append(
                ScheduleEvent.create(
                    tenantId,
                    null,
                    date,
                    ScheduleEventType.FETCH_FAILURE,
                    null,
                    EventJson.obj("fetch_id" to f.fetchId, "reason" to reason),
                    ScheduleEventSource.YSCH,
                    f.lastBatchAt,
                    now,
                    f.fetchId,
                    null,
                ),
            )
        }
        return null
    }

    private fun gapStart(f: FetchRecord): Instant = f.fromtime?.let { LocalDateTime.parse(it, TS14).atZone(Zones.JST).toInstant() } ?: f.startedAt

    private companion object {
        val TS14: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
    }
}
