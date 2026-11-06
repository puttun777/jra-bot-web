package jp.jrabot.bot

import jp.jrabot.domain.backtest.BacktestReport
import jp.jrabot.domain.backtest.BacktestRequest
import jp.jrabot.domain.backtest.ExclusionReason
import jp.jrabot.domain.botrun.RaceEvaluation
import jp.jrabot.domain.botrun.RaceJudge
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** バックテストの実行結果（ML の状態を含む）。 */
sealed interface BacktestRun {
    /** ML を有効にしている（`BOT_RUN_ML_MODEL_TYPE`）のにモデルを固定できなかった。市場確率だけの成績をモデルの成績と取り違えないよう、計算しない。 */
    data class MlUnavailable(
        val modelType: String,
    ) : BacktestRun

    data class Done(
        val report: BacktestReport,
        /** null = ML 無効（市場確率）。 */
        val modelType: String?,
        /** 実際の判定で ML の確率を使えた回数と、そのとき使ったモデルの ID（[MlUsageJudge] が数える。レジストリの ACTIVE の行ではない）。 */
        val ml: MlUsage = MlUsage(),
    ) : BacktestRun
}

/** 判定ごとの ML の使用状況（ML 無効の判定は数えない）。 */
data class MlUsage(
    /** ML の確率を使えた判定の数（一部の役割が欠けた [degraded] を含む）。 */
    val used: Int = 0,
    /** ML を要求したのに市場確率だけになった判定の数（モデルの読込み・検証・推論の失敗）。 */
    val fallback: Int = 0,
    /** ML は使えたが一部（ensemble の片方など）を外した判定の数。 */
    val degraded: Int = 0,
    val modelIds: Set<String> = emptySet(),
)

/**
 * 判定ごとに ML を**実際に**使えたかを数える（D-266 の再レビュー 中: レジストリに ACTIVE の行があっても、成果物が無い・署名や sha256 が合わない・
 * ensemble の片方が REJECTED などでは例外にならず、判定は市場確率に落ちる。行の有無だけで判断すると『ML の成績』と取り違える）。
 */
class MlUsageJudge(
    private val delegate: RaceJudge,
) : RaceJudge {
    private var used = 0
    private var fallback = 0
    private var degraded = 0
    private val ids = sortedSetOf<String>()

    val usage: MlUsage get() = MlUsage(used, fallback, degraded, ids.toSet())

    override fun evaluate(
        tenantId: TenantId,
        raceId: RaceId,
        win: OddsSnapshot,
        now: Instant,
        minutesToPost: Double,
    ): RaceEvaluation =
        delegate.evaluate(tenantId, raceId, win, now, minutesToPost).also { e ->
            val p = e.probability ?: return@also // ML 無効
            val id = e.modelId
            if (p.isMarketFallback || id == null) {
                fallback++
            } else {
                used++
                ids += id
                if (p.fallbackReason != null) degraded++
            }
        }
}

/**
 * `--task=backtest`（R16。D-241・D-265）: 保存済みの観測だけから、過去の各時点（発走の N 分前）の判定を **bot-run と同じ判定**（`RaceEvaluator`・
 * 同じ BOT_RUN_* の設定・同じ ML のモデル）で再計算し、紙上成績を 1 行の JSON（`BACKTEST_REPORT`）でログに出す。
 * **DB に書かない・通知しない**（D-234。結果の保存は V6 の承認後: T39_DESIGN §8）。結果・払戻の保管は [ReadOnlyResults] で包み、書込みは例外にする。
 * ログには件数・率・金額の集計だけを出し、レースや馬の行は出さない。
 *
 * | 結果 | 終了コード | 理由 |
 * |---|---|---|
 * | 評価できた（1 件以上）・判定の失敗なし | 0 | `BACKTEST_DONE` |
 * | 対象が 0、またはすべて除外 | 1 | `BACKTEST_EMPTY`（空の成績を成功にしない） |
 * | 判定が例外で失敗した時点がある | 1 | `BACKTEST_JUDGE_FAILED`（判定の中の DB・GCS の読取りの障害も含み得る。欠けた成績を成功にしない。報告は出す） |
 * | ML を有効にしているのにモデルを固定できない | 1 | `BACKTEST_ML_UNAVAILABLE`（計算しない） |
 * | ML を有効にしているのに市場確率に落ちた判定がある | 1 | `BACKTEST_ML_FALLBACK`（モデルを読めない・検証できない・推論の失敗。報告は出す） |
 * 判定の外の読取りの失敗（DB 障害など）は [BotJobExecutor] が 1（`TASK_FAILED`）にする。運用モードが NORMAL でなければ、ここに来る前に終了している（DB に接続しない）。
 */
class BacktestTaskHandler(
    private val runner: (BacktestRequest) -> BacktestRun,
    private val defaultMaxOddsAge: Duration,
    private val log: Logger = LoggerFactory.getLogger(BacktestTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val args = requireNotNull(command.backtest) { "backtest の引数が無い（BotArgsParser が検証しているはず）" }
        val req =
            BacktestRequest(
                tenantId = command.tenantId,
                from = args.from,
                to = args.to,
                minutesBeforePost = args.minutesBeforePost,
                maxOddsAge = args.maxOddsAge ?: defaultMaxOddsAge,
            )
        return when (val run = runner(req)) {
            is BacktestRun.MlUnavailable -> {
                log.error("BACKTEST_ML_UNAVAILABLE model_type={}（モデルを固定できない。レジストリ・保管先・署名を確認する）", run.modelType)
                JobResult(JobExit.FAILURE, "BACKTEST_ML_UNAVAILABLE")
            }

            is BacktestRun.Done -> {
                val r = run.report
                log.info("BACKTEST_REPORT {}", render(r, run.modelType, run.ml))
                when {
                    r.evaluated == 0 -> JobResult(JobExit.FAILURE, "BACKTEST_EMPTY")
                    (r.excluded[ExclusionReason.JUDGE_FAILED] ?: 0) > 0 -> JobResult(JobExit.FAILURE, "BACKTEST_JUDGE_FAILED")
                    run.modelType != null && (run.ml.fallback > 0 || run.ml.used == 0) -> JobResult(JobExit.FAILURE, "BACKTEST_ML_FALLBACK")
                    else -> JobResult(JobExit.OK, "BACKTEST_DONE")
                }
            }
        }
    }

    companion object {
        /** 1 行の JSON（キーは辞書順の正準形式）。値は件数・率・金額の集計とモデルの ID だけ。非有限の数は null にする（報告を失わない）。 */
        fun render(
            r: BacktestReport,
            modelType: String?,
            ml: MlUsage,
        ): String {
            fun num(v: Double?): JsonValue = v?.takeIf { it.isFinite() }?.let { JsonFloat(it) } ?: JsonNull

            fun str(v: String?): JsonValue = v?.let { JsonString(it) } ?: JsonNull
            val req = r.request
            val json =
                JsonObject.of(
                    "from" to JsonString(req.from.toString()),
                    "to" to JsonString(req.to.toString()),
                    "minutes_before_post" to JsonArray(req.minutesBeforePost.map { num(it) }),
                    "max_odds_age_seconds" to JsonInt(req.maxOddsAge.seconds),
                    "model_type" to str(modelType),
                    // 実際に使えたモデルの ID だけ（使えなければ null。レジストリの ACTIVE の行の ID は出さない）
                    "model_id" to str(ml.modelIds.takeIf { it.isNotEmpty() }?.joinToString(",")),
                    "ml_used" to JsonInt(ml.used.toLong()),
                    "ml_fallback" to JsonInt(ml.fallback.toLong()),
                    "ml_degraded" to JsonInt(ml.degraded.toLong()),
                    "candidates" to JsonInt(r.candidates.toLong()),
                    "evaluated" to JsonInt(r.evaluated.toLong()),
                    "excluded" to JsonObject(ExclusionReason.entries.associate { it.name to JsonInt((r.excluded[it] ?: 0).toLong()) }),
                    "excluded_total" to JsonInt(r.excludedTotal.toLong()),
                    "exclusion_rate" to num(r.exclusionRate),
                    "unsettled_bets" to JsonInt(r.unsettledBets.toLong()),
                    "win" to
                        JsonObject.of(
                            "count" to JsonInt(r.win.count.toLong()),
                            "hits" to JsonInt(r.win.hits.toLong()),
                            "total_stake" to num(r.win.totalStake),
                            "paper_payout" to num(r.win.paperPayout),
                            "actual_payout" to num(r.win.actualPayout),
                            "hit_rate" to num(r.win.hitRate),
                            "paper_roi" to num(r.win.paperRoi),
                            "actual_roi" to num(r.win.actualRoi),
                        ),
                    "combos" to
                        JsonArray(
                            r.combos.map { c ->
                                JsonObject.of(
                                    "market" to JsonString(c.market.code),
                                    "count" to JsonInt(c.count.toLong()),
                                    "hits" to JsonInt(c.hits.toLong()),
                                    "total_stake" to num(c.totalStake),
                                    "actual_payout" to num(c.actualPayout),
                                    "hit_rate" to num(c.hitRate),
                                    "actual_roi" to num(c.actualRoi),
                                )
                            },
                        ),
                    // モデルは**今 ACTIVE のもの**で、学習データにこの期間が含まれ得る（重みの先読み = 楽観的になり得る。特徴量は時点整合）。実データでの検証は未実施
                    "note" to JsonString("paper-only; not persisted; no notifications; model is the currently active one and may be in-sample for this period"),
                )
            return CanonicalJson.encode(json)
        }
    }
}

/** 結果の読取りだけを許す（バックテストは DB に書かない: D-234・D-265）。書込みは例外にして、将来の変更で書込みが紛れ込んでも成功しない。 */
class ReadOnlyResults(
    private val results: ResultStore,
    private val payouts: PayoutStore,
) : ResultStore,
    PayoutStore {
    override fun list(
        tenantId: TenantId,
        raceId: RaceId,
    ) = results.list(tenantId, raceId)

    override fun list(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ) = payouts.list(tenantId, raceId, market)

    override fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        entries: List<ResultEntry>,
    ): Nothing = throw UnsupportedOperationException("backtest は結果を書き込まない")

    override fun upsertAll(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
        rows: List<PayoutRow>,
        provisional: Boolean,
    ): Nothing = throw UnsupportedOperationException("backtest は払戻を書き込まない")
}
