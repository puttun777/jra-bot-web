package jp.jrabot.persistence.integration

import jp.jrabot.domain.botrun.RaceEvaluationRecord
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.botrun.TimingNotice
import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.notify.BundleView
import jp.jrabot.domain.notify.CoverageView
import jp.jrabot.domain.notify.NoticeItem
import jp.jrabot.domain.notify.NoticeSnapshot
import jp.jrabot.domain.notify.RaceLabel
import jp.jrabot.domain.notify.SignalView
import jp.jrabot.domain.notify.StrategyView
import jp.jrabot.domain.notify.TicketView
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通知タイミングの確定（V5 `race_timing_notice`）と評価結果の一意性（`race_evaluations.uq_native`）。`bot` ユーザーの最小権限で、実 MySQL に対して確かめる。
 * RR/RC のどちらのセッション指定でも同時確定は 1 つだけ。MySQL の JSON 型を経由しても通知内容のハッシュが合う。合成データのみ。
 */
@Tag("integration")
class TimingNoticeIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "bot", poolMax = 8)
        private val seed = RepositoryHarness(db, user = "migrate", poolMax = 2)

        @JvmStatic
        @AfterAll
        fun close() {
            h.close()
            seed.close()
        }
    }

    private val raceId = Fixtures.raceId()
    private val store get() = h.bean(TimingNoticeStore::class.java)
    private val evals get() = h.bean(RaceEvaluationWriter::class.java)

    @BeforeEach
    fun reset() {
        db.resetData()
        seed.races.upsert(Fixtures.race(T1))
        seed.races.upsert(Fixtures.race(T2))
    }

    private fun record(
        tenant: TenantId = T1,
        at: Instant = Fixtures.OBSERVED,
    ) = RaceEvaluationRecord(tenant, raceId, at, "market", null, "ML_DISABLED", null, "edge", 0.08, null, null, null, 0.3, "{}", 1)

    private val snapshot =
        NoticeSnapshot(
            RaceLabel("東京", 11, "合成ステークス"),
            listOf(
                NoticeItem(
                    SignalId.of(1),
                    SignalView(
                        "ワイド",
                        5,
                        8.0,
                        0.1234567890123,
                        0.1 + 0.2,
                        pMkt = 0.125,
                        coverage = CoverageView(0.5, null, true, false, 0),
                        betAmount = 3.0,
                        reason = "理由 \"引用\" 🐴\n改行",
                        bundle =
                            BundleView(
                                listOf(StrategyView("ワイド", 2.0, 0.4, 12.3, 1e-5, 0.56, null, listOf(TicketView("2-5", "3.2〜5.1", 0.21, 0.45, 1.0)), null)),
                                null,
                                2.0,
                                0.4,
                            ),
                    ),
                ),
            ),
        )

    private fun notice(
        evaluationId: Long,
        centi: Int,
        tenant: TenantId = T1,
        at: Instant = Fixtures.OBSERVED,
        run: String = "00000000-0000-4000-8000-000000000001",
    ) = TimingNotice(
        tenant,
        raceId,
        centi,
        run,
        evaluationId,
        at,
        NoticeSnapshot.SCHEMA,
        snapshot.canonicalJson(),
        snapshot.sha256(),
        Instant.parse("2026-10-23T03:00:00.123456Z"),
    )

    @Test
    fun `評価結果は新方式の行ごとに一意 観測時刻が違えば別の行 ID は既存の行のもの`() {
        val a = evals.record(record())
        assertEquals(InsertOutcome.INSERTED, a.outcome)
        val again = evals.record(record())
        assertEquals(InsertOutcome.ALREADY_EXISTS, again.outcome)
        assertEquals(a.id, again.id)
        val later = evals.record(record(at = Fixtures.OBSERVED.plusSeconds(60)))
        assertEquals(InsertOutcome.INSERTED, later.outcome)
        assertTrue(later.id != a.id)
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations"))
    }

    @Test
    fun `同じレース 同じタイミングは 1 回しか確定できない オッズの観測時刻が変わっても`() {
        val e1 = evals.record(record(at = Fixtures.OBSERVED)).id
        val e2 = evals.record(record(at = Fixtures.OBSERVED.plusSeconds(90))).id // 新しいオッズ（別の評価結果）
        assertEquals(setOf(500), store.claim(listOf(notice(e1, 500))))
        assertEquals(emptySet(), store.claim(listOf(notice(e2, 500, at = Fixtures.OBSERVED.plusSeconds(90)))), "新しいオッズでも同じタイミングは確定できない")
        assertEquals(
            setOf(3000),
            store.claim(listOf(notice(e2, 500, at = Fixtures.OBSERVED.plusSeconds(90)), notice(e2, 3000, at = Fixtures.OBSERVED.plusSeconds(90)))),
            "新しく取れたものだけ",
        )
        assertEquals(setOf(500, 3000), store.firedCenti(T1, raceId))
        // 確定した内容は変わらない（最初のオッズのまま）
        assertEquals(e1, assertNotNull(store.find(T1, raceId, 500)).evaluationId)
        assertEquals(Fixtures.OBSERVED, assertNotNull(store.find(T1, raceId, 500)).oddsObservedAt)
    }

    @Test
    fun `テナントが違えば別のタイミング 他のテナントの確定は見えない`() {
        val e1 = evals.record(record(T1)).id
        val e2 = evals.record(record(T2)).id
        assertEquals(setOf(500), store.claim(listOf(notice(e1, 500, T1))))
        assertEquals(emptySet(), store.firedCenti(T2, raceId))
        assertEquals(setOf(500), store.claim(listOf(notice(e2, 500, T2))))
        assertNull(store.find(T2, raceId, 3000))
        assertEquals(listOf(500), store.list(T1, raceId).map { it.timingCenti })
        // 他のテナントの評価結果を指す確定は FK が拒否する
        org.junit.jupiter.api.Assertions
            .assertThrows(RuntimeException::class.java) { store.claim(listOf(notice(e2, 700, T1))) }
        assertEquals(setOf(500), store.firedCenti(T1, raceId))
    }

    @Test
    fun `MySQL の JSON 型を経由しても 通知内容が一致し ハッシュが検証できる`() {
        val e = evals.record(record()).id
        store.claim(listOf(notice(e, 500)))
        val stored = assertNotNull(store.find(T1, raceId, 500))
        val back = NoticeSnapshot.decode(stored.noticeSchema, stored.noticeJson, stored.noticeSha256)
        assertEquals(snapshot.items, back.items, "浮動小数・改行・絵文字・買い目まで一致")
        assertEquals(snapshot.race, back.race)
        assertEquals(snapshot.sha256(), back.sha256())
    }

    @Test
    fun `追記専用 bot は確定済みの行を変更も削除もできない`() {
        val e = evals.record(record()).id
        store.claim(listOf(notice(e, 500)))
        java.sql.DriverManager.getConnection(MySqlSupport.jdbcUrl(db.name), "bot", MySqlSupport.PASSWORDS.getValue("bot")).use { c ->
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException::class.java) {
                c.createStatement().use { it.executeUpdate("UPDATE race_timing_notice SET notice_schema = 9") }
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException::class.java) {
                c.createStatement().use { it.executeUpdate("DELETE FROM race_timing_notice") }
            }
        }
        assertEquals(1, store.list(T1, raceId).size)
    }

    /** 同時に確定しようとしても勝つのは 1 つだけ（REPEATABLE READ / READ COMMITTED のセッション指定の両方。接続は REPEATABLE READ に固定される）。 */
    private fun concurrentClaims(isolation: String?) {
        val url = MySqlSupport.jdbcUrl(db.name) + (isolation?.let { "?sessionVariables=transaction_isolation=%27$it%27" } ?: "")
        RepositoryHarness(db, user = "bot", poolMax = 8, url = url).use { harness ->
            val st = harness.bean(TimingNoticeStore::class.java)
            val w = harness.bean(RaceEvaluationWriter::class.java)
            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            try {
                repeat(20) { round ->
                    val at = Fixtures.OBSERVED.plusSeconds(1000L + round)
                    val barrier = CyclicBarrier(threads)
                    val results =
                        (1..threads)
                            .map {
                                pool.submit<Pair<Boolean, Set<Int>>> {
                                    barrier.await()
                                    // 各スレッドが「新しいオッズ」で評価し、同じタイミングを確定しようとする（評価結果の行も同時に作る）
                                    val id = w.record(record(at = at)).id
                                    (true to st.claim(listOf(notice(id, 600 + round, at = at, run = "00000000-0000-4000-8000-0000000000%02d".format(it)))))
                                }
                            }.map { it.get(60, TimeUnit.SECONDS) }
                    assertEquals(1, results.count { it.second.isNotEmpty() }, "round=$round isolation=$isolation: 確定できたのは 1 つだけ")
                }
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(20, db.count("SELECT COUNT(*) FROM race_timing_notice"))
        assertEquals(20, db.count("SELECT COUNT(*) FROM race_evaluations"), "評価結果も重複しない")
    }

    @Test
    fun `同時確定でも 1 つだけ REPEATABLE READ`() = concurrentClaims("REPEATABLE-READ")

    @Test
    fun `同時確定でも 1 つだけ READ COMMITTED を指定されても`() = concurrentClaims("READ-COMMITTED")
}
