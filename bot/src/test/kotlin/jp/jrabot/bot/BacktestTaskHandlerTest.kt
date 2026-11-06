package jp.jrabot.bot

import jp.jrabot.domain.backtest.BacktestReport
import jp.jrabot.domain.backtest.BacktestRequest
import jp.jrabot.domain.backtest.ExclusionReason
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.performance.ComboSummary
import jp.jrabot.domain.performance.PerformanceSummary
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `--task=backtest` のハンドラ（R16。D-265）: 引数から要求を作り、報告を 1 行の JSON にし、評価 0 件を成功にしない。計算そのものは domain の BacktestRunnerTest。 */
class BacktestTaskHandlerTest {
    private fun command(vararg more: String) = (BotArgsParser.parse(args("backtest", "1", *more)) as ParsedArgs.Valid).command

    private fun report(
        req: BacktestRequest,
        evaluated: Int,
    ) = BacktestReport(
        request = req,
        candidates = 10,
        evaluated = evaluated,
        excluded = mapOf(ExclusionReason.NO_ODDS_AT_THAT_TIME to 10 - evaluated),
        unsettledBets = 1,
        win = PerformanceSummary(count = 4, hits = 1, totalStake = 12.0, paperPayout = 30.0, actualPayout = 28.0),
        combos = listOf(ComboSummary(Market.WIDE, 2, 1, 2.0, 3.5)),
    )

    @Test
    fun `引数と既定の鮮度から要求を作り 評価できれば 0（BACKTEST_DONE）`() {
        val seen = mutableListOf<BacktestRequest>()
        val h =
            BacktestTaskHandler({ req ->
                seen += req
                report(req, 8)
            }, Duration.ofSeconds(90), NOPLogger.NOP_LOGGER)
        assertEquals(JobResult(JobExit.OK, "BACKTEST_DONE"), h.execute(command("--from=2026-01-01", "--to=2026-01-31")))
        val req = seen.single()
        assertEquals(listOf(30.0, 5.0), req.minutesBeforePost)
        assertEquals(Duration.ofSeconds(90), req.maxOddsAge, "既定は BOT_RUN_MAX_ODDS_AGE_SECONDS")
        h.execute(command("--from=2026-01-01", "--to=2026-01-31", "--max-odds-age-seconds=30", "--minutes-before-post=10"))
        assertEquals(Duration.ofSeconds(30), seen.last().maxOddsAge)
        assertEquals(listOf(10.0), seen.last().minutesBeforePost)
    }

    @Test
    fun `評価が 0 件なら 1（BACKTEST_EMPTY）で 空の成績を成功にしない`() {
        val h = BacktestTaskHandler({ req -> report(req, 0) }, Duration.ofSeconds(90), NOPLogger.NOP_LOGGER)
        assertEquals(JobResult(JobExit.FAILURE, "BACKTEST_EMPTY"), h.execute(command()))
    }

    @Test
    fun `報告は 1 行の正準 JSON で 件数・率・集計だけを持ち 除外は全理由を 0 も含めて出す`() {
        val cmd = command()
        val bt = checkNotNull(cmd.backtest)
        val req = BacktestRequest(cmd.tenantId, bt.from, bt.to, listOf(30.0, 5.0), Duration.ofSeconds(90))
        val line = BacktestTaskHandler.render(report(req, 8))
        assertFalse(line.contains('\n'))
        val json = StrictJson.parse(line) as JsonObject
        assertEquals(
            setOf(
                "from",
                "to",
                "minutes_before_post",
                "max_odds_age_seconds",
                "candidates",
                "evaluated",
                "excluded",
                "excluded_total",
                "exclusion_rate",
                "unsettled_bets",
                "win",
                "combos",
                "note",
            ),
            json.fields.keys,
        )
        val excluded = json["excluded"] as JsonObject
        assertEquals(ExclusionReason.entries.map { it.name }.toSet(), excluded.fields.keys)
        assertTrue(line.contains("\"exclusion_rate\":0.2"), line)
        assertTrue(line.contains("\"market\":\"wide\""), line)
        assertTrue(line.contains("\"actual_roi\":1.75"), line)
        assertFalse(line.contains("2026010"), "レース ID などの行の値を出さない")
    }
}
