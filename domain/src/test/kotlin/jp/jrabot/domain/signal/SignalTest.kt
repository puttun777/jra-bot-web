package jp.jrabot.domain.signal

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.value.Probability
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignalTest {
    private fun signal(
        ev: Double = 0.1,
        stake: Double = 1.0,
        state: NotificationState = NotificationState.UNSENT,
    ) = Signal(
        tenantId = TenantId.of(1),
        id = null,
        raceId = RaceId.parse("202401010611"),
        market = Market.WIN,
        umaban = HorseNumber.of(3),
        observedAt = Instant.parse("2026-11-01T01:00:00Z"),
        odds = Odds.of(5.0),
        prob = Probability.of(0.22),
        ev = ev,
        isPaper = true,
        stake = stake,
        probSource = "market",
        state = state,
    )

    @Test
    fun `作成直後は未送信で ID を持たない`() {
        assertEquals(NotificationState.UNSENT, signal().state)
        assertEquals(null, signal().id)
    }

    @Test
    fun `EV は負でもよいが有限値 stake は 0 以上の有限値`() {
        assertEquals(-1.0, signal(ev = -1.0).ev)
        assertEquals(0.0, signal(stake = 0.0).stake)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> { signal(ev = bad) }
        for (bad in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY)) assertFailsWith<IllegalArgumentException> { signal(stake = bad) }
    }

    @Test
    fun `SignalId は 1 以上`() {
        assertEquals(1L, SignalId.of(1).value)
        assertFailsWith<IllegalArgumentException> { SignalId.of(0) }
    }

    @Test
    fun `通知の状態は契約の 4 つ`() {
        assertEquals(listOf("UNSENT", "CLAIMED", "SENT", "SEND_UNKNOWN"), NotificationState.entries.map { it.name })
    }
}
