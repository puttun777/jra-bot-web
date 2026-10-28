package jp.jrabot.domain.botrun

import jp.jrabot.domain.decision.BetSignal
import jp.jrabot.domain.decision.DecisionReason
import jp.jrabot.domain.decision.DecisionSignal
import jp.jrabot.domain.decision.SkipSignal
import jp.jrabot.domain.ml.features.FeatureRequest
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder
import jp.jrabot.domain.ml.features.ReasonFormatter
import jp.jrabot.domain.notify.DispatchReport
import jp.jrabot.domain.notify.DispatchStatus
import jp.jrabot.domain.notify.NoticeItem
import jp.jrabot.domain.notify.NoticeViews
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.notify.RaceLabel
import jp.jrabot.domain.notify.RaceNoticeBatch
import jp.jrabot.domain.notify.SignalNotificationDispatcher
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsSnapshotReader
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceReader
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.Signal
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.value.Probability
import java.time.Duration
import java.time.Instant

/** 1 レース・1 サイクルの結果。 */
sealed interface RaceStep {
    /** 評価のタイミングではない（発走までの残りが、まだ未発火のどのタイミングにも達していない）。何もしない。 */
    data object NotDue : RaceStep

    /** 単勝オッズが無い。通知しない。次のサイクルで再確認する（タイミングは発火済みにしない）。 */
    data object NoOdds : RaceStep

    /** オッズが古い（[age] が `maxOddsAge` を超える）。**通知しない**。次のサイクルで再確認する。 */
    data class StaleOdds(
        val observedAt: Instant,
        val age: Duration,
    ) : RaceStep

    /** 観測時刻が現在より未来。信頼しない。 */
    data class FutureOdds(
        val observedAt: Instant,
    ) : RaceStep

    /** checkpoint を保存できなかった（リース喪失など）ので、通知しなかった。 */
    data object NotCommitted : RaceStep

    /** 評価して記録した。[dispatch] は通知の結果（通知対象が無ければ null）。[checkpoint] は発火を記録した後の値。 */
    data class Evaluated(
        val evaluation: RaceEvaluation,
        val firedMinutes: Set<Double>,
        val signalsRecorded: Int,
        /** DB に既にある Signal（同じ自然キー）と今回の計算結果が違うため、**通知しなかった**件数（DB の内容と違う値を通知しない）。 */
        val suppressedMismatch: Int,
        val dispatch: DispatchReport?,
        val checkpoint: RaceCheckpoint,
    ) : RaceStep
}

/**
 * 1 レース・1 サイクルの処理（鮮度の判定 → 判定 → 記録 → 通知）。順序と冪等性:
 *
 * 1. 評価のタイミング（[SnapshotPolicy]）に達していなければ何もしない。単勝オッズが無い・古い・未来なら**通知しない**（次のサイクルで再確認）。
 * 2. [RaceEvaluator] で判定し、`race_evaluations`（自然キー `(race, snapshot_at)`）と `signals`（自然キー `(race, market, umaban, observed_at)`）を
 *    **insert-if-absent** で記録する。同じオッズの観測を再評価しても行は増えない（冪等）。
 * 3. 発火済みのタイミングを [commitFired] で checkpoint に保存する。**通知の前に保存する**: 保存できなければ通知しない。保存の後で落ちると、
 *    そのタイミングの通知は取りこぼす（二重通知より取りこぼしを選ぶ: CONTRACTS §9.2）。
 * 4. 出走表の馬名と point-in-time の理由文（G-17 ⑥。asOf 以前の情報だけ）で表示モデルを作り、[SignalNotificationDispatcher] へ渡す。
 *    出どころ（LIVE のみ）・運用モード・停止要求・claim・Retry-After は dispatcher の責務。
 */
class RaceCycleProcessor(
    private val config: BotRunConfig,
    private val tenantId: TenantId,
    private val origin: NotificationOrigin,
    private val odds: OddsSnapshotReader,
    private val evaluator: RaceJudge,
    private val evaluations: RaceEvaluationWriter,
    private val signals: SignalRepository,
    private val entries: RaceEntryReader,
    private val features: FeatureSource,
    private val races: RaceReader,
    private val dispatcher: SignalNotificationDispatcher,
) {
    fun process(
        raceId: RaceId,
        postAt: Instant,
        checkpoint: RaceCheckpoint,
        now: Instant,
        commitFired: (RaceCheckpoint) -> Boolean,
    ): RaceStep {
        val minutesToPost = Duration.between(now, postAt).toMillis() / 60_000.0
        val due = config.snapshot.descending.filter { minutesToPost <= it && it !in checkpoint.firedMinutes }
        if (due.isEmpty()) return RaceStep.NotDue

        val win = odds.latest(tenantId, raceId, Market.WIN) ?: return RaceStep.NoOdds
        when (OddsFreshness.of(win.observedAt, now, config.maxOddsAge)) {
            OddsFreshness.FRESH -> Unit
            OddsFreshness.STALE -> return RaceStep.StaleOdds(win.observedAt, Duration.between(win.observedAt, now))
            OddsFreshness.FUTURE -> return RaceStep.FutureOdds(win.observedAt)
            OddsFreshness.MISSING -> return RaceStep.NoOdds
        }

        val evaluation = evaluator.evaluate(tenantId, raceId, win, now, minutesToPost)
        evaluations.insertIfAbsent(record(evaluation))
        val recorded = recordSignals(raceId, evaluation)

        val fired = checkpoint.firedMinutes + due
        val next = RaceCheckpoint(RaceStage.MONITORING, win.observedAt, fired)
        if (!commitFired(next)) return RaceStep.NotCommitted

        val items = noticeItems(raceId, evaluation, recorded, postAt, now)
        val report =
            if (items.isEmpty()) {
                null
            } else {
                dispatcher.dispatchRace(RaceNoticeBatch(tenantId, raceId, raceLabel(raceId), items), origin)
            }
        return RaceStep.Evaluated(evaluation, fired, recorded.size, recorded.count { it.mismatch }, report, next)
    }

    private class Recorded(
        val signal: DecisionSignal,
        val id: SignalId,
        val state: NotificationState,
        /** 既存の行と今回の計算結果が一致しない（通知しない）。 */
        val mismatch: Boolean,
    )

    private fun recordSignals(
        raceId: RaceId,
        evaluation: RaceEvaluation,
    ): List<Recorded> {
        val out = mutableListOf<Recorded>()
        for (s in evaluation.result.signals) {
            val row = toSignal(raceId, evaluation, s) ?: continue
            val saved = signals.insertIfAbsent(row)
            val id = saved.signal.id ?: continue
            // 既存の行（再起動・再評価で見つかったもの）が今回の計算結果と違うなら、DB の内容と違う値を通知しない（最新の計算結果で勝手に通知しない）
            val mismatch = saved.outcome == InsertOutcome.ALREADY_EXISTS && row.copy(id = saved.signal.id, state = saved.signal.state) != saved.signal
            out += Recorded(s, id, saved.signal.state, mismatch)
        }
        return out
    }

    /** 保存できない値（オッズが 0 以下・確率が範囲外）の Signal は作らない（補正しない）。 */
    private fun toSignal(
        raceId: RaceId,
        evaluation: RaceEvaluation,
        s: DecisionSignal,
    ): Signal? {
        val (oddsValue, prob, stake) =
            when (s) {
                is BetSignal -> Triple(s.odds, s.prob.value, s.betAmount)
                is SkipSignal -> Triple(s.odds, s.prob.value, s.betAmount ?: 0.0)
            }
        val o = Odds.ofOrNull(oddsValue) ?: return null
        val p = Probability.ofOrNull(prob) ?: return null
        val horse = HorseNumber.ofOrNull(s.umaban) ?: return null
        if (!s.ev.value.isFinite() || !stake.isFinite() || stake < 0.0) return null
        return Signal(
            tenantId = tenantId,
            id = null,
            raceId = raceId,
            market = s.betType.market(),
            umaban = horse,
            observedAt = evaluation.snapshotAt,
            odds = o,
            prob = p,
            ev = s.ev.value,
            // この Bot は購入しない（通知のみ）ので、記録はすべてペーパー扱い（旧 `is_paper` の既定と同じ）
            isPaper = true,
            stake = stake,
            probSource = s.probSource.take(100),
        )
    }

    private fun noticeItems(
        raceId: RaceId,
        evaluation: RaceEvaluation,
        recorded: List<Recorded>,
        postAt: Instant,
        now: Instant,
    ): List<NoticeItem> {
        // 今回新しく作った（UNSENT）ものだけを通知する。既に claim / 送信済みの行（再評価で見つかった既存の行）は dispatcher も NOT_UNSENT で除く
        val pending = recorded.filter { it.state == NotificationState.UNSENT && !it.mismatch }
        if (pending.isEmpty()) return emptyList()
        val names = runCatching { entries.horseNames(tenantId, raceId) }.getOrDefault(emptyMap())
        val builder = PointInTimeFeatureBuilder(features)
        return pending.map { r ->
            val view = NoticeViews.from(r.signal)
            val reason =
                runCatching {
                    ReasonFormatter.format(builder.reasonFacts(FeatureRequest(tenantId, raceId.value, r.signal.umaban, now), names[r.signal.umaban].orEmpty()))
                }.getOrNull()
            NoticeItem(r.id, if (reason.isNullOrEmpty() || r.signal is SkipSignal) view else view.copy(reason = reason))
        }
    }

    private fun raceLabel(raceId: RaceId): RaceLabel {
        val name = runCatching { races.find(tenantId, raceId)?.name }.getOrNull().orEmpty()
        return RaceLabel(raceId.venue.nameJa, raceId.raceNo, name)
    }

    private fun record(e: RaceEvaluation): RaceEvaluationRecord {
        val d = config.decision
        val counts =
            e.result.rejections
                .groupingBy { it.reason }
                .eachCount()
        val summary =
            DecisionReason.entries
                .filter { counts.containsKey(it) }
                .joinToString(",", "{", "}") { "\"${it.name}\":${counts.getValue(it)}" }
        return RaceEvaluationRecord(
            tenantId = tenantId,
            raceId = e.raceId,
            snapshotAt = e.snapshotAt,
            modelTypeUsed = e.modelTypeUsed,
            modelId = e.modelId,
            fallbackReason = e.fallbackReason?.name,
            betTypesJson = d.betTypes?.joinToString(",", "[", "]") { "\"${it.label}\"" },
            priorityMode = d.priorityMode.name.lowercase(),
            evThreshold = e.evThresholdUsed ?: d.evThreshold,
            winOddsMax = d.winOddsMax,
            winProbMin = d.winProbMin,
            winRatioMin = d.winRatioMin,
            bestEv = e.result.signals.maxOfOrNull { it.ev.value },
            rejectionSummaryJson = summary,
            signalCount = e.result.signals.count { it is BetSignal },
        )
    }
}

/** 通知の結果のうち、運用者が気付くべきもの（送信結果が不明・取りこぼし）。 */
fun DispatchReport.needsAttention(): Boolean =
    status == DispatchStatus.UNKNOWN || status == DispatchStatus.NOT_SENT_RECORDED_UNKNOWN || recordFailures.isNotEmpty()
