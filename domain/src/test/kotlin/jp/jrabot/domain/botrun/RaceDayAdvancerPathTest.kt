package jp.jrabot.domain.botrun

import jp.jrabot.domain.calendar.RaceDayStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** 開催日の進め方は途中の状態を飛ばさない（更新が途中で失敗して取り残された日も、次の呼出しで追いつく）。 */
class RaceDayAdvancerPathTest {
    private fun path(
        from: RaceDayStatus,
        to: RaceDayStatus,
    ) = RaceDayAdvancerImpl.path(from, to)

    @Test
    fun `CONFIRMED から COMPLETED までは RUNNING と SETTLING を順にたどる`() {
        assertEquals(listOf(RaceDayStatus.RUNNING, RaceDayStatus.SETTLING, RaceDayStatus.COMPLETED), path(RaceDayStatus.CONFIRMED, RaceDayStatus.COMPLETED))
        assertEquals(listOf(RaceDayStatus.SETTLING, RaceDayStatus.INCOMPLETE), path(RaceDayStatus.RUNNING, RaceDayStatus.INCOMPLETE))
        assertEquals(listOf(RaceDayStatus.COMPLETED), path(RaceDayStatus.SETTLING, RaceDayStatus.COMPLETED))
        assertEquals(listOf(RaceDayStatus.COMPLETED), path(RaceDayStatus.INCOMPLETE, RaceDayStatus.COMPLETED))
    }

    @Test
    fun `到達できない遷移や既に同じ状態は何もしない`() {
        assertEquals(emptyList(), path(RaceDayStatus.COMPLETED, RaceDayStatus.INCOMPLETE))
        assertEquals(emptyList(), path(RaceDayStatus.CANCELLED, RaceDayStatus.COMPLETED))
        assertEquals(emptyList(), path(RaceDayStatus.PLANNED, RaceDayStatus.COMPLETED))
        assertEquals(emptyList(), path(RaceDayStatus.RUNNING, RaceDayStatus.RUNNING))
        assertEquals(emptyList(), path(RaceDayStatus.POSTPONED, RaceDayStatus.SETTLING))
    }
}
