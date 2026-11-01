package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** 予定の反映を**受け付けなかった**理由（状態は変えていない。呼び出し側はログ・通知に使う）。 */
enum class RejectReason {
    /** 保存済みより古い版（古い版で新しい予定を上書きしない）。 */
    OLDER_SOURCE_VERSION,

    /** 同じ版なのに内容が違う（作成日は日単位で順序を決められない）。 */
    SAME_VERSION_DIFFERENT_CONTENT,

    /** 同じ作成日の中止（9）と削除（0）。一次仕様なしに順序を決めない。 */
    UNDECIDABLE_SOURCE_VERSION,

    /** 保存済みの版が解釈できない（比較の根拠が無いので上書きしない）。 */
    UNPARSEABLE_STORED_VERSION,

    /** 遷移表に無い状態の変更（例: 中止・削除されたレースの復活）。 */
    INVALID_TRANSITION,

    /** 成績系のデータ区分（T3.4 以降の担当）。 */
    UNSUPPORTED_KUBUN,

    /** レースが無い（時刻変更・取消の対象）。 */
    RACE_NOT_FOUND,

    /** 発走後・中止・削除のレースの時刻変更。 */
    TIME_CHANGE_NOT_ACCEPTED,

    /** 時刻の日付（JST）がレースの開催日と違う。 */
    POST_TIME_DATE_MISMATCH,

    /** 既に反映済みの観測より古い取得時刻。 */
    OLDER_OBSERVATION,

    /** イベントの種類が無い変更（例: 状態だけの変更）。記録できないので反映しない。 */
    UNRECORDABLE_CHANGE,

    /** 開催日の初期状態に使えない値。 */
    INVALID_INITIAL_STATUS,
}

enum class NoChangeReason {
    /** 同じ版・同じ内容の再配信。 */
    DUPLICATE_VERSION,

    /** 版は新しいが、反映すべき変化がない（版・名前だけの違いは保存しない）。 */
    NO_EFFECTIVE_CHANGE,

    /** 同じ履歴が既に記録されている（反映済み）。 */
    DUPLICATE_EVENT,

    /** 削除する対象のレースが無い（削除のために行を作らない）。 */
    NOTHING_TO_DELETE,

    /** 確認の時刻だけを進めた（開催日）。 */
    CONFIRMED_ONLY,
}

sealed interface ApplyResult<out T> {
    data class Applied<T>(
        val value: T,
        val event: ScheduleEvent,
    ) : ApplyResult<T>

    data class NoChange(
        val reason: NoChangeReason,
    ) : ApplyResult<Nothing>

    data class Rejected(
        val reason: RejectReason,
    ) : ApplyResult<Nothing>

    /** 楽観ロックの競合が続いた（再試行の上限）。成功ではない。 */
    data object Conflict : ApplyResult<Nothing>
}

/**
 * レース詳細（RA 由来を想定）の取得内容。**パース（T3.4）の後**の値で、T3.3 では合成データのテストが唯一の入力。
 * [postTime] は JST の発走時刻（`HHMM`。`0000`・欠落・不正は null として渡す）、[createdOn] はデータ作成年月日。
 */
data class RaceScheduleObservation(
    val tenantId: TenantId,
    val raceId: RaceId,
    val kubun: RaceDataKubun,
    val createdOn: LocalDate,
    val postTime: LocalTime?,
    val raceName: String?,
    val observedAt: Instant,
    val source: ScheduleEventSource = ScheduleEventSource.RACE,
    val batchId: String? = null,
) {
    val version: SourceVersion get() = SourceVersion(createdOn, kubun)

    /** 発走時刻の瞬間。`00:00` と null は不明（参照元の「00:00 を窓の開始に置換」という暗黙処理は廃止。RACE_CALENDAR_DESIGN §3.3）。 */
    val postAt: Instant?
        get() =
            postTime?.takeIf { it != LocalTime.MIDNIGHT }?.let {
                raceId.date
                    .atTime(it)
                    .atZone(Zones.JST)
                    .toInstant()
            }
}

/**
 * 開催予定（`race_schedule`）の更新（取得内容 → 版の比較 → 状態遷移の検証 → 更新と履歴の原子的な反映）。
 *
 * - **古い版は反映しない**（[SourceVersion.compareTo]。文字列比較ではなく作成日 → 区分の規則）。同じ版の再配信は何もしない（冪等）。
 * - 状態は遷移表（[RaceScheduleTransitions]）に従う。中止（9）・削除（0）は終端で、新しい版が来ても復活させない（拒否）。
 * - 発走時刻は出馬表（区分 2）以降だけ採用する（出走馬名表の時刻は一次仕様未確認のため採用しない）。
 * - 1 回の更新に履歴 1 件。履歴の種類が無い変化（状態だけの変更・名前だけの違い）は保存しない（[NoChangeReason.NO_EFFECTIVE_CHANGE]・[RejectReason.UNRECORDABLE_CHANGE]）。
 * - 楽観ロックの競合は読み直して再判定する（最大 [MAX_ATTEMPTS] 回。尽きたら [ApplyResult.Conflict]）。
 */
class RaceScheduleUpdater(
    private val schedules: RaceScheduleRepository,
    private val events: ScheduleEventReader,
    private val clock: Clock,
) {
    fun apply(obs: RaceScheduleObservation): ApplyResult<RaceSchedule> {
        if (!obs.kubun.handledByCalendar) return ApplyResult.Rejected(RejectReason.UNSUPPORTED_KUBUN)
        repeat(MAX_ATTEMPTS) {
            val existing = schedules.find(obs.tenantId, obs.raceId)
            val outcome = if (existing == null) insertNew(obs) else updateExisting(existing, obs)
            if (outcome != null) return outcome
        }
        return ApplyResult.Conflict
    }

    /** 発走時刻の変更（`0B14` の TC。スナップショット差分から呼ぶ）。[snapshotKey] は同じスナップショットの再配信を同一視する識別子。 */
    fun applyTimeChange(
        tenantId: TenantId,
        raceId: RaceId,
        newPostAt: Instant,
        observedAt: Instant,
        snapshotKey: String,
        batchId: String? = null,
    ): ApplyResult<RaceSchedule> = changePostTime(tenantId, raceId, newPostAt, observedAt, snapshotKey, batchId, ScheduleEventType.TIME_CHANGE)

    /** TC の取消（`0B14` は全置換なので、前回あって今回無い TC は取り消された）。[restoredPostAt] は取消後の元の時刻。 */
    fun revokeTimeChange(
        tenantId: TenantId,
        raceId: RaceId,
        restoredPostAt: Instant,
        observedAt: Instant,
        snapshotKey: String,
        batchId: String? = null,
    ): ApplyResult<RaceSchedule> = changePostTime(tenantId, raceId, restoredPostAt, observedAt, snapshotKey, batchId, ScheduleEventType.REVOKED)

    private fun changePostTime(
        tenantId: TenantId,
        raceId: RaceId,
        newPostAt: Instant,
        observedAt: Instant,
        snapshotKey: String,
        batchId: String?,
        type: ScheduleEventType,
    ): ApplyResult<RaceSchedule> {
        if (newPostAt.atZone(Zones.JST).toLocalDate() != raceId.date) return ApplyResult.Rejected(RejectReason.POST_TIME_DATE_MISMATCH)
        repeat(MAX_ATTEMPTS) {
            val existing = schedules.find(tenantId, raceId) ?: return ApplyResult.Rejected(RejectReason.RACE_NOT_FOUND)
            if (!RaceScheduleTransitions.acceptsPostTimeChange(existing.status)) return ApplyResult.Rejected(RejectReason.TIME_CHANGE_NOT_ACCEPTED)
            if (existing.scheduledPostAt == newPostAt && !existing.postTimeUnknown) return ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE)
            val lastObserved =
                events
                    .eventsOfRace(tenantId, raceId)
                    .filter { it.type == ScheduleEventType.ADDED || it.type == ScheduleEventType.TIME_CHANGE || it.type == ScheduleEventType.REVOKED }
                    .maxOfOrNull { it.observedAt }
            if (lastObserved != null && observedAt.isBefore(lastObserved)) return ApplyResult.Rejected(RejectReason.OLDER_OBSERVATION)
            val now = clock.now()
            val updated = existing.copy(scheduledPostAt = newPostAt, postTimeUnknown = false, lastChangedAt = now)
            val event =
                ScheduleEvent.create(
                    tenantId,
                    raceId,
                    raceId.date,
                    type,
                    oldValue = EventJson.obj("post_at" to existing.scheduledPostAt?.toString()),
                    newValue = EventJson.obj("post_at" to newPostAt.toString()),
                    source = ScheduleEventSource.B14,
                    observedAt = observedAt,
                    receivedAt = now,
                    dedupGroup = snapshotKey,
                    batchId = batchId,
                )
            when (val r = schedules.update(updated, event)) {
                is ChangeResult.Applied -> return ApplyResult.Applied(r.value, r.event)
                ChangeResult.Duplicate -> return ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
                ChangeResult.Conflict -> Unit
                ChangeResult.NotFound -> return ApplyResult.Rejected(RejectReason.RACE_NOT_FOUND)
                ChangeResult.AlreadyExists -> error("update が AlreadyExists を返した")
            }
        }
        return ApplyResult.Conflict
    }

    /** null は「競合・競り負け。読み直して再判定」。 */
    private fun insertNew(obs: RaceScheduleObservation): ApplyResult<RaceSchedule>? {
        if (obs.kubun == RaceDataKubun.DELETED) return ApplyResult.NoChange(NoChangeReason.NOTHING_TO_DELETE)
        val postAt = if (obs.kubun == RaceDataKubun.CARD) obs.postAt else null
        val status =
            when (obs.kubun) {
                RaceDataKubun.CANCELLED -> RaceScheduleStatus.CANCELLED
                RaceDataKubun.CARD -> if (postAt != null) RaceScheduleStatus.CARD else RaceScheduleStatus.ANNOUNCED
                else -> RaceScheduleStatus.ANNOUNCED
            }
        val now = clock.now()
        val schedule =
            RaceSchedule(
                tenantId = obs.tenantId,
                raceId = obs.raceId,
                scheduledPostAt = postAt,
                postTimeUnknown = postAt == null,
                status = status,
                dataKubun = obs.kubun.code,
                sourceVersion = obs.version.encode(),
                raceName = obs.raceName?.take(100),
                lastChangedAt = now,
            )
        val type = if (status == RaceScheduleStatus.CANCELLED) ScheduleEventType.CANCELLED else ScheduleEventType.ADDED
        val event =
            ScheduleEvent.create(
                obs.tenantId,
                obs.raceId,
                obs.raceId.date,
                type,
                oldValue = null,
                newValue = EventJson.obj("status" to status.name, "post_at" to postAt?.toString(), "source_version" to obs.version.encode()),
                source = obs.source,
                observedAt = obs.observedAt,
                receivedAt = now,
                dedupGroup = obs.version.encode(),
                batchId = obs.batchId,
            )
        return when (val r = schedules.insertIfAbsent(schedule, event)) {
            is ChangeResult.Applied -> ApplyResult.Applied(r.value, r.event)
            ChangeResult.Duplicate -> ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
            ChangeResult.AlreadyExists -> null
            ChangeResult.Conflict, ChangeResult.NotFound -> error("insertIfAbsent が ${r::class.simpleName} を返した")
        }
    }

    private fun updateExisting(
        existing: RaceSchedule,
        obs: RaceScheduleObservation,
    ): ApplyResult<RaceSchedule>? {
        val stored = SourceVersion.parseOrNull(existing.sourceVersion) ?: return ApplyResult.Rejected(RejectReason.UNPARSEABLE_STORED_VERSION)
        when (obs.version.compareTo(stored)) {
            VersionOrder.OLDER -> {
                return ApplyResult.Rejected(RejectReason.OLDER_SOURCE_VERSION)
            }

            VersionOrder.UNDECIDABLE -> {
                return ApplyResult.Rejected(RejectReason.UNDECIDABLE_SOURCE_VERSION)
            }

            VersionOrder.SAME -> {
                return if (sameContent(existing, obs)) {
                    ApplyResult.NoChange(NoChangeReason.DUPLICATE_VERSION)
                } else {
                    ApplyResult.Rejected(RejectReason.SAME_VERSION_DIFFERENT_CONTENT)
                }
            }

            VersionOrder.NEWER -> {}
        }
        val observedPostAt = if (obs.kubun == RaceDataKubun.CARD) obs.postAt else null
        val targetStatus =
            when (obs.kubun) {
                RaceDataKubun.CANCELLED -> {
                    RaceScheduleStatus.CANCELLED
                }

                RaceDataKubun.DELETED -> {
                    RaceScheduleStatus.DELETED
                }

                RaceDataKubun.CARD -> {
                    if (existing.status == RaceScheduleStatus.ANNOUNCED && observedPostAt != null) RaceScheduleStatus.CARD else existing.status
                }

                else -> {
                    existing.status
                }
            }
        if (targetStatus != existing.status && !RaceScheduleTransitions.canTransition(existing.status, targetStatus)) {
            return ApplyResult.Rejected(RejectReason.INVALID_TRANSITION)
        }
        if (targetStatus == existing.status && RaceScheduleTransitions.isTerminal(existing.status) &&
            obs.kubun in setOf(RaceDataKubun.ANNOUNCEMENT, RaceDataKubun.CARD)
        ) {
            // 中止・削除・確定済みのレースに新しい版（出走馬名表・出馬表）が来ても復活させない
            return ApplyResult.Rejected(RejectReason.INVALID_TRANSITION)
        }
        val timeAccepted = obs.kubun == RaceDataKubun.CARD && RaceScheduleTransitions.acceptsPostTimeChange(existing.status)
        val newPostAt = if (timeAccepted) observedPostAt else existing.scheduledPostAt
        val newUnknown = if (timeAccepted) observedPostAt == null else existing.postTimeUnknown
        val timeChanged = newPostAt != existing.scheduledPostAt || newUnknown != existing.postTimeUnknown
        val type =
            when {
                targetStatus == RaceScheduleStatus.CANCELLED && existing.status != RaceScheduleStatus.CANCELLED -> ScheduleEventType.CANCELLED
                targetStatus == RaceScheduleStatus.DELETED && existing.status != RaceScheduleStatus.DELETED -> ScheduleEventType.DELETED
                timeChanged -> ScheduleEventType.TIME_CHANGE
                else -> null
            }
        if (type == null) {
            return if (targetStatus != existing.status) {
                ApplyResult.Rejected(RejectReason.UNRECORDABLE_CHANGE)
            } else {
                ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE)
            }
        }
        val now = clock.now()
        val updated =
            existing.copy(
                scheduledPostAt = newPostAt,
                postTimeUnknown = newUnknown,
                status = targetStatus,
                dataKubun = obs.kubun.code,
                sourceVersion = obs.version.encode(),
                raceName = obs.raceName?.take(100) ?: existing.raceName,
                lastChangedAt = now,
            )
        val event =
            ScheduleEvent.create(
                obs.tenantId,
                obs.raceId,
                obs.raceId.date,
                type,
                oldValue =
                    EventJson.obj(
                        "status" to existing.status.name,
                        "post_at" to existing.scheduledPostAt?.toString(),
                        "source_version" to existing.sourceVersion,
                    ),
                newValue = EventJson.obj("status" to targetStatus.name, "post_at" to newPostAt?.toString(), "source_version" to obs.version.encode()),
                source = obs.source,
                observedAt = obs.observedAt,
                receivedAt = now,
                dedupGroup = obs.version.encode(),
                batchId = obs.batchId,
            )
        return when (val r = schedules.update(updated, event)) {
            is ChangeResult.Applied -> ApplyResult.Applied(r.value, r.event)
            ChangeResult.Duplicate -> ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
            ChangeResult.Conflict, ChangeResult.NotFound -> null
            ChangeResult.AlreadyExists -> error("update が AlreadyExists を返した")
        }
    }

    private fun sameContent(
        existing: RaceSchedule,
        obs: RaceScheduleObservation,
    ): Boolean {
        val expectsTime = obs.kubun == RaceDataKubun.CARD
        return !expectsTime || (existing.scheduledPostAt == obs.postAt)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * 開催日（`race_day`）の更新（YSCH・RA・手動の判断から呼ぶ）。状態は遷移表（[RaceDayTransitions]）に従い、状態の変更は
 * 履歴（[ScheduleEventType.DAY_STATUS]、延期は [ScheduleEventType.POSTPONED]）と同じトランザクションで反映する。
 * 取得で確認できた時刻だけを進める操作（[confirm]）は履歴を残さない。
 */
class RaceDayUpdater(
    private val days: RaceDayRepository,
    private val clock: Clock,
) {
    fun apply(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        kai: Int,
        nichime: Int,
        target: RaceDayStatus,
        replacedByDate: LocalDate?,
        observedAt: Instant,
        source: ScheduleEventSource,
        dedupGroup: String,
    ): ApplyResult<RaceDay> {
        repeat(RaceScheduleUpdater.MAX_ATTEMPTS) {
            val existing = days.find(tenantId, date, venue)
            val now = clock.now()
            val outcome =
                if (existing == null) {
                    if (target !in RaceDayTransitions.initialStatuses) return ApplyResult.Rejected(RejectReason.INVALID_INITIAL_STATUS)
                    val day = RaceDay(tenantId, date, venue, kai, nichime, target, replacedByDate, firstSeenAt = now, lastConfirmedAt = observedAt)
                    when (val r = days.insertIfAbsent(day, event(tenantId, date, venue, null, target, observedAt, now, source, dedupGroup, replacedByDate))) {
                        is ChangeResult.Applied -> ApplyResult.Applied(r.value, r.event)
                        ChangeResult.Duplicate -> ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
                        ChangeResult.AlreadyExists -> null
                        else -> error("insertIfAbsent が ${r::class.simpleName} を返した")
                    }
                } else {
                    updateExisting(existing, kai, nichime, target, replacedByDate, observedAt, now, source, dedupGroup)
                }
            if (outcome != null) return outcome
        }
        return ApplyResult.Conflict
    }

    /**
     * bot-run・reconcile が開催日の**進行**（RUNNING → SETTLING → COMPLETED / INCOMPLETE）を記録する。**`last_confirmed_at`（スケジュール取得で確認できた時刻）は変えない**
     * （進行の記録で『確認済み』を更新すると、予定が古くなったこと〈STALE〉を隠してしまうため）。遷移表を通らない辺・既に同じ状態・行が無い場合は何もしない。
     * 履歴（DAY_STATUS）は同じトランザクションで追記する。競合は読み直して再判定（最大 [RaceScheduleUpdater.MAX_ATTEMPTS] 回）。
     */
    fun advance(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        target: RaceDayStatus,
        dedupGroup: String,
    ): ApplyResult<RaceDay> {
        repeat(RaceScheduleUpdater.MAX_ATTEMPTS) {
            val existing = days.find(tenantId, date, venue) ?: return ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE)
            if (existing.status == target) return ApplyResult.NoChange(NoChangeReason.NO_EFFECTIVE_CHANGE)
            if (!RaceDayTransitions.canTransition(existing.status, target)) return ApplyResult.Rejected(RejectReason.INVALID_TRANSITION)
            val now = clock.now()
            val event = event(tenantId, date, venue, existing.status, target, now, now, ScheduleEventSource.SYSTEM, dedupGroup, null)
            when (val r = days.update(existing.copy(status = target), event)) {
                is ChangeResult.Applied -> {
                    return ApplyResult.Applied(r.value, r.event)
                }

                ChangeResult.Duplicate -> {
                    return ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
                }

                ChangeResult.Conflict, ChangeResult.NotFound -> {}

                ChangeResult.AlreadyExists -> {
                    error("update が AlreadyExists を返した")
                }
            }
        }
        return ApplyResult.Conflict
    }

    /** 取得で確認できた時刻だけを進める。行が無ければ false。 */
    fun confirm(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        observedAt: Instant,
    ): Boolean = days.confirm(tenantId, date, venue, observedAt)

    private fun updateExisting(
        existing: RaceDay,
        kai: Int,
        nichime: Int,
        target: RaceDayStatus,
        replacedByDate: LocalDate?,
        observedAt: Instant,
        now: Instant,
        source: ScheduleEventSource,
        dedupGroup: String,
    ): ApplyResult<RaceDay>? {
        require(kai == existing.kai && nichime == existing.nichime) { "開催回・日目は変更できない（別の開催日の行）" }
        val statusChanged = target != existing.status
        val refinesTarget = !statusChanged && target == RaceDayStatus.POSTPONED && existing.replacedByDate == null && replacedByDate != null
        if (!statusChanged && !refinesTarget) {
            days.confirm(existing.tenantId, existing.raceDate, existing.venue, observedAt)
            return ApplyResult.NoChange(NoChangeReason.CONFIRMED_ONLY)
        }
        if (statusChanged && !RaceDayTransitions.canTransition(existing.status, target)) return ApplyResult.Rejected(RejectReason.INVALID_TRANSITION)
        if (existing.lastConfirmedAt != null && observedAt.isBefore(existing.lastConfirmedAt)) return ApplyResult.Rejected(RejectReason.OLDER_OBSERVATION)
        val updated =
            existing.copy(
                status = target,
                replacedByDate = if (target == RaceDayStatus.POSTPONED) replacedByDate else null,
                lastConfirmedAt = observedAt,
            )
        val event = event(existing.tenantId, existing.raceDate, existing.venue, existing.status, target, observedAt, now, source, dedupGroup, replacedByDate)
        return when (val r = days.update(updated, event)) {
            is ChangeResult.Applied -> ApplyResult.Applied(r.value, r.event)
            ChangeResult.Duplicate -> ApplyResult.NoChange(NoChangeReason.DUPLICATE_EVENT)
            ChangeResult.Conflict, ChangeResult.NotFound -> null
            ChangeResult.AlreadyExists -> error("update が AlreadyExists を返した")
        }
    }

    private fun event(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        from: RaceDayStatus?,
        to: RaceDayStatus,
        observedAt: Instant,
        now: Instant,
        source: ScheduleEventSource,
        dedupGroup: String,
        replacedByDate: LocalDate?,
    ) = ScheduleEvent.create(
        tenantId,
        null,
        date,
        if (to == RaceDayStatus.POSTPONED) ScheduleEventType.POSTPONED else ScheduleEventType.DAY_STATUS,
        oldValue = from?.let { EventJson.obj("status" to it.name, "venue" to venue.code) },
        newValue = EventJson.obj("status" to to.name, "venue" to venue.code, "replaced_by" to replacedByDate?.toString()),
        source = source,
        observedAt = observedAt,
        receivedAt = now,
        dedupGroup = dedupGroup,
    )
}
