package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.race.Venue
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LeaseAndDayModelTest {
    private val now = Instant.parse("2026-10-24T00:00:00Z")

    @Test
    fun `リースの設定は heartbeat が期限より短くなければならない`() {
        LeaseSettings(Duration.ofMinutes(5), Duration.ofSeconds(60))
        assertFailsWith<IllegalArgumentException> { LeaseSettings(Duration.ofMinutes(5), Duration.ofMinutes(5)) }
        assertFailsWith<IllegalArgumentException> { LeaseSettings(Duration.ofMinutes(5), Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { LeaseSettings(Duration.ZERO, Duration.ofSeconds(1)) }
    }

    @Test
    fun `取得依頼の owner と期限を検証する`() {
        val id = RunId.of(UUID.randomUUID())
        LeaseRequest(Cal.T1, id, Cal.DATE, BotRunTrigger.SCHEDULER, "exec-1", now, Duration.ofMinutes(5))
        assertFailsWith<IllegalArgumentException> { LeaseRequest(Cal.T1, id, Cal.DATE, BotRunTrigger.SCHEDULER, "", now, Duration.ofMinutes(5)) }
        assertFailsWith<IllegalArgumentException> { LeaseRequest(Cal.T1, id, Cal.DATE, BotRunTrigger.SCHEDULER, "x".repeat(101), now, Duration.ofMinutes(5)) }
        assertFailsWith<IllegalArgumentException> { LeaseRequest(Cal.T1, id, Cal.DATE, BotRunTrigger.SCHEDULER, "o", now, Duration.ZERO) }
    }

    @Test
    fun `BotRun の checkpoint とリース所有者の上限`() {
        val ok = Cal.run(expiresAt = now)
        ok.copy(checkpoint = "x".repeat(65_535))
        assertFailsWith<IllegalArgumentException> { ok.copy(checkpoint = "x".repeat(65_536)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(leaseOwner = "") }
        assertFailsWith<IllegalArgumentException> { ok.copy(state = BotRunState.RUNNING, finishedAt = now.plusSeconds(1)) }
    }

    @Test
    fun `開催日の検証（回・日目・振替先）`() {
        val base = Cal.day()
        assertFailsWith<IllegalArgumentException> { base.copy(kai = 0) }
        assertFailsWith<IllegalArgumentException> { base.copy(nichime = 100) }
        assertFailsWith<IllegalArgumentException> { base.copy(replacedByDate = Cal.DATE.plusDays(1)) } // POSTPONED 以外
        assertFailsWith<IllegalArgumentException> { base.copy(status = RaceDayStatus.POSTPONED, replacedByDate = Cal.DATE) }
        base.copy(status = RaceDayStatus.POSTPONED, replacedByDate = Cal.DATE.plusDays(1), venue = Venue.NAKAYAMA)
    }
}
