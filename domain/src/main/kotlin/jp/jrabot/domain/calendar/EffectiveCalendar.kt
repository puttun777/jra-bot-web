package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 開催日の有効な判定。**曜日・祝日は使わない**（DB の予定と取得の事実だけが根拠。D-024）。
 *
 * 次の 5 つを区別する（「データが無い」ことを「開催なし」にしない）:
 * 1. [CONFIRMED]: 開催ありと確認できた（出走表の発走時刻があり、確認が新しい、または手動で開催日と指定）。
 * 2. [NO_RACE_CONFIRMED]: 開催なしと確認できた（新しい取得の事実があり、その日の予定が無い。または手動で開催なしと指定）。
 * 3. [UNKNOWN]: データが無く、有無が不明（取得の事実が無い）。
 * 4. [STALE]: データが古く信頼できない（確認が [CalendarSettings.staleAfter] を超えた）。
 * 5. 手動の指定は [VerdictBasis.MANUAL]（判定の根拠が違うだけで、[CONFIRMED]・[NO_RACE_CONFIRMED]・[CANCELLED]・[POSTPONED] のどれかになる）。
 * ほかに、予定はあるが出走表が未確認の [UNCONFIRMED]、中止の [CANCELLED]、延期の [POSTPONED]。
 */
enum class DayVerdict { CONFIRMED, NO_RACE_CONFIRMED, UNKNOWN, STALE, UNCONFIRMED, CANCELLED, POSTPONED }

enum class VerdictBasis { SOURCE, MANUAL }

/** 開催日の進行（`race_day.status` の RUNNING・SETTLING・COMPLETED・INCOMPLETE から。T3.8 の bot-run が更新する）。 */
enum class DayProgress { NOT_STARTED, RUNNING, SETTLING, COMPLETED, INCOMPLETE }

/** 手動補正と元の予定の食い違い（ダッシュボードで管理者に確認を促す `override_conflict`）。 */
enum class ConflictKind {
    /** 手動で開催なし・中止・延期と指定したが、元の予定は開催あり。 */
    MANUAL_NO_RACE_BUT_SOURCE_HAS_RACES,

    /** 手動で開催日と指定したが、元の予定は中止・延期・開催なし。 */
    MANUAL_RACE_DAY_BUT_SOURCE_HAS_NO_RACE,
}

data class OverrideConflict(
    val kind: ConflictKind,
    val overrideId: Long?,
)

data class CalendarSettings(
    val watch: WatchSettings,
    /** 確認（`last_confirmed_at`・取得の事実）がこれより古いと信頼しない。ちょうどの経過は新鮮（超えたら古い）。運用値は未確定（既定なし）。 */
    val staleAfter: Duration,
) {
    init {
        require(staleAfter.isPositive) { "staleAfter は正: $staleAfter" }
    }
}

/** 有効なレース（元の予定に手動補正を適用したもの）。 */
data class EffectiveRace(
    val raceId: RaceId,
    val status: RaceScheduleStatus,
    /** 有効な発走時刻。不明は null（手動の時刻、なければ出馬表以降の予定の時刻）。 */
    val postAt: Instant?,
    val cancelledByManual: Boolean,
    val postTimeByManual: Boolean,
    /** 起動ウィンドウの計算に使う（有効・時刻あり・信頼できる場の予定）。 */
    val inWindow: Boolean,
)

data class EffectiveDay(
    val date: LocalDate,
    val verdict: DayVerdict,
    val basis: VerdictBasis,
    val progress: DayProgress,
    val races: List<EffectiveRace>,
    /** [EffectiveRace.inWindow] のレースの最初・最後の発走。ウィンドウが作れない（時刻が 1 つも無い）ときは null。 */
    val firstPost: Instant?,
    val lastPost: Instant?,
    /** 有効な DENY_RUN がある。 */
    val denyRun: Boolean,
    /** 有効な ALLOW_RUN がある（元の予定が不明・古い・未確認・開催なしでも起動を許可する。手動の中止・延期・開催なしや DENY_RUN は覆さない）。 */
    val allowRun: Boolean,
    val postponedTo: LocalDate?,
    val conflicts: List<OverrideConflict>,
    /** 確認が古くて起動ウィンドウから外した場。 */
    val staleVenues: Set<Venue>,
) {
    /** 有効なレースで発走時刻が分からないもの（時刻不明のレースでは通知しない。T3.8）。 */
    val unknownTimeRaces: Int
        get() = races.count { it.status != RaceScheduleStatus.CANCELLED && it.postAt == null }

    fun window(settings: WatchSettings): WatchWindow? = if (firstPost != null && lastPost != null) WatchWindow(firstPost, lastPost, settings) else null
}

/**
 * 元の予定（`race_day`・`race_schedule`・取得の事実）に手動補正（`calendar_override`）を適用して、その日の有効な状態を導出する純粋な関数。
 *
 * 手動補正の優先順位（RACE_CALENDAR_DESIGN §4）: **DENY_RUN > CANCEL・MARK_NO_RACE > ALLOW_RUN・MARK_RACE_DAY・SET_POST_TIME・POSTPONE_TO > 元の予定**。
 * - 許可（[EffectiveDay.denyRun]・[EffectiveDay.allowRun]）: DENY_RUN があれば ALLOW_RUN があっても拒否。
 * - 判定: 日単位の CANCEL・MARK_NO_RACE（上位）が MARK_RACE_DAY・POSTPONE_TO（下位）に勝つ。同じ階層は**作成が新しい方**（同時刻は `id` の大きい方）。
 * - レース単位: CANCEL が SET_POST_TIME に勝つ。SET_POST_TIME は作成が新しい方。日単位の CANCEL は全レースを中止にする。
 * - 取消済み・期限切れの補正は適用しない。同じ入力は必ず同じ結果（入力の並びに依存しない）。
 */
object EffectiveCalendar {
    fun derive(
        tenantId: TenantId,
        date: LocalDate,
        now: Instant,
        settings: CalendarSettings,
        days: List<RaceDay>,
        races: List<RaceSchedule>,
        overrides: List<CalendarOverride>,
        fetchFact: ScheduleFetchFact?,
    ): EffectiveDay {
        require(days.all { it.tenantId == tenantId && it.raceDate == date }) { "別のテナント・日付の開催日が混ざっている" }
        require(races.all { it.tenantId == tenantId && it.raceId.date == date }) { "別のテナント・日付のレースが混ざっている" }
        require(overrides.all { it.tenantId == tenantId && it.raceDate == date }) { "別のテナント・日付の補正が混ざっている" }
        require(fetchFact == null || fetchFact.date == date) { "別の日付の取得の事実" }

        val active = overrides.filter { it.isActiveAt(now) }.sortedWith(newestLast)
        val source = deriveSource(date, now, settings, days, races, fetchFact)

        val denyRun = active.any { it.action == OverrideAction.DENY_RUN }
        val allowRun = active.any { it.action == OverrideAction.ALLOW_RUN }
        val dayOverrides = active.filter { it.scope == OverrideScope.DAY }
        val tierA = dayOverrides.lastOrNull { it.action == OverrideAction.CANCEL || it.action == OverrideAction.MARK_NO_RACE }
        val tierB = dayOverrides.lastOrNull { it.action == OverrideAction.MARK_RACE_DAY || it.action == OverrideAction.POSTPONE_TO }
        val manual = tierA ?: tierB

        var verdict = source.verdict
        var basis = VerdictBasis.SOURCE
        var postponedTo = source.postponedTo
        val conflicts = mutableListOf<OverrideConflict>()
        if (manual != null) {
            basis = VerdictBasis.MANUAL
            val sourceHasRaces = source.verdict == DayVerdict.CONFIRMED
            val sourceHasNoRace =
                source.verdict == DayVerdict.NO_RACE_CONFIRMED || source.verdict == DayVerdict.CANCELLED || source.verdict == DayVerdict.POSTPONED
            when (manual.action) {
                OverrideAction.CANCEL -> {
                    verdict = DayVerdict.CANCELLED
                    if (sourceHasRaces) conflicts += OverrideConflict(ConflictKind.MANUAL_NO_RACE_BUT_SOURCE_HAS_RACES, manual.id)
                }

                OverrideAction.MARK_NO_RACE -> {
                    verdict = DayVerdict.NO_RACE_CONFIRMED
                    if (sourceHasRaces) conflicts += OverrideConflict(ConflictKind.MANUAL_NO_RACE_BUT_SOURCE_HAS_RACES, manual.id)
                }

                OverrideAction.POSTPONE_TO -> {
                    verdict = DayVerdict.POSTPONED
                    postponedTo = (manual.value as OverrideValue.PostponedTo).date
                    if (sourceHasRaces) conflicts += OverrideConflict(ConflictKind.MANUAL_NO_RACE_BUT_SOURCE_HAS_RACES, manual.id)
                }

                OverrideAction.MARK_RACE_DAY -> {
                    verdict = DayVerdict.CONFIRMED
                    if (sourceHasNoRace) conflicts += OverrideConflict(ConflictKind.MANUAL_RACE_DAY_BUT_SOURCE_HAS_NO_RACE, manual.id)
                }

                else -> {
                    error("日単位の判定の補正ではない: ${manual.action}")
                }
            }
        }

        val raceOverrides = active.filter { it.scope == OverrideScope.RACE }
        val trustAllVenues = (manual?.action == OverrideAction.MARK_RACE_DAY) || allowRun
        val effectiveRaces =
            races
                .filter { it.status != RaceScheduleStatus.DELETED }
                .sortedBy { it.raceId }
                .map { race ->
                    val mine = raceOverrides.filter { it.raceId == race.raceId }
                    val cancelledByManual = mine.any { it.action == OverrideAction.CANCEL }
                    val manualTime = (mine.lastOrNull { it.action == OverrideAction.SET_POST_TIME }?.value as? OverrideValue.PostAt)?.at
                    val status = if (cancelledByManual) RaceScheduleStatus.CANCELLED else race.status
                    val sourceTime = if (RaceScheduleTransitions.hasCard(race.status) && !race.postTimeUnknown) race.scheduledPostAt else null
                    val postAt = if (cancelledByManual) null else manualTime ?: sourceTime
                    val usableVenue = trustAllVenues || race.raceId.venue in source.confirmedVenues
                    EffectiveRace(
                        raceId = race.raceId,
                        status = status,
                        postAt = postAt,
                        cancelledByManual = cancelledByManual,
                        postTimeByManual = !cancelledByManual && manualTime != null,
                        inWindow = RaceScheduleTransitions.isActive(status) && postAt != null && usableVenue,
                    )
                }
        val windowTimes = effectiveRaces.filter { it.inWindow }.mapNotNull { it.postAt }
        return EffectiveDay(
            date = date,
            verdict = verdict,
            basis = basis,
            progress = source.progress,
            races = effectiveRaces,
            firstPost = windowTimes.minOrNull(),
            lastPost = windowTimes.maxOrNull(),
            denyRun = denyRun,
            allowRun = allowRun,
            postponedTo = postponedTo,
            conflicts = conflicts,
            staleVenues = source.staleVenues,
        )
    }

    /** 作成の古い順（同時刻は id の小さい順。id 未採番は最も新しい扱い）。最後の要素が最も優先される。 */
    private val newestLast: Comparator<CalendarOverride> =
        compareBy<CalendarOverride> { it.createdAt }.thenBy { it.id ?: Long.MAX_VALUE }

    private class Source(
        val verdict: DayVerdict,
        val progress: DayProgress,
        val postponedTo: LocalDate?,
        val confirmedVenues: Set<Venue>,
        val staleVenues: Set<Venue>,
    )

    private enum class VenueVerdict { CONFIRMED, STALE, UNCONFIRMED, CANCELLED, POSTPONED }

    private fun deriveSource(
        date: LocalDate,
        now: Instant,
        settings: CalendarSettings,
        days: List<RaceDay>,
        races: List<RaceSchedule>,
        fetchFact: ScheduleFetchFact?,
    ): Source {
        val venues = (days.map { it.venue } + races.map { it.raceId.venue }).toSortedSet()
        val verdicts =
            venues.associateWith { venue ->
                val day = days.firstOrNull { it.venue == venue }
                val mine = races.filter { it.raceId.venue == venue && it.status != RaceScheduleStatus.DELETED }
                val activeRaces = mine.filter { RaceScheduleTransitions.isActive(it.status) }
                val finished = day?.status == RaceDayStatus.COMPLETED || day?.status == RaceDayStatus.INCOMPLETE
                val fresh = finished || isFresh(day?.lastConfirmedAt, now, settings.staleAfter)
                when {
                    day?.status == RaceDayStatus.POSTPONED -> VenueVerdict.POSTPONED

                    day?.status == RaceDayStatus.CANCELLED -> VenueVerdict.CANCELLED

                    activeRaces.isEmpty() && mine.isNotEmpty() -> VenueVerdict.CANCELLED

                    !fresh -> VenueVerdict.STALE

                    activeRaces.any {
                        RaceScheduleTransitions.hasCard(
                            it.status,
                        ) && !it.postTimeUnknown && it.scheduledPostAt != null
                    } -> VenueVerdict.CONFIRMED

                    else -> VenueVerdict.UNCONFIRMED
                }
            }
        val confirmed = verdicts.filterValues { it == VenueVerdict.CONFIRMED }.keys
        val stale = verdicts.filterValues { it == VenueVerdict.STALE }.keys
        val verdict =
            when {
                confirmed.isNotEmpty() -> DayVerdict.CONFIRMED
                stale.isNotEmpty() -> DayVerdict.STALE
                verdicts.containsValue(VenueVerdict.UNCONFIRMED) -> DayVerdict.UNCONFIRMED
                verdicts.containsValue(VenueVerdict.CANCELLED) -> DayVerdict.CANCELLED
                verdicts.containsValue(VenueVerdict.POSTPONED) -> DayVerdict.POSTPONED
                fetchFact == null -> DayVerdict.UNKNOWN
                isFresh(fetchFact.fetchedAt, now, settings.staleAfter) -> DayVerdict.NO_RACE_CONFIRMED
                else -> DayVerdict.STALE
            }
        val postponedTo =
            if (verdict ==
                DayVerdict.POSTPONED
            ) {
                days.filter { it.status == RaceDayStatus.POSTPONED }.mapNotNull { it.replacedByDate }.minOrNull()
            } else {
                null
            }
        return Source(verdict, progressOf(days.filter { it.venue in confirmed }), postponedTo, confirmed, stale)
    }

    private fun progressOf(racing: List<RaceDay>): DayProgress {
        val statuses = racing.map { it.status }
        return when {
            statuses.any { it == RaceDayStatus.RUNNING } -> {
                DayProgress.RUNNING
            }

            statuses.any { it == RaceDayStatus.SETTLING } -> {
                DayProgress.SETTLING
            }

            statuses.isNotEmpty() && statuses.all { it == RaceDayStatus.COMPLETED || it == RaceDayStatus.INCOMPLETE } -> {
                if (statuses.any { it == RaceDayStatus.INCOMPLETE }) DayProgress.INCOMPLETE else DayProgress.COMPLETED
            }

            else -> {
                DayProgress.NOT_STARTED
            }
        }
    }

    /** 確認の時刻 [confirmedAt] が [staleAfter] 以内か。時刻が無い（確認の記録なし）は古い扱い。未来の時刻は経過 0 とみなす。 */
    internal fun isFresh(
        confirmedAt: Instant?,
        now: Instant,
        staleAfter: Duration,
    ): Boolean {
        if (confirmedAt == null) return false
        val age = Duration.between(confirmedAt, now)
        return age.isNegative || age <= staleAfter
    }
}
