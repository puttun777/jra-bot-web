package jp.jrabot.domain.notify

import jp.jrabot.domain.decision.PythonCompat

/**
 * 本文の作り方。[LEGACY_COMPAT] は旧実装の癖をそのまま再現する（ゴールデン比較用）、[CORRECTED] は運用用。
 * 差は『ℹ️ 期待利益は投資額計算に使った確率…』の行だけ: 旧実装は集約後の辞書に `prob` が無く 0.0 と比べるため、ほぼ常にこの行を出す（旧欠陥）。
 * [CORRECTED] は本来の意図（生の確率と投資額計算に使った確率が 0.0001 を超えて違うときだけ）で出す。数値は変わらない。
 */
enum class FormatMode { LEGACY_COMPAT, CORRECTED }

/**
 * Discord 通知の本文を作る（旧 `notify/discord.py` の `send_signal` と `send_signals_grouped` の本文部分。配送は含まない）。
 *
 * - 数値の整形は Python の書式（`:.2%` `:.1f` `:+.1f` など）と同じ丸め（[PythonCompat]）。**表示の丸めを計算に使わない**。
 * - 動的な文字列は [DiscordText.neutralizeMentions] を通す。メンションは [MentionPolicy] の `<@id>` だけ。
 * - 欠損値を 0 で埋めない: 無い項目は行ごと出さない。旧実装が『0.00%』などを出していた箇所は、導出できないとき行を出さない（意図した差。PROGRESS）。
 * - 旧実装の『単勝のみの投資戦略（`strategies` なし）』の分岐は移植しない（判定サービスは必ず `strategies` を作る）。
 */
object NoticeFormatter {
    private const val RULE = "━━━━━━━━━━━━━━━━"
    private const val MAX_TICKETS = 6

    private fun esc(s: String) = DiscordText.neutralizeMentions(s)

    private fun f1(v: Double) = PythonCompat.fixed(v, 1)

    private fun f2(v: Double) = PythonCompat.fixed(v, 2)

    private fun signed1(v: Double): String {
        val s = PythonCompat.fixed(v, 1)
        return if (s.startsWith("-")) s else "+$s"
    }

    private fun pct1(v: Double) = PythonCompat.percent(v, 1)

    private fun pct2(v: Double) = PythonCompat.percent(v, 2)

    private fun header(race: RaceLabel): String = "【JRA EV検知】\n**${esc(race.track)} ${race.raceNo ?: ""}R** ${esc(race.name)}\n$RULE\n"

    /** 通知の件名（`Notification.subject`）。 */
    fun subject(race: RaceLabel): String = "JRA EV検知 ${race.track} ${race.raceNo ?: ""}R".take(200)

    /** 旧 `send_signal`（1 シグナル）の本文。 */
    fun formatSingle(
        race: RaceLabel,
        signal: SignalView,
        mention: MentionPolicy = MentionPolicy.NONE,
    ): String {
        val odds = signal.odds
        val prob = signal.prob
        val sb = StringBuilder(header(race))
        sb.append("🎯 **賭式：${esc(signal.betType)}**\n")
        sb.append("🐴 **馬番：${signal.umaban}**\n")
        sb.append("💰 オッズ：**${PythonCompat.repr(odds)}**\n")
        sb.append("📊 推定勝率：${pct2(prob)}\n")
        sb.append("📈 期待値（EV）：**${pct1(signal.ev)}**\n")
        val pMkt = nonZero(signal.pMkt) ?: if (odds > 0) 1.0 / odds else null
        val ratio = nonZero(signal.ratio) ?: if (pMkt != null && pMkt > 0) prob / pMkt else null
        val expectedReturn = nonZero(signal.expectedReturn) ?: if (odds > 0 && prob > 0) odds * prob else null
        if (pMkt != null) sb.append("📉 市場確率：${pct2(pMkt)}\n")
        if (ratio != null) sb.append("📊 倍率（p/mkt）：${f2(ratio)}\n")
        if (expectedReturn != null) sb.append("💰 期待回収：${f2(expectedReturn)}x\n")
        signal.probSource?.let { sb.append("🔍 確率ソース：${esc(it)}\n") }

        val cov = signal.coverage
        if (cov != null && (cov.coverageScore < 1.0 || cov.hasHorseMaster || cov.hasPastRuns)) {
            sb.append("📋 特徴量カバレッジ：${pct2(cov.coverageScore)}")
            if (cov.hasHorseMaster) sb.append(" (master✓)")
            if (cov.hasPastRuns) sb.append(" (past_runs✓, n=${cov.pastN})")
            sb.append('\n')
            cov.betaEff?.let { sb.append("⚙️ 動的β：${PythonCompat.fixed(it, 3)}\n") }
        }
        signal.betAmount?.takeIf { it > 0 }?.let { sb.append("💵 推奨投資額：**${f1(it)}点**\n") }

        var content = sb.toString()
        mention.prefix?.let { content = "$it\n$content" }

        val out = StringBuilder(content)
        signal.bundle?.let { b ->
            out.append("\n💵 **投資戦略**\n")
            for (s in b.strategies) {
                out.append("\n**${esc(s.type)}:**\n")
                out.append("  推奨投資額：**${f1(s.suggestedBet)}点**\n")
                out.append("  オッズ：${f1(s.odds)}倍\n")
                out.append("  勝率：${pct1(s.prob)}\n")
                out.append("  期待値（EV）：${pct1(s.ev)}\n")
                out.append("  期待利益：**${signed1(s.expectedProfit)}点**\n")
                if (!s.note.isNullOrEmpty()) out.append("  ⚠️ ${esc(s.note)}\n")
                if (s.tickets.isNotEmpty()) {
                    out.append("  買い目:\n")
                    for (t in s.tickets.take(MAX_TICKETS)) out.append(ticketLine(t))
                }
            }
            b.best?.let { best ->
                out.append("\n⭐ **最推奨：${esc(best.type)}** （EV=${pct1(best.ev)}, 投資額=${f1(best.suggestedBet)}点）\n")
            }
            out.append("\n📊 **合計投資額：${f1(b.totalSuggestedBet)}点**\n")
            out.append("📈 **合計期待利益：${signed1(b.totalExpectedProfit)}点**\n")
        }
        out.append("💡 理由：${esc(signal.reason.orEmpty())}\n$RULE")
        return out.toString()
    }

    private class Agg(
        val type: String,
    ) {
        var suggestedBet = 0.0
        var expectedProfit = 0.0
        var kelly: KellyView? = null
        var prob = 0.0
    }

    /**
     * 旧 `send_signals_grouped`（1 レースの複数シグナルを 1 つの本文に）。見送り（[SignalView.skipReason]）は戦略へ統合しない。
     * 賭式ごとの推奨額・期待利益は旧実装のとおり**最大値**を採用（合算すると二重計上になるため）、買い目は同じ組合せを 1 つ（EV の高い方）にして EV 降順で上位 6 点。
     */
    fun formatGrouped(
        race: RaceLabel,
        signals: List<SignalView>,
        mention: MentionPolicy = MentionPolicy.NONE,
        mode: FormatMode = FormatMode.CORRECTED,
    ): String {
        require(signals.isNotEmpty()) { "シグナルが空" }
        val sb = StringBuilder()
        mention.prefix?.let { sb.append(it).append('\n') }
        sb.append(header(race))
        if (signals.size > 1) sb.append("**検知シグナル: ${signals.size}件**\n\n")

        val strategies = LinkedHashMap<String, Agg>()
        val ticketsByType = linkedMapOf("単勝" to mutableListOf<TicketView>(), "ワイド" to mutableListOf(), "三連複" to mutableListOf())
        var bestOverall: BestView? = null

        for (signal in signals) {
            val betType = esc(signal.betType)
            val evPct = signal.ev * 100.0
            val skip = signal.skipReason
            if (!skip.isNullOrEmpty()) {
                sb.append("⚠️ **馬番${signal.umaban}** （$betType EV=${f1(evPct)}%） - 見送り\n  ${esc(skip)}\n")
                continue
            }
            sb.append("🐴 **馬番${signal.umaban}** （$betType EV=${f1(evPct)}%）\n")
            signal.betAmount?.takeIf { it > 0 }?.let { sb.append("  推奨投資額（このシグナル）: **${f1(it)}点**\n") }
            val odds = signal.odds
            val p = signal.prob
            if (odds > 0 && p > 0) {
                val pMkt = 1.0 / odds
                val ratio = p / pMkt
                val expReturn = odds * p
                var mkt = "mkt=${pct2(pMkt)}"
                val payout = signal.payoutRate
                if (signal.showAdjustedMkt && payout != null && payout != 0.0) mkt = "mkt=${pct2(pMkt)} (mkt_adj=${pct2(payout / odds)})"
                sb.append("  odds=${f1(odds)} / p=${pct2(p)} / $mkt / ratio=${f2(ratio)} / 期待回収=${f2(expReturn)}x / EV%=${f1(evPct)}%\n")
            }
            val bundle = signal.bundle ?: continue
            for (s in bundle.strategies) {
                val agg = strategies.getOrPut(s.type) { Agg(s.type) }
                agg.suggestedBet = PythonCompat.max(agg.suggestedBet, s.suggestedBet)
                agg.expectedProfit = PythonCompat.max(agg.expectedProfit, s.expectedProfit)
                if (agg.kelly == null && s.kelly != null) {
                    agg.kelly = s.kelly
                    agg.prob = if (mode == FormatMode.LEGACY_COMPAT) 0.0 else s.prob
                }
                if (s.tickets.isNotEmpty()) ticketsByType.getOrPut(s.type) { mutableListOf() }.addAll(s.tickets)
            }
            bundle.best?.let { b ->
                val cur = bestOverall
                if (cur == null || b.ev > cur.ev) bestOverall = b
            }
        }

        sb.append("\n💵 **投資戦略（統合）**\n")
        val shown = mutableListOf<Agg>()
        for (stype in listOf("単勝", "ワイド", "三連複")) {
            val agg = strategies[stype] ?: continue
            shown += agg
            val tickets = NoticeTickets.displayed(ticketsByType[stype].orEmpty())
            sb.append("\n**${esc(stype)}:**\n")
            sb.append("  推奨投資額：**${f1(agg.suggestedBet)}点**\n")
            sb.append("  期待利益：**${signed1(agg.expectedProfit)}点**\n")
            agg.kelly?.let { k ->
                if (!k.skipReason.isNullOrEmpty()) {
                    sb.append("  ⚠️ 見送り: ${esc(k.skipReason)}\n")
                } else if (k.clipped) {
                    sb.append(
                        "  📊 Kelly: raw=${PythonCompat.fixed(
                            k.raw,
                            4,
                        )} adjusted=${PythonCompat.fixed(k.adjusted, 4)} (推奨=${f2(k.betBeforeClip)}点 → max_betでクリップ)\n",
                    )
                } else if (k.betBeforeClip > 0) {
                    sb.append("  📊 Kelly: raw=${PythonCompat.fixed(k.raw, 4)} adjusted=${PythonCompat.fixed(k.adjusted, 4)} (推奨=${f2(k.betBeforeClip)}点)\n")
                }
                val used = k.probUsedForBet
                if (used != null && Math.abs(agg.prob - used) > 0.0001) {
                    sb.append("  ℹ️ 期待利益は投資額計算に使った確率(p=${pct2(used)})で計算\n")
                }
            }
            if (tickets.isNotEmpty()) {
                sb.append("  買い目:\n")
                for (t in tickets) sb.append(ticketLine(t))
            }
        }
        bestOverall?.let { best ->
            val bet = strategies[best.type]?.suggestedBet ?: best.suggestedBet
            sb.append("\n⭐ **最推奨：${esc(best.type)}** （EV=${pct1(best.ev)}, 投資額=${f1(bet)}点）\n")
        }
        val totalBet = PythonCompat.sum(shown.map { it.suggestedBet })
        val totalProfit = PythonCompat.sum(shown.map { it.expectedProfit })
        sb.append("\n📊 **合計投資額：${f1(totalBet)}点**\n📈 **合計期待利益：${signed1(totalProfit)}点**\n")
        sb.append("\n$RULE")
        return sb.toString()
    }

    private fun ticketLine(t: TicketView): String {
        val stake =
            t.stake
                ?.takeIf { it > 0 }
                ?.let { " stake=${f1(it)}点" }
                .orEmpty()
        return "    - ${esc(t.kumi)} odds=${esc(t.oddsText)} p=${pct2(t.prob)} EV=${pct1(t.ev)}$stake\n"
    }

    /** Python の `a or b` の『a が 0.0 でも偽』に合わせる（0.0 は未指定と同じ扱い）。 */
    private fun nonZero(v: Double?): Double? = v?.takeIf { it != 0.0 }
}

object NoticeTickets {
    /** 通知の本文に実際に載せる買い目の上限（賭式ごと）。 */
    const val MAX_DISPLAYED = 6

    /** 通知の本文に載せる買い目の選び方（重複除去 → EV 降順 → 上位 [MAX_DISPLAYED] 点）。成績の集計も同じ選び方にそろえる（実際に通知した買い目だけを数える）。 */
    fun displayed(tickets: List<TicketView>): List<TicketView> = deduplicated(tickets).sortedByDescending { it.ev }.take(MAX_DISPLAYED)

    /** 旧 `_deduplicate_tickets`: `[BOX]` の有無を無視して同じ組合せを 1 つにし、EV が厳密に大きい方を残す（同値は先のもの。順序は初出順）。 */
    fun deduplicated(tickets: List<TicketView>): List<TicketView> {
        val seen = LinkedHashMap<String, TicketView>()
        for (t in tickets) {
            val key = t.kumi.replace("[BOX]", "").trim { it.isWhitespace() }
            val existing = seen[key]
            if (existing == null || t.ev > existing.ev) seen[key] = t
        }
        return seen.values.toList()
    }
}
