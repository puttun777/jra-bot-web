package jp.jrabot.persistence.integration

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.BotRunConfig
import jp.jrabot.domain.botrun.BotRunOrchestrator
import jp.jrabot.domain.botrun.MlRunContext
import jp.jrabot.domain.botrun.Pacer
import jp.jrabot.domain.botrun.PollingIntervals
import jp.jrabot.domain.botrun.RaceCycleProcessor
import jp.jrabot.domain.botrun.RaceDayAdvancerImpl
import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.botrun.ResultWaitConfig
import jp.jrabot.domain.botrun.RunExit
import jp.jrabot.domain.botrun.RunRequest
import jp.jrabot.domain.botrun.SignalNotifiedProbe
import jp.jrabot.domain.botrun.SnapshotPolicy
import jp.jrabot.domain.botrun.StoreResultAvailability
import jp.jrabot.domain.botrun.TightenMode
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.RaceDayUpdater
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.calendar.WatchSettings
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.notify.DeliveryResult
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.notify.Notifier
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.performance.PerformanceReadService
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.signal.NotificationState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * bot-run の全体ループを**本物の MySQL**（Testcontainers）と**最小権限の `bot` ユーザー**で通す（GRANT の過不足の確認を兼ねる）。
 * 取込側の書込み（races・odds）は `migrate` ユーザーで用意する。通知は偽物（実 Discord へは送らない）。時間は手で進める。合成データのみ。
 */
@Tag("integration")
class BotRunOrchestratorIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val seed = RepositoryHarness(db, user = "migrate", poolMax = 4)
        private val bot = RepositoryHarness(db, user = "bot", poolMax = 6)

        @JvmStatic
        @AfterAll
        fun close() {
            bot.close()
            seed.close()
        }
    }

    private val raceId = Fixtures.raceId()
    private val postAt = Instant.parse("2026-10-24T06:40:00Z") // JST 15:40

    @BeforeEach
    fun reset() {
        db.resetData()
        seed.races.upsert(Fixtures.race(T1, raceId))
        val day = Fixtures.day(status = RaceDayStatus.CONFIRMED)
        seed.raceDays.insertIfAbsent(day, Fixtures.dayEvent())
        val schedule = Fixtures.schedule(raceId = raceId, postAt = postAt, status = RaceScheduleStatus.CARD)
        seed.schedules.insertIfAbsent(schedule, Fixtures.event(schedule))
    }

    private val notified = CopyOnWriteArrayList<String>()

    private val notifier =
        object : Notifier {
            override fun send(notification: jp.jrabot.domain.notify.Notification): DeliveryResult {
                notified += notification.body
                return DeliveryResult.DELIVERED
            }
        }

    private fun config() =
        BotRunConfig(
            lease = LeaseSettings(Duration.ofMinutes(5), Duration.ofMinutes(1)),
            calendar = CalendarSettings(WatchSettings(Duration.ofMinutes(120), Duration.ofMinutes(10), Duration.ofMinutes(90)), Duration.ofHours(12)),
            polling = PollingIntervals(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30)),
            snapshotHistoryLimit = 3,
            snapshot = SnapshotPolicy(listOf(30.0, 5.0), TightenMode.OFF),
            maxOddsAge = Duration.ofSeconds(180),
            maxRuntime = Duration.ofHours(14),
            staleClaimAfter = Duration.ofMinutes(10),
            maxConsecutiveUnknown = 3,
            modeCheckInterval = Duration.ofSeconds(15),
            maxConsecutiveCycleFailures = 3,
            mlModelType = null,
            decision = WinDecisionConfig.legacyFunctionDefaults(),
        )

    private fun run(
        start: Instant = Instant.parse("2026-10-24T04:40:00Z"), // JST 13:40（起動可 13:30〜）
        origin: NotificationOrigin = NotificationOrigin.LIVE,
        owner: String = "owner-1",
        resultWait: ResultWaitConfig? = null,
    ): jp.jrabot.domain.botrun.RunOutcome {
        val clock = ManualClock(start)
        val cfg = config().copy(resultWait = resultWait)
        val modes = ModeProvider { OpsMode.NORMAL }
        val signals = bot.signals
        val dispatcher = SignalNotificationDispatcher(signals, notifier, modes, clock, MentionPolicy.NONE)
        val evaluations = bot.bean(RaceEvaluationWriter::class.java)
        val entries = bot.bean(RaceEntryReader::class.java)
        val timings = bot.bean(jp.jrabot.domain.botrun.TimingNoticeStore::class.java)
        val features = bot.bean(FeatureSource::class.java)

        fun feed() {
            // 取込側が新しい観測を保存し続ける状況（migrate ユーザーで書く）
            if (clock.now() <
                postAt
            ) {
                seed.odds.upsert(Fixtures.odds(raceId = raceId, observedAt = clock.now(), values = arrayOf(1 to 1.2, 2 to 8.0, 3 to 12.0)))
            }
            // 取込側が結果・確定払戻を保存する（発走の 35 分後）。2 番が 1 着、単勝 800 円
            if (resultWait != null && !clock.now().isBefore(postAt.plus(Duration.ofMinutes(35))) && seed.results.list(T1, raceId).isEmpty()) {
                seed.results.upsertAll(T1, raceId, listOf(ResultEntry(2, 1), ResultEntry(1, 2), ResultEntry(3, 3)))
                seed.payouts.upsertAll(T1, raceId, Market.WIN, listOf(PayoutRow("02", 800.0)), provisional = false)
            }
        }
        val orchestrator =
            BotRunOrchestrator(
                cfg,
                clock,
                modes,
                bot.botRuns,
                bot.raceDays,
                bot.schedules,
                bot.overrides,
                bot.bean(ScheduleFetchFactReader::class.java),
                mlStarter = { MlRunContext.DISABLED },
                processorFactory = { req, _ ->
                    RaceCycleProcessor(
                        cfg,
                        req.tenantId,
                        req.origin,
                        bot.odds,
                        StubJudge(),
                        evaluations,
                        signals,
                        entries,
                        features,
                        bot.races,
                        dispatcher,
                        timings,
                        req.runId.value,
                    )
                },
                dispatcher = dispatcher,
                notifiedProbe = SignalNotifiedProbe(signals),
                timings = timings,
                errors = ErrorReporter(notifier, clock),
                pacer =
                    Pacer { d, _ ->
                        clock.advance(d)
                        feed()
                    },
                stopRequested = { false },
                availability = StoreResultAvailability(bot.results, bot.payouts),
                advancer = RaceDayAdvancerImpl(bot.raceDays, RaceDayUpdater(bot.raceDays, clock)),
            )
        feed()
        return orchestrator.run(RunRequest(T1, Fixtures.DATE, BotRunTrigger.SCHEDULER, origin, owner, RunId.of(UUID.randomUUID())))
    }

    @Test
    fun `開催日の 1 レースを通す リース 評価 Signal 通知 checkpoint を最小権限の bot ユーザーで`() {
        val out = run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
        assertEquals("SUCCEEDED", db.scalar("SELECT state FROM bot_run"))
        assertEquals(null, db.scalar("SELECT active_slot FROM bot_run")) // リース解放
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations")) // 30 分前と 5 分前
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations WHERE model_type_used = 'market'"))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations WHERE fallback_reason = 'ML_DISABLED'"))
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals"))
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals WHERE notify_state = 'SENT'"))
        assertEquals(2, notified.size)
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run WHERE JSON_UNQUOTE(JSON_EXTRACT(checkpoint, '\$.races.\"${raceId.value}\".stage')) = 'OFF'"))
        // V5: 評価タイミングごとに確定した記録（30 分前 = 3000、5 分前 = 500）と、通知内容のスナップショット
        assertEquals(listOf("500", "3000"), db.query("SELECT timing_centi FROM race_timing_notice ORDER BY timing_centi") { it.getString(1) })
        assertEquals(
            2,
            db.count("SELECT COUNT(*) FROM race_timing_notice WHERE JSON_VALID(notice_json) AND notice_schema = 1 AND CHAR_LENGTH(notice_sha256) = 64"),
        )
    }

    @Test
    fun `通し — 監視と通知のあと結果と払戻の取込を待ち 開催日が完了し 紙上成績が MySQL 上で計算できる`() {
        val out = run(resultWait = ResultWaitConfig(Duration.ofMinutes(30), Duration.ofMinutes(5)))
        assertEquals("ALL_SETTLED", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertEquals("COMPLETED", db.scalar("SELECT status FROM race_day"))
        // 履歴: RUNNING → SETTLING → COMPLETED が SYSTEM の DAY_STATUS として残る（最小権限の bot ユーザーで INSERT できた）
        assertEquals(3, db.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'DAY_STATUS' AND source = 'SYSTEM'"))
        val service =
            PerformanceReadService(bot.schedules, bot.signals, bot.bean(jp.jrabot.domain.botrun.TimingNoticeStore::class.java), bot.results, bot.payouts)
        val r = service.report(T1, Fixtures.DATE, Fixtures.DATE)
        // StubJudge は 2 番（推奨額 3・オッズ 8）と 4 番（推奨額 1）を推奨し、30 分前と 5 分前の 2 回通知する。代表集計は馬ごとに発走前の最新 1 件
        assertEquals(2, r.win.count)
        assertEquals(1, r.win.hits)
        assertEquals(4.0, r.win.totalStake)
        assertEquals(3.0 * 800.0 / 100.0, r.win.actualPayout)
        assertEquals(3.0 * 8.0, r.win.paperPayout)
        assertEquals(0, r.winUnsettled)
    }

    @Test
    fun `checkpoint が失われ 新しいオッズが届いても 同じタイミングは再通知しない V5 の確定記録が正`() {
        run()
        assertEquals(2, notified.size)
        db.exec("UPDATE bot_run SET checkpoint = NULL")
        val again = run(owner = "owner-2") // 発走前から再実行（新しいオッズが届き続ける）
        assertEquals("ALL_RACES_OFF", again.reason)
        assertEquals(2, notified.size, "再通知なし")
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations"))
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals"))
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_timing_notice"))
    }

    @Test
    fun `完了後の再実行は何も増やさない 評価も Signal も通知も`() {
        run()
        val again = run(start = Instant.parse("2026-10-24T06:50:00Z"), owner = "owner-2") // 発走後
        assertEquals("ALL_RACES_OFF", again.reason)
        assertEquals(2, db.count("SELECT COUNT(*) FROM race_evaluations"))
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals"))
        assertEquals(2, notified.size)
        assertEquals(2, db.count("SELECT COUNT(*) FROM bot_run"))
    }

    @Test
    fun `BACKFILL は評価と Signal を記録するが通知しない`() {
        val out = run(origin = NotificationOrigin.BACKFILL)
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(4, db.count("SELECT COUNT(*) FROM signals WHERE notify_state = 'UNSENT'"))
        assertEquals(0, notified.size)
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_timing_notice"), "BACKFILL は本番の通知タイミングを消費しない")
    }

    @Test
    fun `有効なリースがあれば 2 つ目は始まらない DB の UNIQUE が最後の防壁`() {
        val first = bot.botRuns.acquire(Fixtures.lease(now = Instant.parse("2026-10-24T04:39:00Z")))
        assertEquals(true, first is jp.jrabot.domain.bot.AcquireResult.Acquired)
        val out = run()
        assertEquals("ALREADY_ACTIVE", out.reason)
        assertEquals(1, db.count("SELECT COUNT(*) FROM bot_run"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM race_evaluations"))
    }

    @Test
    fun `他テナントのデータは読まず 書かない`() {
        db.exec("UPDATE race_day SET tenant_id = 2") // 開催日が T2 のもの = T1 には予定が無い
        val out = run()
        assertEquals(RunExit.TEMPFAIL, out.exitCode) // 開催日が確認できない = 要確認（fail closed）
        assertEquals(true, out.reason in setOf("SCHEDULE_UNKNOWN", "SCHEDULE_UNCONFIRMED", "SCHEDULE_STALE"), out.reason)
        assertEquals(0, db.count("SELECT COUNT(*) FROM bot_run"))
    }
}
