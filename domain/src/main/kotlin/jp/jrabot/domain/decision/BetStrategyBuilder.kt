package jp.jrabot.domain.decision

/**
 * 1 頭（軸）分の戦略一式を作る。参照元 `ev/bet_strategy.py` の `suggest_multiple_bet_strategies` の移植。
 *
 * - 単勝: 確率を縮小（[WinStake.probUsed]）→ EV → Kelly の賭け額 → 期待利益。
 * - ワイド（軸＋相手の流し ＋ 軸が BOX 候補内のときの BOX）／ 三連複（軸＋相手 2 頭の流し ＋ BOX）: 買い目ごとに
 *   実オッズ（無ければ `require_real_odds_for_combo=false` のときだけ単勝からの推定オッズ）→ Plackett–Luce 確率（[PlackettLuce]）→
 *   縮小（`ev_shrink_factor`）→ `*_max_prob` → 市場比上限 `*_max_over_market` → `*_min_prob` で足切り → EV > 0 の順（E8。この順序は変えない）。
 *   EV 降順（同値は生成順）で `max(1, max_tickets_per_type)` 点に絞り、**最良の 1 点**の Kelly を賭け額とし、`ticket_allocation` で買い目へ配分する。
 *   買い目が 1 点も無いとき、`require_real_odds_for_combo=true` なら『実オッズ未取得』の**見送りエントリ**（ワイド・三連複とも。E11 は訂正済み: D-109③）、`false` ならエントリなし。
 * - 最推奨: 賭け額 > 0 のものの中で EV 最大、無ければ全体で EV 最大（**見送りエントリが最推奨になり得る**: 旧欠陥 E7。同値は先に挿入されたものが残る）。
 *
 * 旧実装の dict の挿入順は 単勝 → ワイド → 三連複。`max()` の同値の扱い（先勝ち）はこの順序に依存する。
 * 数値の近似・順序の変更はしない。**重複した買い目（流しと BOX が同じ組合せ）も除かない**（点数上限・按分の数値が変わるため。除去は [ComboTickets.deduplicated]）。
 */
object BetStrategyBuilder {
    private const val COMBO_SKIP_REASON = "実オッズ未取得のため見送り（推定オッズは使用しない設定）"
    private const val COMBO_SKIP_NOTE = "※ 実オッズが取れた場合のみ判定/投資します"
    private const val NOTE_ESTIMATED = "※ オッズは推定値"
    private const val NOTE_WIDE_REAL = "※ 実オッズ優先（低い方でEV計算）"
    private const val NOTE_SANRENPUKU_REAL = "※ 実オッズ優先"
    private const val RANGE_SEPARATOR = "〜" // 〜（WAVE DASH。旧実装の文字）

    /** 戦略が 1 つも作れない（対象の賭式が無い）ときは null。単勝で `odds == 1.0`（確率 > 0）のときは [LegacyZeroDivisionException]。 */
    fun build(
        umaban: Int,
        probRaw: ProbRaw,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
        race: ComboRaceContext,
    ): BetStrategyBundle? {
        val entries = mutableListOf<StrategyEntry>()
        if (config.allows(BetType.WIN)) entries += winEntry(umaban, probRaw, winOdds, config, stake)
        if (config.allows(BetType.WIDE)) wideEntry(umaban, winOdds, config, stake, race)?.let { entries += it }
        if (config.allows(BetType.SANRENPUKU)) sanrenpukuEntry(umaban, winOdds, config, stake, race)?.let { entries += it }
        if (entries.isEmpty()) return null

        val candidates = entries.filter { it.suggestedBet > 0 }.ifEmpty { entries }
        var best = candidates.first()
        for (e in candidates.drop(1)) if (e.ev.value > best.ev.value) best = e

        val bundle =
            BetStrategyBundle(
                strategies = entries,
                best = best,
                totalSuggestedBet = PythonCompat.sum(entries.map { it.suggestedBet }),
                totalExpectedProfit = PythonCompat.sum(entries.map { it.expectedProfit }),
                meta =
                    StrategyMeta(
                        requireRealOddsForCombo = config.requireRealOddsForCombo,
                        ticketAllocation = config.ticketAllocation.ifEmpty { "best_only" },
                        probSource = config.probSource.ifEmpty { "unknown" },
                        kellyFractionInput = config.kellyFraction,
                        kellyFractionEffective = stake.kellyFractionAdjusted(config.kellyFraction),
                        evShrinkFactor = stake.evShrinkFactorForMeta(),
                    ),
            )
        return if (config.betTypeMode == BetTypeMode.BEST_ONLY) bundle.prunedToBest() else bundle
    }

    private fun winEntry(
        umaban: Int,
        probRaw: ProbRaw,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
    ): StrategyEntry {
        val probUsed = stake.probUsed(probRaw)
        val winEv = EvMath.calcEv(probUsed.value, winOdds)
        val info = stake.calcBetAmount(probUsed, winOdds)
        val winBet = info.suggestedBet
        val expectedProfit = if (winBet > 0) EvMath.calcExpectedProfit(winBet, probUsed.value, winOdds) else 0.0
        return StrategyEntry(
            type = BetType.WIN,
            umaban = umaban,
            odds = winOdds,
            probRaw = probRaw,
            probUsed = probUsed,
            ev = Ev(winEv),
            suggestedBet = winBet,
            expectedProfit = expectedProfit,
            kellyFraction = stake.displayKellyFraction(probUsed, winOdds),
            kellyRaw = info.kellyRaw,
            kellyAdjusted = info.kellyAdjusted,
            kellyBetBeforeClip = info.kellyBetBeforeClip,
            clipped = info.clipped,
            skipReason = info.skipReason,
            // 旧実装は賭けの方式の引数がローカル変数 `bet_strategy = None` に隠れるため、ラベルは常に "None"
            strategy = "投資戦略: None (Kelly分数=${PythonCompat.fixed(config.kellyFraction, 2)})",
        )
    }

    // ---- ワイド ----------------------------------------------------------------------------------------------------

    private fun wideEntry(
        axis: Int,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
        race: ComboRaceContext,
    ): StrategyEntry? {
        val s = config.combo
        val require = config.requireRealOddsForCombo
        val tickets = mutableListOf<ComboTicket>()

        fun offer(
            a: Int,
            b: Int,
            real: WideOddsRange?,
            estimateBase: () -> Double,
            prefix: String,
        ) {
            val key = if (a < b) a to b else b to a
            val oddsUsed: Double
            val note: () -> String
            val source: OddsSource
            if (real != null) {
                oddsUsed = real.low // 保守的に低い方で EV
                note = { "${PythonCompat.fixed(real.low, 1)}$RANGE_SEPARATOR${PythonCompat.fixed(real.high, 1)}" }
                source = OddsSource.REAL
            } else {
                if (require) return
                oddsUsed = ComboOddsEstimate.wide(estimateBase())
                note = { "${PythonCompat.fixed(oddsUsed, 1)}（推定）" }
                source = OddsSource.ESTIMATED
            }
            val raw = race.pl.widePairProb(a, b)
            priceTicket(
                kumi = { "$prefix${ComboTicket.two(key.first)}-${ComboTicket.two(key.second)}" },
                raw = raw,
                oddsUsed = oddsUsed,
                oddsNote = note,
                source = source,
                maxProb = s.wideMaxProb,
                minProb = s.wideMinProb,
                payoutRate = config.widePayoutRate,
                maxOverMarket = s.wideMaxOverMarket,
                stake = stake,
            )?.let { tickets += it }
        }

        // 流し: 軸 × 人気上位の相手
        for (p in race.partnersByPopularity(axis).take(maxOf(1, s.topKPartners))) {
            offer(axis, p, race.realOdds.wide(axis, p), { winOdds }, "")
        }
        // BOX: 軸が BOX 候補に入っているときだけ
        val box = race.boxCandidates
        if (box.size >= 3 && axis in box) {
            for (i in box.indices) {
                for (j in i + 1 until box.size) {
                    val a = box[i]
                    val b = box[j]
                    offer(a, b, race.realOdds.wide(a, b), { PythonCompat.min(race.winOddsOf(a) ?: winOdds, race.winOddsOf(b) ?: winOdds) }, ComboTicket.BOX_PREFIX)
                }
            }
        }

        return comboEntry(
            type = BetType.WIDE,
            axis = axis,
            tickets = tickets,
            noteWhenReal = if (race.realOdds.hasWide) NOTE_WIDE_REAL else NOTE_ESTIMATED,
            config = config,
            stake = stake,
        )
    }

    // ---- 三連複 ----------------------------------------------------------------------------------------------------

    private fun sanrenpukuEntry(
        axis: Int,
        winOdds: Double,
        config: WinDecisionConfig,
        stake: WinStake,
        race: ComboRaceContext,
    ): StrategyEntry? {
        val s = config.combo
        val require = config.requireRealOddsForCombo
        val tickets = mutableListOf<ComboTicket>()

        fun offer(
            a: Int,
            b: Int,
            c: Int,
            estimateBase: () -> Double,
            prefix: String,
        ) {
            val sorted = intArrayOf(a, b, c).also { it.sort() }
            val real = race.realOdds.sanrenpuku(a, b, c)
            val oddsUsed: Double
            val note: () -> String
            val source: OddsSource
            // 旧実装は `if odds_val:`。0.0 は『無い』扱い
            if (real != null && real != 0.0) {
                oddsUsed = real
                note = { PythonCompat.fixed(real, 1) }
                source = OddsSource.REAL
            } else {
                if (require) return
                oddsUsed = ComboOddsEstimate.trifecta(estimateBase())
                note = { "${PythonCompat.fixed(oddsUsed, 1)}（推定）" }
                source = OddsSource.ESTIMATED
            }
            val raw = race.pl.top3SetProb(a, b, c)
            priceTicket(
                kumi = { "$prefix${ComboTicket.two(sorted[0])}-${ComboTicket.two(sorted[1])}-${ComboTicket.two(sorted[2])}" },
                raw = raw,
                oddsUsed = oddsUsed,
                oddsNote = note,
                source = source,
                maxProb = s.sanrenpukuMaxProb,
                minProb = s.sanrenpukuMinProb,
                payoutRate = config.sanrenpukuPayoutRate,
                maxOverMarket = s.sanrenpukuMaxOverMarket,
                stake = stake,
            )?.let { tickets += it }
        }

        // 2 頭流し: 軸 + 人気上位の相手 2 頭の全組合せ（相手は max(3, top_k_partners) 頭）
        val partners = race.partnersByPopularity(axis).take(maxOf(3, s.topKPartners))
        for (i in partners.indices) {
            for (j in i + 1 until partners.size) {
                offer(axis, partners[i], partners[j], { winOdds }, "")
            }
        }
        // BOX: 軸が BOX 候補に入っているときだけ
        val box = race.boxCandidates
        if (box.size >= 3 && axis in box) {
            for (i in box.indices) {
                for (j in i + 1 until box.size) {
                    for (k in j + 1 until box.size) {
                        val a = box[i]
                        val b = box[j]
                        val c = box[k]
                        offer(
                            a,
                            b,
                            c,
                            {
                                PythonCompat.min(
                                    PythonCompat.min(race.winOddsOf(a) ?: winOdds, race.winOddsOf(b) ?: winOdds),
                                    race.winOddsOf(c) ?: winOdds,
                                )
                            },
                            ComboTicket.BOX_PREFIX,
                        )
                    }
                }
            }
        }

        return comboEntry(
            type = BetType.SANRENPUKU,
            axis = axis,
            tickets = tickets,
            noteWhenReal = if (race.realOdds.hasSanrenpuku) NOTE_SANRENPUKU_REAL else NOTE_ESTIMATED,
            config = config,
            stake = stake,
        )
    }

    // ---- 共通 ------------------------------------------------------------------------------------------------------

    /** 縮小 → 上限 → 市場比上限 → 下限 → EV > 0（E8 の順）。通らなければ null。 */
    private fun priceTicket(
        kumi: () -> String,
        raw: Double,
        oddsUsed: Double,
        oddsNote: () -> String,
        source: OddsSource,
        maxProb: Double,
        minProb: Double,
        payoutRate: Double,
        maxOverMarket: Double,
        stake: WinStake,
    ): ComboTicket? {
        var p = stake.probUsed(ProbRaw(raw)).value
        if (maxProb > 0) p = PythonCompat.min(p, maxProb)
        p = capOverMarket(p, oddsUsed, payoutRate, maxOverMarket)
        if (minProb > 0 && p < minProb) return null
        val ev = EvMath.calcEv(p, oddsUsed)
        if (ev <= 0) return null
        // 表示用の文字列は、残る買い目だけ作る（数値には影響しない）
        return ComboTicket(kumi(), oddsUsed, oddsNote(), ProbRaw(raw), ProbUsed(p), Ev(ev), source)
    }

    /** 旧 `_cap_over_market`: 市場の暗黙確率 `払戻率 / max(odds, 0.1)` の `max_over_market` 倍を上限にする。`max_over_market <= 0` は無効。 */
    private fun capOverMarket(
        p: Double,
        odds: Double,
        payoutRate: Double,
        maxOverMarket: Double,
    ): Double {
        if (maxOverMarket <= 0) return p
        val o = PythonCompat.max(odds, 0.1)
        val r = PythonCompat.min(PythonCompat.max(payoutRate, 0.0), 1.0)
        val pMarket = r / o
        val pCap = maxOverMarket * pMarket
        return PythonCompat.min(p, pCap)
    }

    /** 買い目の並べ替え・点数上限・賭け額・配分から 1 賭式のエントリを作る。買い目が無ければ、見送りエントリ（実オッズ必須のとき）または null。 */
    private fun comboEntry(
        type: BetType,
        axis: Int,
        tickets: List<ComboTicket>,
        noteWhenReal: String,
        config: WinDecisionConfig,
        stake: WinStake,
    ): StrategyEntry? {
        // EV 降順。Python の sort(reverse=True) は安定（同値は生成順）で、Kotlin の sortedByDescending も安定
        val top = tickets.sortedByDescending { it.ev.value }.take(maxOf(1, config.combo.maxTicketsPerType))
        if (top.isEmpty()) return if (config.requireRealOddsForCombo) comboSkipEntry(type, axis) else null

        val best = top[0]
        val info = stake.calcBetAmount(best.probUsed, best.odds)
        val bet = info.suggestedBet
        val staked = if (bet > 0) allocate(top, bet, config.ticketAllocation) else top

        // 期待利益は買い目の合計（素の + で足す。旧実装のまま）
        var expectedProfit = 0.0
        for (t in staked) {
            val st = t.stake ?: 0.0
            if (st > 0 && t.probUsed.value > 0 && t.odds > 0) expectedProfit += EvMath.calcExpectedProfit(st, t.probUsed.value, t.odds)
        }

        return StrategyEntry(
            type = type,
            umaban = axis,
            odds = best.odds,
            probRaw = best.probRaw,
            probUsed = best.probUsed,
            ev = best.ev,
            suggestedBet = bet,
            expectedProfit = expectedProfit,
            kellyFraction = stake.displayKellyFraction(best.probUsed, best.odds),
            kellyRaw = info.kellyRaw,
            kellyAdjusted = info.kellyAdjusted,
            kellyBetBeforeClip = info.kellyBetBeforeClip,
            clipped = info.clipped,
            skipReason = info.skipReason,
            strategy = "${type.label}：軸$axis→流し＋BOX候補（上位${staked.size}点）",
            note = noteWhenReal,
            tickets = staked,
            kumi = best.kumi,
        )
    }

    /**
     * 旧 `ticket_allocation`: `str(x or "best_only").lower()` が `proportional_ev` なら EV（負は 0）に比例して合計が賭け額になるよう配分
     * （全買い目の EV が正なので重みの和は正。`s <= 0` のときは先頭に全額）。それ以外の文字列はすべて `best_only`（先頭の買い目に全額、他は 0.0）。
     */
    private fun allocate(
        tickets: List<ComboTicket>,
        bet: Double,
        allocation: String,
    ): List<ComboTicket> {
        val mode = allocation.ifEmpty { "best_only" }.lowercase()
        if (mode == "proportional_ev") {
            var ws = tickets.map { PythonCompat.max(it.ev.value, 0.0) }
            var s = PythonCompat.sum(ws)
            if (s <= 0) {
                ws = listOf(1.0) + List(tickets.size - 1) { 0.0 }
                s = 1.0
            }
            return tickets.zip(ws).map { (t, w) -> t.copy(stake = bet * w / s) }
        }
        return tickets.mapIndexed { i, t -> t.copy(stake = if (i == 0) bet else 0.0) }
    }

    private fun comboSkipEntry(
        type: BetType,
        umaban: Int,
    ) = StrategyEntry(
        type = type,
        umaban = umaban,
        odds = 0.0,
        probRaw = ProbRaw(0.0),
        probUsed = ProbUsed(0.0),
        ev = Ev(0.0),
        suggestedBet = 0.0,
        expectedProfit = 0.0,
        kellyFraction = 0.0,
        kellyRaw = 0.0,
        kellyAdjusted = 0.0,
        kellyBetBeforeClip = 0.0,
        clipped = false,
        skipReason = COMBO_SKIP_REASON,
        strategy = "${type.label}: 見送り",
        note = COMBO_SKIP_NOTE,
    )
}
