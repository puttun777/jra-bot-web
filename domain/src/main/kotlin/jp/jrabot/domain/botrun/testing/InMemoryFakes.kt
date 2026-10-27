package jp.jrabot.domain.botrun.testing

import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationRecord
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.odds.ComboOddsSnapshot
import jp.jrabot.domain.odds.ComboOddsSnapshotReader
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.race.Race
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceReader
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.RecordedSignal
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Instant
import java.time.LocalDate

// bot-run のテスト用の偽物（合成データ専用）。永続化層の意味（テナント境界・冪等・一方向の通知状態）を写す。DB そのものの検証ではない。

/** 手で進める時計。待機（[jp.jrabot.domain.botrun.Pacer]）がこれを進めるので、テストは実時間を待たない。 */
class ManualClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant

    fun advance(d: java.time.Duration) {
        instant = instant.plus(d)
    }
}

/** 単勝オッズの偽物。テナントとレースで分ける。 */
class InMemoryOdds : OddsSnapshotReader {
    private val win = ArrayList<OddsSnapshot>()

    /** 次の操作で投げる例外（1 回だけ）。DB 障害の再現。 */
    @Volatile
    var failNext: RuntimeException? = null

    /** 設定すると、毎回この例外を投げる（DB が落ち続ける状況の再現）。 */
    @Volatile
    var failAlways: RuntimeException? = null

    @Synchronized
    fun add(s: OddsSnapshot) {
        win += s
    }

    @Synchronized
    override fun latest(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): OddsSnapshot? {
        failAlways?.let { throw it }
        failNext?.let {
            failNext = null
            throw it
        }
        return win.filter { it.tenantId == tenantId && it.raceId == raceId && it.market == market }.maxByOrNull { it.observedAt }
    }

    @Synchronized
    override fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<OddsSnapshot> = win.filter { it.tenantId == tenantId && it.raceId == raceId && it.market == market }.sortedBy { it.observedAt }
}

/** ワイド・三連複の実オッズの偽物。 */
class InMemoryComboOdds : ComboOddsSnapshotReader {
    private val combo = ArrayList<ComboOddsSnapshot>()

    @Synchronized
    fun add(s: ComboOddsSnapshot) {
        combo += s
    }

    @Synchronized
    override fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<ComboOddsSnapshot> = combo.filter { it.tenantId == tenantId && it.raceId == raceId && it.market == market }.sortedBy { it.collectedAt }
}

class InMemoryEvaluations : RaceEvaluationWriter {
    val rows = LinkedHashMap<Triple<TenantId, RaceId, Instant>, RaceEvaluationRecord>()

    @Synchronized
    override fun insertIfAbsent(record: RaceEvaluationRecord): InsertOutcome {
        val key = Triple(record.tenantId, record.raceId, record.snapshotAt)
        if (rows.containsKey(key)) return InsertOutcome.ALREADY_EXISTS
        rows[key] = record
        return InsertOutcome.INSERTED
    }
}

class InMemoryEntries(
    private val names: Map<RaceId, Map<Int, String>> = emptyMap(),
) : RaceEntryReader {
    override fun horseNames(
        tenantId: TenantId,
        raceId: RaceId,
    ): Map<Int, String> = names[raceId].orEmpty()
}

class InMemoryRaces(
    private val races: List<Race> = emptyList(),
) : RaceReader {
    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): Race? = races.firstOrNull { it.tenantId == tenantId && it.id == raceId }

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<Race> = races.filter { it.tenantId == tenantId && it.date == date }
}

/** 開催日・予定・補正の読取りの偽物（テスト中に差し替えて、予定の変更・中止を再現する）。 */
class InMemoryRaceDays(
    @Volatile var rows: List<RaceDay> = emptyList(),
) : RaceDayReader {
    override fun find(
        tenantId: TenantId,
        date: LocalDate,
        venue: Venue,
    ): RaceDay? = rows.firstOrNull { it.tenantId == tenantId && it.raceDate == date && it.venue == venue }

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceDay> = rows.filter { it.tenantId == tenantId && it.raceDate == date }
}

class InMemoryScheduleReader(
    @Volatile var rows: List<RaceSchedule> = emptyList(),
) : RaceScheduleReader {
    override fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): RaceSchedule? = rows.firstOrNull { it.tenantId == tenantId && it.raceId == raceId }

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceSchedule> = rows.filter { it.tenantId == tenantId && it.raceId.date == date }
}

class InMemoryOverrideReader(
    @Volatile var rows: List<CalendarOverride> = emptyList(),
) : CalendarOverrideReader {
    override fun find(
        tenantId: TenantId,
        id: Long,
    ): CalendarOverride? = rows.firstOrNull { it.tenantId == tenantId && it.id == id }

    override fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<CalendarOverride> = rows.filter { it.tenantId == tenantId && it.raceDate == date }
}

/** [SignalRepository] の偽物。通知の状態は UNSENT → CLAIMED → SENT | SEND_UNKNOWN の一方向だけ。 */
class InMemorySignals(
    private val clock: Clock,
) : SignalRepository {
    private val rows = LinkedHashMap<SignalId, Signal>()
    private val touched = HashMap<SignalId, Instant>()
    private var seq = 0L

    @Synchronized
    fun all(): List<Signal> = rows.values.toList()

    @Synchronized
    override fun find(
        tenantId: TenantId,
        id: SignalId,
    ): Signal? = rows[id]?.takeIf { it.tenantId == tenantId }

    @Synchronized
    override fun listByRace(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<Signal> = rows.values.filter { it.tenantId == tenantId && it.raceId == raceId }.sortedBy { it.observedAt }

    @Synchronized
    override fun listUnsent(
        tenantId: TenantId,
        limit: Int,
    ): List<Signal> = rows.values.filter { it.tenantId == tenantId && it.state == NotificationState.UNSENT }.take(limit)

    @Synchronized
    override fun listClaimedBefore(
        tenantId: TenantId,
        cutoff: Instant,
        limit: Int,
    ): List<Signal> =
        rows.values
            .filter { it.tenantId == tenantId && it.state == NotificationState.CLAIMED && (touched[it.id] ?: Instant.EPOCH).isBefore(cutoff) }
            .take(limit)

    @Synchronized
    override fun insertIfAbsent(signal: Signal): RecordedSignal {
        val existing =
            rows.values.firstOrNull {
                it.tenantId == signal.tenantId && it.raceId == signal.raceId && it.market == signal.market &&
                    it.umaban == signal.umaban && it.observedAt == signal.observedAt
            }
        if (existing != null) return RecordedSignal(InsertOutcome.ALREADY_EXISTS, existing)
        val id = SignalId.of(++seq)
        val saved = signal.copy(id = id, state = NotificationState.UNSENT)
        rows[id] = saved
        touched[id] = clock.now()
        return RecordedSignal(InsertOutcome.INSERTED, saved)
    }

    @Synchronized
    private fun move(
        tenantId: TenantId,
        id: SignalId,
        from: NotificationState,
        to: NotificationState,
    ): Boolean {
        val s = rows[id]?.takeIf { it.tenantId == tenantId } ?: return false
        if (s.state == to) return to != NotificationState.CLAIMED
        if (s.state != from) return false
        rows[id] = s.copy(state = to)
        touched[id] = clock.now()
        return true
    }

    override fun claim(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = move(tenantId, id, NotificationState.UNSENT, NotificationState.CLAIMED)

    override fun markSent(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = move(tenantId, id, NotificationState.CLAIMED, NotificationState.SENT)

    override fun markSendUnknown(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean = move(tenantId, id, NotificationState.CLAIMED, NotificationState.SEND_UNKNOWN)
}
