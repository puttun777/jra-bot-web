package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FetchPolicyTest {
    private val t0 = Instant.parse("2026-11-01T00:00:00Z")
    private val agent = AgentId.of("ag_test")

    private fun fetch(
        dataspec: String = "YSCH",
        from: LocalDate? = LocalDate.of(2026, 11, 1),
        to: LocalDate? = LocalDate.of(2027, 12, 31),
        origin: FetchOrigin = FetchOrigin.LIVE,
    ) = FetchRecord(
        TenantId.of(1),
        "11111111-2222-4333-8444-555555555555",
        agent,
        dataspec,
        2,
        "20261001000000",
        origin,
        null,
        from,
        to,
        null,
        FetchStatus.IN_PROGRESS,
        null,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        t0,
        t0,
        null,
    )

    private fun env(
        seq: Int,
        eof: Boolean,
        lastTs: String? = "20261031200000",
    ): IngestEnvelope {
        val bytes = "x".toByteArray()
        return IngestEnvelope(
            agent,
            "01890a5d-ac96-774b-bcce-b302099a8057",
            BatchClass.STORED,
            t0,
            "YSCH",
            2,
            "20261001000000",
            lastTs,
            eof,
            FetchInfo("11111111-2222-4333-8444-555555555555", seq, null, null, FetchOrigin.LIVE, null),
            listOf(IngestRecordIn("YS", bytes, Hashing.sha256Hex(bytes))),
        )
    }

    private fun stats(
        unparsed: Int = 0,
        failed: Int = 0,
        max: LocalDate? = LocalDate.of(2026, 12, 27),
    ) = BatchStats(1, unparsed, failed, max)

    @Test
    fun `途中のバッチ（EOF なし）では成功にならない`() {
        val f = FetchPolicy.advance(fetch(), env(0, eof = false), stats(), t0.plusSeconds(1))
        assertEquals(FetchStatus.IN_PROGRESS, f.status)
        assertEquals(1, f.nextSeq)
        assertNull(f.completedAt)
        assertNull(f.coveredTo)
    }

    @Test
    fun `EOF まで連続して読めて全レコードを解析できたときだけ成功し、事実の範囲は見えた最後の開催日まで`() {
        val mid = FetchPolicy.advance(fetch(), env(0, false), stats(max = LocalDate.of(2026, 11, 29)), t0.plusSeconds(1))
        val done = FetchPolicy.advance(mid, env(1, true), stats(max = LocalDate.of(2026, 12, 27)), t0.plusSeconds(2))
        assertEquals(FetchStatus.SUCCEEDED, done.status)
        assertEquals(LocalDate.of(2026, 11, 1), done.coveredFrom)
        assertEquals(LocalDate.of(2026, 12, 27), done.coveredTo) // 申告した範囲（2027-12-31）まで広げない
        assertEquals(t0.plusSeconds(2), done.completedAt)
        assertEquals(2, done.recordCount)
    }

    @Test
    fun `申告した範囲より後の開催日は範囲に含めない`() {
        val f = FetchPolicy.advance(fetch(to = LocalDate.of(2026, 11, 30)), env(0, true), stats(max = LocalDate.of(2026, 12, 27)), t0)
        assertEquals(LocalDate.of(2026, 11, 30), f.coveredTo)
    }

    @Test
    fun `開催日を1件も見ていない成功は事実にならない（予定なしを確認したことにしない）`() {
        val f = FetchPolicy.advance(fetch(), env(0, true), stats(max = null), t0)
        assertEquals(FetchStatus.SUCCEEDED, f.status)
        assertNull(f.coveredFrom)
        assertNull(f.coveredTo)
        // 範囲の申告が無い場合も同じ
        val g = FetchPolicy.advance(fetch(from = null, to = null), env(0, true), stats(), t0)
        assertNull(g.coveredTo)
        // 見えた最後の開催日が対象範囲より前
        val h = FetchPolicy.advance(fetch(from = LocalDate.of(2027, 1, 1)), env(0, true), stats(max = LocalDate.of(2026, 12, 27)), t0)
        assertNull(h.coveredTo)
    }

    @Test
    fun `YSCH は保留・不正なレコードが1件でもあれば失敗（事実にしない）`() {
        val unparsed = FetchPolicy.advance(fetch(), env(0, true), stats(unparsed = 1), t0)
        assertEquals(FetchStatus.FAILED, unparsed.status)
        assertEquals("UNPARSED_RECORDS", unparsed.failReason)
        assertNull(unparsed.coveredTo)
        val bad = FetchPolicy.advance(fetch(), env(0, true), stats(failed = 1), t0)
        assertEquals("PARSE_FAILURES", bad.failReason)
        // 途中のバッチで見つかった不正も EOF で効く
        val mid = FetchPolicy.advance(fetch(), env(0, false), stats(failed = 1), t0)
        assertEquals(FetchStatus.FAILED, FetchPolicy.advance(mid, env(1, true), stats(), t0).status)
    }

    @Test
    fun `YSCH 以外は EOF まで読めれば成功（解析できなくても生レコードは保存済み）、事実の範囲は持たない`() {
        val f = FetchPolicy.advance(fetch(dataspec = "RACE", from = null, to = null), env(0, true), stats(unparsed = 5), t0)
        assertEquals(FetchStatus.SUCCEEDED, f.status)
        assertNull(f.coveredFrom)
    }

    @Test
    fun `連番が連続していない・終了済みの取得は進められない`() {
        assertFailsWith<IllegalArgumentException> { FetchPolicy.advance(fetch(), env(1, false), stats(), t0) }
        val done = FetchPolicy.advance(fetch(), env(0, true), stats(), t0)
        assertFailsWith<IllegalArgumentException> { FetchPolicy.advance(done, env(1, true), stats(), t0) }
    }

    @Test
    fun `lastFileTimestamp は最後に指定された値を持ち、無ければ前の値を保つ`() {
        val a = FetchPolicy.advance(fetch(), env(0, false, lastTs = "20261031200000"), stats(), t0)
        val b = FetchPolicy.advance(a, env(1, false, lastTs = null), stats(), t0)
        assertEquals("20261031200000", b.lastFileTimestamp)
    }

    @Test
    fun `カーソルの種別名`() {
        assertEquals("RACE:2", IngestCursors.dataType("RACE", 2))
        assertEquals("YSCH", IngestCursors.dataType("YSCH", null))
        assertEquals(24, Base64.getEncoder().encodeToString(ByteArray(16)).length) // 依存の健全性確認（未使用 import 回避）
    }
}
