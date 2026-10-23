package jp.jrabot.persistence.integration

import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.SignalId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
class SignalRepositoryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 10)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val raceId = Fixtures.raceId()

    @BeforeEach
    fun reset() {
        db.resetData()
        h.races.upsert(Fixtures.race(T1))
        h.races.upsert(Fixtures.race(T2))
    }

    private fun record(
        umaban: Int = 3,
        tenant: jp.jrabot.domain.tenant.TenantId = T1,
    ) = h.signals.insertIfAbsent(Fixtures.signal(tenant, umaban = umaban))

    private fun state(id: SignalId): String? = db.scalar("SELECT notify_state FROM signals WHERE id = ${id.value}")

    @Test
    fun `挿入すると ID 付きの UNSENT として読み戻せ、自然キーの再実行は既存の行を返す`() {
        val first = record()
        assertEquals(InsertOutcome.INSERTED, first.outcome)
        val id = checkNotNull(first.signal.id)
        assertEquals(Fixtures.signal().copy(id = id), first.signal)
        assertEquals(first.signal, h.signals.find(T1, id))

        val again = record()
        assertEquals(InsertOutcome.ALREADY_EXISTS, again.outcome)
        assertEquals(id, again.signal.id)
        assertEquals(1, db.count("SELECT COUNT(*) FROM signals"))
    }

    @Test
    fun `再送で通知の状態は戻らない（claim 済みの行の insertIfAbsent は状態を変えない）`() {
        val id = checkNotNull(record().signal.id)
        assertTrue(h.signals.claim(T1, id))
        val again = record()
        assertEquals(InsertOutcome.ALREADY_EXISTS, again.outcome)
        assertEquals(NotificationState.CLAIMED, again.signal.state)
        assertEquals("CLAIMED", state(id))
    }

    @Test
    fun `馬番・市場・観測時刻が違えば別の Signal`() {
        record(3)
        record(4)
        h.signals.insertIfAbsent(Fixtures.signal(umaban = 3, market = jp.jrabot.domain.odds.Market.WIDE))
        h.signals.insertIfAbsent(Fixtures.signal(umaban = 3, observedAt = Instant.parse("2026-10-24T00:35:00Z")))
        assertEquals(4, h.signals.listByRace(T1, raceId).size)
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals"))
    }

    @Test
    fun `claim は UNSENT から CLAIMED への 1 回だけ勝てる`() {
        val id = checkNotNull(record().signal.id)
        assertTrue(h.signals.claim(T1, id))
        assertFalse(h.signals.claim(T1, id))
        assertEquals("CLAIMED", state(id))
        assertNotNull(db.scalar("SELECT notify_updated_at FROM signals WHERE id = ${id.value}"))
    }

    @Test
    fun `claim → SENT と claim → SEND_UNKNOWN が通り、同じ操作の再実行は冪等に true`() {
        val a = checkNotNull(record(1).signal.id)
        val b = checkNotNull(record(2).signal.id)
        assertTrue(h.signals.claim(T1, a))
        assertTrue(h.signals.markSent(T1, a))
        assertTrue(h.signals.markSent(T1, a))
        assertEquals("SENT", state(a))

        assertTrue(h.signals.claim(T1, b))
        assertTrue(h.signals.markSendUnknown(T1, b))
        assertTrue(h.signals.markSendUnknown(T1, b))
        assertEquals("SEND_UNKNOWN", state(b))
    }

    @Test
    fun `不正な遷移は拒否され、状態は変わらない`() {
        val id = checkNotNull(record().signal.id)
        // UNSENT からの終端遷移は不可
        assertFalse(h.signals.markSent(T1, id))
        assertFalse(h.signals.markSendUnknown(T1, id))
        assertEquals("UNSENT", state(id))

        assertTrue(h.signals.claim(T1, id))
        assertTrue(h.signals.markSent(T1, id))
        // 終端からの逆戻り・別の終端・再 claim は不可
        assertFalse(h.signals.claim(T1, id))
        assertFalse(h.signals.markSendUnknown(T1, id))
        assertEquals("SENT", state(id))

        val other = checkNotNull(record(9).signal.id)
        h.signals.claim(T1, other)
        h.signals.markSendUnknown(T1, other)
        assertFalse(h.signals.markSent(T1, other))
        assertFalse(h.signals.claim(T1, other))
        assertEquals("SEND_UNKNOWN", state(other))

        // 存在しない ID
        val missing = SignalId.of(999_999)
        assertFalse(h.signals.claim(T1, missing))
        assertFalse(h.signals.markSent(T1, missing))
        assertFalse(h.signals.markSendUnknown(T1, missing))
    }

    @Test
    fun `他テナントの Signal は読めず、claim も状態遷移もできない`() {
        val id = checkNotNull(record(tenant = T1).signal.id)
        assertNull(h.signals.find(T2, id))
        assertEquals(emptyList(), h.signals.listByRace(T2, raceId))
        assertEquals(emptyList(), h.signals.listUnsent(T2, 10))
        assertFalse(h.signals.claim(T2, id))
        assertFalse(h.signals.markSent(T2, id))
        assertFalse(h.signals.markSendUnknown(T2, id))
        assertEquals("UNSENT", state(id))
        assertEquals(listOf(id), h.signals.listUnsent(T1, 10).map { it.id })
    }

    @Test
    fun `他テナントのレースを親にした Signal は複合 FK で拒否される`() {
        val onlyT1 = Fixtures.raceId(no = 3)
        h.races.upsert(Fixtures.race(T1, id = onlyT1))
        assertSqlError(MySqlError.FK_CHILD) { h.signals.insertIfAbsent(Fixtures.signal(T2, raceId = onlyT1)) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM signals"))
    }

    @Test
    fun `listUnsent は未通知だけを古い順に、件数を制限して返す`() {
        val ids = (1..5).map { checkNotNull(record(it).signal.id) }
        h.signals.claim(T1, ids[0])
        assertEquals(ids.drop(1).take(2), h.signals.listUnsent(T1, 2).map { it.id })
        assertFailsWith<IllegalArgumentException> { h.signals.listUnsent(T1, 0) }
        assertFailsWith<IllegalArgumentException> { h.signals.listUnsent(T1, 1001) }
    }

    @Test
    fun `新規の Signal は UNSENT 以外では保存できない`() {
        assertFailsWith<IllegalArgumentException> { h.signals.insertIfAbsent(Fixtures.signal().copy(state = NotificationState.SENT)) }
    }

    @Test
    fun `実際に競合する複数の接続で claim しても、勝てるのは 1 つだけ`() {
        repeat(10) { round ->
            val id = checkNotNull(record(umaban = round + 1).signal.id)
            val wins = runConcurrently(8) { h.signals.claim(T1, id) }.map { it.getOrThrow() }
            assertEquals(1, wins.count { it }, "round=$round $wins")
            assertEquals("CLAIMED", state(id))
        }
    }

    @Test
    fun `claim 後の SENT と SEND_UNKNOWN が同時に来ても、どちらか 1 つだけが成立する`() {
        repeat(10) { round ->
            val id = checkNotNull(record(umaban = round + 1).signal.id)
            h.signals.claim(T1, id)
            val results = runConcurrently(2) { i -> if (i == 0) h.signals.markSent(T1, id) else h.signals.markSendUnknown(T1, id) }.map { it.getOrThrow() }
            assertEquals(1, results.count { it }, "round=$round $results")
            assertTrue(state(id) in setOf("SENT", "SEND_UNKNOWN"))
        }
    }

    @Test
    fun `同じ自然キーを同時に insertIfAbsent しても、挿入は 1 回で全員が同じ ID を得る`() {
        repeat(10) { round ->
            val results = runConcurrently(6) { h.signals.insertIfAbsent(Fixtures.signal(umaban = round + 1)) }.map { it.getOrThrow() }
            assertEquals(1, results.count { it.outcome == InsertOutcome.INSERTED }, "round=$round")
            assertEquals(1, results.map { it.signal.id }.toSet().size, "round=$round")
        }
        assertEquals(10, db.count("SELECT COUNT(*) FROM signals"))
    }
}
