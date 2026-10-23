package jp.jrabot.domain.calendar

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunStateMachine
import jp.jrabot.domain.race.RaceScheduleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionsTest {
    private val rs = RaceScheduleStatus.entries

    private fun edges(pairs: List<Pair<RaceScheduleStatus, RaceScheduleStatus>>) = pairs.toSet()

    @Test
    fun `レース状態の許可する辺は設計の図どおりで、それ以外は拒否する`() {
        val expected =
            edges(
                listOf(
                    RaceScheduleStatus.ANNOUNCED to RaceScheduleStatus.CARD,
                    RaceScheduleStatus.ANNOUNCED to RaceScheduleStatus.CANCELLED,
                    RaceScheduleStatus.ANNOUNCED to RaceScheduleStatus.DELETED,
                    RaceScheduleStatus.CARD to RaceScheduleStatus.MONITORING,
                    RaceScheduleStatus.CARD to RaceScheduleStatus.CANCELLED,
                    RaceScheduleStatus.CARD to RaceScheduleStatus.DELETED,
                    RaceScheduleStatus.MONITORING to RaceScheduleStatus.OFF,
                    RaceScheduleStatus.MONITORING to RaceScheduleStatus.CANCELLED,
                    RaceScheduleStatus.OFF to RaceScheduleStatus.RESULT_PENDING,
                    RaceScheduleStatus.RESULT_PENDING to RaceScheduleStatus.RESULT_PARTIAL,
                    RaceScheduleStatus.RESULT_PENDING to RaceScheduleStatus.SETTLED,
                    RaceScheduleStatus.RESULT_PARTIAL to RaceScheduleStatus.SETTLED,
                    RaceScheduleStatus.SETTLED to RaceScheduleStatus.FINALIZED,
                ),
            )
        val actual = rs.flatMap { a -> rs.filter { b -> RaceScheduleTransitions.canTransition(a, b) }.map { a to it } }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `逆方向・同じ状態・終端からの遷移は拒否する`() {
        assertFalse(RaceScheduleTransitions.canTransition(RaceScheduleStatus.CARD, RaceScheduleStatus.ANNOUNCED))
        assertFalse(RaceScheduleTransitions.canTransition(RaceScheduleStatus.OFF, RaceScheduleStatus.MONITORING))
        assertFalse(RaceScheduleTransitions.canTransition(RaceScheduleStatus.CARD, RaceScheduleStatus.CARD))
        for (terminal in listOf(RaceScheduleStatus.CANCELLED, RaceScheduleStatus.DELETED, RaceScheduleStatus.FINALIZED)) {
            assertTrue(RaceScheduleTransitions.isTerminal(terminal))
            assertTrue(rs.none { RaceScheduleTransitions.canTransition(terminal, it) }, "$terminal")
        }
    }

    @Test
    fun `発走後の中止は遷移表に無い`() {
        assertFalse(RaceScheduleTransitions.canTransition(RaceScheduleStatus.OFF, RaceScheduleStatus.CANCELLED))
        assertFalse(RaceScheduleTransitions.canTransition(RaceScheduleStatus.MONITORING, RaceScheduleStatus.DELETED))
    }

    @Test
    fun `時刻変更は発走前の状態だけ、有効・出馬表の判定`() {
        val accept = rs.filter { RaceScheduleTransitions.acceptsPostTimeChange(it) }.toSet()
        assertEquals(setOf(RaceScheduleStatus.ANNOUNCED, RaceScheduleStatus.CARD, RaceScheduleStatus.MONITORING), accept)
        assertFalse(RaceScheduleTransitions.isActive(RaceScheduleStatus.CANCELLED))
        assertFalse(RaceScheduleTransitions.isActive(RaceScheduleStatus.DELETED))
        assertFalse(RaceScheduleTransitions.hasCard(RaceScheduleStatus.ANNOUNCED))
        assertTrue(RaceScheduleTransitions.hasCard(RaceScheduleStatus.CARD))
        assertTrue(RaceScheduleTransitions.hasCard(RaceScheduleStatus.SETTLED))
        assertFalse(RaceScheduleTransitions.hasCard(RaceScheduleStatus.CANCELLED))
    }

    @Test
    fun `開催日の許可する辺は設計の図どおり`() {
        val d = RaceDayStatus.entries
        val actual = d.flatMap { a -> d.filter { b -> RaceDayTransitions.canTransition(a, b) }.map { a to it } }.toSet()
        val expected =
            setOf(
                RaceDayStatus.UNPLANNED to RaceDayStatus.PLANNED,
                RaceDayStatus.UNPLANNED to RaceDayStatus.CONFIRMED,
                RaceDayStatus.PLANNED to RaceDayStatus.CONFIRMED,
                RaceDayStatus.PLANNED to RaceDayStatus.CANCELLED,
                RaceDayStatus.PLANNED to RaceDayStatus.POSTPONED,
                RaceDayStatus.PLANNED to RaceDayStatus.UNCONFIRMED,
                RaceDayStatus.UNCONFIRMED to RaceDayStatus.CONFIRMED,
                RaceDayStatus.UNCONFIRMED to RaceDayStatus.CANCELLED,
                RaceDayStatus.CONFIRMED to RaceDayStatus.CANCELLED,
                RaceDayStatus.CONFIRMED to RaceDayStatus.POSTPONED,
                RaceDayStatus.CONFIRMED to RaceDayStatus.RUNNING,
                RaceDayStatus.RUNNING to RaceDayStatus.SETTLING,
                RaceDayStatus.SETTLING to RaceDayStatus.COMPLETED,
                RaceDayStatus.SETTLING to RaceDayStatus.INCOMPLETE,
                RaceDayStatus.INCOMPLETE to RaceDayStatus.COMPLETED,
            )
        assertEquals(expected, actual)
        for (t in listOf(RaceDayStatus.COMPLETED, RaceDayStatus.CANCELLED, RaceDayStatus.POSTPONED)) assertTrue(RaceDayTransitions.isTerminal(t))
    }

    @Test
    fun `bot-run の許可する辺と終端`() {
        val s = BotRunState.entries
        val actual = s.flatMap { a -> s.filter { b -> BotRunStateMachine.canTransition(a, b) }.map { a to it } }.toSet()
        val expected =
            setOf(
                BotRunState.STARTING to BotRunState.RUNNING,
                BotRunState.STARTING to BotRunState.ABORTED,
                BotRunState.STARTING to BotRunState.FAILED,
                BotRunState.STARTING to BotRunState.INTERRUPTED,
                BotRunState.RUNNING to BotRunState.DRAINING,
                BotRunState.RUNNING to BotRunState.FAILED,
                BotRunState.RUNNING to BotRunState.STOPPED_BY_MODE,
                BotRunState.RUNNING to BotRunState.INTERRUPTED,
                BotRunState.RUNNING to BotRunState.ABORTED,
                BotRunState.DRAINING to BotRunState.SUCCEEDED,
                BotRunState.DRAINING to BotRunState.FAILED,
                BotRunState.DRAINING to BotRunState.STOPPED_BY_MODE,
                BotRunState.DRAINING to BotRunState.INTERRUPTED,
                BotRunState.DRAINING to BotRunState.ABORTED,
            )
        assertEquals(expected, actual)
        for (t in s.filter { !it.holdsActiveSlot }) {
            assertTrue(BotRunStateMachine.isTerminal(t))
            assertTrue(s.none { BotRunStateMachine.canTransition(t, it) }, "$t")
        }
        assertFalse(BotRunStateMachine.canTransition(BotRunState.RUNNING, BotRunState.STARTING))
        assertFalse(BotRunStateMachine.canTransition(BotRunState.DRAINING, BotRunState.RUNNING))
        assertFalse(BotRunStateMachine.canTransition(BotRunState.STARTING, BotRunState.SUCCEEDED))
    }
}
