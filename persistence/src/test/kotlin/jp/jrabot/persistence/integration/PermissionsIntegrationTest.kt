package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.TransitionResult
import jp.jrabot.domain.calendar.AppendResult
import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.OverrideCreateResult
import jp.jrabot.domain.calendar.OverrideRevokeResult
import jp.jrabot.domain.calendar.ScheduleEventType
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
        assertIs<ChangeResult.Applied<*>>(ingest.schedules.insertIfAbsent(Fixtures.schedule(), Fixtures.event(Fixtures.schedule())))
        val carded = Fixtures.schedule().copy(status = RaceScheduleStatus.CARD)
        assertIs<ChangeResult.Applied<*>>(ingest.schedules.update(carded, Fixtures.event(carded, ScheduleEventType.TIME_CHANGE, "c")))
        // 開催日・履歴（取込側が書く）。補正の表は読めない・書けない
        assertIs<ChangeResult.Applied<*>>(ingest.raceDays.insertIfAbsent(Fixtures.day(), Fixtures.dayEvent()))
        assertDenied { ingest.overrides.listByDate(T1, Fixtures.DATE) }
        assertDenied { ingest.overrides.create(Fixtures.manual(), Fixtures.overrideEvent()) }

        assertDenied { ingest.botRuns.acquire(Fixtures.lease()) }
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
        val run = assertIs<AcquireResult.Acquired>(bot.botRuns.acquire(Fixtures.lease())).run
        assertIs<TransitionResult.Done>(bot.botRuns.transition(T1, run.runId, "owner-1", BotRunState.INTERRUPTED, Fixtures.NOW))
        assertIs<AcquireResult.Acquired>(bot.botRuns.acquire(Fixtures.lease(owner = "o2", now = Fixtures.NOW.plusSeconds(1))))

        val id =
            checkNotNull(
                bot.signals
                    .insertIfAbsent(Fixtures.signal())
                    .signal.id,
            )
        assertEquals(true, bot.signals.claim(T1, id))
        assertEquals(true, bot.signals.markSent(T1, id))

        assertIs<ChangeResult.Applied<*>>(bot.schedules.insertIfAbsent(Fixtures.schedule(), Fixtures.event(Fixtures.schedule())))
        val monitoring = Fixtures.schedule().copy(status = RaceScheduleStatus.CARD)
        assertIs<ChangeResult.Applied<*>>(bot.schedules.update(monitoring, Fixtures.event(monitoring, ScheduleEventType.TIME_CHANGE, "m")))
        assertEquals(emptyList(), bot.odds.history(T1, raceId, Market.WIN))
        // 開催日・履歴の追記もできる
        assertIs<ChangeResult.Applied<*>>(bot.raceDays.insertIfAbsent(Fixtures.day(), Fixtures.dayEvent()))
        assertIs<AppendResult.Appended>(bot.events.append(Fixtures.staleEvent()))
    }

    @Test
    fun `bot は手動補正を読めるが、書けない（補正の書込みは管理者の app だけ）`() {
        val app = app.overrides.create(Fixtures.manual(), Fixtures.overrideEvent())
        val created = assertIs<OverrideCreateResult.Created>(app).override
        assertEquals(listOf(created), bot.overrides.listByDate(T1, Fixtures.DATE))
        assertDenied { bot.overrides.create(Fixtures.manual(by = "bot@example.com"), Fixtures.overrideEvent(by = "bot@example.com")) }
        assertDenied { bot.overrides.revoke(T1, checkNotNull(created.id), "bot@example.com", Fixtures.NOW, Fixtures.revokeEvent(created)) }
        assertEquals(1, db.count("SELECT COUNT(*) FROM calendar_override"))
        assertEquals(null, bot.overrides.find(T1, checkNotNull(created.id))!!.revokedAt)
    }

    @Test
    fun `app(ダッシュボード)は全て読め、書けるのは補正（と履歴の追記）と bot_run への手動起動の INSERT だけ`() {
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

        val run = assertIs<AcquireResult.Acquired>(app.botRuns.acquire(Fixtures.lease())).run // INSERT bot_run は許可
        // 稼働中の行を行ロックして読む・更新する操作（奪取・遷移・heartbeat）は UPDATE 権限が要るので拒否される
        assertDenied { app.botRuns.acquire(Fixtures.lease(owner = "o2")) }
        assertDenied { app.botRuns.transition(T1, run.runId, "owner-1", BotRunState.RUNNING, Fixtures.NOW) }
        assertDenied { app.botRuns.heartbeat(T1, run.runId, "owner-1", Fixtures.NOW, java.time.Duration.ofMinutes(5)) }
        assertDenied { app.races.upsert(Fixtures.race(name = "app")) }
        assertDenied { app.odds.upsert(Fixtures.odds()) }
        assertDenied { app.schedules.insertIfAbsent(Fixtures.schedule(), Fixtures.event(Fixtures.schedule())) }
        assertDenied { app.raceDays.insertIfAbsent(Fixtures.day(), Fixtures.dayEvent()) }
        assertDenied { app.signals.insertIfAbsent(Fixtures.signal()) }
        assertEquals(BotRunState.STARTING, app.botRuns.find(T1, run.runId)?.state)
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_day"))

        // 手動補正: 追記・取消（と履歴 MANUAL_OVERRIDE・MANUAL_REVOKE の追記）は許可（同じトランザクション）
        val override = assertIs<OverrideCreateResult.Created>(app.overrides.create(Fixtures.manual(), Fixtures.overrideEvent())).override
        assertIs<OverrideRevokeResult.Revoked>(
            app.overrides.revoke(T1, checkNotNull(override.id), "boss@example.com", Fixtures.NOW, Fixtures.revokeEvent(override)),
        )
        assertEquals(setOf("MANUAL_OVERRIDE", "MANUAL_REVOKE"), db.query("SELECT event_type FROM race_schedule_event") { it.getString(1) }.toSet())
        // 履歴は追記専用: app が書けるのは INSERT だけ（DB の権限）
        assertDenied { db.asUser("app") { it.createStatement().executeUpdate("UPDATE race_schedule_event SET source = 'SYSTEM'") } }
        assertDenied { db.asUser("app") { it.createStatement().executeUpdate("DELETE FROM race_schedule_event") } }
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
        assertDenied { mltrain.botRuns.acquire(Fixtures.lease()) }
        assertDenied { mltrain.signals.insertIfAbsent(Fixtures.signal()) }
        assertDenied { mltrain.schedules.insertIfAbsent(Fixtures.schedule(), Fixtures.event(Fixtures.schedule())) }
        assertDenied { mltrain.raceDays.insertIfAbsent(Fixtures.day(), Fixtures.dayEvent()) }
        assertDenied { mltrain.overrides.create(Fixtures.manual(), Fixtures.overrideEvent()) }
        assertDenied { mltrain.events.append(Fixtures.staleEvent()) }
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
