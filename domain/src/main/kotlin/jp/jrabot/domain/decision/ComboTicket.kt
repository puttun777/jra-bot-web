package jp.jrabot.domain.decision

import java.util.Locale

/** 買い目のオッズの出所。`label` は旧実装の `source`。 */
enum class OddsSource(
    val label: String,
) {
    /** JRA が公表した実オッズ（[ComboOdds]）。 */
    REAL("実オッズ"),

    /** 単勝オッズからの推定（[ComboOddsEstimate]）。`require_real_odds_for_combo=false` のときだけ使う。 */
    ESTIMATED("推定"),
}

/**
 * ワイド・三連複の 1 点の買い目（旧 `tickets[]` の dict）。確率・EV・金額の基準を混同しない:
 * - [probRaw] = Plackett–Luce の生の確率（`prob`）。[probUsed] = 縮小 → `*_max_prob` → 市場比上限 → （`*_min_prob` で足切り）後の確率（`prob_used`）。
 * - [ev] は [probUsed] と [odds] で計算（`ev > 0` の買い目だけが残る）。ワイドの [odds] は実オッズの**下限**。
 * - [stake] は旧実装の「点」。賭け額が 0 のときは null（旧実装は `stake` キー自体が無い）、`best_only` の 2 点目以降は 0.0。
 * - [kumi] は旧実装の表示名（BOX 由来は `[BOX]` 付き。流しと BOX で同じ組合せになり得る → [ComboTickets.deduplicated]）。
 */
data class ComboTicket(
    val kumi: String,
    val odds: Double,
    val oddsNote: String,
    val probRaw: ProbRaw,
    val probUsed: ProbUsed,
    val ev: Ev,
    val source: OddsSource,
    val stake: Double? = null,
) {
    val isBox: Boolean get() = kumi.startsWith(BOX_PREFIX)

    internal fun scaledStake(scale: Double): ComboTicket = if (stake == null) this else copy(stake = stake * scale)

    companion object {
        const val BOX_PREFIX = "[BOX]"

        /** 旧 `f"{n:02d}"`。 */
        internal fun two(n: Int): String = String.format(Locale.ROOT, "%02d", n)
    }
}

object ComboTickets {
    /**
     * 同じ組合せの買い目を 1 つにまとめる（旧 `notify/discord.py` の `_deduplicate_tickets` と同じ規則）。`[BOX]` の有無は無視して同一視し、
     * **EV が厳密に大きい方**を残す（同値は先に出たもの）。結果の順序は組合せが最初に現れた順。
     *
     * 旧実装はこれを**通知の層**（複数シグナルの買い目を集めた後）で行う。この版は `:domain` に同じ規則を置くが、
     * [BetStrategyBundle] の買い目リスト自体は**重複を含んだまま**（旧実装と同じ）にする: 点数上限（`max_tickets_per_type`）の切り詰めと
     * `proportional_ev` の按分は重複を含んだ状態で計算されるので、生成の段階で除くと賭け額・期待利益が変わる（未承認の数値変更）。
     * 通知（T3.7）は、これを通して表示する買い目を作る。
     */
    fun deduplicated(tickets: List<ComboTicket>): List<ComboTicket> {
        val seen = LinkedHashMap<String, ComboTicket>()
        for (t in tickets) {
            val key = t.kumi.replace(ComboTicket.BOX_PREFIX, "").trim { it.isWhitespace() }
            val existing = seen[key]
            if (existing == null || t.ev.value > existing.ev.value) seen[key] = t
        }
        return seen.values.toList()
    }
}
