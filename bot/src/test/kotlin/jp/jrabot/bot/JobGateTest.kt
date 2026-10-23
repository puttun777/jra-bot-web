package jp.jrabot.bot

import jp.jrabot.domain.ops.OpsMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JobGateTest {
    @Test
    fun `NORMAL だけが続行する`() {
        assertEquals(JobGate.Proceed, JobGate.decide(OpsMode.NORMAL))
        for (m in OpsMode.entries.filter { it != OpsMode.NORMAL }) assertIs<JobGate>(JobGate.decide(m)).also { check(it != JobGate.Proceed) { "$m" } }
    }

    @Test
    fun `STARTING STOPPING STOPPED は何もせず 0 で理由は MODE_ モード`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            val d = assertIs<JobGate.Skip>(JobGate.decide(m))
            assertEquals(JobResult(0, "MODE_${m.name}"), d.result)
        }
    }

    @Test
    fun `UNKNOWN は NORMAL とみなさず 75`() {
        val d = assertIs<JobGate.Unavailable>(JobGate.decide(OpsMode.UNKNOWN))
        assertEquals(JobResult(75, "MODE_UNKNOWN"), d.result)
    }

    @Test
    fun `終了コードの定義は CONTRACTS 9_1 のとおり（db-migration の 10 は使わない）`() {
        assertEquals(listOf(0, 1, 2, 75), listOf(JobExit.OK, JobExit.FAILURE, JobExit.USAGE, JobExit.TEMPFAIL))
    }
}

class CycleGateTest {
    private class Sequence(
        vararg modes: OpsMode,
    ) : jp.jrabot.domain.ops.ModeProvider {
        private val queue = ArrayDeque(modes.toList())

        override fun current(): OpsMode = queue.removeFirst()
    }

    @Test
    fun `NORMAL ならサイクルを始める`() {
        val g = CycleGate(Sequence(OpsMode.NORMAL, OpsMode.NORMAL))
        assertEquals(CycleDecision.StartCycle, g.onCycleStart())
        assertEquals(CycleDecision.StartCycle, g.onCycleStart())
    }

    @Test
    fun `確定した非 NORMAL は DRAINING で exit 0 STOPPED_BY_MODE`() {
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED)) {
            assertEquals(CycleDecision.Drain("STOPPED_BY_MODE", 0), CycleGate(Sequence(m)).onCycleStart(), "$m")
        }
    }

    @Test
    fun `UNKNOWN は 1 回目 2 回目は新しいサイクルを始めず、3 回連続で DRAINING exit 75`() {
        val g = CycleGate(Sequence(OpsMode.UNKNOWN, OpsMode.UNKNOWN, OpsMode.UNKNOWN))
        assertEquals(CycleDecision.SkipCycle(1), g.onCycleStart())
        assertEquals(CycleDecision.SkipCycle(2), g.onCycleStart())
        assertEquals(CycleDecision.Drain("MODE_UNKNOWN", 75), g.onCycleStart())
    }

    @Test
    fun `UNKNOWN の連続は NORMAL を挟むとリセットされる`() {
        val g = CycleGate(Sequence(OpsMode.UNKNOWN, OpsMode.UNKNOWN, OpsMode.NORMAL, OpsMode.UNKNOWN, OpsMode.UNKNOWN))
        assertEquals(CycleDecision.SkipCycle(1), g.onCycleStart())
        assertEquals(CycleDecision.SkipCycle(2), g.onCycleStart())
        assertEquals(CycleDecision.StartCycle, g.onCycleStart())
        assertEquals(CycleDecision.SkipCycle(1), g.onCycleStart())
        assertEquals(CycleDecision.SkipCycle(2), g.onCycleStart())
    }

    @Test
    fun `DRAINING に入った後は NORMAL に戻っても新しいサイクルを始めない`() {
        val g = CycleGate(Sequence(OpsMode.STOPPING, OpsMode.NORMAL))
        assertEquals(CycleDecision.Drain("STOPPED_BY_MODE", 0), g.onCycleStart())
        assertEquals(CycleDecision.Drain("STOPPED_BY_MODE", 0), g.onCycleStart()) // ModeProvider はもう読まない
    }

    @Test
    fun `回数は設定できる（1 以上）`() {
        assertEquals(CycleDecision.Drain("MODE_UNKNOWN", 75), CycleGate(Sequence(OpsMode.UNKNOWN), maxConsecutiveUnknown = 1).onCycleStart())
        kotlin.test.assertFailsWith<IllegalArgumentException> { CycleGate(Sequence(), maxConsecutiveUnknown = 0) }
    }
}
