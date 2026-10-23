package jp.jrabot.domain.ingest

import jp.jrabot.domain.odds.ComboKeys
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartbeatAndBackfillTest {
    private fun hb(vararg e: Pair<String, Any?>) = HeartbeatValidator.validate(mapOf("agentVersion" to "1.0.0") + e.toMap())

    @Test
    fun `heartbeat の検証`() {
        val ok =
            assertIs<HeartbeatResult.Valid>(
                hb(
                    "clockSkewMs" to 12,
                    "queueDepth" to 3,
                    "diskFreeMb" to 100,
                    "oldestQueuedAt" to "2026-11-01T00:00:00Z",
                    "ackedTs" to mapOf("RACE" to "20261031200000"),
                    "lastJvResult" to mapOf("code" to -1, "ok" to true, "note" to null),
                    "lastError" to "bad\u0000error",
                    "mode" to mapOf("mode" to "NORMAL"),
                ),
            ).report
        assertEquals("""{"RACE":"20261031200000"}""", ok.ackedTsJson)
        assertEquals("""{"code":-1,"note":null,"ok":true}""", ok.lastJvResultJson)
        assertEquals("baderror", ok.lastError) // 制御文字を除く
        assertEquals("NORMAL", ok.localMode)
        assertEquals(400, assertIs<HeartbeatResult.Invalid>(HeartbeatValidator.validate("x")).rejection.status)
        assertEquals("UNKNOWN_FIELD", assertIs<HeartbeatResult.Invalid>(hb("tenantId" to 1)).rejection.code)
        assertEquals("INVALID_NUMBER", assertIs<HeartbeatResult.Invalid>(hb("queueDepth" to -1)).rejection.code)
        assertEquals("INVALID_MODE", assertIs<HeartbeatResult.Invalid>(hb("mode" to "WEIRD")).rejection.code)
        assertEquals("INVALID_ACKED_TS", assertIs<HeartbeatResult.Invalid>(hb("ackedTs" to mapOf("bad key!" to "x"))).rejection.code)
        assertEquals("INVALID_AGENT_VERSION", assertIs<HeartbeatResult.Invalid>(HeartbeatValidator.validate(mapOf("agentVersion" to "!"))).rejection.code)
        assertTrue(assertIs<HeartbeatResult.Valid>(hb("lastError" to "x".repeat(900))).report.lastError!!.length == 500)
    }

    @Test
    fun `組み合わせキーは昇順・2桁ゼロ埋めに正規化し、逆順でも同じ`() {
        assertEquals("02-05", ComboKeys.wide(5, 2))
        assertEquals("02-05", ComboKeys.wide(2, 5))
        assertEquals("01-04-09", ComboKeys.sanrenpuku(9, 1, 4))
        assertNull(ComboKeys.wide(3, 3), "同じ馬番")
        assertNull(ComboKeys.wide(0, 3))
        assertNull(ComboKeys.sanrenpuku(1, 2, 100))
        assertNull(ComboKeys.sanrenpuku(1, 2, 2))
    }

    private class FakeBackfills : BackfillStore {
        var requests = 0

        override fun request(
            tenantId: TenantId,
            request: NewBackfill,
            now: Instant,
        ): BackfillRequestResult {
            requests++
            return BackfillRequestResult.Created(1)
        }

        override fun find(
            tenantId: TenantId,
            id: Long,
        ): Backfill? = null

        override fun listActive(tenantId: TenantId) = emptyList<Backfill>()

        override fun attachFetch(
            tenantId: TenantId,
            id: Long,
            fetchId: String,
            now: Instant,
            takeoverBefore: Instant,
        ) = true

        override fun complete(
            tenantId: TenantId,
            id: Long,
            fetchId: String,
            now: Instant,
        ) = true

        override fun releaseFetch(
            tenantId: TenantId,
            id: Long,
            fetchId: String,
            reason: String,
            now: Instant,
        ) = true

        override fun cancel(
            tenantId: TenantId,
            id: Long,
            now: Instant,
        ) = true
    }

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-11-01T00:00:00Z")
        }

    private fun req(
        dataspec: String = "RACE",
        fromtime: String = "20261001000000",
        option: Int? = 2,
        from: LocalDate? = LocalDate.of(2026, 10, 1),
        to: LocalDate? = LocalDate.of(2026, 10, 31),
        by: String = "me@example.com",
    ) = NewBackfill(dataspec, option, fromtime, from, to, null, by)

    @Test
    fun `バックフィル要求の検証： 蓄積系だけ・形式・範囲`() {
        val store = FakeBackfills()
        val svc = BackfillService(store, clock)
        assertIs<BackfillRequestResult.Created>(svc.request(TenantId.of(1), req()))
        assertEquals(1, store.requests)
        // 速報オッズは過去時点を再構成できないので対象外
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(dataspec = "0B31")) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(dataspec = "NOPE")) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(fromtime = "2026")) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(from = LocalDate.of(2026, 11, 1), to = LocalDate.of(2026, 10, 1))) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(to = null)) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(from = LocalDate.of(2020, 1, 1), to = LocalDate.of(2026, 1, 1))) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(by = " ")) }
        assertFailsWith<InvalidBackfillRequest> { svc.request(TenantId.of(1), req(option = 99)) }
        assertEquals(1, store.requests, "不正な要求は保存しない")
    }
}
