package jp.jrabot.domain.ingest

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val base: Instant = Instant.parse("2026-11-01T00:00:00Z")

/**
 * 保留の再適用（[PendingReplayer]）の走査の性質。DB を使わないのでページ境界・大量件数・走査中の追加を安く検査できる。
 * 『全件を走査する』ことと『失敗行の再処理が保証される』ことは別なので、後者は『次の契機で再試行される』ことを別に確かめる。
 */
class PendingReplayerTest {
    private val t1 = TenantId.of(1)
    private val t2 = TenantId.of(2)
    private val raceA = RaceId.parse("202611010511")
    private val raceB = RaceId.parse("202611020511")

    private class Row(
        val tenant: TenantId,
        val hash: String,
        val batchId: String,
        val seq: Int,
        val receivedAt: Instant,
        val race: RaceId,
        var status: ParseStatus = ParseStatus.PENDING,
        var error: String,
        val tag: String = "ok",
    )

    /** 本物の SQL（新しい順のキーセット）と同じ意味の保留の置き場。 */
    private class FakeRaw : RawRecordStore {
        val rows = mutableListOf<Row>()
        var listCalls = 0
        var onList: (Int) -> Unit = {}
        var failListAt = 0
        private var next = 0

        fun add(
            tenant: TenantId,
            race: RaceId,
            receivedAt: Instant,
            batchId: String = "b-$next",
            seq: Int = 0,
            error: String = raceNotFoundReason(race),
            tag: String = "ok",
        ): Row = Row(tenant, "h${next++}", batchId, seq, receivedAt, race, error = error, tag = tag).also { rows += it }

        override fun insertIfAbsent(row: RawRecordRow): Boolean = error("使わない")

        override fun settlePending(
            tenantId: TenantId,
            recordHash: String,
            status: ParseStatus,
            error: String?,
        ): Boolean {
            val r = rows.firstOrNull { it.tenant == tenantId && it.hash == recordHash && it.status == ParseStatus.PENDING } ?: return false
            r.status = status
            return true
        }

        override fun listPending(
            tenantId: TenantId,
            reasons: List<String>,
            limit: Int,
            after: PendingCursor?,
        ): List<PendingRawRecord> {
            listCalls++
            onList(listCalls)
            if (failListAt != 0 && listCalls == failListAt) throw IllegalStateException("injected")
            return rows
                .filter { it.tenant == tenantId && it.status == ParseStatus.PENDING && it.error in reasons }
                .filter { after == null || compareKey(it, after) < 0 }
                .sortedWith(compareByDescending<Row> { it.receivedAt }.thenByDescending { it.batchId }.thenByDescending { it.seq })
                .take(limit)
                .map {
                    PendingRawRecord(
                        it.hash,
                        it.batchId,
                        BatchClass.REALTIME,
                        base,
                        "0B31",
                        "NOW",
                        "${it.race.value}|${it.tag}".toByteArray(),
                        it.receivedAt,
                        it.seq,
                    )
                }
        }

        /** 行値の比較 `(received_at, batch_id, seq) < (cursor)` の符号（辞書順）。 */
        private fun compareKey(
            r: Row,
            c: PendingCursor,
        ): Int {
            val c1 = r.receivedAt.compareTo(c.receivedAt)
            if (c1 != 0) return c1
            val c2 = r.batchId.compareTo(c.batchId)
            return if (c2 != 0) c2 else r.seq.compareTo(c.seq)
        }
    }

    private val parsers =
        RecordParsers(
            listOf(
                RecordParser { _, rec ->
                    val (race, tag) = String(rec.bytes).split("|")
                    ParseOutcome.Parsed(listOf(TimeChangeObservation(RaceId.parse(race), base, false, tag)))
                },
            ),
        )

    private class Env(
        val raw: FakeRaw,
        val existing: MutableSet<RaceId>,
    ) {
        val applied = mutableListOf<Pair<TenantId, String>>()
        val appliedByRace = mutableMapOf<RaceId, Int>()
        var poison = false

        fun apply(
            tenant: TenantId,
            obs: Observation,
            @Suppress("UNUSED_PARAMETER") ctx: ApplyContext,
        ): ApplyOutcome {
            obs as TimeChangeObservation
            applied += tenant to obs.snapshotKey
            appliedByRace.merge(obs.raceId, 1, Int::plus)
            if (obs.snapshotKey == "boom" && poison) throw IllegalStateException("poison")
            if (obs.snapshotKey == "bad") return ApplyOutcome.Quarantined("BAD")
            return if (obs.raceId in existing) ApplyOutcome.Applied else ApplyOutcome.Deferred(raceNotFoundReason(obs.raceId))
        }
    }

    private fun newReplayer(
        env: Env,
        page: Int = PendingReplayer.REPLAY_PAGE,
    ) = PendingReplayer(
        env.raw,
        parsers,
        env::apply,
        object : UnitOfWork {
            override fun <T> run(block: () -> T): T = block()
        },
        page,
    )

    private fun env() = Env(FakeRaw(), mutableSetOf(raceA))

    @Test
    fun `旧 10,000 件の上限を超える保留も 1 回の走査で最後まで再適用される`() {
        val e = env()
        val n = 25_000
        repeat(n) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%06d".format(it)) }
        val report = newReplayer(e).replay(t1, setOf(raceA))
        assertEquals(n, report.scanned)
        assertEquals(n, report.settled)
        assertEquals(n, e.raw.rows.count { it.status == ParseStatus.PARSED }, "上限で打ち切られず、全件が確定する")
        assertEquals(0, e.raw.rows.count { it.status == ParseStatus.PENDING })
        assertTrue(!report.leftover)
        assertEquals(n, e.appliedByRace[raceA], "各行をちょうど 1 回適用する")
    }

    @Test
    fun `ページ境界 ページ未満 ちょうど ちょうど+1 倍数 でも漏れず 重複せず 終わる`() {
        for (n in listOf(0, 1, 4, 5, 6, 9, 10, 11, 25)) {
            val e = env()
            repeat(n) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%03d".format(it)) }
            val report = newReplayer(e, page = 5).replay(t1, setOf(raceA))
            assertEquals(n, report.scanned, "n=$n")
            assertEquals(n, e.applied.size, "n=$n 各行 1 回")
            assertTrue(e.raw.rows.all { it.status == ParseStatus.PARSED }, "n=$n")
            // 空のページを余分に読むのは ちょうど倍数のときだけ
            assertEquals(n / 5 + 1, e.raw.listCalls, "n=$n のページ読み取り回数")
        }
    }

    @Test
    fun `失敗する行は同じ走査で選び直されず 他の行は処理され 失敗行は次の契機で再試行される`() {
        val e = env()
        e.poison = true
        repeat(30) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%03d".format(it), tag = if (it % 7 == 0) "boom" else "ok") }
        val boom = e.raw.rows.count { it.tag == "boom" }
        val first = newReplayer(e, page = 4).replay(t1, setOf(raceA))
        assertEquals(30, first.scanned)
        assertEquals(boom, first.rowErrors)
        assertEquals(30 - boom, first.settled)
        assertEquals(30, e.applied.size, "走査の中で各行を 1 回だけ適用する（失敗行を無限に選び直さない）")
        assertTrue(first.leftover, "失敗行が残ったことを報告する")
        assertEquals(boom, e.raw.rows.count { it.status == ParseStatus.PENDING })
        // 走査が全件に及んでも、失敗行の再処理は起動されない: 次の契機（次のレース観測）まで PENDING のまま
        assertEquals(boom, e.raw.rows.count { it.status == ParseStatus.PENDING })
        // 次の契機。原因が直っていれば、残りだけが処理される
        e.poison = false
        e.applied.clear()
        val second = newReplayer(e, page = 4).replay(t1, setOf(raceA))
        assertEquals(boom, second.scanned, "確定済みの行は走査の対象にならない")
        assertEquals(boom, second.settled)
        assertEquals(0, e.raw.rows.count { it.status == ParseStatus.PENDING })
    }

    @Test
    fun `依存するレースがまだ無い行は保留のまま残り 無限に再選択されない`() {
        val e = env()
        e.existing.clear() // レースがまだ無い
        repeat(12) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%03d".format(it)) }
        val report = newReplayer(e, page = 5).replay(t1, setOf(raceA))
        assertEquals(12, report.scanned)
        assertEquals(12, report.stillPending)
        assertEquals(12, e.applied.size)
        assertTrue(report.leftover)
        assertEquals(12, e.raw.rows.count { it.status == ParseStatus.PENDING })
    }

    @Test
    fun `隔離される行はFAILEDに確定し 別のレース待ちと別のテナントと対象外の理由には触れない`() {
        val e = env()
        e.raw.add(t1, raceA, base, batchId = "a1", tag = "bad")
        e.raw.add(t1, raceA, base.plusSeconds(1), batchId = "a2")
        val otherRace = e.raw.add(t1, raceB, base.plusSeconds(2), batchId = "b1")
        val otherTenant = e.raw.add(t2, raceA, base.plusSeconds(3), batchId = "c1")
        val otherReason = e.raw.add(t1, raceA, base.plusSeconds(4), batchId = "d1", error = "NO_PARSER:0B31/NOW")
        val legacy = e.raw.add(t1, raceA, base.plusSeconds(5), batchId = "e1", error = REASON_RACE_NOT_FOUND)
        val report = newReplayer(e).replay(t1, setOf(raceA))
        assertEquals(3, report.scanned, "a1, a2, 旧形式")
        assertEquals(
            ParseStatus.PARSED,
            e.raw.rows
                .first { it.batchId == "a2" }
                .status,
        )
        assertEquals(ParseStatus.PARSED, legacy.status, "待つレースが不明な旧形式の保留も対象")
        assertEquals(
            ParseStatus.FAILED,
            e.raw.rows
                .first { it.batchId == "a1" }
                .status,
            "不正と判断された行は隔離（FAILED）",
        )
        for (untouched in listOf(otherRace, otherTenant, otherReason)) assertEquals(ParseStatus.PENDING, untouched.status)
        assertTrue(e.applied.none { it.first == t2 }, "別のテナントには適用しない")
        assertEquals(ReplayReport.NONE, newReplayer(e).replay(t1, emptySet()), "待つレースが無ければ何もしない")
    }

    @Test
    fun `走査中に新しい保留が追加され続けても走査は終わり 追加分は次の契機で処理される`() {
        val e = env()
        repeat(40) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%03d".format(it)) }
        var added = 0
        e.raw.onList = { call ->
            if (call <= 3) {
                // 各ページの読み取りの前に、新しい保留（より新しい received_at）が到着する
                repeat(10) { e.raw.add(t1, raceA, base.plusSeconds(1000L + added++), batchId = "n%03d".format(added)) }
            }
        }
        val first = newReplayer(e, page = 5).replay(t1, setOf(raceA))
        assertTrue(first.scanned >= 40 && !first.aborted, "走査は有限で終わる")
        // 到着した分のうち、走査が通り過ぎた位置より新しいものは今回は見えない（次の契機で処理される）。それでも次の走査で漏れなく処理される
        e.raw.onList = {}
        newReplayer(e, page = 5).replay(t1, setOf(raceA))
        assertEquals(0, e.raw.rows.count { it.status == ParseStatus.PENDING })
        assertEquals(40 + added, e.raw.rows.count { it.status == ParseStatus.PARSED })
    }

    @Test
    fun `取得の途中で失敗しても途中までは確定し 残りは次の契機で漏れなく処理される`() {
        val e = env()
        repeat(23) { e.raw.add(t1, raceA, base.plusSeconds(it.toLong()), batchId = "b%03d".format(it)) }
        e.raw.failListAt = 3
        val first = newReplayer(e, page = 5).replay(t1, setOf(raceA))
        assertTrue(first.aborted && first.leftover)
        assertEquals(10, first.settled, "失敗するまでの 2 ページ分")
        assertEquals(13, e.raw.rows.count { it.status == ParseStatus.PENDING })
        e.raw.failListAt = 0
        val second = newReplayer(e, page = 5).replay(t1, setOf(raceA))
        assertEquals(13, second.settled)
        assertEquals(0, e.raw.rows.count { it.status == ParseStatus.PENDING })
    }

    @Test
    fun `同じ received_at の行が複数のバッチにまたがっても ページ境界で飛ばさず 重複しない`() {
        val e = env()
        val ts = base
        for (b in 0 until 5) for (s in 0 until 40) e.raw.add(t1, raceA, ts, batchId = "batch-$b", seq = s)
        val report = newReplayer(e, page = 7).replay(t1, setOf(raceA))
        assertEquals(200, report.scanned)
        assertEquals(200, e.applied.size)
        assertEquals(0, e.raw.rows.count { it.status == ParseStatus.PENDING })
    }
}
