package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.StartResult
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.repository.UpdateResult
import jp.jrabot.domain.repository.UpsertOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 用途別 DB ユーザー（R__grants.sql）で Repository を動かす。許可される操作は成功し、範囲外の操作は DB が拒否する（強い権限で回避していない）。
 * 準備のデータは root で入れる。
 */
@Tag("integration")
class PermissionsIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val ingest = RepositoryHarness(db, "ingest")
        private val bot = RepositoryHarness(db, "bot")
        private val app = RepositoryHarness(db, "app")
        private val mltrain = RepositoryHarness(db, "mltrain")

        @JvmStatic
        @AfterAll
        fun close() = listOf(ingest, bot, app, mltrain).forEach { it.close() }
    }

    private val raceId = Fixtures.raceId()

    @BeforeEach
    fun reset() {
        db.resetData()
        db.exec("DELETE FROM app_user")
        ingest.races.upsert(Fixtures.race())
    }

    @Test
    fun `ingest は races・オッズ・予定を書けるが、bot_run と signals は書けない`() {
        assertEquals(UpsertOutcome.UNCHANGED, ingest.races.upsert(Fixtures.race()))
        assertEquals(UpsertOutcome.UPDATED, ingest.races.upsert(Fixtures.race(name = "ingestが更新")))
        assertEquals(UpsertOutcome.CREATED, ingest.odds.upsert(Fixtures.odds()))
        assertEquals(InsertOutcome.INSERTED, ingest.schedules.insertIfAbsent(Fixtures.schedule()))
        assertIs<UpdateResult.Updated<*>>(ingest.schedules.update(Fixtures.schedule().copy(status = RaceScheduleStatus.CARD)))

        assertDenied { ingest.botRuns.tryStart(Fixtures.run()) }
        assertDenied { ingest.signals.insertIfAbsent(Fixtures.signal()) }
        // ingest は bot_run・signals を読むこともできない(最小権限)
        assertDenied { ingest.botRuns.findActive(T1, Fixtures.DATE) }
        assertDenied { ingest.signals.listUnsent(T1, 10) }
        assertEquals(0, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `bot は races とオッズを書けない（必要な操作だけ許可）`() {
        assertDenied { bot.races.upsert(Fixtures.race(name = "botが更新しようとした")) }
        assertDenied { bot.odds.upsert(Fixtures.odds()) }
        assertEquals("テストレース", bot.races.find(T1, raceId)?.name) // 読取りは通り、書込みは反映されていない
    }

    @Test
    fun `bot は bot_run・signals・race_schedule を使える`() {
        val run = assertIs<StartResult.Started>(bot.botRuns.tryStart(Fixtures.run())).run
        assertIs<UpdateResult.Updated<*>>(bot.botRuns.update(run.copy(state = BotRunState.INTERRUPTED)))

        val id =
            checkNotNull(
                bot.signals
                    .insertIfAbsent(Fixtures.signal())
                    .signal.id,
            )
        assertEquals(true, bot.signals.claim(T1, id))
        assertEquals(true, bot.signals.markSent(T1, id))

        assertEquals(InsertOutcome.INSERTED, bot.schedules.insertIfAbsent(Fixtures.schedule()))
        assertIs<UpdateResult.Updated<*>>(bot.schedules.update(Fixtures.schedule().copy(status = RaceScheduleStatus.MONITORING)))
        assertEquals(emptyList(), bot.odds.history(T1, raceId, Market.WIN))
    }

    @Test
    fun `app(ダッシュボード)は全て読め、書けるのは bot_run への手動起動の INSERT だけ`() {
        ingest.odds.upsert(Fixtures.odds())
        assertEquals("テストレース", app.races.find(T1, raceId)?.name)
        assertEquals(
            3,
            app.odds
                .latest(T1, raceId, Market.WIN)
                ?.entries
                ?.size,
        )
        assertEquals(emptyList(), app.schedules.listByDate(T1, Fixtures.DATE))

        val run = assertIs<StartResult.Started>(app.botRuns.tryStart(Fixtures.run())).run // INSERT bot_run は許可
        assertDenied { app.botRuns.update(run.copy(state = BotRunState.RUNNING)) }
        assertDenied { app.races.upsert(Fixtures.race(name = "app")) }
        assertDenied { app.odds.upsert(Fixtures.odds()) }
        assertDenied { app.schedules.insertIfAbsent(Fixtures.schedule()) }
        assertDenied { app.signals.insertIfAbsent(Fixtures.signal()) }
        assertEquals(BotRunState.STARTING, app.botRuns.find(T1, run.runId)?.state)
    }

    @Test
    fun `claim は UPDATE 権限が要る（ingest・app・mltrain は通知を claim できない）`() {
        val id =
            checkNotNull(
                bot.signals
                    .insertIfAbsent(Fixtures.signal())
                    .signal.id,
            )
        for (h in listOf(ingest, app, mltrain)) assertDenied { h.signals.claim(T1, id) }
        assertEquals("UNSENT", db.scalar("SELECT notify_state FROM signals WHERE id = ${id.value}"))
        assertEquals(true, bot.signals.claim(T1, id))
    }

    @Test
    fun `mltrain は Repository の書込みを何も行えない`() {
        assertDenied { mltrain.races.upsert(Fixtures.race(name = "mltrain")) }
        assertDenied { mltrain.odds.upsert(Fixtures.odds()) }
        assertDenied { mltrain.botRuns.tryStart(Fixtures.run()) }
        assertDenied { mltrain.signals.insertIfAbsent(Fixtures.signal()) }
        assertDenied { mltrain.schedules.insertIfAbsent(Fixtures.schedule()) }
    }

    @Test
    fun `app_user を読めるのは app(ダッシュボードのログイン解決)だけ`() {
        db.exec("INSERT INTO app_user (tenant_id, email, role, enabled, created_at, updated_at) VALUES (1, 'a@example.com', 'ADMIN', 1, NOW(6), NOW(6))")
        val email =
            jp.jrabot.domain.tenant.EmailAddress
                .of("a@example.com")
        assertEquals(T1, app.appUsers.findByEmail(email)?.tenantId)
        assertEquals(1, app.appUsers.listByTenant(T1).size)
        for (h in listOf(ingest, bot, mltrain)) {
            assertDenied { h.appUsers.findByEmail(email) }
            assertDenied { h.appUsers.listByTenant(T1) }
        }
    }
}
