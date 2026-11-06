package jp.jrabot.bot

import jp.jrabot.domain.backtest.BacktestReport
import jp.jrabot.domain.backtest.BacktestRequest
import jp.jrabot.domain.backtest.ExclusionReason
import jp.jrabot.domain.botrun.RaceJudge
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * `--task=backtest`（R16。D-241・D-265）: 保存済みの観測だけから、過去の各時点（発走の N 分前）の判定を **bot-run と同じ [RaceJudge]**（[jp.jrabot.domain.botrun.RaceEvaluator]・
 * 同じ BOT_RUN_* の設定・同じ ML のモデル）で再計算し、紙上成績を 1 行の JSON（`BACKTEST_REPORT`）でログに出す。
 * **DB に書かない・通知しない**（D-234。結果の保存は V6 の承認後: T39_DESIGN §8）。ログには件数・率・金額の集計だけを出し、レースや馬の行は出さない。
 *
 * | 結果 | 終了コード | 理由 |
 * |---|---|---|
 * | 評価できた（1 件以上） | 0 | `BACKTEST_DONE` |
 * | 対象が 0、またはすべて除外 | 1 | `BACKTEST_EMPTY`（空の成績を成功にしない。除外の内訳はログ） |
 * 想定外の例外（DB 障害など）は [BotJobExecutor] が 1（`TASK_FAILED`）にする。運用モードが NORMAL でなければ、ここに来る前に終了している（DB に接続しない）。
 */
class BacktestTaskHandler(
    private val runner: (BacktestRequest) -> BacktestReport,
    private val defaultMaxOddsAge: Duration,
    private val log: Logger = LoggerFactory.getLogger(BacktestTaskHandler::class.java),
) : BotTaskHandler {
    override fun execute(command: BotCommand): JobResult {
        val args = requireNotNull(command.backtest) { "backtest の引数が無い（BotArgsParser が検証しているはず）" }
        val report =
            runner(
                BacktestRequest(
                    tenantId = command.tenantId,
                    from = args.from,
                    to = args.to,
                    minutesBeforePost = args.minutesBeforePost,
                    maxOddsAge = args.maxOddsAge ?: defaultMaxOddsAge,
                ),
            )
        log.info("BACKTEST_REPORT {}", render(report))
        return if (report.evaluated == 0) JobResult(JobExit.FAILURE, "BACKTEST_EMPTY") else JobResult(JobExit.OK, "BACKTEST_DONE")
    }

    companion object {
        /** 1 行の JSON（キーは辞書順の正準形式）。値は件数・率・金額の集計だけ。 */
        fun render(r: BacktestReport): String {
            fun num(v: Double?): JsonValue = v?.let { JsonFloat(it) } ?: JsonNull
            val req = r.request
            val json =
                JsonObject.of(
                    "from" to JsonString(req.from.toString()),
                    "to" to JsonString(req.to.toString()),
                    "minutes_before_post" to JsonArray(req.minutesBeforePost.map { JsonFloat(it) }),
                    "max_odds_age_seconds" to JsonInt(req.maxOddsAge.seconds),
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
                            "total_stake" to JsonFloat(r.win.totalStake),
                            "paper_payout" to JsonFloat(r.win.paperPayout),
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
                                    "total_stake" to JsonFloat(c.totalStake),
                                    "actual_payout" to JsonFloat(c.actualPayout),
                                    "hit_rate" to num(c.hitRate),
                                    "actual_roi" to num(c.actualRoi),
                                )
                            },
                        ),
                    // 実データでの検証は未実施（合成データでの再現性だけを確認済み。T39_DESIGN §8）
                    "note" to JsonString("paper-only; not persisted; no notifications"),
                )
            return CanonicalJson.encode(json)
        }
    }
}
