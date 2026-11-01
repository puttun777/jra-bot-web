package jp.jrabot.golden

import jp.jrabot.domain.settlement.CandidateView
import jp.jrabot.domain.settlement.ComboMarket
import jp.jrabot.domain.settlement.EvaluationRef
import jp.jrabot.domain.settlement.FinishRow
import jp.jrabot.domain.settlement.SettleStatus
import jp.jrabot.domain.settlement.SettlementCalculator
import jp.jrabot.domain.settlement.TicketRef
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 決済のゴールデン（GD11）の Kotlin 側。旧 Python の `settle_batch`（合成 SQLite に対する旧 Repository の呼び出し列）と同じ入力（`db` と `ops`）を、
 * **メモリ上の表**に対して :domain の [SettlementCalculator] で実行し、同じ形（各操作の戻り値と、操作後の評価・bets の行）を返す。
 * DB の読み書き（行の選択・更新）はここで再現し、計算（キー・代表評価・払戻・的中）は :domain を呼ぶ。
 */
internal object GoldenSettle {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    private class Eval(
        val id: Long,
        val raceId: String,
        val snapshotAt: String?,
        val bestCandidateJson: String?,
        val createdAt: String?,
        var settled: Boolean = false,
        var hit: Int? = null,
        var payoutUsed: Double? = null,
        var isProvisional: Int = 0,
        var settleStatus: String? = null,
    )

    private class Bet(
        val id: Long,
        val raceId: String,
        val market: String,
        val stake: Double?,
        val selection: String?,
        val oddsAtBet: Double?,
        var status: String?,
        var settled: Boolean = false,
        var payout: Double? = null,
        var profit: Double? = null,
    )

    private class State(
        val races: MutableMap<String, Pair<String?, String?>> = HashMap(),
        val results: MutableList<Triple<String, Int, Int?>> = ArrayList(),
        val payouts: MutableMap<Triple<String, String, String>, Double> = HashMap(),
        val evals: MutableList<Eval> = ArrayList(),
        val tickets: MutableList<Pair<String, TicketRef>> = ArrayList(),
        val comboOdds: MutableList<Triple<String, String, Triple<String, String, Double>>> = ArrayList(),
        val bets: MutableList<Bet> = ArrayList(),
    )

    private fun JsonNode?.str(): String? = this?.takeUnless { it.isNull }?.stringValue()

    private fun JsonNode?.dbl(): Double? = this?.takeUnless { it.isNull }?.asDouble("number")

    private fun JsonNode?.int(): Int? = this?.takeUnless { it.isNull }?.asInt("number")

    fun settleBatch(a: JsonNode): Any {
        val st = State()
        load(st, a.get("db"))
        val ops = ArrayList<Any?>()
        for (op in a.get("ops").values()) ops.add(runOp(st, op))
        val evals =
            st.evals.sortedBy { it.id }.map {
                obj(
                    "id" to it.id,
                    "hit" to it.hit,
                    "payout_used" to it.payoutUsed,
                    "is_provisional" to it.isProvisional,
                    "settle_status" to it.settleStatus,
                    "settled" to it.settled,
                )
            }
        val bets =
            st.bets.sortedBy { it.id }.map {
                obj("id" to it.id, "status" to it.status, "settled" to it.settled, "payout" to it.payout, "profit" to it.profit)
            }
        return obj("ops" to ops, "race_evaluations" to evals, "bets" to bets)
    }

    private fun load(
        st: State,
        db: JsonNode,
    ) {
        db.get("races")?.values()?.forEach { st.races[it.get("race_id").stringValue()] = it.get("date").str() to it.get("post_time").str() }
        db.get("results")?.values()?.forEach { addResult(st, it) }
        db.get("payouts")?.values()?.forEach { addPayout(st, it) }
        db.get("race_evaluations")?.values()?.forEach { addEvaluation(st, it) }
        db.get("ticket_evaluations")?.values()?.forEach {
            st.tickets +=
                it.get("race_evaluation_id").asLong().toString() to
                TicketRef(it.get("bet_type").stringValue(), it.get("combo_key").str(), it.get("rank").int(), it.get("accepted").int())
        }
        db.get("combo_odds_snapshots")?.values()?.forEach {
            st.comboOdds +=
                Triple(
                    it.get("race_id").stringValue(),
                    it.get("market").stringValue(),
                    Triple(it.get("collected_at").stringValue(), it.get("combo_key").stringValue(), it.get("odds").dbl()!!),
                )
        }
        db.get("bets")?.values()?.forEach {
            st.bets +=
                Bet(
                    it.get("id").asLong(),
                    it.get("race_id").stringValue(),
                    it.get("market").stringValue(),
                    it.get("stake").dbl(),
                    it.get("selection").str(),
                    it.get("odds_at_bet").dbl(),
                    it.get("status").str(),
                )
        }
    }

    private fun addResult(
        st: State,
        n: JsonNode,
    ) {
        st.results += Triple(n.get("race_id").stringValue(), n.get("umaban").asInt("umaban"), n.get("finish").int())
    }

    private fun addPayout(
        st: State,
        n: JsonNode,
    ) {
        st.payouts[Triple(n.get("race_id").stringValue(), n.get("market").stringValue(), n.get("key").stringValue())] = n.get("payout").dbl()!!
    }

    private fun addEvaluation(
        st: State,
        n: JsonNode,
    ) {
        st.evals +=
            Eval(
                n.get("id").asLong(),
                n.get("race_id").stringValue(),
                n.get("snapshot_at").str(),
                n.get("best_candidate_json").str(),
                n.get("created_at").str(),
            )
    }

    private fun runOp(
        st: State,
        op: JsonNode,
    ): Any? {
        val raceId = op.get("race_id")?.str()
        return when (val kind = op.get("op").stringValue()) {
            "winning_keys" -> {
                val k = winning(st, raceId!!)
                obj("wide" to k.wide, "sanrenpuku" to k.sanrenpuku)
            }

            "settle_evaluations" -> {
                settleEvaluations(st, raceId!!, op.get("force")?.asBoolean(false) ?: false)
            }

            "settle_win_bets" -> {
                settleWinBets(st, raceId!!)
            }

            "get_payout" -> {
                st.payouts[Triple(raceId!!, op.get("market").stringValue(), op.get("key").stringValue())]
            }

            "add_payout" -> {
                addPayout(st, op)
                null
            }

            "add_evaluation" -> {
                addEvaluation(st, op)
                null
            }

            "add_result" -> {
                addResult(st, op)
                null
            }

            else -> {
                throw UnsupportedGoldenCall("未対応の op: $kind")
            }
        }
    }

    private fun winning(
        st: State,
        raceId: String,
    ) = SettlementCalculator.winningKeys(st.results.filter { it.first == raceId }.map { FinishRow(it.second, it.third) })

    private fun candidateOf(json: String?): CandidateView? {
        if (json.isNullOrEmpty()) return null
        val n =
            try {
                mapper.readTree(json)
            } catch (_: RuntimeException) {
                return null
            }
        if (n == null || !n.isObject) return null

        fun s(name: String): String? =
            n
                .get(name)
                ?.takeUnless { it.isNull }
                ?.takeIf { it.isString }
                ?.stringValue()
        val tickets = n.get("tickets")?.takeIf { it.isArray && it.size() > 0 }
        val t0 = tickets?.get(0)
        return CandidateView(
            betType = s("bet_type"),
            type = s("type"),
            comboKey = s("combo_key"),
            kumi = s("kumi"),
            combo = s("combo"),
            firstTicketKumi = t0?.get("kumi")?.takeIf { it.isString }?.stringValue(),
            firstTicketComboKey = t0?.get("combo_key")?.takeIf { it.isString }?.stringValue(),
            hasTickets = tickets != null,
        )
    }

    private fun settleEvaluations(
        st: State,
        raceId: String,
        force: Boolean,
    ): Int {
        val winning = winning(st, raceId)
        val all = st.evals.filter { it.raceId == raceId }
        val race = st.races[raceId]
        val start = race?.let { SettlementCalculator.raceStartIso(it.first, it.second) }
        val rep = SettlementCalculator.selectRepresentative(all.map { EvaluationRef(it.id, it.snapshotAt, it.createdAt) }, start) ?: return 0
        val row = all.first { it.id == rep.id }
        if (!force && row.settled) return 0
        val tickets = st.tickets.filter { it.first == row.id.toString() }.map { it.second }
        val resolved = SettlementCalculator.resolveBestCombo(tickets, candidateOf(row.bestCandidateJson))
        val result =
            SettlementCalculator.settleEvaluation(
                resolved,
                winning,
                { market, key -> st.payouts[Triple(raceId, market.code, key)] },
                { market -> latestOdds(st, raceId, market) },
            )
        row.settled = true
        row.settleStatus = result.status.code
        if (result.status == SettleStatus.SETTLED) {
            row.hit = if (result.hit == true) 1 else 0
            row.payoutUsed = result.payoutUsed
            row.isProvisional = if (result.isProvisional) 1 else 0
        }
        return 1
    }

    private fun latestOdds(
        st: State,
        raceId: String,
        market: ComboMarket,
    ): Map<String, Double> {
        val rows = st.comboOdds.filter { it.first == raceId && it.second == market.code }
        val latest = rows.maxOfOrNull { it.third.first } ?: return emptyMap()
        return rows.filter { it.third.first == latest }.associate { it.third.second to it.third.third }
    }

    /** SQLite の `CAST(text AS INTEGER)`: 先頭の空白を飛ばし、符号と数字の続きだけを読む。読めなければ 0。 */
    private fun castInt(s: String?): Int {
        val m = Regex("^\\s*([+-]?\\d+)").find(s ?: return 0) ?: return 0
        return m.groupValues[1].toLongOrNull()?.toInt() ?: 0
    }

    private fun settleWinBets(
        st: State,
        raceId: String,
    ): Int {
        var updated = 0
        for (b in st.bets.sortedBy { it.id }) {
            if (b.raceId != raceId || b.market != "win" || !(b.status == null || b.status == "open")) continue
            val finish = st.results.firstOrNull { it.first == raceId && it.second == castInt(b.selection) }?.third
            val r = SettlementCalculator.settleWinBet(b.stake, b.oddsAtBet, finish) ?: continue
            b.status = "settled"
            b.settled = true
            b.payout = r.payout
            b.profit = r.profit
            updated++
        }
        return updated
    }
}
