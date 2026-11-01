package jp.jrabot.domain.performance

import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.odds.ComboKeys
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import java.time.Instant

/*
 * ワイド・三連複の買い目の**紙上決済**（購入していない仮想の損益）。DB には書かず、通知のスナップショット（`race_timing_notice` の買い目）× 払戻（`payouts`）から
 * 毎回計算する純粋関数（D-234）。**的中の判定は取り込んだ確定払戻の行の有無で行う**（着順から組み合わせを推測しない）: ワイドは 3 着までの任意の 2 頭が的中
 * （同着で 4 組以上になることがある）、三連複は 3 着までの 3 頭だが、JRA が公表した払戻の行がそのまま正になる。
 * ※ 旧実装のワイドは『1・2 着の組み合わせ』だけを的中とみなしていた（E17）。実際の払戻とずれるので引き継がず、払戻の行に従う（意図した差異）。
 * 実払戻 = 推奨額 × 100 円あたりの払戻 / 100。通知時の予測オッズ（範囲表示）は払戻の根拠ではないので使わない。
 */

enum class ComboUnsettledReason { NOT_A_BET, UNSUPPORTED_MARKET, UNPARSEABLE_KUMI, NO_CONFIRMED_PAYOUT }

sealed interface ComboOutcome {
    data class Unsettled(
        val reason: ComboUnsettledReason,
    ) : ComboOutcome

    /** [actualPayout] は的中なら stake × 払戻 / 100、外れなら 0。 */
    data class Settled(
        val hit: Boolean,
        val stake: Double,
        val actualPayout: Double,
    ) : ComboOutcome {
        val actualProfit: Double get() = actualPayout - stake
    }
}

/** 通知した買い目 1 点。[kumi] は表示名（`02-03`・`[BOX]02-03-05` など。数字の並びだけを読む）。 */
data class ComboTicketInput(
    val raceId: RaceId,
    val market: Market,
    val kumi: String,
    val stake: Double,
    val observedAt: Instant,
    val postAt: Instant,
)

object ComboPaperSettlement {
    /** 表示名から組み合わせキー（0 埋め・昇順）へ。ワイド 2 頭・三連複 3 頭以外、重複、範囲外は null。 */
    fun comboKey(
        market: Market,
        kumi: String,
    ): String? {
        val numbers = Regex("""\d+""").findAll(kumi).map { it.value.toIntOrNull() }.toList()
        if (numbers.any { it == null }) return null
        val n = numbers.filterNotNull()
        return when (market) {
            Market.WIDE -> if (n.size == 2) ComboKeys.wide(n[0], n[1]) else null
            Market.SANRENPUKU -> if (n.size == 3) ComboKeys.sanrenpuku(n[0], n[1], n[2]) else null
            else -> null
        }
    }

    /** [payouts] はこのレース・この賭式の払戻。**確定（provisional = false）の行だけ**を使い、1 行も無ければ判定できない（外れにしない）。 */
    fun settle(
        ticket: ComboTicketInput,
        payouts: List<StoredPayout>,
    ): ComboOutcome {
        if (ticket.market != Market.WIDE && ticket.market != Market.SANRENPUKU) return ComboOutcome.Unsettled(ComboUnsettledReason.UNSUPPORTED_MARKET)
        if (!(ticket.stake > 0.0)) return ComboOutcome.Unsettled(ComboUnsettledReason.NOT_A_BET)
        val key = comboKey(ticket.market, ticket.kumi) ?: return ComboOutcome.Unsettled(ComboUnsettledReason.UNPARSEABLE_KUMI)
        val confirmed = payouts.filter { !it.provisional }
        if (confirmed.isEmpty()) return ComboOutcome.Unsettled(ComboUnsettledReason.NO_CONFIRMED_PAYOUT)
        val per100 = confirmed.firstOrNull { it.comboKey == key }?.payout
        return if (per100 != null) {
            ComboOutcome.Settled(true, ticket.stake, ticket.stake * per100 / 100.0)
        } else {
            ComboOutcome.Settled(false, ticket.stake, 0.0)
        }
    }
}

/** 集計の入力 1 件。 */
data class SettledCombo(
    val raceId: RaceId,
    val market: Market,
    val comboKey: String,
    val observedAt: Instant,
    val postAt: Instant,
    val outcome: ComboOutcome.Settled,
)

/** 賭式別の集計。サンプルが 0 件の指標は null。 */
data class ComboSummary(
    val market: Market,
    val count: Int,
    val hits: Int,
    val totalStake: Double,
    val actualPayout: Double,
) {
    val hitRate: Double? get() = if (count == 0) null else hits.toDouble() / count
    val actualProfit: Double get() = actualPayout - totalStake
    val actualRoi: Double? get() = if (totalStake <= 0.0) null else actualPayout / totalStake
}

object ComboPerformance {
    /**
     * **代表集計**: 同じ (レース, 賭式, 組み合わせ) の買い目が複数のタイミングで通知されているとき、発走前（観測時刻 <= 発走）の**最新の 1 件**だけを数える
     * （30 分前と 5 分前に同じ組み合わせを推奨した分を二重計上しない）。発走後の観測は数えない。入力の並びに依存しない。賭式ごとに集計する。
     */
    fun representative(items: List<SettledCombo>): List<ComboSummary> =
        items
            .filter { !it.observedAt.isAfter(it.postAt) }
            .groupBy { Triple(it.raceId, it.market, it.comboKey) }
            .values
            .map { g -> g.maxBy { it.observedAt } }
            .groupBy { it.market }
            .toSortedMap(compareBy { it.name })
            .map { (market, rows) ->
                ComboSummary(market, rows.size, rows.count { it.outcome.hit }, rows.sumOf { it.outcome.stake }, rows.sumOf { it.outcome.actualPayout })
            }
}
