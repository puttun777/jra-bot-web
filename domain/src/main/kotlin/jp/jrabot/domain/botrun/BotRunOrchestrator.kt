package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRun
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.calendar.ActiveRun
import jp.jrabot.domain.calendar.CalendarCheckPolicy
import jp.jrabot.domain.calendar.CalendarOverrideReader
import jp.jrabot.domain.calendar.CheckOutcome
import jp.jrabot.domain.calendar.DayVerdict
import jp.jrabot.domain.calendar.EffectiveCalendar
import jp.jrabot.domain.calendar.EffectiveDay
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.calendar.WatchWindow
import jp.jrabot.domain.notify.ErrorReporter
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 1 回の bot-run の依頼（引数の検証済みの値）。[owner] は実行ホストの識別（コンテナ・execution ID。1〜100 文字）。 */
data class RunRequest(
    val tenantId: TenantId,
    val date: LocalDate,
    val trigger: BotRunTrigger,
    /** 通知の出どころ。LIVE 以外・判定不能では通知しない（BACKFILL・UNDETERMINED）。 */
    val origin: NotificationOrigin,
    val owner: String,
    val runId: RunId,
)

/**
 * bot-run の結果。[exitCode] は契約の終了コード（0 / 1 / 75。[RunExit]）、[reason] は固定語彙（値・秘密を含めない）、[state] は `bot_run` に記録した終了の状態
 * （リースを取得できなかった・失った場合は null）。
 */
data class RunOutcome(
    val exitCode: Int,
    val reason: String,
    val state: BotRunState?,
    val runId: RunId?,
)

/** bot-run 1 回の実行の口（実装は [BotRunOrchestrator]。`:bot` のハンドラは、テストでこれを差し替える）。 */
fun interface BotRunRunner {
    fun run(request: RunRequest): RunOutcome
}

/** 構造化ログへの出口（`:bot` が slf4j に出す）。値・秘密・URL を含めない。 */
fun interface RunEvents {
    fun event(
        name: String,
        detail: String,
    )

    companion object {
        val NONE = RunEvents { _, _ -> }
    }
}

/**
 * bot-run の業務ループ（T3.8）。**既存の部品を正しい順序で接続するだけ**で、判定の数値・状態遷移表・リースの規則は変えない。
 *
 * ```
 * 運用モード → 開催日の検証 → リース取得（bot_run の UNIQUE が最終防壁）→ 開催日の再検証（STARTING）→ 前回の checkpoint の復元 →
 * 古い claim の回収 → ML の固定（run 単位）→ RUNNING →
 *   ┌ 停止要求 / 最大稼働 / 運用モード / heartbeat ┐
 *   │ 開催日の再導出 → 監視対象の決定 → レースごとの判定・記録・通知 → checkpoint 保存 → 待機 │ を繰り返す
 * → DRAINING → 終了の状態（SUCCEEDED / STOPPED_BY_MODE / INTERRUPTED / ABORTED / FAILED）+ checkpoint → リース解放（終了の状態への遷移が解放）
 * ```
 *
 * - **終了条件**（上から評価）: 停止要求（SIGTERM → INTERRUPTED）、最大稼働（ABORTED）、運用モード（確定した非 NORMAL → STOPPED_BY_MODE exit 0 /
 *   UNKNOWN が連続 → STOPPED_BY_MODE exit 75）、リース喪失（何も書かず exit 75）、DENY_RUN・中止・延期（開催日の状態）、全レースが OFF または中止（SUCCEEDED。**結果・払戻・決済は
 *   T3.9 で、ここでは扱わない**）、監視ウィンドウの終わり（未完了なら FAILED）。
 * - 状態は DB のスケジュールと現在時刻から**毎サイクル再導出**する。checkpoint は『発火済みのタイミング』と進行の記録で、メモリに依存しない。
 * - 例外: DB 等の一時的な障害（[isTransient]）はサイクルの失敗として数え、[BotRunConfig.maxConsecutiveCycleFailures] 回続いたら終了（exit 75）。
 *   その他の予期しない例外は FAILED（exit 1）。レース単位の失敗は他のレースを止めない。
 * - 待機は [Pacer]、時刻は [Clock]。SIGTERM は [stopRequested]。
 */
class BotRunOrchestrator(
    private val config: BotRunConfig,
    private val clock: Clock,
    private val modes: ModeProvider,
    private val runs: BotRunRepository,
    private val days: RaceDayReader,
    private val schedules: RaceScheduleReader,
    private val overrides: CalendarOverrideReader,
    private val facts: ScheduleFetchFactReader,
    private val mlStarter: (RunRequest) -> MlRunContext,
    private val processorFactory: (RunRequest, MlRunContext) -> RaceCycleProcessor,
    private val dispatcher: SignalNotificationDispatcher,
    private val notifiedProbe: NotifiedSignalProbe,
    private val timings: TimingNoticeStore,
    private val errors: ErrorReporter,
    private val pacer: Pacer,
    private val stopRequested: () -> Boolean,
    private val isTransient: (Throwable) -> Boolean = { false },
    private val events: RunEvents = RunEvents.NONE,
    /** 通知の送信の直前にリースを確認する窓口（Discord 通知の `sendAllowed` と共有する）。 */
    private val leaseProbe: LeaseProbe = LeaseProbe(),
    /** 発走後の結果の取り込み状況（T3.9。`config.resultWait` が有効なときに使う）。 */
    private val availability: ResultAvailability = ResultAvailability.NONE,
    /** 開催日の状態（RUNNING → SETTLING → COMPLETED / INCOMPLETE）を進める（LIVE の run だけ）。 */
    private val advancer: RaceDayAdvancer = RaceDayAdvancer.NONE,
) : BotRunRunner {
    override fun run(request: RunRequest): RunOutcome =
        try {
            runGuarded(request)
        } catch (e: Exception) {
            val transient = isTransient(e)
            events.event("RUN_ABORTED_BEFORE_LEASE", e.javaClass.simpleName)
            RunOutcome(if (transient) RunExit.TEMPFAIL else RunExit.FAILURE, if (transient) "DB_UNAVAILABLE" else "RUN_FAILED_BEFORE_LEASE", null, null)
        }

    private fun runGuarded(req: RunRequest): RunOutcome {
        val startMode = modes.current()
        if (!startMode.allowsWork) return modeOutcome(startMode)
        val first = startGate(req, clock.now(), startMode, deriveDay(req, clock.now()))
        if (first != null) return first
        // 必要な評価機会を失って終えた日は、もう評価できない（全レースが発走後）。同じ日を再実行して何度も FAILED・警告を繰り返さない（exit 0 で何もしない）
        if (runs.listByDate(req.tenantId, req.date).any { it.state == BotRunState.FAILED && it.exitReason == REASON_OPPORTUNITY_LOST }) {
            events.event("LOSS_ALREADY_RECORDED", req.date.toString())
            return RunOutcome(RunExit.OK, "EVALUATION_LOSS_ALREADY_RECORDED", null, null)
        }

        val acquired =
            runs.acquire(LeaseRequest(req.tenantId, req.runId, req.date, req.trigger, req.owner, clock.now(), config.lease.duration))
        val run: BotRun =
            when (acquired) {
                is AcquireResult.Acquired -> {
                    acquired.run
                }

                is AcquireResult.AlreadyActive -> {
                    warnIfBlockedByNonLive(req, acquired)
                    return RunOutcome(RunExit.OK, "ALREADY_ACTIVE", null, null)
                }

                AcquireResult.Contended -> {
                    return RunOutcome(RunExit.TEMPFAIL, "LEASE_CONTENDED", null, null)
                }
            }
        events.event("LEASE_ACQUIRED", "takenOver=${acquired.takenOver != null}")
        return Session(req, LeaseKeeper(runs, req.owner, clock, config.lease, run)).execute()
    }

    /**
     * G-34: LIVE の bot-run が、LIVE 以外（BACKFILL / RECONCILE / 出どころ不明）の実行中の run に塞がれて起動できないとき、**黙って終わらず警告する**
     * （その間は開催日の通知が止まる）。LIVE どうしの二重起動は正常なので警告しない。通知の失敗は本処理を止めない。
     */
    private fun warnIfBlockedByNonLive(
        req: RunRequest,
        active: AcquireResult.AlreadyActive,
    ) {
        if (req.origin != NotificationOrigin.LIVE) return
        try {
            val run = runs.listByDate(req.tenantId, req.date).firstOrNull { it.runId == active.activeRunId } ?: return
            val checkpointOrigin = RunCheckpoint.parse(run.checkpoint)?.origin
            val nonLive =
                run.trigger == BotRunTrigger.BACKFILL || run.trigger == BotRunTrigger.RECONCILE ||
                    (checkpointOrigin != null && checkpointOrigin != NotificationOrigin.LIVE.name)
            if (!nonLive) return
            events.event("LIVE_BLOCKED_BY_NON_LIVE_RUN", "trigger=${run.trigger} origin=${checkpointOrigin ?: "unknown"}")
            errors.report(
                "bot-run: LIVE_BLOCKED",
                "実行中の LIVE 以外の bot-run（BACKFILL/RECONCILE 等）があるため、LIVE の bot-run を起動できず、その間の通知は止まります",
                "trigger=${run.trigger} leaseExpiresAt=${active.leaseExpiresAt}",
            )
        } catch (_: Exception) {
            // 警告の失敗で終了の結果（ALREADY_ACTIVE）を変えない
        }
    }

    /**
     * 開始時の検証（運用モードは呼び出し側）。曜日・祝日は使わず、DB の有効予定（[EffectiveDay]）と [CalendarCheckPolicy] で判定する（calendar-check と同じ規則）。
     * 起動してよいなら null、そうでなければ終了の結果: 起動しないのが正常（開催なし・中止・延期・窓の外・DENY_RUN・完了済み）= exit 0、
     * 要確認（予定が不明・古い・未確認・発走時刻なし）= exit 75（fail closed）。
     */
    private fun startGate(
        req: RunRequest,
        now: Instant,
        mode: OpsMode,
        day: EffectiveDay,
    ): RunOutcome? {
        val today = now.atZone(clock.zone).toLocalDate()
        val d = CalendarCheckPolicy.decide(now, req.date, today, mode, day, ActiveRun.None, config.calendar.watch)
        return when (d.reason.outcome) {
            CheckOutcome.LAUNCH -> null
            CheckOutcome.NO_LAUNCH -> RunOutcome(RunExit.OK, d.reason.name, null, null)
            CheckOutcome.ATTENTION -> RunOutcome(RunExit.TEMPFAIL, d.reason.name, null, null)
        }
    }

    private fun modeOutcome(mode: OpsMode): RunOutcome =
        if (mode == OpsMode.UNKNOWN) {
            RunOutcome(RunExit.TEMPFAIL, ModeGate.REASON_MODE_UNKNOWN, null, null)
        } else {
            RunOutcome(RunExit.OK, "MODE_${mode.name}", null, null)
        }

    private fun deriveDay(
        req: RunRequest,
        now: Instant,
    ): EffectiveDay =
        EffectiveCalendar.derive(
            req.tenantId,
            req.date,
            now,
            config.calendar,
            days.listByDate(req.tenantId, req.date),
            schedules.listByDate(req.tenantId, req.date),
            overrides.listByDate(req.tenantId, req.date),
            facts.latestSuccess(req.tenantId, req.date),
        )

    private sealed interface Finish {
        val state: BotRunState
        val reason: String
        val exitCode: Int
        val viaDraining: Boolean
        val alert: Boolean
    }

    private data class Done(
        override val state: BotRunState,
        override val reason: String,
        override val exitCode: Int,
        override val viaDraining: Boolean = true,
        override val alert: Boolean = false,
    ) : Finish

    private class CycleResult(
        val finish: Finish?,
        val tick: Duration,
    )

    /** 1 回の run の状態（リース取得後）。 */
    private inner class Session(
        val req: RunRequest,
        val keeper: LeaseKeeper,
    ) {
        private var checkpoint = RunCheckpoint.EMPTY
        private val modeGate = ModeGate(modes, config.maxConsecutiveUnknown)
        private var cachedWindow: WatchWindow? = null
        private var leaseLost: String? = null
        private val reported = HashSet<String>()

        /** 全レースが発走した後の結果待ちに入っている（[settleCycle]）。 */
        private var settlePhase = false

        /** checkpoint を信頼できず、二重通知を避けるため通知を止めたレース（[restore]）。 */
        private val quarantined = HashSet<RaceId>()
        private lateinit var processor: RaceCycleProcessor

        fun execute(): RunOutcome {
            leaseProbe.bind(::leaseHeld)
            return try {
                start() ?: loop()
            } catch (e: Exception) {
                crashed(e)
            } finally {
                leaseProbe.unbind()
            }
        }

        /**
         * 新しい評価・通知タイミングの確定・送信を始めてよいか。リースを失っていれば false（以後この run は何も始めない）。期限は手元の値で厳密に見る
         * （[LeaseKeeper.holdsNow]）。失ったと分かったら [leaseLost] を立て、ループが止まる。
         */
        private fun leaseHeld(): Boolean {
            if (leaseLost != null) return false
            (keeper.holdsNow() as? LeaseStatus.Lost)?.let { leaseLost = it.detail }
            return leaseLost == null
        }

        /** STARTING の処理。終えるべきなら結果を返す。続けるなら null。 */
        private fun start(): RunOutcome? {
            val mode = modes.current()
            val now = clock.now()
            val verdict = if (mode.allowsWork) startGate(req, now, mode, deriveDay(req, now)) else modeOutcome(mode)
            if (verdict != null) return finishStarting(verdict)
            checkpoint = restore()
            recoverStaleClaims()
            val ml = startMl()
            processor = processorFactory(req, ml)
            val s = keeper.transition(BotRunState.RUNNING, null, null)
            if (s is LeaseStatus.Lost) return lost(s)
            events.event("RUNNING", "cycle=${checkpoint.cycle}")
            if (req.origin == NotificationOrigin.LIVE) {
                advancer.advance(req.tenantId, req.date, RaceDayStatus.RUNNING, "run:${req.runId.value}")
                detectCommittedButUnsent()
            }
            return null
        }

        /**
         * M2: 通知タイミングを確定したのに Signal が UNSENT のまま（確定の直後に落ちた・checkpoint の保存に失敗した等）のレースを検出し、**1 回だけ警告する**。
         * **再送はしない**（D-18: 確定済みは at-most-once。二重通知より取りこぼしを選ぶ）。運用者は管理画面・Discord で確認する。
         */
        private fun detectCommittedButUnsent() {
            val affected = mutableListOf<RaceId>()
            for (race in deriveDay(req, clock.now()).races) {
                val committed = timings.list(req.tenantId, race.raceId).map { it.oddsObservedAt }.toSet()
                if (committed.isEmpty()) continue
                if ((notifiedProbe.unsentObservations(req.tenantId, race.raceId) intersect committed).isNotEmpty()) affected += race.raceId
            }
            if (affected.isEmpty()) return
            events.event("COMMITTED_BUT_UNSENT", "races=${affected.size}")
            report(
                "COMMITTED_BUT_UNSENT",
                "通知のタイミングを確定したのに送信されていない通知があります（自動では再送しません）。内容は管理画面で確認してください",
                "races=${affected.size} first=${affected.first().value}",
            )
        }

        private fun finishStarting(verdict: RunOutcome): RunOutcome {
            val s = keeper.transition(BotRunState.ABORTED, verdict.reason, null)
            if (s is LeaseStatus.Lost) return lost(s)
            return RunOutcome(verdict.exitCode, verdict.reason, BotRunState.ABORTED, keeper.run.runId)
        }

        /**
         * 発火済みのタイミングを復元する。**正は DB の確定記録（`race_timing_notice`）**で、前の run の checkpoint（INTERRUPTED・奪取された run を含む）は進行のヒント。
         * checkpoint が欠損・破損・古くても、DB に確定したタイミングは必ず発火済みとして扱う（同じタイミングを新しいオッズで再通知しない）。
         *
         * **初回起動の空 checkpoint** と **異常（記録があるのに復元できない）** を区別する:
         * - 前の run が無い、または checkpoint が null の run だけ: 正常（通知の前に必ず確定・checkpoint を保存するので、null の run は何も通知していない）。
         * - V5 より前の版が送った通知（確定の記録が無い）: DB に『通知を試みた Signal』があり、その観測時刻が確定記録で説明できず、checkpoint にもそのレースの発火記録が
         *   無い（または最新の checkpoint が壊れている）レースは、どのタイミングを通知したか分からないので**この run では通知を止める**（[quarantined]。他のレースは続ける）。
         *   必要な評価機会を失ったまま終わるなら FAILED になる（D-19）。
         * LIVE 以外（BACKFILL など）は確定の記録を読まない（本番の通知タイミングと混ぜない）。
         */
        private fun restore(): RunCheckpoint {
            val previous = runs.listByDate(req.tenantId, req.date).filter { it.runId != keeper.run.runId }
            val liveRun = req.origin == NotificationOrigin.LIVE
            // LIVE の run は、LIVE 以外の run の checkpoint を引き継がない（実際には通知していないタイミングを『発火済み』にして取りこぼすため。レビュー M1）
            val written =
                previous.filter { p ->
                    !p.checkpoint.isNullOrBlank() &&
                        !(liveRun && RunCheckpoint.parse(p.checkpoint)?.origin.let { it != null && it != NotificationOrigin.LIVE.name })
                }
            val latestCorrupt = written.firstOrNull()?.let { RunCheckpoint.parse(it.checkpoint) == null } ?: false
            val usable =
                written.firstNotNullOfOrNull { p ->
                    RunCheckpoint.parse(p.checkpoint)?.also { events.event("CHECKPOINT_RESTORED", "from=${p.state} cycle=${it.cycle}") }
                }
            val base = usable ?: RunCheckpoint.EMPTY
            if (latestCorrupt) events.event("CHECKPOINT_CORRUPT", "runs=${written.size}")
            val live = req.origin == NotificationOrigin.LIVE
            val merged = LinkedHashMap(base.races)
            if (live || previous.isNotEmpty()) {
                for (race in deriveDay(req, clock.now()).races) {
                    val slots = if (live) timings.list(req.tenantId, race.raceId) else emptyList()
                    if (slots.isNotEmpty()) {
                        val old = merged[race.raceId]
                        merged[race.raceId] =
                            RaceCheckpoint(
                                old?.stage ?: RaceStage.MONITORING,
                                old?.lastObservedAt,
                                old?.firedMinutes.orEmpty() + slots.map { TimingKey.minutes(it.timingCenti) },
                            )
                    }
                    if (previous.isEmpty()) continue
                    val cpRecorded =
                        base.races[race.raceId]
                            ?.firedMinutes
                            .orEmpty()
                            .isNotEmpty()
                    if (!latestCorrupt && cpRecorded) continue
                    val explained = slots.map { it.oddsObservedAt }.toSet()
                    if ((notifiedProbe.notifiedObservations(req.tenantId, race.raceId) - explained).isNotEmpty()) quarantined += race.raceId
                }
            }
            if (quarantined.isNotEmpty()) {
                events.event("RACES_QUARANTINED", "count=${quarantined.size} corrupt=$latestCorrupt")
                report(
                    "CHECKPOINT_UNRELIABLE",
                    "通知済みの記録があるのに checkpoint から発火済みのタイミングを復元できないレースがあり、二重通知を避けるため通知を止めています",
                    "races=${quarantined.size} corrupt=$latestCorrupt",
                )
            }
            return base.copy(races = merged, origin = req.origin.name)
        }

        private fun recoverStaleClaims() {
            val recovered = dispatcher.recoverStaleClaims(req.tenantId, config.staleClaimAfter)
            if (recovered.isNotEmpty()) {
                events.event("STALE_CLAIMS_RECOVERED", "count=${recovered.size}")
                report("STALE_CLAIMS", "通知の送信結果が記録されていない Signal を SEND_UNKNOWN にしました（再送しません）", "count=${recovered.size}")
            }
        }

        private fun startMl(): MlRunContext {
            val ml =
                try {
                    mlStarter(req)
                } catch (e: Exception) {
                    val type = config.mlModelType ?: return MlRunContext.DISABLED
                    MlRunStarter.unavailable(
                        type,
                        jp.jrabot.domain.ml.pipeline.FallbackReason.MODEL_STORAGE_ERROR,
                        "ML の固定に失敗: ${e.javaClass.simpleName}",
                    )
                }
            events.event("ML_PINNED", "type=${ml.modelType} model=${ml.pinnedModelId} note=${ml.startupNote}")
            return ml
        }

        private fun loop(): RunOutcome {
            val startedAt = keeper.run.startedAt
            var consecutiveFailures = 0
            var nextCycleAt = clock.now()
            while (true) {
                if (stopRequested()) return finish(interruptedFinish())
                val now = clock.now()
                if (Duration.between(startedAt, now) >= config.maxRuntime) {
                    return finish(Done(BotRunState.ABORTED, "MAX_RUNTIME_EXCEEDED", RunExit.FAILURE, alert = true))
                }
                val gate = modeGate.onCycleStart()
                when (gate) {
                    is ModeDecision.Drain -> return finish(Done(BotRunState.STOPPED_BY_MODE, gate.reason, gate.exitCode, alert = gate.exitCode != RunExit.OK))
                    is ModeDecision.Skip -> events.event("MODE_UNKNOWN_SKIP", "consecutive=${gate.consecutiveUnknown}")
                    ModeDecision.Proceed -> Unit
                }
                (keeper.heartbeatIfDue() as? LeaseStatus.Lost)?.let { return lost(it) }
                // 評価のサイクルは nextCycleAt まで間を空ける。運用モードの確認と heartbeat はそれより細かい間隔（modeCheckInterval 以下）で行う
                // （UNKNOWN の 3 回連続 ≒ 45 秒以内、STOPPED の検知も同じ。リースの heartbeat の間隔とは独立）
                if (gate == ModeDecision.Proceed && !now.isBefore(nextCycleAt)) {
                    var wait = config.lease.heartbeatInterval
                    try {
                        val result = cycle(now)
                        consecutiveFailures = 0
                        result.finish?.let { return finish(it) }
                        leaseLost?.let { return lost(LeaseStatus.Lost(it)) }
                        wait = result.tick
                    } catch (e: Exception) {
                        if (leaseLost != null) return lost(LeaseStatus.Lost(leaseLost!!))
                        consecutiveFailures++
                        events.event("CYCLE_FAILED", "${e.javaClass.simpleName} consecutive=$consecutiveFailures")
                        if (consecutiveFailures >= config.maxConsecutiveCycleFailures) {
                            val transient = isTransient(e)
                            return finish(
                                Done(
                                    BotRunState.FAILED,
                                    if (transient) "DB_UNAVAILABLE" else "CYCLE_FAILED",
                                    if (transient) RunExit.TEMPFAIL else RunExit.FAILURE,
                                    alert = true,
                                ),
                            )
                        }
                    }
                    nextCycleAt = now.plus(wait)
                    (saveCheckpoint() as? LeaseStatus.Lost)?.let { return lost(it) }
                }
                val untilCycle = Duration.between(clock.now(), nextCycleAt)
                val pause = if (gate == ModeDecision.Proceed) minOf(config.modeCheckInterval, maxOf(untilCycle, MIN_TICK)) else config.modeCheckInterval
                pacer.pause(pause, stopRequested)
            }
        }

        private fun cycle(now: Instant): CycleResult {
            val day = deriveDay(req, now)
            day.window(config.calendar.watch)?.let { cachedWindow = it }
            val window = day.window(config.calendar.watch) ?: cachedWindow
            dayFinish(day)?.let { return CycleResult(it, config.lease.heartbeatInterval) }
            val plan = RaceMonitorPlanner.plan(day, window, now)
            if (plan.allDone && settlePhase) return settleCycle(day, window, now)
            if (plan.allDone) {
                // 時刻が過ぎただけでは正常終了にしない。予定を信頼できず評価を逃したレースがあれば FAILED、データ不足の取りこぼしは警告付きの SUCCEEDED
                val assessment = CompletionAssessment.assess(checkpoint.races, plan.stages, config.snapshot.descending, quarantined)
                checkpoint = checkpoint.copy(lastCycleAt = clock.now(), races = mergeStages(plan))
                val finish = completion(assessment)
                // 評価の機会を失っていなければ、結果待ちに入る（有効な構成のとき）。失っていれば従来どおり FAILED（結果は reconcile が後で回復する）
                if (config.resultWait != null && finish is Done && finish.state == BotRunState.SUCCEEDED) {
                    settlePhase = true
                    if (req.origin ==
                        NotificationOrigin.LIVE
                    ) {
                        advancer.advance(req.tenantId, req.date, RaceDayStatus.SETTLING, "run:${req.runId.value}")
                    }
                    events.event("SETTLE_WAIT_STARTED", "races=${plan.stages.size}")
                    return settleCycle(day, window, now)
                }
                return CycleResult(finish, config.lease.heartbeatInterval)
            }
            if (window == null) return CycleResult(Done(BotRunState.ABORTED, "NO_POST_TIME", RunExit.TEMPFAIL, alert = true), config.lease.heartbeatInterval)
            if (now.isAfter(window.settleDeadlineAt)) {
                return CycleResult(Done(BotRunState.FAILED, "INCOMPLETE_AT_DEADLINE", RunExit.FAILURE, alert = true), config.lease.heartbeatInterval)
            }
            if (plan.unresolved.isNotEmpty()) {
                report("UNRESOLVED_RACES", "発走時刻が不明、または確認が古いレースがあり、通知していません", "races=${plan.unresolved.size}")
            }
            val postAt = day.races.associate { it.raceId to it.postAt }
            for (raceId in plan.monitoring) {
                if (stopRequested() || leaseLost != null) break
                // 1 レースの通知が長引く（Discord の 429 の待機は最大数分）間に、リースが切れないように heartbeat を更新する（以前はサイクルの先頭だけだった）
                (keeper.heartbeatIfDue() as? LeaseStatus.Lost)?.let { leaseLost = it.detail }
                if (leaseLost != null) break
                processRace(raceId, postAt.getValue(raceId) ?: continue)
            }
            checkpoint = checkpoint.copy(cycle = checkpoint.cycle + 1, lastCycleAt = clock.now(), races = mergeStages(plan))
            return CycleResult(null, tickFor(plan, postAt, clock.now()))
        }

        /**
         * 発走後の結果待ち（T3.9）。発走から [ResultWaitConfig.resultAfter] 後に、各レースの結果の取り込み状況（[ResultAvailability]）を [ResultWaitConfig.poll] ごとに確認する。
         * 全レース（中止を除く）の結果がそろえば開催日を COMPLETED にして `ALL_SETTLED` で終了。締切（`CALENDAR_SETTLE_DEADLINE_MINUTES`）を過ぎてもそろわなければ
         * 開催日を INCOMPLETE にして FAILED（`INCOMPLETE_AT_DEADLINE`）で終了し、後から届いた結果は reconcile が回復する。状態は DB から毎回導出する（再起動しても同じ）。
         */
        private fun settleCycle(
            day: EffectiveDay,
            window: WatchWindow?,
            now: Instant,
        ): CycleResult {
            val rw = checkNotNull(config.resultWait)
            val live = req.origin == NotificationOrigin.LIVE
            val stages = LinkedHashMap<RaceId, RaceStage>()
            var nextDue: Instant? = null
            for (race in day.races.sortedBy { it.raceId }) {
                val postAt = race.postAt
                if (race.status == jp.jrabot.domain.race.RaceScheduleStatus.CANCELLED || race.status == jp.jrabot.domain.race.RaceScheduleStatus.DELETED) {
                    stages[race.raceId] = RaceStage.CANCELLED
                    continue
                }
                val due = postAt?.plus(rw.resultAfter)
                if (due == null || now.isBefore(due)) {
                    stages[race.raceId] = RaceStage.OFF
                    if (due != null) nextDue = nextDue?.let { minOf(it, due) } ?: due
                    continue
                }
                stages[race.raceId] =
                    when (availability.state(req.tenantId, race.raceId)) {
                        ResultState.NONE -> RaceStage.RESULT_PENDING
                        ResultState.RESULT_ONLY -> RaceStage.RESULT_PARTIAL
                        ResultState.COMPLETE -> RaceStage.SETTLED
                    }
            }
            checkpoint =
                checkpoint.copy(
                    cycle = checkpoint.cycle + 1,
                    lastCycleAt = clock.now(),
                    races =
                        stages.mapValues { (id, st) ->
                            RaceCheckpoint(st, checkpoint.races[id]?.lastObservedAt, checkpoint.races[id]?.firedMinutes.orEmpty())
                        },
                )
            val active = stages.filterValues { it != RaceStage.CANCELLED }
            if (active.isNotEmpty() && active.values.all { it == RaceStage.SETTLED }) {
                if (live) advancer.advance(req.tenantId, req.date, RaceDayStatus.COMPLETED, "run:${req.runId.value}")
                events.event("ALL_SETTLED", "races=${active.size}")
                return CycleResult(Done(BotRunState.SUCCEEDED, REASON_ALL_SETTLED, RunExit.OK), rw.poll)
            }
            if (window != null && now.isAfter(window.settleDeadlineAt)) {
                if (live) advancer.advance(req.tenantId, req.date, RaceDayStatus.INCOMPLETE, "run:${req.runId.value}")
                val pending = active.filterValues { it != RaceStage.SETTLED }.size
                events.event("SETTLE_DEADLINE", "unsettled=$pending")
                return CycleResult(Done(BotRunState.FAILED, "INCOMPLETE_AT_DEADLINE", RunExit.FAILURE, alert = true), rw.poll)
            }
            val untilDue = nextDue?.let { Duration.between(now, it) }
            return CycleResult(null, maxOf(MIN_TICK, listOfNotNull(rw.poll, untilDue?.takeIf { it.isPositive }).min()))
        }

        /**
         * 全レースが発走済みまたは中止になったときの終了。必要な評価のタイミングを逃したレースが 1 つでもあれば **FAILED・exit 1**（D-19）。逃した理由は
         * 予定を信頼できなかった・オッズが無い/古い・checkpoint の不整合のどれでも同じ。回復できない（発走後に再実行しても評価できない）ので 75 ではなく 1。
         * 評価した結果の買い目 0 件、見送り、中止、遅い起動で評価対象にならなかったレースは正常終了。
         */
        private fun completion(a: CompletionAssessment): Finish {
            val lost = a.lost
            events.event("COMPLETION", "lostCalendar=${a.calendarLost.size} lostData=${a.dataLost.size} quarantined=${a.quarantinedLost.size}")
            return if (lost.isNotEmpty()) {
                Done(BotRunState.FAILED, REASON_OPPORTUNITY_LOST, RunExit.FAILURE, alert = true)
            } else {
                Done(BotRunState.SUCCEEDED, "ALL_RACES_OFF", RunExit.OK)
            }
        }

        private fun dayFinish(day: EffectiveDay): Finish? =
            when {
                day.denyRun -> Done(BotRunState.ABORTED, "DENIED_BY_OVERRIDE", RunExit.OK)
                day.verdict == DayVerdict.CANCELLED -> Done(BotRunState.SUCCEEDED, "DAY_CANCELLED", RunExit.OK)
                day.verdict == DayVerdict.POSTPONED -> Done(BotRunState.SUCCEEDED, "DAY_POSTPONED", RunExit.OK)
                day.verdict == DayVerdict.NO_RACE_CONFIRMED && !day.allowRun -> Done(BotRunState.SUCCEEDED, "NOT_A_RACE_DAY", RunExit.OK)
                else -> null
            }

        private fun processRace(
            raceId: RaceId,
            postAt: Instant,
        ) {
            if (raceId in quarantined) {
                events.event("RACE_QUARANTINED", raceId.value)
                return
            }
            val prev = checkpoint.races[raceId] ?: RaceCheckpoint(RaceStage.MONITORING)
            val step =
                try {
                    processor.process(raceId, postAt, prev, clock.now(), ::leaseHeld) { next -> commit(raceId, next) }
                } catch (e: Exception) {
                    if (isTransient(e)) throw e
                    events.event("RACE_FAILED", "${raceId.value} ${e.javaClass.simpleName}")
                    report("RACE_FAILED:${raceId.value}", "レースの処理に失敗しました（他のレースは続けます）", "race=${raceId.value} error=${e.javaClass.simpleName}")
                    return
                }
            when (step) {
                RaceStep.NotDue -> {
                    // 評価のタイミングではない。何もしない
                }

                RaceStep.NoOdds -> {
                    events.event("NO_ODDS", raceId.value)
                }

                is RaceStep.FutureOdds -> {
                    events.event("ODDS_FROM_FUTURE", raceId.value)
                }

                RaceStep.NotCommitted -> {
                    events.event("NOT_COMMITTED", raceId.value)
                }

                is RaceStep.StaleOdds -> {
                    events.event("STALE_ODDS", "${raceId.value} ageSec=${step.age.seconds}")
                    report("STALE_ODDS:${raceId.value}", "オッズが古いため通知していません（エージェント停止・取込の遅延の可能性）", "race=${raceId.value} age=${step.age.seconds}s")
                }

                is RaceStep.AlreadyFired -> {
                    events.event("TIMING_ALREADY_FIRED", "${raceId.value} timings=${step.timingCenti.sorted()}")
                }

                RaceStep.SnapshotInvalid -> {
                    events.event("NOTICE_SNAPSHOT_INVALID", raceId.value)
                    report(
                        "NOTICE_SNAPSHOT_INVALID:${raceId.value}",
                        "確定した通知内容が壊れている・版が違う・ハッシュが合わないため、通知しませんでした（別の値で補いません。管理画面で確認してください）",
                        "race=${raceId.value}",
                    )
                }

                is RaceStep.Evaluated -> {
                    val ev = step.evaluation
                    events.event(
                        "EVALUATED",
                        "${raceId.value} signals=${step.signalsRecorded} model=${ev.modelTypeUsed} fallback=${ev.fallbackReason} dispatch=${step.dispatch?.status}",
                    )
                    if (step.suppressedMismatch > 0) {
                        report(
                            "SIGNAL_MISMATCH:${raceId.value}",
                            "保存済みの Signal と今回の計算結果が違うため、通知を抑止しました（DB の内容と違う値は通知しません。原因を確認してください）",
                            "race=${raceId.value} count=${step.suppressedMismatch}",
                        )
                    }
                    if (ev.isMlMarketFallback) {
                        report("ML_MARKET_FALLBACK:${ev.fallbackReason}", "ML を有効にしていますが市場確率だけで判定しています", "reason=${ev.fallbackReason}")
                    }
                    if (step.dispatch?.needsAttention() == true) {
                        report(
                            "NOTIFY_UNKNOWN:${raceId.value}",
                            "通知の送信結果が不明、または送信できませんでした（再送しません。管理画面で確認してください）",
                            "race=${raceId.value} status=${step.dispatch.status}",
                        )
                    }
                }
            }
        }

        /** 発火済みのタイミングを通知の**前に** checkpoint へ保存する（保存できなければ通知しない）。 */
        private fun commit(
            raceId: RaceId,
            next: RaceCheckpoint,
        ): Boolean {
            val updated = checkpoint.copy(races = checkpoint.races + (raceId to next))
            return when (val s = keeper.saveCheckpoint(updated.toJson())) {
                LeaseStatus.Held -> {
                    checkpoint = updated
                    true
                }

                is LeaseStatus.Lost -> {
                    leaseLost = s.detail
                    false
                }
            }
        }

        private fun saveCheckpoint(): LeaseStatus = keeper.saveCheckpoint(checkpoint.toJson())

        private fun mergeStages(plan: MonitorPlan): Map<RaceId, RaceCheckpoint> =
            plan.stages.mapValues { (id, stage) ->
                val old = checkpoint.races[id]
                RaceCheckpoint(stage, old?.lastObservedAt, old?.firedMinutes.orEmpty())
            }

        /** 次の起床までの時間: heartbeat の間隔、監視中のレースのポーリング間隔、次のタイミング・監視開始までの時間のうち最短（1 秒以上）。 */
        private fun tickFor(
            plan: MonitorPlan,
            postAt: Map<RaceId, Instant?>,
            now: Instant,
        ): Duration {
            var d = config.lease.heartbeatInterval
            for (id in plan.monitoring) {
                val p = postAt[id] ?: continue
                val minutes = Duration.between(now, p).toMillis() / 60_000.0
                d = minOf(d, config.polling.forMinutesToPost(minutes))
                val fired = checkpoint.races[id]?.firedMinutes.orEmpty()
                val nextTiming =
                    config.snapshot.descending
                        .filter { it !in fired && minutes > it }
                        .minOrNull()
                if (nextTiming != null) d = minOf(d, Duration.ofMillis(((minutes - nextTiming) * 60_000.0).toLong() + 1))
            }
            plan.watchStartsAt?.takeIf { now.isBefore(it) }?.let { d = minOf(d, Duration.between(now, it)) }
            return maxOf(d, MIN_TICK)
        }

        private fun interruptedFinish(): Finish {
            val mode = modes.current()
            // NORMAL のままの cancel は 75（再実行されるべき）。確定した非 NORMAL は計画的な停止なので 0。UNKNOWN は安全側で 75
            val exit = if (mode.isConfirmed && !mode.allowsWork) RunExit.OK else RunExit.TEMPFAIL
            return Done(BotRunState.INTERRUPTED, "SIGTERM", exit, viaDraining = false)
        }

        private fun finish(f: Finish): RunOutcome {
            val json = checkpoint.toJson()
            if (f.viaDraining) {
                val d = keeper.transition(BotRunState.DRAINING, null, json)
                if (d is LeaseStatus.Lost) return lost(d)
            }
            val s = keeper.transition(f.state, f.reason, json)
            if (s is LeaseStatus.Lost) return lost(s)
            events.event("FINISHED", "${f.state} ${f.reason} exit=${f.exitCode}")
            if (f.alert) report("RUN_${f.reason}", "bot-run が ${f.reason} で終了しました", "state=${f.state} exit=${f.exitCode}")
            return RunOutcome(f.exitCode, f.reason, f.state, keeper.run.runId)
        }

        private fun lost(s: LeaseStatus.Lost): RunOutcome {
            events.event("LEASE_LOST", s.detail)
            report("LEASE_LOST", "bot-run がリースを失ったため処理を止めました（別の実行が引き継いだ可能性）", s.detail)
            return RunOutcome(RunExit.TEMPFAIL, s.detail, null, keeper.run.runId)
        }

        /** 予期しない例外（開始処理など）。可能なら FAILED を記録する。記録できなくても、リースの期限切れで次の実行が奪取できる。 */
        private fun crashed(e: Exception): RunOutcome {
            val transient = isTransient(e)
            val reason = if (transient) "DB_UNAVAILABLE" else "RUN_FAILED"
            events.event("RUN_CRASHED", e.javaClass.simpleName)
            val recorded =
                try {
                    keeper.transition(BotRunState.FAILED, reason, null) is LeaseStatus.Held
                } catch (_: Exception) {
                    false
                }
            report("RUN_CRASHED", "bot-run が予期しない例外で終了しました", "error=${e.javaClass.simpleName} recorded=$recorded")
            return RunOutcome(if (transient) RunExit.TEMPFAIL else RunExit.FAILURE, reason, if (recorded) BotRunState.FAILED else null, keeper.run.runId)
        }

        /** 同じ種類のエラー通知は run あたり 1 回（ErrorReporter 自身の抑制に加えて、run 内の重複を避ける）。通知の失敗は本処理を止めない。 */
        private fun report(
            key: String,
            message: String,
            details: String,
        ) {
            if (!reported.add(key)) return
            try {
                // 同じ種類（title）の通知は ErrorReporter の cooldown で畳まれる。畳まれた件数はログに残す（レースごとの異常の数を失わない）
                val outcome = errors.report("bot-run: ${key.substringBefore(':')}", message, details)
                if (outcome == ErrorReporter.Outcome.COOLDOWN) events.event("ERROR_REPORT_SUPPRESSED", key)
            } catch (_: Exception) {
                // ErrorReporter は例外を投げない契約だが、念のため本処理を止めない
            }
        }
    }

    companion object {
        /** 必要な評価のタイミングを逃して終えた run の理由（`bot_run.exit_reason`）。 */
        const val REASON_OPPORTUNITY_LOST = "EVALUATION_OPPORTUNITY_LOST"

        /** 全レースの結果（着順と単勝の確定払戻）がそろって終えた run の理由。 */
        const val REASON_ALL_SETTLED = "ALL_SETTLED"

        private val MIN_TICK: Duration = Duration.ofSeconds(1)
    }
}
