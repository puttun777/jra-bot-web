package jp.jrabot.bot

import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.botrun.BotRunOrchestrator
import jp.jrabot.domain.botrun.MlRunContext
import jp.jrabot.domain.botrun.MlRunStarter
import jp.jrabot.domain.botrun.Pacer
import jp.jrabot.domain.botrun.RaceCycleProcessor
import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.botrun.RaceEvaluator
import jp.jrabot.domain.botrun.RunEvents
import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.model.ModelLoader
import jp.jrabot.domain.ml.model.ModelTrust
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.ComboOddsSnapshotReader
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.race.RaceReader
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import org.slf4j.LoggerFactory
import java.time.Duration

/** `bot-run` の部品を接続する（Spring の Bean から渡された実装を [BotRunOrchestrator] へ）。ML のモデルの読込みは run の開始時（[MlRunStarter]）まで遅延する。 */
internal object BotRunWiring {
    private val log = LoggerFactory.getLogger("jp.jrabot.bot.BotRun")

    @Suppress("LongParameterList")
    fun orchestrator(
        settings: BotRunSettings,
        clock: Clock,
        modes: ModeProvider,
        runs: BotRunRepository,
        days: RaceDayReader,
        schedules: RaceScheduleReader,
        overrides: CalendarOverrideReader,
        facts: ScheduleFetchFactReader,
        odds: OddsSnapshotReader,
        combos: ComboOddsSnapshotReader,
        races: RaceReader,
        evaluations: RaceEvaluationWriter,
        entries: RaceEntryReader,
        signals: SignalRepository,
        registry: ModelRegistryReader,
        features: FeatureSource,
        dispatcher: SignalNotificationDispatcher,
        errors: ErrorReporter,
        stop: StopFlag,
    ): BotRunOrchestrator {
        val config = settings.run
        return BotRunOrchestrator(
            config = config,
            clock = clock,
            modes = modes,
            runs = runs,
            days = days,
            schedules = schedules,
            overrides = overrides,
            facts = facts,
            mlStarter = { req -> startMl(settings, req.tenantId, registry, clock) },
            processorFactory = { req, ml ->
                RaceCycleProcessor(
                    config, req.tenantId, req.origin, odds, RaceEvaluator(config, odds, combos, features, ml),
                    evaluations, signals, entries, features, races, dispatcher,
                )
            },
            dispatcher = dispatcher,
            errors = errors,
            pacer = SleepPacer(),
            stopRequested = stop::isRequested,
            isTransient = BotRunTaskHandler::isTransient,
            events = RunEvents { name, detail -> log.info("BOT_RUN_EVENT name={} {}", name, detail) },
        )
    }

    private fun startMl(
        settings: BotRunSettings,
        tenantId: TenantId,
        registry: ModelRegistryReader,
        clock: Clock,
    ) = settings.run.mlModelType.let { type ->
        if (type == null) {
            MlRunContext.DISABLED
        } else {
            val uri = checkNotNull(settings.modelStoreUri) { "MODEL_STORE_URI（BotRunSettingsParser が検証済み）" }
            val key = checkNotNull(settings.modelVerifyPubKey) { "MODEL_VERIFY_PUBKEY（BotRunSettingsParser が検証済み）" }
            MlRunStarter.start(type, tenantId, registry, ModelStoreFactory.create(uri), ModelLoader(ModelTrust.fromBase64(key)), clock)
        }
    }
}

/** 実時間の待機。[stopRequested] を 0.5 秒ごとに確認し、立ったら早く戻る。 */
class SleepPacer(
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) : Pacer {
    override fun pause(
        duration: Duration,
        stopRequested: () -> Boolean,
    ) {
        val end = nanoTime() + duration.toNanos()
        while (!stopRequested()) {
            val remaining = end - nanoTime()
            if (remaining <= 0) return
            sleepMillis(minOf(SLICE_MILLIS, maxOf(1L, remaining / 1_000_000)))
        }
    }

    private companion object {
        const val SLICE_MILLIS = 500L
    }
}
