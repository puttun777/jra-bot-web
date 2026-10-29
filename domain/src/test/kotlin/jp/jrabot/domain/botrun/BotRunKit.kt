package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseSettings
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.botrun.testing.InMemoryBotRunRepository
import jp.jrabot.domain.botrun.testing.InMemoryComboOdds
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
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.FixedMode
import jp.jrabot.domain.calendar.NoScheduleFetchFacts
import jp.jrabot.domain.decision.WinDecisionConfig
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.notify.DeliveryResult
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.Notification
import jp.jrabot.domain.notify.NotificationChannel
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.notify.Notifier
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 送られた通知を記録する偽物（合成データ専用。実 Discord ではない）。結果は [results] から順に返し、空なら [DeliveryResult.DELIVERED]。 */
class RecordingNotifier(
    private val clock: ManualClock,
) : Notifier {
    data class Sent(
        val at: Instant,
        val channel: NotificationChannel,
        val subject: String,
        val body: String,
    )

    val sent = mutableListOf<Sent>()
    val results = ArrayDeque<DeliveryResult>()
    var configured = true

    val signals get() = sent.filter { it.channel == NotificationChannel.SIGNAL }
    val errors get() = sent.filter { it.channel == NotificationChannel.ERROR }

    override fun canDeliver(channel: NotificationChannel): Boolean = configured

    override fun send(notification: Notification): DeliveryResult {
        sent += Sent(clock.now(), notification.channel, notification.subject, notification.body)
        // 結果の列は SIGNAL の通知だけに使う（エラー通知は常に成功）
        return if (notification.channel == NotificationChannel.SIGNAL) results.removeFirstOrNull() ?: DeliveryResult.DELIVERED else DeliveryResult.DELIVERED
    }
}

/**
 * bot-run の統合試験の部品一式（合成データ・メモリ上の偽物。実 DB・実 Discord・実データに触れない）。時間は [ManualClock] で、[pacer] が進める
 * （実時間の sleep を使わない）。設定値はテスト用の固定値で、運用の既定値ではない。
 *
 * 既定の 1 日: 2026-10-24（東京）R1〜R3 の発走 09:55 / 10:20 / 10:45。監視ウィンドウは 起動可 07:45・監視開始 07:55・締切 12:15。評価のタイミングは発走の 5 分前。
 */
class Kit(
    start: Instant = Cal.jst(Cal.DATE, 7, 50),
    val tenant: TenantId = Cal.T1,
    races: List<RaceSchedule> = Cal.races(count = 3),
    mlContext: MlRunContext = MlRunContext.DISABLED,
    config: BotRunConfig? = null,
    features: FeatureSource = InMemoryFeatureSource(),
    /** 判定の差し替え。null なら実物の [RaceEvaluator]。既定は固定の買い目を返す [StubJudge]（オーケストレーションの検証用。判定の数値は golden・単体試験が担う）。 */
    var judge: RaceJudge? = StubJudge(),
) {
    val clock = ManualClock(start)
    val mode = FixedMode(OpsMode.NORMAL)
    val runs = InMemoryBotRunRepository()
    val days = InMemoryRaceDays(listOf(Cal.day()))
    val schedules = InMemoryScheduleReader(races)
    val overrides = InMemoryOverrideReader()
    val odds = InMemoryOdds()
    val combos = InMemoryComboOdds()
    val signals = InMemorySignals(clock)
    val evaluations = InMemoryEvaluations()
    val timings =
        jp.jrabot.domain.botrun.testing
            .InMemoryTimingNotices()
    val notifier = RecordingNotifier(clock)
    val events = mutableListOf<String>()
    val config: BotRunConfig = config ?: defaultConfig(mlContext.modelType?.let { it })
    var stop = false
    var transient: (Throwable) -> Boolean = { it is TransientTestFailure }

    /** 待機のたびに呼ばれる（時計を進めた後）。テスト中の出来事（モード変更・SIGTERM・予定の変更）の注入に使う。 */
    var onPause: (Duration) -> Unit = {}

    /** true の間、待機のたびに全レースへ現在時刻の単勝オッズを追加する（取込 API が新しい観測を保存し続ける状況の再現）。 */
    var autoOdds = true
    val pauses = mutableListOf<Duration>()
    var ml: MlRunContext = mlContext
    val featureSource: FeatureSource = features
    var raceIds: List<RaceId> = races.map { it.raceId }

    val dispatcher =
        SignalNotificationDispatcher(signals, notifier, mode, clock, MentionPolicy.NONE, stopRequested = { stop })
    val errorReporter = ErrorReporter(notifier, clock, Duration.ofMinutes(5))

    private val pacer =
        Pacer { d, _ ->
            pauses += d
            clock.advance(d)
            if (autoOdds) feedOdds()
            onPause(d)
        }

    fun realJudge(mlc: MlRunContext = ml): RaceJudge = RaceEvaluator(config, odds, combos, featureSource, mlc)

    fun feedOdds(at: Instant = clock.now()) {
        raceIds.forEach { odds.add(snapshot(it, at)) }
    }

    fun orchestrator(
        mlFactory: (RunRequest) -> MlRunContext = { ml },
        pacerOverride: Pacer? = null,
    ): BotRunOrchestrator =
        BotRunOrchestrator(
            config = config,
            clock = clock,
            modes = mode,
            runs = runs,
            days = days,
            schedules = schedules,
            overrides = overrides,
            facts = NoScheduleFetchFacts,
            mlStarter = mlFactory,
            processorFactory = { req, mlc ->
                RaceCycleProcessor(
                    config,
                    req.tenantId,
                    req.origin,
                    odds,
                    judge ?: realJudge(mlc),
                    evaluations,
                    signals,
                    InMemoryEntries(raceIds.associateWith { mapOf(1 to "合成馬一", 2 to "合成馬二") }),
                    featureSource,
                    InMemoryRaces(),
                    dispatcher,
                    timings,
                    req.runId.value,
                )
            },
            dispatcher = dispatcher,
            notifiedProbe = SignalNotifiedProbe(signals),
            timings = timings,
            errors = errorReporter,
            pacer = pacerOverride ?: pacer,
            stopRequested = { stop },
            isTransient = { transient(it) },
            events = RunEvents { n, d -> events += "$n $d" },
        )

    fun request(
        origin: NotificationOrigin = NotificationOrigin.LIVE,
        trigger: BotRunTrigger = BotRunTrigger.SCHEDULER,
        owner: String = "owner-A",
        tenantId: TenantId = tenant,
    ) = RunRequest(tenantId, Cal.DATE, trigger, origin, owner, RunId.of(UUID.randomUUID()))

    fun run(req: RunRequest = request()): RunOutcome {
        if (autoOdds) feedOdds()
        return orchestrator().run(req)
    }

    fun at(
        h: Int,
        m: Int,
        s: Int = 0,
    ): Instant = Cal.jst(Cal.DATE, h, m, s)

    companion object {
        /** テスト用の設定値（運用の既定ではない）。 */
        fun defaultConfig(ml: jp.jrabot.domain.ml.pipeline.ProbabilityModelType? = null): BotRunConfig =
            BotRunConfig(
                lease = LeaseSettings(Duration.ofMinutes(5), Duration.ofMinutes(1)),
                calendar = CalendarSettings(Cal.WATCH, staleAfter = Duration.ofHours(12)),
                polling = PollingIntervals(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30)),
                snapshotHistoryLimit = 3,
                snapshot = SnapshotPolicy(listOf(5.0), TightenMode.OFF),
                maxOddsAge = Duration.ofSeconds(180),
                maxRuntime = Duration.ofHours(14),
                staleClaimAfter = Duration.ofMinutes(10),
                maxConsecutiveUnknown = 3,
                modeCheckInterval = Duration.ofSeconds(15),
                maxConsecutiveCycleFailures = 3,
                mlModelType = ml,
                decision = WinDecisionConfig.legacyFunctionDefaults().copy(notifySkips = true),
            )

        /** 本命が圧倒的に人気（オッズ 1.2）の合成レース。市場確率をクリップすると、他の馬の確率が上がって期待値が正になる（合成の都合。予測の良し悪しではない）。 */
        val ODDS = listOf(1.2, 8.0, 12.0, 15.0, 20.0, 30.0)

        fun snapshot(
            raceId: RaceId,
            at: Instant,
            tenant: TenantId = Cal.T1,
            odds: List<Double> = ODDS,
        ) = OddsSnapshot(
            tenant,
            raceId,
            Market.WIN,
            at,
            odds.mapIndexed { i, o -> OddsEntry(HorseNumber.of(i + 1), Odds.ofOrNull(o)!!, i + 1) },
        )
    }
}

/** 一時的な障害（DB 不達など）の合成。 */
class TransientTestFailure(
    message: String = "synthetic transient failure",
) : RuntimeException(message)
