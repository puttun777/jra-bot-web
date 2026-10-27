package jp.jrabot.domain.notify

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.RecordedSignal
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.value.Probability
import java.time.Instant

/** 合成データ（実在の馬・レースではない）。 */
internal object NoticeFixtures {
    val race = RaceLabel("合成", 11, "合成記念")

    fun ticket(
        kumi: String,
        ev: Double,
        oddsText: String = "3.2〜5.1",
        prob: Double = 0.0345,
        stake: Double? = 1.0,
    ) = TicketView(kumi, oddsText, prob, ev, stake)

    fun strategy(
        type: String,
        bet: Double,
        profit: Double,
        tickets: List<TicketView> = emptyList(),
        kelly: KellyView? = KellyView(0.1, 0.05, bet, false, null, 0.25),
        note: String? = null,
        prob: Double = 0.25,
    ) = StrategyView(type, bet, profit, 4.5, prob, 0.12, note, tickets, kelly)

    fun bundle(
        vararg s: StrategyView,
        best: BestView? = BestView(s.first().type, 0.12, s.first().suggestedBet),
    ) = BundleView(s.toList(), best, s.sumOf { it.suggestedBet }, s.sumOf { it.expectedProfit })

    fun win(
        umaban: Int = 3,
        odds: Double = 4.5,
        prob: Double = 0.3,
        ev: Double = 0.35,
        bundle: BundleView? = bundle(strategy("単勝", 2.0, 0.7)),
        betType: String = "単勝",
    ) = SignalView(
        betType = betType,
        umaban = umaban,
        odds = odds,
        prob = prob,
        ev = ev,
        pMkt = 1.0 / odds,
        ratio = prob * odds,
        expectedReturn = odds * prob,
        probSource = "ml:alpha",
        coverage = CoverageView(0.8, 0.456, true, true, 5),
        betAmount = 2.0,
        reason = "馬名=合成一号",
        bundle = bundle,
    )
}

/** テスト用の固定時計。 */
internal class FixedClock(
    var instant: Instant = Instant.parse("2026-01-04T05:00:00Z"),
) : Clock {
    override fun now(): Instant = instant
}

/** 状態遷移（UNSENT → CLAIMED → SENT | SEND_UNKNOWN）を本物と同じ規則で持つ、テスト用のメモリ実装。claim は同時に呼んでも勝者は 1 つ。 */
internal class InMemorySignalRepository : SignalRepository {
    private val rows = LinkedHashMap<Pair<Long, Long>, Signal>()
    private val claimedAt = HashMap<Pair<Long, Long>, Instant>()
    private var nextId = 1L
    var clock: Clock = FixedClock()
    var failMarkSentFor: Set<Long> = emptySet()
    val calls = mutableListOf<String>()

    @Synchronized
    fun add(
        tenant: TenantId,
        raceId: String = "202601040611",
        market: Market = Market.WIN,
        umaban: Int = 3,
        state: NotificationState = NotificationState.UNSENT,
    ): SignalId {
        val id = nextId++
        rows[tenant.value to id] =
            Signal(
                tenant,
                SignalId.of(id),
                RaceId.parse(raceId),
                market,
                HorseNumber.of(umaban),
                Instant.parse("2026-01-04T05:00:00Z"),
                Odds.of(4.5),
                Probability.of(0.3),
                0.35,
                false,
                2.0,
                "ml:alpha",
                state,
            )
        return SignalId.of(id)
    }

    @Synchronized
    fun stateOf(
        tenant: TenantId,
        id: SignalId,
    ): NotificationState? = rows[tenant.value to id.value]?.state

    @Synchronized
    override fun find(
        tenantId: TenantId,
        id: SignalId,
    ): Signal? = rows[tenantId.value to id.value]

    @Synchronized
    override fun listByRace(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<Signal> = rows.filterKeys { it.first == tenantId.value }.values.filter { it.raceId == raceId }

    @Synchronized
    override fun listUnsent(
        tenantId: TenantId,
        limit: Int,
    ): List<Signal> =
        rows
            .filterKeys { it.first == tenantId.value }
            .values
            .filter { it.state == NotificationState.UNSENT }
            .take(limit)

    @Synchronized
    override fun listClaimedBefore(
        tenantId: TenantId,
        cutoff: Instant,
        limit: Int,
    ): List<Signal> =
        rows
            .filter { (k, v) -> k.first == tenantId.value && v.state == NotificationState.CLAIMED && claimedAt.getValue(k).isBefore(cutoff) }
            .values
            .take(limit)

    @Synchronized
    override fun insertIfAbsent(signal: Signal): RecordedSignal = RecordedSignal(InsertOutcome.INSERTED, signal)

    @Synchronized
    override fun claim(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean {
        calls += "claim:${id.value}"
        val key = tenantId.value to id.value
        val row = rows[key] ?: return false
        if (row.state != NotificationState.UNSENT) return false
        rows[key] = row.copy(state = NotificationState.CLAIMED)
        claimedAt[key] = clock.now()
        return true
    }

    @Synchronized
    override fun markSent(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean {
        calls += "markSent:${id.value}"
        if (id.value in failMarkSentFor) throw IllegalStateException("DB 障害（テスト）")
        return transition(tenantId, id, NotificationState.SENT)
    }

    @Synchronized
    override fun markSendUnknown(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean {
        calls += "markSendUnknown:${id.value}"
        return transition(tenantId, id, NotificationState.SEND_UNKNOWN)
    }

    private fun transition(
        tenantId: TenantId,
        id: SignalId,
        target: NotificationState,
    ): Boolean {
        val key = tenantId.value to id.value
        val row = rows[key] ?: return false
        if (row.state == target) return true
        if (row.state != NotificationState.CLAIMED) return false
        rows[key] = row.copy(state = target)
        return true
    }
}
