package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.StartResult
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.repository.UpdateResult
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * テナント 2 件による越境テスト（最重要）。T1 のデータを T2 の資格で、全 Repository の全操作から読めず・書けず・動かせないことを確かめる。
 * MySQL に行単位のアクセス制御は無いので、DAO の必須引数・SQL の tenant_id 条件・複合 FK で防ぐ（SqlTemplateTenantGuardTest が SQL を静的に検査する）。
 */
@Tag("integration")
class TenantIsolationIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val raceId = Fixtures.raceId()

    @BeforeEach
    fun seedTenant1() {
        db.resetData()
        h.races.upsert(Fixtures.race(T1))
        h.odds.upsert(Fixtures.odds(T1))
        h.schedules.insertIfAbsent(Fixtures.schedule(T1))
    }

    @Test
    fun `レース・オッズ・予定は T2 から見えない`() {
        assertNull(h.races.find(T2, raceId))
        assertEquals(emptyList(), h.races.listByDate(T2, Fixtures.DATE))
        assertNull(h.odds.latest(T2, raceId, Market.WIN))
        assertEquals(emptyList(), h.odds.history(T2, raceId, Market.WIN))
        assertNull(h.schedules.find(T2, raceId))
        assertEquals(emptyList(), h.schedules.listByDate(T2, Fixtures.DATE))
    }

    @Test
    fun `T2 の書込みは T1 の行を変えない（同じ自然キーでも別の行）`() {
        h.races.upsert(Fixtures.race(T2, name = "T2が書いた"))
        h.odds.upsert(Fixtures.odds(T2, values = arrayOf(1 to 99.0)))
        assertEquals("テストレース", h.races.find(T1, raceId)?.name)
        assertEquals(3, checkNotNull(h.odds.latest(T1, raceId, Market.WIN)).entries.size)
        assertEquals(
            99.0,
            checkNotNull(h.odds.latest(T2, raceId, Market.WIN))
                .entries
                .single()
                .odds.value,
        )
    }

    @Test
    fun `BotRun は T2 から見えず・更新できず、T1 の排他も T2 を妨げない`() {
        val run = assertIs<StartResult.Started>(h.botRuns.tryStart(Fixtures.run(T1))).run
        assertNull(h.botRuns.find(T2, run.runId))
        assertNull(h.botRuns.findActive(T2, Fixtures.DATE))
        assertEquals(emptyList(), h.botRuns.listRecent(T2, 10))
        assertEquals(UpdateResult.NotFound, h.botRuns.update(run.copy(tenantId = T2, state = BotRunState.FAILED)))
        assertEquals(BotRunState.STARTING, h.botRuns.find(T1, run.runId)?.state)
        assertIs<StartResult.Started>(h.botRuns.tryStart(Fixtures.run(T2)))
    }

    @Test
    fun `Signal は T2 から見えず・claim できず・状態も変えられない`() {
        val id =
            checkNotNull(
                h.signals
                    .insertIfAbsent(Fixtures.signal(T1))
                    .signal.id,
            )
        assertNull(h.signals.find(T2, id))
        assertEquals(emptyList(), h.signals.listByRace(T2, raceId))
        assertEquals(emptyList(), h.signals.listUnsent(T2, 10))
        assertFalse(h.signals.claim(T2, id))
        assertFalse(h.signals.markSent(T2, id))
        assertFalse(h.signals.markSendUnknown(T2, id))
        assertEquals("UNSENT", db.scalar("SELECT notify_state FROM signals WHERE id = ${id.value}"))
    }

    @Test
    fun `他テナントの親(レース)を使った関連付けは DB の複合 FK が拒否する`() {
        // T2 には無い T1 のレースを親にして、オッズと Signal を T2 として書く
        assertSqlError(MySqlError.FK_CHILD) { h.odds.upsert(Fixtures.odds(T2)) }
        assertSqlError(MySqlError.FK_CHILD) { h.signals.insertIfAbsent(Fixtures.signal(T2)) }
        // DAO を介さない生の SQL でも同じ(複合 FK は表の性質)
        assertSqlError(MySqlError.FK_CHILD) {
            db.exec("INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds) VALUES (2, '${raceId.value}', 'win', NOW(6), 1, 2.0)")
        }
        assertEquals(3, db.count("SELECT COUNT(*) FROM odds_snapshots"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM signals"))
    }

    @Test
    fun `利用者は自分のテナントの一覧にしか出ない`() {
        db.exec("DELETE FROM app_user")
        db.exec(
            "INSERT INTO app_user (tenant_id, email, role, enabled, created_at, updated_at) VALUES (1, 'a@example.com', 'ADMIN', 1, NOW(6), NOW(6)), (2, 'b@example.com', 'VIEWER', 1, NOW(6), NOW(6))",
        )
        assertEquals(listOf("a@example.com"), h.appUsers.listByTenant(T1).map { it.email.value })
        assertEquals(listOf("b@example.com"), h.appUsers.listByTenant(T2).map { it.email.value })
    }

    @Test
    fun `不正な TenantId は作れず、存在しないテナントは全件へフォールバックせず空になる`() {
        assertFailsWith<IllegalArgumentException> { TenantId.of(0) }
        assertFailsWith<IllegalArgumentException> { TenantId.of(-1) }
        val ghost = TenantId.of(12345)
        assertNull(h.races.find(ghost, raceId))
        assertEquals(emptyList(), h.races.listByDate(ghost, Fixtures.DATE))
        assertNull(h.odds.latest(ghost, raceId, Market.WIN))
        assertEquals(emptyList(), h.schedules.listByDate(ghost, Fixtures.DATE))
        assertEquals(emptyList(), h.botRuns.listRecent(ghost, 10))
        assertEquals(emptyList(), h.signals.listUnsent(ghost, 10))
        assertEquals(emptyList(), h.appUsers.listByTenant(ghost))
    }
}
