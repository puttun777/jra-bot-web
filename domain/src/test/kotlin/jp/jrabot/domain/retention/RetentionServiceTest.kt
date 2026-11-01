package jp.jrabot.domain.retention

import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionServiceTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = ManualClock(now)
    private val t1 = TenantId.of(1)
    private val t2 = TenantId.of(2)

    /** 行の古さ（日）の一覧を持つ偽の保管庫。 */
    private class Fake : RetentionStore {
        val rows = HashMap<Pair<TenantId, RetentionTarget>, MutableList<Instant>>()
        val deletes = mutableListOf<Triple<TenantId, RetentionTarget, Int>>()

        fun add(
            t: TenantId,
            target: RetentionTarget,
            vararg at: Instant,
        ) {
            rows.getOrPut(t to target) { mutableListOf() }.addAll(at)
        }

        override fun count(
            tenantId: TenantId,
            target: RetentionTarget,
            before: Instant,
        ) = (rows[tenantId to target] ?: emptyList<Instant>()).count { it.isBefore(before) }.toLong()

        override fun delete(
            tenantId: TenantId,
            target: RetentionTarget,
            before: Instant,
            limit: Int,
        ): Int {
            val list = rows[tenantId to target] ?: return 0
            var removed = 0
            val it = list.iterator()
            while (it.hasNext() && removed < limit) {
                if (it.next().isBefore(before)) {
                    it.remove()
                    removed++
                }
            }
            val victims = List(removed) { 0 }
            deletes += Triple(tenantId, target, victims.size)
            return victims.size
        }
    }

    private fun days(n: Long) = now.minus(Duration.ofDays(n))

    @Test
    fun `既定はドライラン 対象の件数を数えるだけで 1 行も消さない`() {
        val f = Fake()
        f.add(t1, RetentionTarget.COMBO_ODDS, days(20), days(20), days(3))
        val r = RetentionService(clock, f, RetentionPolicy()).run(t1, execute = false)
        assertTrue(r.dryRun)
        assertEquals(2, r.lines.single { it.target == RetentionTarget.COMBO_ODDS }.eligible)
        assertEquals(0, r.lines.sumOf { it.deleted })
        assertTrue(f.deletes.isEmpty())
        assertEquals(3, f.rows.getValue(t1 to RetentionTarget.COMBO_ODDS).size)
    }

    @Test
    fun `execute のときだけ保持期間より古い行を消し 新しい行とほかのテナントは残す 再実行しても同じ`() {
        val f = Fake()
        f.add(t1, RetentionTarget.COMBO_ODDS, days(20), days(20), days(3))
        f.add(t2, RetentionTarget.COMBO_ODDS, days(20))
        f.add(t1, RetentionTarget.INGEST_RAW, days(40), days(10))
        val svc = RetentionService(clock, f, RetentionPolicy())
        val first = svc.run(t1, execute = true)
        assertFalse(first.dryRun)
        assertEquals(2, first.lines.single { it.target == RetentionTarget.COMBO_ODDS }.deleted)
        assertEquals(1, first.lines.single { it.target == RetentionTarget.INGEST_RAW }.deleted)
        assertEquals(1, f.rows.getValue(t1 to RetentionTarget.COMBO_ODDS).size)
        assertEquals(1, f.rows.getValue(t2 to RetentionTarget.COMBO_ODDS).size, "別のテナントは触らない")
        val second = svc.run(t1, execute = true)
        assertEquals(0, second.lines.sumOf { it.deleted })
        assertEquals(0, second.lines.sumOf { it.eligible })
    }

    @Test
    fun `停止が要求されたら区切りで止め 打ち切りを報告する`() {
        val f = Fake()
        f.add(t1, RetentionTarget.COMBO_ODDS, *Array(RetentionService.CHUNK * 2 + 1) { days(30) })
        var calls = 0
        val svc = RetentionService(clock, f, RetentionPolicy()) { ++calls > 1 }
        val r = svc.run(t1, execute = true).lines.single { it.target == RetentionTarget.COMBO_ODDS }
        assertEquals(RetentionService.CHUNK.toLong(), r.deleted)
        assertTrue(r.truncated)
    }

    @Test
    fun `保持日数は範囲外を拒否し 全対象の指定を要求する`() {
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(RetentionTarget.entries.associateWith { 1L }) }
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(mapOf(RetentionTarget.COMBO_ODDS to 14L)) }
    }
}
