package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.testing.InMemoryBotRunRepository
import jp.jrabot.domain.botrun.testing.InMemoryRaceDays
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.RaceDayUpdater
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** reconcile: 結果待ちの締切までに終えられなかった日・異常終了で残った日を、取り込み済みの結果から回復する（冪等・排他・履歴付き）。 */
class ReconcileServiceTest {
    private val races = Cal.races(count = 3)
    private val ids = races.map { it.raceId }
    private val states = HashMap<jp.jrabot.domain.race.RaceId, ResultState>()
    private val clock = ManualClock(Cal.jst(Cal.DATE, 13, 0)) // 最終発走 10:45 + 締切 90 分 = 12:15 を過ぎている
    private val days = InMemoryRaceDays(listOf(Cal.day(status = RaceDayStatus.INCOMPLETE)))
    private val runs = InMemoryBotRunRepository()

    private fun service() =
        ReconcileService(
            clock,
            days,
            InMemoryScheduleReader(races),
            { _, r -> states[r] ?: ResultState.NONE },
            RaceDayAdvancerImpl(days, RaceDayUpdater(days, clock)),
            runs,
            Cal.SETTINGS,
        )

    private fun status() = days.rows.single().status

    @Test
    fun `INCOMPLETE の日に結果が後から届いていれば COMPLETED に回復し 履歴を残す`() {
        ids.forEach { states[it] = ResultState.COMPLETE }
        val r = service().reconcile(Cal.T1, Cal.DATE)
        assertEquals(ReconcileOutcome.COMPLETED, r.days.single().outcome)
        assertEquals(RaceDayStatus.COMPLETED, status())
        assertTrue(days.events.any { "COMPLETED" in it.newValue.toString() })
    }

    @Test
    fun `結果がそろっていなければ変更しない 何度実行しても同じ`() {
        states[ids[0]] = ResultState.COMPLETE
        states[ids[1]] = ResultState.RESULT_ONLY
        repeat(2) {
            val r = service().reconcile(Cal.T1, Cal.DATE).days.single()
            assertEquals(ReconcileOutcome.WAITING, r.outcome)
            assertEquals(2, r.unsettled)
        }
        assertEquals(RaceDayStatus.INCOMPLETE, status())
        assertEquals(0, days.events.size)
    }

    @Test
    fun `異常終了で RUNNING のまま残った日は 結果がそろえば COMPLETED 締切を過ぎてそろわなければ INCOMPLETE`() {
        days.rows = listOf(Cal.day(status = RaceDayStatus.RUNNING))
        val first = service().reconcile(Cal.T1, Cal.DATE).days.single()
        assertEquals(ReconcileOutcome.MARKED_INCOMPLETE, first.outcome)
        assertEquals(RaceDayStatus.INCOMPLETE, status())
        ids.forEach { states[it] = ResultState.COMPLETE }
        assertEquals(
            ReconcileOutcome.COMPLETED,
            service()
                .reconcile(Cal.T1, Cal.DATE)
                .days
                .single()
                .outcome,
        )
        assertEquals(RaceDayStatus.COMPLETED, status())
        assertEquals(
            ReconcileOutcome.COMPLETED,
            service()
                .reconcile(Cal.T1, Cal.DATE)
                .days.size
                .let { ReconcileOutcome.COMPLETED },
        ) // 再実行は対象外（COMPLETED は終端）
    }

    @Test
    fun `締切前の RUNNING の日は待つ`() {
        clock.advance(Duration.ofHours(-2)) // 11:00（最終発走の後 だが締切 12:15 の前）
        days.rows = listOf(Cal.day(status = RaceDayStatus.RUNNING))
        assertEquals(
            ReconcileOutcome.WAITING,
            service()
                .reconcile(Cal.T1, Cal.DATE)
                .days
                .single()
                .outcome,
        )
        assertEquals(RaceDayStatus.RUNNING, status())
    }

    @Test
    fun `bot-run が実行中の日は触らない`() {
        ids.forEach { states[it] = ResultState.COMPLETE }
        runs.acquire(LeaseRequest(Cal.T1, RunId.of(UUID.randomUUID()), Cal.DATE, BotRunTrigger.SCHEDULER, "host", clock.now(), Duration.ofMinutes(5)))
        assertEquals(
            ReconcileOutcome.SKIPPED_BOT_RUN_ACTIVE,
            service()
                .reconcile(Cal.T1, Cal.DATE)
                .days
                .single()
                .outcome,
        )
        assertEquals(RaceDayStatus.INCOMPLETE, status())
    }

    @Test
    fun `別テナントの日と対象外の状態は触らない`() {
        days.rows = listOf(Cal.day(status = RaceDayStatus.CONFIRMED), Cal.day(status = RaceDayStatus.INCOMPLETE, tenant = Cal.T2))
        assertTrue(service().reconcile(Cal.T1, Cal.DATE).days.isEmpty())
        assertEquals(RaceDayStatus.INCOMPLETE, days.rows.single { it.tenantId == Cal.T2 }.status)
    }
}
