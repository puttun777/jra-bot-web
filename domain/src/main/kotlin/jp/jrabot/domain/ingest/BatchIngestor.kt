package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.calendar.EventJson
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.calendar.ScheduleEventType
import jp.jrabot.domain.calendar.ScheduleEventWriter
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceReader
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
    private val races: RaceReader,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val policy: IngestPolicy = IngestPolicy(),
    /** 保留の再適用の結果の通知（記録用。失敗や残件を観測できるようにする。ここで例外を投げても取込には影響しない）。 */
    private val onReplay: (TenantId, ReplayReport) -> Unit = { _, _ -> },
) {
    private val replayer = PendingReplayer(raw, parsers, applier::apply, uow)

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
                    // 最初の処理の後の保留の再適用が途中で終わった（応答前の切断・タイムアウトなど）かもしれない。再送されたバッチに含まれるレースで再開する（冪等）
                    replayPending(tenantId, existingRaces(tenantId, racesIn(env, observedAt)))
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
            val done = uow.run { process(caller, env, token, now, observedAt) }
            // 新しいレースが入ったら、レース待ちで保留にした生レコードを再適用する（コミットの後に別の処理として。失敗しても今回のバッチの ACK には影響させない）
            // 同時に処理中の別のバッチがレースを入れたため、このバッチが『レース無し』と判断して保留にした場合も、コミット後にレースの有無を確認して再適用する
            // （確認せずに終わると、相手の再適用の走査がこの保留を見る前に終わっていた場合に、次のレースの観測まで取り残される）
            replayPending(tenantId, done.racesToReplay + existingRaces(tenantId, done.deferredRaces))
            IngestOutcome.Accepted(done.ack)
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
    ): Processed {
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
        val racesToReplay = LinkedHashSet<RaceId>()
        val deferredRaces = LinkedHashSet<RaceId>()

        // 解析は全レコード先に行い、適用は『レースを定義する観測（開催日・レース・発走時刻の変更）→ それに依存する観測（オッズ）』の順にする。
        // 同じバッチの中でオッズがレースより前に並んでいても、レース待ち（保留）にしない。生レコードの保存は元の並び（seq）のまま。
        val parsedAll = env.records.map { rec -> parsers.parse(parseCtx, rec) }
        val statuses = Array(env.records.size) { ParseStatus.PARSED }
        val errors = arrayOfNulls<String>(env.records.size)
        val applyOrder =
            parsedAll
                .flatMapIndexed { index, parsed -> (parsed as? ParseOutcome.Parsed)?.observations.orEmpty().map { index to it } }
                .sortedBy { (_, obs) -> if (obs.definesRace()) 0 else 1 }
        for ((index, obs) in applyOrder) {
            if (obs is ScheduleDayObservation) maxRaceDate = listOfNotNull(maxRaceDate, obs.date).max()
            when (val r = applier.apply(tenantId, obs, applyCtx)) {
                is ApplyOutcome.Quarantined -> {
                    statuses[index] = ParseStatus.FAILED
                    errors[index] = r.reason
                }

                is ApplyOutcome.Deferred -> {
                    if (statuses[index] == ParseStatus.PARSED) {
                        statuses[index] = ParseStatus.PENDING
                        errors[index] = r.reason
                    }
                    waitedRace(r.reason)?.let { deferredRaces += it }
                }

                // レースの観測は、新規（Applied）でも既存（NoChange）でも、そのレースを待つ保留を再適用の対象にする
                // （前回の再適用が一時的に失敗して保留に残った行が、同じレースの観測が来るたびに再試行される）
                ApplyOutcome.Applied, ApplyOutcome.NoChange -> {
                    if (obs is RaceObservation) racesToReplay += obs.raceId
                }

                else -> {}
            }
        }
        env.records.forEachIndexed { index, rec ->
            var status = statuses[index]
            var error = errors[index]
            when (val parsed = parsedAll[index]) {
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
            if (inserted) {
                accepted++
            } else {
                duplicates++
                // 再送された保留中のレコードが今回は適用できた（依存するデータが揃った）なら、保留のままにしない
                if (status != ParseStatus.PENDING) raw.settlePending(tenantId, hash, status, error?.take(200))
            }
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
        return Processed(
            IngestAck(accepted, duplicates, quarantined, unsupported, now, duplicateBatch = false, fetch = progress, cursor = cursorAck),
            racesToReplay,
            deferredRaces,
        )
    }

    private class Processed(
        val ack: IngestAck,
        /** このバッチでレースの情報が反映された（または確認できた）レース。これを待つ保留を再適用できる。 */
        val racesToReplay: Set<RaceId>,
        /** このバッチが『レースがまだ無い』として保留にした観測の待つレース（コミット後に、その間に入っていないか確認する）。 */
        val deferredRaces: Set<RaceId>,
    )

    /** レース・開催日・発走時刻の変更など、他の観測が依存する観測。 */
    private fun Observation.definesRace(): Boolean = this is ScheduleDayObservation || this is RaceObservation || this is TimeChangeObservation

    /** [PendingReplayer.replay] を呼ぶ。コミット後の別の処理で、失敗しても今回のバッチの ACK には影響させない（結果は [onReplay] で通知）。 */
    private fun replayPending(
        tenantId: TenantId,
        raceIds: Set<RaceId>,
    ) {
        if (raceIds.isEmpty()) return
        val report = replayer.replay(tenantId, raceIds)
        try {
            onReplay(tenantId, report)
        } catch (_: RuntimeException) {
            // 通知の失敗で取込を失敗させない
        }
    }

    /** 再送された（完了済みの）バッチに含まれるレースの観測のレース。解析だけで適用しない（解析できないレコードは無視）。 */
    private fun racesIn(
        env: IngestEnvelope,
        observedAt: Instant,
    ): Set<RaceId> {
        val ctx = ParseContext(env.dataspec, env.batchClass, observedAt)
        val ids = LinkedHashSet<RaceId>()
        for (rec in env.records) {
            val parsed = parsers.parse(ctx, rec) as? ParseOutcome.Parsed ?: continue
            parsed.observations.filterIsInstance<RaceObservation>().forEach { ids += it.raceId }
        }
        return ids
    }

    /** [ids] のうち DB に既にあるレース。確認に失敗したら空（再適用は次の契機に回す。コミット済みのバッチの結果は変えない）。 */
    private fun existingRaces(
        tenantId: TenantId,
        ids: Set<RaceId>,
    ): Set<RaceId> =
        try {
            ids.filterTo(LinkedHashSet()) { races.find(tenantId, it) != null }
        } catch (_: RuntimeException) {
            emptySet()
        }

    private fun waitedRace(reason: String): RaceId? =
        if (reason.startsWith("$REASON_RACE_NOT_FOUND:")) RaceId.parseOrNull(reason.removePrefix("$REASON_RACE_NOT_FOUND:")) else null

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

    companion object {
        private val TS14: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")

        const val REPLAY_PAGE = PendingReplayer.REPLAY_PAGE
    }
}
