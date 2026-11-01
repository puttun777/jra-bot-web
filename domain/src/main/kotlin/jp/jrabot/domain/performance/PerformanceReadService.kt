package jp.jrabot.domain.performance

import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.notify.NoticeSnapshot
import jp.jrabot.domain.notify.NoticeSnapshotException
import jp.jrabot.domain.notify.NoticeTickets
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.SignalReader
import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate

/** 成績の集計結果（読取り API の元）。数えなかったものは理由別に件数を出す（黙って落とさない）。 */
data class PerformanceReport(
    val tenantId: TenantId,
    val from: LocalDate,
    val to: LocalDate,
    val win: PerformanceSummary,
    val combos: List<ComboSummary>,
    /** 通知した単勝 Signal のうち、結果・払戻がまだ無い／見送り等で損益に数えなかった数。 */
    val winUnsettled: Int,
    /** 通知した買い目のうち数えなかった数（賭式別でなく合計）。 */
    val comboUnsettled: Int,
    /** 発走時刻が不明で代表を選べず対象外にしたレース数。 */
    val racesWithoutPostTime: Int,
    /** 保存した通知内容が壊れていて読めなかった数（その通知の買い目は数えない）。 */
    val unreadableNotices: Int,
)

/**
 * 紙上成績の読取り（D-234: 保存せず、`signals` + `race_timing_notice` + `results` + `payouts` から毎回計算する）。対象は**通知済み（SENT）の Signal**と、
 * 通知内容のスナップショットにある買い目（ワイド・三連複）。テナント単位・期間は 366 日以内。ここは読取りだけで、何も書かない。
 */
class PerformanceReadService(
    private val schedules: RaceScheduleReader,
    private val signals: SignalReader,
    private val timings: TimingNoticeStore,
    private val results: ResultStore,
    private val payouts: PayoutStore,
) {
    fun report(
        tenantId: TenantId,
        from: LocalDate,
        to: LocalDate,
    ): PerformanceReport {
        require(!to.isBefore(from)) { "to は from 以降" }
        require(
            java.time.temporal.ChronoUnit.DAYS
                .between(from, to) <= MAX_DAYS,
        ) { "期間は $MAX_DAYS 日以内" }
        val winItems = mutableListOf<SettledSignal>()
        val comboItems = mutableListOf<SettledCombo>()
        var winUnsettled = 0
        var comboUnsettled = 0
        var noPost = 0
        var unreadable = 0
        var date = from
        while (!date.isAfter(to)) {
            for (race in schedules.listByDate(tenantId, date)) {
                val postAt = race.scheduledPostAt?.takeIf { !race.postTimeUnknown }
                val sent = signals.listByRace(tenantId, race.raceId).filter { it.market == Market.WIN && it.state == NotificationState.SENT }
                val notices = timings.list(tenantId, race.raceId)
                if (postAt == null) {
                    if (sent.isNotEmpty() || notices.isNotEmpty()) noPost++
                    continue
                }
                if (sent.isEmpty() && notices.isEmpty()) continue
                val finishers = results.list(tenantId, race.raceId)
                for (s in sent) {
                    val outcome =
                        PaperSettlement.settleWin(
                            WinSignal(race.raceId, s.umaban.value, s.observedAt, s.odds.value, s.stake),
                            finishers,
                            payouts.list(tenantId, race.raceId, Market.WIN),
                        )
                    if (outcome is PaperOutcome.Settled) {
                        winItems +=
                            SettledSignal(
                                race.raceId,
                                s.umaban.value,
                                s.observedAt,
                                postAt,
                                outcome,
                            )
                    } else {
                        winUnsettled++
                    }
                }
                for (n in notices) {
                    val snapshot =
                        try {
                            NoticeSnapshot.decode(n.noticeSchema, n.noticeJson, n.noticeSha256)
                        } catch (_: NoticeSnapshotException) {
                            unreadable++
                            continue
                        }
                    // 通知の本文に実際に載せた買い目だけを数える（見送りの item を除き、賭式ごとに重複除去 → EV 降順 → 上位 N 点: NoticeTickets.displayed）
                    val byMarket = LinkedHashMap<Market, MutableList<jp.jrabot.domain.notify.TicketView>>()
                    for (item in snapshot.items.filter { it.view.skipReason == null }) {
                        for (strategy in item.view.bundle
                            ?.strategies
                            .orEmpty()) {
                            val market =
                                when (strategy.type) {
                                    BetType.WIDE.label -> Market.WIDE
                                    BetType.SANRENPUKU.label -> Market.SANRENPUKU
                                    else -> continue
                                }
                            byMarket.getOrPut(market) { mutableListOf() } += strategy.tickets
                        }
                    }
                    for ((market, all) in byMarket) {
                        val pay = payouts.list(tenantId, race.raceId, market)
                        for (t in NoticeTickets.displayed(all)) {
                            val stake = t.stake?.takeIf { it > 0.0 } ?: continue
                            val key = ComboPaperSettlement.comboKey(market, t.kumi)
                            val outcome =
                                ComboPaperSettlement.settle(
                                    ComboTicketInput(race.raceId, market, t.kumi, stake, n.oddsObservedAt, postAt),
                                    pay,
                                    finishers,
                                )
                            if (outcome is ComboOutcome.Settled && key != null) {
                                comboItems += SettledCombo(race.raceId, market, key, n.oddsObservedAt, postAt, outcome)
                            } else {
                                comboUnsettled++
                            }
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return PerformanceReport(
            tenantId,
            from,
            to,
            PerformanceAggregator.representative(winItems),
            ComboPerformance.representative(comboItems),
            winUnsettled,
            comboUnsettled,
            noPost,
            unreadable,
        )
    }

    companion object {
        const val MAX_DAYS = 366L
    }
}
