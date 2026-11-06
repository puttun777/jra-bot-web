package jp.jrabot.bot

import jp.jrabot.domain.backtest.BacktestReport
import jp.jrabot.domain.backtest.BacktestRequest
import jp.jrabot.domain.backtest.ExclusionReason
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.performance.ComboSummary
import jp.jrabot.domain.performance.PerformanceSummary
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.api.Test
import org.slf4j.helpers.NOPLogger
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `--task=backtest` のハンドラ（R16。D-265）: 引数から要求を作り、報告を 1 行の JSON にし、欠けた・空の成績を成功にしない。計算そのものは domain の BacktestRunnerTest。 */
class BacktestTaskHandlerTest {
    private fun command(vararg more: String) = (BotArgsParser.parse(args("backtest", "1", *more)) as ParsedArgs.Valid).command

    private fun report(
        req: BacktestRequest,
        evaluated: Int,
        judgeFailed: Int = 0,
    ) = BacktestReport(
        request = req,
        candidates = 10,
        evaluated = evaluated,
        excluded =
            mapOf(ExclusionReason.NO_ODDS_AT_THAT_TIME to 10 - evaluated - judgeFailed, ExclusionReason.JUDGE_FAILED to judgeFailed)
                .filterValues { it > 0 },
        unsettledBets = 1,
        win = PerformanceSummary(count = 4, hits = 1, totalStake = 12.0, paperPayout = 30.0, actualPayout = 28.0),
        combos = listOf(ComboSummary(Market.WIDE, 2, 1, 2.0, 3.5)),
    )

    private fun handler(run: (BacktestRequest) -> BacktestRun) = BacktestTaskHandler(run, Duration.ofSeconds(90), NOPLogger.NOP_LOGGER)

    @Test
    fun `引数と既定の鮮度から要求を作り 評価できれば 0（BACKTEST_DONE）`() {
        val seen = mutableListOf<BacktestRequest>()
        val h =
            handler { req ->
                seen += req
                BacktestRun.Done(report(req, 8), null, null)
            }
        assertEquals(JobResult(JobExit.OK, "BACKTEST_DONE"), h.execute(command("--from=2026-01-01", "--to=2026-01-31")))
        val req = seen.single()
        assertEquals(listOf(30.0, 5.0), req.minutesBeforePost)
        assertEquals(Duration.ofSeconds(90), req.maxOddsAge, "既定は BOT_RUN_MAX_ODDS_AGE_SECONDS")
        h.execute(command("--from=2026-01-01", "--to=2026-01-31", "--max-odds-age-seconds=30", "--minutes-before-post=10"))
        assertEquals(Duration.ofSeconds(30), seen.last().maxOddsAge)
        assertEquals(listOf(10.0), seen.last().minutesBeforePost)
    }

    @Test
    fun `評価 0 件・判定の失敗・ML のモデルを固定できないときは 1 で 成功にしない`() {
        assertEquals(JobResult(JobExit.FAILURE, "BACKTEST_EMPTY"), handler { BacktestRun.Done(report(it, 0), null, null) }.execute(command()))
        assertEquals(
            JobResult(JobExit.FAILURE, "BACKTEST_JUDGE_FAILED"),
            handler { BacktestRun.Done(report(it, 8, judgeFailed = 1), "ensemble", "ens-1") }.execute(command()),
            "判定の中の読取りの障害が 1 件でもあれば欠けた成績",
        )
        assertEquals(JobResult(JobExit.FAILURE, "BACKTEST_ML_UNAVAILABLE"), handler { BacktestRun.MlUnavailable("ensemble") }.execute(command()))
    }

    @Test
    fun `報告は 1 行の正準 JSON で 件数・率・集計・モデルの ID だけを持ち 除外は全理由を 0 も含めて出す`() {
        val cmd = command()
        val bt = checkNotNull(cmd.backtest)
        val req = BacktestRequest(cmd.tenantId, bt.from, bt.to, listOf(30.0, 5.0), Duration.ofSeconds(90))
        val line = BacktestTaskHandler.render(report(req, 8), "ensemble", "ens-1")
        assertFalse(line.contains('\n'))
        val json = StrictJson.parse(line) as JsonObject
        assertEquals(
            setOf(
                "from",
                "to",
                "minutes_before_post",
                "max_odds_age_seconds",
                "model_type",
                "model_id",
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
        assertEquals(JsonString("ens-1"), json["model_id"])
        assertEquals(ExclusionReason.entries.map { it.name }.toSet(), (json["excluded"] as JsonObject).fields.keys)
        assertTrue(line.contains("\"exclusion_rate\":0.2"), line)
        assertTrue(line.contains("\"market\":\"wide\""), line)
        assertTrue(line.contains("\"actual_roi\":1.75"), line)
        assertTrue(line.contains("in-sample"), "今のモデルを過去に当てる注意を報告に含める")
        assertFalse(line.contains("2026010"), "レース ID などの行の値を出さない")
        val market = StrictJson.parse(BacktestTaskHandler.render(report(req, 8), null, null)) as JsonObject
        assertEquals(JsonNull, market["model_type"], "ML 無効は null")
        val nan = report(req, 8).copy(win = PerformanceSummary(count = 1, hits = 0, totalStake = Double.NaN, paperPayout = 0.0, actualPayout = null))
        assertTrue(BacktestTaskHandler.render(nan, null, null).contains("\"total_stake\":null"), "非有限の数でも報告を失わない")
    }

    @Test
    fun `結果・払戻の保管は読取りだけを通し 書込みは例外にする`() {
        val t = TenantId.of(1)
        val race = RaceId.parse("202610240501")
        val store =
            object : ResultStore, PayoutStore {
                var reads = 0

                override fun upsertAll(
                    tenantId: TenantId,
                    raceId: RaceId,
                    entries: List<ResultEntry>,
                ) = UpsertOutcome.CREATED.also { throw AssertionError("委譲先に書込みが届いた") }

                override fun list(
                    tenantId: TenantId,
                    raceId: RaceId,
                ) = emptyList<ResultEntry>().also { reads++ }

                override fun upsertAll(
                    tenantId: TenantId,
                    raceId: RaceId,
                    market: Market,
                    rows: List<PayoutRow>,
                    provisional: Boolean,
                ) = UpsertOutcome.CREATED.also { throw AssertionError("委譲先に書込みが届いた") }

                override fun list(
                    tenantId: TenantId,
                    raceId: RaceId,
                    market: Market,
                ) = emptyList<StoredPayout>().also { reads++ }
            }
        val ro = ReadOnlyResults(store, store)
        ro.list(t, race)
        ro.list(t, race, Market.WIN)
        assertEquals(2, store.reads)
        assertFailsWith<UnsupportedOperationException> { ro.upsertAll(t, race, emptyList()) }
        assertFailsWith<UnsupportedOperationException> { ro.upsertAll(t, race, Market.WIN, emptyList(), false) }
    }
}
