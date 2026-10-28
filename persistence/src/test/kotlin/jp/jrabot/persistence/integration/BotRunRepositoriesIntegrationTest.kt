package jp.jrabot.persistence.integration

import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationRecord
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.repository.InsertOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** bot-run が使う `race_evaluations` の冪等な追記と出走表の馬名の読取り（`bot` ユーザーの最小権限で）。 */
@Tag("integration")
class BotRunRepositoriesIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "bot", poolMax = 4)
        private val seed = RepositoryHarness(db, user = "migrate", poolMax = 2)

        @JvmStatic
        @AfterAll
        fun close() {
            h.close()
            seed.close()
        }
    }

    private val raceId = Fixtures.raceId()
    private val writer get() = h.bean(RaceEvaluationWriter::class.java)
    private val entries get() = h.bean(RaceEntryReader::class.java)

    @BeforeEach
    fun reset() {
        db.resetData()
        seed.races.upsert(Fixtures.race(T1))
        seed.races.upsert(Fixtures.race(T2))
    }

    private fun record(
        tenant: jp.jrabot.domain.tenant.TenantId = T1,
        snapshotAt: Instant = Fixtures.OBSERVED,
        modelId: String? = null,
    ) = RaceEvaluationRecord(
        tenantId = tenant,
        raceId = raceId,
        snapshotAt = snapshotAt,
        modelTypeUsed = if (modelId == null) "market" else "calibration",
        modelId = modelId,
        fallbackReason = if (modelId == null) "ML_DISABLED" else null,
        betTypesJson = """["単勝","ワイド"]""",
        priorityMode = "edge",
        evThreshold = 0.08,
        winOddsMax = null,
        winProbMin = null,
        winRatioMin = null,
        bestEv = 0.31,
        rejectionSummaryJson = """{"EV_NOT_ABOVE_THRESHOLD":3}""",
        signalCount = 2,
    )

    @Test
    fun `判定の記録は自然キーで冪等 同じ観測の再評価で行が増えない`() {
        assertEquals(InsertOutcome.INSERTED, writer.insertIfAbsent(record()))
        assertEquals(InsertOutcome.ALREADY_EXISTS, writer.insertIfAbsent(record()))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_evaluations"))
        assertEquals(InsertOutcome.INSERTED, writer.insertIfAbsent(record(snapshotAt = Fixtures.OBSERVED.plusSeconds(60))))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations"))
    }

    @Test
    fun `値と JSON 列が保存され モデルの ID とフォールバック理由が残る`() {
        writer.insertIfAbsent(record())
        assertEquals("market", db.scalar("SELECT model_type_used FROM race_evaluations"))
        assertEquals("ML_DISABLED", db.scalar("SELECT fallback_reason FROM race_evaluations"))
        assertEquals(null, db.scalar("SELECT model_id FROM race_evaluations"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_evaluations WHERE JSON_VALID(bet_types) AND JSON_VALID(rejection_summary_json)"))
        assertEquals("0.31", db.scalar("SELECT best_ev FROM race_evaluations"))
        assertEquals("1", db.scalar("SELECT signal_sent FROM race_evaluations"))
        assertEquals(
            "3",
            db.scalar("SELECT JSON_EXTRACT(rejection_summary_json, '\$.EV_NOT_ABOVE_THRESHOLD') FROM race_evaluations"),
        )
    }

    @Test
    fun `テナントが違えば別の行 同じレース同じ観測でも混ざらない`() {
        assertEquals(InsertOutcome.INSERTED, writer.insertIfAbsent(record(T1)))
        assertEquals(InsertOutcome.INSERTED, writer.insertIfAbsent(record(T2)))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_evaluations WHERE tenant_id = 1"))
        assertEquals(1, db.count("SELECT COUNT(*) FROM race_evaluations WHERE tenant_id = 2"))
    }

    @Test
    fun `出走表の馬名 馬名の無い馬は含めず 他テナントは見えない`() {
        db.exec(
            "INSERT INTO race_horses (tenant_id, race_id, umaban, ketto_num, bamei, created_at, updated_at) VALUES (1, '${raceId.value}', 1, NULL, '合成一', NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO race_horses (tenant_id, race_id, umaban, ketto_num, bamei, created_at, updated_at) VALUES (1, '${raceId.value}', 2, NULL, NULL, NOW(6), NOW(6))",
        )
        db.exec(
            "INSERT INTO race_horses (tenant_id, race_id, umaban, ketto_num, bamei, created_at, updated_at) VALUES (2, '${raceId.value}', 1, NULL, '他テナント', NOW(6), NOW(6))",
        )
        assertEquals(mapOf(1 to "合成一"), entries.horseNames(T1, raceId))
        assertEquals(mapOf(1 to "他テナント"), entries.horseNames(T2, raceId))
    }

    /**
     * 監査 D: UNIQUE の無い `race_evaluations` へ、同じ (race, snapshot_at) を同時に挿入しても重複しない（分離レベルの固定 + デッドロック再試行）。
     * REPEATABLE READ（MySQL 既定）と READ COMMITTED の両方で確認する。
     */
    private fun concurrentInsertsStayUnique(isolation: String?) {
        val url = MySqlSupport.jdbcUrl(db.name) + (isolation?.let { "?sessionVariables=transaction_isolation=%27$it%27" } ?: "")
        RepositoryHarness(db, user = "bot", poolMax = 8, url = url).use { harness ->
            val w = harness.bean(RaceEvaluationWriter::class.java)
            val threads = 8
            val rounds = 25
            val pool =
                java.util.concurrent.Executors
                    .newFixedThreadPool(threads)
            try {
                repeat(rounds) { round ->
                    val barrier = java.util.concurrent.CyclicBarrier(threads)
                    val at = Fixtures.OBSERVED.plusSeconds(round.toLong())
                    val outcomes =
                        (1..threads)
                            .map {
                                pool.submit<InsertOutcome> {
                                    barrier.await()
                                    w.insertIfAbsent(record(snapshotAt = at))
                                }
                            }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                    assertEquals(1, outcomes.count { it == InsertOutcome.INSERTED }, "round=$round isolation=$isolation: 挿入できたのは 1 つだけ")
                }
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(25, db.count("SELECT COUNT(*) FROM race_evaluations"), "isolation=$isolation: 重複なし")
    }

    @Test
    fun `同時挿入でも重複しない REPEATABLE READ`() = concurrentInsertsStayUnique("REPEATABLE-READ")

    @Test
    fun `セッションが READ COMMITTED を指定しても REPEATABLE READ に固定され 同時挿入で重複しない`() = concurrentInsertsStayUnique("READ-COMMITTED")
}
