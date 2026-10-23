package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant
import java.time.LocalDate

/**
 * 開催日（`race_day`。場ごとの行）。[lastConfirmedAt] は「この日はこの状態」と**取得で確認できた**最後の時刻（鮮度判定の基準。UTC）。
 * レースの集合から導出される要約で、真実の源は `race_schedule`（RACE_CALENDAR_DESIGN §2.1）。YSCH の中止・延期・振替先だけがここにしか無い。
 * 開催の有無の最終判断は [EffectiveCalendar] が行う（この行の status をそのまま信用しない）。
 */
data class RaceDay(
    override val tenantId: TenantId,
    val raceDate: LocalDate,
    val venue: Venue,
    val kai: Int,
    val nichime: Int,
    val status: RaceDayStatus,
    /** 延期・代替開催の振替先。延期（[RaceDayStatus.POSTPONED]）以外では持てない。延期でも不明（null）があり得る。 */
    val replacedByDate: LocalDate?,
    val firstSeenAt: Instant,
    val lastConfirmedAt: Instant?,
    val version: Int = 0,
) : TenantScoped {
    init {
        require(kai in 1..99) { "kai は 1〜99: $kai" }
        require(nichime in 1..99) { "nichime は 1〜99: $nichime" }
        require(version >= 0) { "version は 0 以上: $version" }
        require(replacedByDate == null || status == RaceDayStatus.POSTPONED) { "振替先は POSTPONED のときだけ持てる: $status" }
        require(replacedByDate == null || replacedByDate.isAfter(raceDate)) { "振替先は開催日より後" }
    }
}

/** 開催予定の書込みの結果。更新と履歴（[ScheduleEvent]）の追記は常に 1 つのトランザクションで、どちらか一方だけが残ることはない。 */
sealed interface ChangeResult<out T> {
    /** 反映した。[value] は保存後の状態（`version` が進んだもの）、[event] は追記した履歴（`id` 付き）。 */
    data class Applied<T>(
        val value: T,
        val event: ScheduleEvent,
    ) : ChangeResult<T>

    /** 同じ履歴（`dedupHash`）が既にある = 同じ変化が反映済み。**何も変更していない**（再実行しても結果が同じ）。 */
    data object Duplicate : ChangeResult<Nothing>

    /** 楽観ロックの競合（他の更新が先に入った）。読み直して再計算する。 */
    data object Conflict : ChangeResult<Nothing>

    /** 更新対象が無い（別テナントの行を含む）。 */
    data object NotFound : ChangeResult<Nothing>

    /** 挿入しようとした自然キーが既にある。**何も変更していない**。 */
    data object AlreadyExists : ChangeResult<Nothing>
}

interface RaceDayReader {
    fun find(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
    ): RaceDay?

    /** その日の全場。場コードの昇順。 */
    fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceDay>
}

interface RaceDayWriter {
    /** 自然キー `(tenantId, raceDate, venue)` が無いときだけ挿入し、[event]（[ScheduleEventType.DAY_STATUS] か [ScheduleEventType.POSTPONED]）を同じトランザクションで追記する。 */
    fun insertIfAbsent(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay>

    /** 楽観ロック更新（`day.version` が保存値と一致するときだけ）と [event] の追記。状態の遷移規則は呼び出し側（[RaceDayUpdater]）が検証する。 */
    fun update(
        day: RaceDay,
        event: ScheduleEvent,
    ): ChangeResult<RaceDay>

    /**
     * 取得で確認できた時刻 [observedAt] を `last_confirmed_at` に反映する（**前進だけ**。古い時刻で戻さない）。履歴は残さない（取得のたびに行が増えるのを避ける）。
     * 行が無ければ false。反映した（時刻が前進した）か、既に同じかそれ以降なら true。
     */
    fun confirm(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
        observedAt: Instant,
    ): Boolean
}

interface RaceDayRepository :
    RaceDayReader,
    RaceDayWriter

sealed interface AppendResult {
    data class Appended(
        val event: ScheduleEvent,
    ) : AppendResult

    /** 同じ履歴が既にある（冪等）。 */
    data object Duplicate : AppendResult
}

interface ScheduleEventReader {
    /** レースの履歴（追記順）。 */
    fun eventsOfRace(
        tenantId: TenantId,
        raceId: jp.jrabot.domain.race.RaceId,
    ): List<ScheduleEvent>

    /** 開催日の履歴（日単位・レース単位とも。追記順）。 */
    fun eventsOfDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<ScheduleEvent>
}

/** 追記専用。更新・削除の操作は提供しない（保持期間による削除は retention Job の別経路）。 */
interface ScheduleEventWriter {
    /** 予定の更新を伴わない履歴（STALE・FETCH_FAILURE など）を追記する。[ScheduleEvent.dedupHash] が重複していれば [AppendResult.Duplicate]。 */
    fun append(event: ScheduleEvent): AppendResult
}

interface ScheduleEventRepository :
    ScheduleEventReader,
    ScheduleEventWriter
