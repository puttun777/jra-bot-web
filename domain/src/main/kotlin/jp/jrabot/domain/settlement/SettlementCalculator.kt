package jp.jrabot.domain.settlement

/*
 * 結果の決済の純粋な計算（T3.9 ①）。旧 Python（`storage/repo.py` `get_winning_combo_keys`、`evaluation_repo.py` `settle_evaluations_for_race`、
 * `bets_repo.py` `settle_open_win_bets_for_race`）の**計算部分**を DB から切り離して移植したもの。DB の読み書き・トランザクション・時刻は呼び出し側
 * （`ResultSettler`）の責務で、本ファイルは入出力だけで決まる（冪等・時刻非依存）。ゴールデン GD11 で旧実装と一致を確認する。
 *
 * 払戻の単位: 旧 `payouts.payout` は **100 円あたりの払戻金額**（円）で、評価の `payout_used` は `payout / 100`（元本込みの倍率）。
 * 単勝 bets の払戻は `stake × odds_at_bet`（元本込み）。丸めない（旧は丸めていない）。
 * 同着・返還・取消・レース中止の払戻の扱いは旧実装に分岐が無く、JV-Data 仕様の確認後（G-5/G-16）に別途決める（推測で決めない）。
 */

/** 評価の対象にする賭式（旧は wide / sanrenpuku のみ。単勝の評価は決済しない = `no_ticket`）。 */
enum class ComboMarket(
    val code: String,
) {
    WIDE("wide"),
    SANRENPUKU("sanrenpuku"),
    ;

    companion object {
        /** 旧 `_bet_type_to_canon`: 「ワイド」「wide」「三連複」「sanrenpuku」（前後の空白は無視）。それ以外は null。 */
        fun fromBetType(betType: String?): ComboMarket? =
            when (betType?.trim()) {
                "ワイド", "wide" -> WIDE
                "三連複", "sanrenpuku" -> SANRENPUKU
                else -> null
            }
    }
}

/**
 * 組み合わせキーの表記。[LEGACY] は旧 Python（`2-3` のように 0 埋めなし）で、ゴールデン GD11 の比較用。
 * [PADDED] は新 DB の `ComboKeys`（`02-03`）で、実運用（`ResultSettler`）はこちらを使う。
 */
enum class KeyFormat {
    LEGACY,
    PADDED,
}

/** 着順の 1 行（取消などで着順が無い馬は [finish] が null）。 */
data class FinishRow(
    val umaban: Int,
    val finish: Int?,
)

/** 結果から決まる当たりの組み合わせキー（昇順の馬番を `-` でつないだ文字列）。着順が足りなければ null。 */
data class WinningKeys(
    val wide: String?,
    val sanrenpuku: String?,
) {
    fun forMarket(market: ComboMarket): String? =
        when (market) {
            ComboMarket.WIDE -> wide
            ComboMarket.SANRENPUKU -> sanrenpuku
        }
}

/** 評価に紐づく買い目 1 行（`ticket_evaluations`）。 */
data class TicketRef(
    val betType: String,
    val comboKey: String?,
    val rank: Int?,
    val accepted: Int?,
)

/** 評価の `best_candidate_json` を読み取った値（JSON の解釈は呼び出し側。キーが無い/空は null）。 */
data class CandidateView(
    val betType: String? = null,
    val type: String? = null,
    val comboKey: String? = null,
    val kumi: String? = null,
    val combo: String? = null,
    val firstTicketKumi: String? = null,
    val firstTicketComboKey: String? = null,
    val hasTickets: Boolean = false,
)

/** 決済の対象になる最良の組み合わせ（賭式と、昇順に正規化したキー）。 */
data class ResolvedCombo(
    val market: ComboMarket,
    val comboKey: String,
)

/** 評価 1 件の識別と並べ替えに使う時刻（`race_evaluations` の行）。 */
data class EvaluationRef(
    val id: Long,
    val snapshotAt: String?,
    val createdAt: String?,
)

enum class SettleStatus(
    val code: String,
) {
    SETTLED("settled"),
    NO_TICKET("no_ticket"),
}

/**
 * 評価の決済結果。[SettleStatus.NO_TICKET] のときは [hit]・[payoutUsed]・[isProvisional] は**更新しない**（旧は `settled_at` と `settle_status` だけを書く）。
 * [SettleStatus.SETTLED] で [hit] が true かつ [payoutUsed] が null は『的中したが払戻もオッズも無い』（旧挙動）。
 */
data class EvaluationSettlement(
    val status: SettleStatus,
    val hit: Boolean?,
    val payoutUsed: Double?,
    val isProvisional: Boolean,
)

/** 単勝 bets 1 件の決済結果（[payout] は元本込み、[profit] = payout − stake）。 */
data class WinBetSettlement(
    val payout: Double,
    val profit: Double,
)

object SettlementCalculator {
    /**
     * 旧 `get_winning_combo_keys`。着順が正（> 0）の馬を着順の昇順に並べ、上位 2 頭でワイド、上位 3 頭で三連複のキー（馬番の昇順）を作る。
     * 同着は着順が同じ馬を**馬番の昇順**に並べる（旧は SQL の `ORDER BY finish` だけで同値の並びは未規定。SQLite の実際の並び = 主キー順を再現）。
     * 同着が 2・3 着の境目に掛かる場合の当たりは JRA の確定払戻が正で、ここでは決めない（払戻の取込後に `payouts` の行を正とする: T3.9 ②）。
     */
    fun winningKeys(
        rows: List<FinishRow>,
        format: KeyFormat = KeyFormat.LEGACY,
    ): WinningKeys {
        val order =
            rows
                .filter { it.finish != null && it.finish > 0 }
                .sortedWith(compareBy<FinishRow> { it.finish }.thenBy { it.umaban })
                .map { it.umaban }

        fun key(n: Int): String? = if (order.size >= n) order.take(n).sorted().joinToString("-") { fmt(it, format) } else null
        return WinningKeys(wide = key(2), sanrenpuku = key(3))
    }

    /**
     * 旧 `_normalize_combo_key`。`-` で分けた整数を昇順にそろえる。整数にできない部分があれば前後の空白を除いた元の文字列、空なら空文字。
     */
    fun normalizeComboKey(
        selection: String?,
        format: KeyFormat = KeyFormat.LEGACY,
    ): String {
        if (selection.isNullOrEmpty()) return ""
        val parts = selection.split("-").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val numbers = parts.map { it.toIntOrNull() ?: return selection.trim() }
        return numbers.sorted().joinToString("-") { fmt(it, format) }
    }

    private fun fmt(
        n: Int,
        format: KeyFormat,
    ): String = if (format == KeyFormat.PADDED) "%02d".format(n) else n.toString()

    /**
     * 旧 `_resolve_best_combo`。① 買い目（賭式が「ワイド」「三連複」のどちらかで、採用済みまたは順位 1。英語表記は含めない = 旧 SQL どおり）の先頭（採用済み優先 → 順位の昇順。順位なしが先）が空でないキーを持てばそれ。
     * ② なければ `best_candidate` の `bet_type`（なければ `type`）と、`combo_key` → `kumi` → `combo` → `tickets[0]` のキー。賭式かキーが決まらなければ null。
     */
    fun resolveBestCombo(
        tickets: List<TicketRef>,
        candidate: CandidateView?,
        format: KeyFormat = KeyFormat.LEGACY,
    ): ResolvedCombo? {
        val top =
            tickets
                .filter { (it.betType == "ワイド" || it.betType == "三連複") && (it.accepted == 1 || it.rank == 1) }
                .sortedWith(
                    compareByDescending<TicketRef, Int?>(nullsFirst(naturalOrder())) { it.accepted }
                        .thenBy(nullsFirst(naturalOrder())) { it.rank },
                ).firstOrNull()
        if (top != null && !top.comboKey.isNullOrEmpty()) {
            val market = ComboMarket.fromBetType(top.betType)
            if (market != null) return ResolvedCombo(market, normalizeComboKey(top.comboKey, format))
        }
        if (candidate == null) return null
        val market = ComboMarket.fromBetType(candidate.betType?.takeIf { it.isNotEmpty() } ?: candidate.type) ?: return null
        var key = candidate.comboKey?.takeIf { it.isNotEmpty() } ?: candidate.kumi?.takeIf { it.isNotEmpty() } ?: candidate.combo?.takeIf { it.isNotEmpty() }
        if (key == null && candidate.hasTickets) {
            key = candidate.firstTicketKumi?.takeIf { it.isNotEmpty() } ?: candidate.firstTicketComboKey?.takeIf { it.isNotEmpty() }
        }
        if (key == null) return null
        return ResolvedCombo(market, normalizeComboKey(key, format))
    }

    /** 旧 `_race_start_iso`: `date`（8 桁 `yyyyMMdd`）と `post_time`（`HH:MM` は秒を補う。無ければ `00:00`）から `yyyy-MM-ddTHH:MM:SS` を作る。作れなければ null。 */
    fun raceStartIso(
        date: String?,
        postTime: String?,
    ): String? {
        val d = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (d.length != 8) return null
        var pt = postTime?.trim()?.takeIf { it.isNotEmpty() } ?: "00:00"
        if (pt.length == 5) pt += ":00"
        return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}T$pt"
    }

    /** 旧 `_ts_asc`: 比較用の文字列（空・null は空文字、先頭 19 文字、日付だけなら `T00:00:00` を補う）。 */
    private fun tsAsc(ts: String?): String {
        if (ts == null || ts.isBlank()) return ""
        val s = ts.trim().take(19)
        return if (s.length == 10 && !s.contains('T')) s + "T00:00:00" else s
    }

    /**
     * 決済する代表の評価を選ぶ（旧: 発走前の最新 1 件）。並びは `COALESCE(snapshot_at, created_at) DESC, created_at DESC, id DESC`（文字列比較）。
     * 発走時刻が分かれば**発走前（時刻 <= 発走）**の評価に限り、1 件も無ければ全評価の最新。発走時刻が不明なら全評価の最新。評価が無ければ null。
     */
    fun selectRepresentative(
        evaluations: List<EvaluationRef>,
        raceStartIso: String?,
    ): EvaluationRef? {
        val sorted =
            evaluations.sortedWith(
                compareByDescending<EvaluationRef> { it.snapshotAt ?: it.createdAt ?: "" }
                    .thenByDescending { it.createdAt ?: "" }
                    .thenByDescending { it.id },
            )
        val pre =
            if (raceStartIso == null) {
                sorted
            } else {
                sorted.filter { tsAsc(it.snapshotAt?.takeIf { s -> s.isNotEmpty() } ?: it.createdAt) <= raceStartIso }
            }
        return pre.firstOrNull() ?: sorted.firstOrNull()
    }

    /**
     * 旧 `settle_evaluations_for_race` の中核。[resolved] が null、または結果から当たりのキーが決まらなければ `no_ticket`。
     * 的中（キー一致）の払戻: 確定払戻 [rawPayoutPer100]（> 0）なら `/100`（確定）、無ければ [latestComboOdds]（その賭式の最新の組み合わせオッズ）の当たりキーのオッズ（> 0）を暫定値とする。
     * どちらも無ければ [EvaluationSettlement.payoutUsed] は null。外れは 0.0（確定）。
     */
    fun settleEvaluation(
        resolved: ResolvedCombo?,
        winning: WinningKeys,
        rawPayoutPer100: (market: ComboMarket, key: String) -> Double?,
        latestComboOdds: (market: ComboMarket) -> Map<String, Double>,
    ): EvaluationSettlement {
        val noTicket = EvaluationSettlement(SettleStatus.NO_TICKET, null, null, false)
        if (resolved == null || resolved.comboKey.isEmpty()) return noTicket
        val winKey = winning.forMarket(resolved.market) ?: return noTicket
        if (resolved.comboKey != winKey) return EvaluationSettlement(SettleStatus.SETTLED, hit = false, payoutUsed = 0.0, isProvisional = false)
        val raw = rawPayoutPer100(resolved.market, winKey)
        if (raw != null && raw > 0.0) return EvaluationSettlement(SettleStatus.SETTLED, true, raw / 100.0, false)
        val odds = latestComboOdds(resolved.market)[winKey]
        if (odds != null && odds > 0.0) return EvaluationSettlement(SettleStatus.SETTLED, true, odds, true)
        return EvaluationSettlement(SettleStatus.SETTLED, true, null, false)
    }

    /**
     * 旧 `settle_open_win_bets_for_race` の 1 件。着順 [finish] が null（結果に馬が無い）なら null = 未確定のまま。
     * 的中（1 着）かつ stake・odds が正なら払戻 = stake × odds（元本込み）、それ以外は 0。profit = payout − stake。
     */
    fun settleWinBet(
        stake: Double?,
        oddsAtBet: Double?,
        finish: Int?,
    ): WinBetSettlement? {
        if (finish == null) return null
        val s = stake ?: 0.0
        val o = oddsAtBet ?: 0.0
        val payout = if (finish == 1 && s > 0.0 && o > 0.0) s * o else 0.0
        return WinBetSettlement(payout, payout - s)
    }
}
