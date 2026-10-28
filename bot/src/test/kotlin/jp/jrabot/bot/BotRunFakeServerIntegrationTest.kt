package jp.jrabot.bot

import jp.jrabot.bot.discord.DiscordNotifier
import jp.jrabot.bot.discord.FakeWebhookServer
import jp.jrabot.bot.discord.JdkWebhookTransport
import jp.jrabot.bot.discord.Reply
import jp.jrabot.bot.discord.Sleeper
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.BotRunConfig
import jp.jrabot.domain.botrun.BotRunOrchestrator
import jp.jrabot.domain.botrun.MlRunContext
import jp.jrabot.domain.botrun.NotifiedSignalProbe
import jp.jrabot.domain.botrun.Pacer
import jp.jrabot.domain.botrun.PollingIntervals
import jp.jrabot.domain.botrun.RaceCycleProcessor
import jp.jrabot.domain.botrun.RunExit
import jp.jrabot.domain.botrun.RunRequest
import jp.jrabot.domain.botrun.SnapshotPolicy
import jp.jrabot.domain.botrun.TightenMode
import jp.jrabot.domain.botrun.testing.InMemoryBotRunRepository
import jp.jrabot.domain.botrun.testing.InMemoryEntries
import jp.jrabot.domain.botrun.testing.InMemoryEvaluations
import jp.jrabot.domain.botrun.testing.InMemoryOdds
import jp.jrabot.domain.botrun.testing.InMemoryOverrideReader
import jp.jrabot.domain.botrun.testing.InMemoryRaceDays
import jp.jrabot.domain.botrun.testing.InMemoryRaces
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.InMemorySignals
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.NoScheduleFetchFacts
import jp.jrabot.domain.calendar.RaceDay
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.WatchSettings
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Zones
import org.junit.jupiter.api.AfterEach
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bot-run の全体ループ + **実際の Discord 通知クラス** + Fake HTTP サーバ（127.0.0.1）。実 Discord・実 DB・実データに触れない（合成のみ）。
 * 1 日（3 レース）を通して、通知が 1 レース 1 通 HTTP で届く・429 の待機・5xx は再送しない・URL を本文に出さない、を確かめる。
 */
class BotRunFakeServerIntegrationTest {
    private val server = FakeWebhookServer()
    private val sleeps = CopyOnWriteArrayList<Duration>()
    private val tenant = TenantId.of(1)
    private val date = LocalDate.of(2026, 10, 24)

    @AfterEach
    fun tearDown() = server.close()

    private fun jst(
        h: Int,
        m: Int,
    ): Instant = date.atTime(h, m).atZone(Zones.JST).toInstant()

    private inner class Day {
        val clock = ManualClock(jst(7, 50))
        val modes = FixedMode(OpsMode.NORMAL)
        val runs = InMemoryBotRunRepository()
        val signals = InMemorySignals(clock)
        val odds = InMemoryOdds()
        val evaluations = InMemoryEvaluations()
        val raceIds = (1..3).map { RaceId.of(date, Venue.TOKYO, it) }
        val schedules =
            raceIds.mapIndexed { i, id ->
                val post = date.atTime(LocalTime.of(9, 55).plusMinutes(25L * i)).atZone(Zones.JST).toInstant()
                RaceSchedule(tenant, id, post, false, RaceScheduleStatus.CARD, '2', "202610232", "R${i + 1}", Instant.parse("2026-10-23T03:00:00Z"))
            }
        val config =
            BotRunConfig(
                lease = LeaseSettings(Duration.ofMinutes(5), Duration.ofMinutes(1)),
                calendar = CalendarSettings(WatchSettings(Duration.ofMinutes(120), Duration.ofMinutes(10), Duration.ofMinutes(90)), Duration.ofHours(12)),
                polling = PollingIntervals(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30)),
                snapshotHistoryLimit = 3,
                snapshot = SnapshotPolicy(listOf(5.0), TightenMode.OFF),
                maxOddsAge = Duration.ofSeconds(180),
                maxRuntime = Duration.ofHours(14),
                staleClaimAfter = Duration.ofMinutes(10),
                maxConsecutiveUnknown = 3,
                modeCheckInterval = Duration.ofSeconds(15),
                maxConsecutiveCycleFailures = 3,
                mlModelType = null,
                decision = WinDecisionConfig.legacyFunctionDefaults(),
            )

        fun feed() =
            raceIds.forEach {
                odds.add(
                    OddsSnapshot(
                        tenant,
                        it,
                        Market.WIN,
                        clock.now(),
                        listOf(1.2, 8.0, 12.0).mapIndexed { i, o ->
                            OddsEntry(
                                HorseNumber.of(i + 1),
                                Odds.ofOrNull(o)!!,
                                i + 1,
                            )
                        },
                    ),
                )
            }

        fun run(
            origin: NotificationOrigin = NotificationOrigin.LIVE,
            scriptSleeper: Sleeper = Sleeper { sleeps += it },
        ): jp.jrabot.domain.botrun.RunOutcome {
            val url = server.url()
            val notifier = DiscordNotifier(url, url, MentionPolicy.NONE, JdkWebhookTransport(Duration.ofSeconds(2), Duration.ofSeconds(2)), scriptSleeper)
            val dispatcher = SignalNotificationDispatcher(signals, notifier, modes, clock, MentionPolicy.NONE)
            val judge = StubJudge()
            val calendarDays =
                InMemoryRaceDays(listOf(RaceDay(tenant, date, Venue.TOKYO, 4, 8, RaceDayStatus.CONFIRMED, null, jst(6, 0).minusSeconds(86_400), jst(6, 0))))
            val orchestrator =
                BotRunOrchestrator(
                    config,
                    clock,
                    modes,
                    runs,
                    calendarDays,
                    InMemoryScheduleReader(schedules),
                    InMemoryOverrideReader(),
                    NoScheduleFetchFacts,
                    mlStarter = { MlRunContext.DISABLED },
                    processorFactory = { req, _ ->
                        RaceCycleProcessor(
                            config,
                            req.tenantId,
                            req.origin,
                            odds,
                            judge,
                            evaluations,
                            signals,
                            InMemoryEntries(),
                            InMemoryFeatureSource(),
                            InMemoryRaces(),
                            dispatcher,
                        )
                    },
                    dispatcher = dispatcher,
                    notifiedProbe = NotifiedSignalProbe { t, r -> signals.listByRace(t, r).any { it.state != NotificationState.UNSENT } },
                    errors = ErrorReporter(notifier, clock),
                    pacer =
                        Pacer { d, _ ->
                            clock.advance(d)
                            feed()
                        },
                    stopRequested = { false },
                )
            feed()
            return orchestrator.run(RunRequest(tenant, date, BotRunTrigger.SCHEDULER, origin, "test-owner", RunId.of(UUID.randomUUID())))
        }
    }

    @Test
    fun `1 日を通して Fake の Discord サーバへ 1 レース 1 通 HTTP で届き Signal は SENT になる`() {
        val d = Day()
        val out = d.run()
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(BotRunState.SUCCEEDED, out.state)
        assertEquals(3, server.requests.size)
        for (req in server.requests) {
            assertEquals("POST", req.method)
            assertEquals("/api/webhooks/123456789012345678/fake-token_123", req.path)
            val text = req.content()
            assertTrue(text.contains("単勝"), text)
            assertFalse(text.contains("fake-token_123"), "本文に Webhook のトークンを出さない")
            assertFalse(text.contains("http"), "本文に URL を出さない")
        }
        assertTrue(d.signals.all().all { it.state == NotificationState.SENT })
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `429 は Retry-After を待って再試行し 成功すれば SENT 待機は実際には眠らない`() {
        server.script(Reply.Status(429, """{"retry_after": 1.5}""", mapOf("Retry-After" to "2")))
        val d = Day()
        d.run()
        assertEquals(4, server.requests.size) // R1 は 429 の後に再送（同じ通知の再試行）+ R2・R3
        assertEquals(listOf(Duration.ofMillis(1500)), sleeps)
        assertTrue(d.signals.all().all { it.state == NotificationState.SENT })
    }

    @Test
    fun `5xx は再送せず SEND_UNKNOWN になり 運用者向けのエラー通知が出る`() {
        server.script(Reply.Status(503))
        val d = Day()
        d.run()
        val r1 = d.signals.all().filter { it.raceId == d.raceIds[0] }
        assertTrue(r1.all { it.state == NotificationState.SEND_UNKNOWN })
        // R1 の 1 通 + エラー通知 1 通 + R2・R3
        assertEquals(4, server.requests.size)
        assertTrue(server.requests.any { it.content().contains("エラー") })
        assertTrue(sleeps.isEmpty()) // 5xx は待って再試行しない
    }

    @Test
    fun `BACKFILL の run は Fake サーバへ何も送らない`() {
        val d = Day()
        val out = d.run(NotificationOrigin.BACKFILL)
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(0, server.requests.size)
        assertTrue(d.signals.all().all { it.state == NotificationState.UNSENT })
    }
}
