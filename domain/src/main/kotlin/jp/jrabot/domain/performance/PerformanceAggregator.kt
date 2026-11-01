package jp.jrabot.domain.performance

import jp.jrabot.domain.race.RaceId
import java.time.Instant

/** 集計の入力 1 件: 確定済みの Signal の紙上成績。[postAt] が不明なら代表を選べないので入力に含めない（呼び出し側が除外して件数を報告する）。 */
data class SettledSignal(
    val raceId: RaceId,
    val umaban: Int,
    val observedAt: Instant,
    val postAt: Instant,
    val outcome: PaperOutcome.Settled,
)

/** 集計結果。サンプルが 0 件の指標は null（0 で埋めない）。 */
data class PerformanceSummary(
    val count: Int,
    val hits: Int,
    val totalStake: Double,
    val paperPayout: Double,
    val actualPayout: Double?,
) {
    val hitRate: Double? get() = if (count == 0) null else hits.toDouble() / count
    val paperProfit: Double get() = paperPayout - totalStake
    val paperRoi: Double? get() = if (totalStake <= 0.0) null else paperPayout / totalStake
    val actualProfit: Double? get() = actualPayout?.let { it - totalStake }
    val actualRoi: Double? get() = if (totalStake <= 0.0) null else actualPayout?.let { it / totalStake }
}

object PerformanceAggregator {
    /**
     * **代表集計**（既定）: 同じ (レース, 馬番) の Signal が複数タイミング（30 分前・5 分前など）にあるとき、**発走前（観測時刻 <= 発走）の最新の 1 件**だけを数える
     * （複数タイミングを足すと同じ馬への推奨額・損益を二重計上する。旧は『発走前の最新 1 件』）。発走後の観測は数えない。入力の並びに依存しない。
     */
    fun representative(signals: List<SettledSignal>): PerformanceSummary =
        summarize(
            signals
                .filter { !it.observedAt.isAfter(it.postAt) }
                .groupBy { it.raceId to it.umaban }
                .values
                .map { g -> g.maxBy { it.observedAt } },
        )

    /** タイミング別の分析（代表集計とは別の見出しで使う。**代表集計と足し合わせない**）。キーは発走の何分前か（切り捨て）。 */
    fun byTiming(signals: List<SettledSignal>): Map<Long, PerformanceSummary> =
        signals
            .filter { !it.observedAt.isAfter(it.postAt) }
            .groupBy {
                java.time.Duration
                    .between(it.observedAt, it.postAt)
                    .toMinutes()
            }.toSortedMap()
            .mapValues { summarize(it.value) }

    private fun summarize(items: List<SettledSignal>): PerformanceSummary {
        val withActual = items.mapNotNull { it.outcome.actualPayout }
        return PerformanceSummary(
            count = items.size,
            hits = items.count { it.outcome.hit },
            totalStake = items.sumOf { it.outcome.stake },
            paperPayout = items.sumOf { it.outcome.paperPayout },
            // 実払戻が全件そろったときだけ出す（一部だけの合計は ROI を歪めるので出さない）
            actualPayout = if (items.isNotEmpty() && withActual.size == items.size) withActual.sum() else null,
        )
    }
}
