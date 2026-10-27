package jp.jrabot.golden

import jp.jrabot.domain.ml.features.LegacyFeatureBuilder
import jp.jrabot.domain.ml.features.ReasonFormatter
import jp.jrabot.domain.notify.BestView
import jp.jrabot.domain.notify.BundleView
import jp.jrabot.domain.notify.ContentSplitter
import jp.jrabot.domain.notify.CoverageView
import jp.jrabot.domain.notify.FormatMode
import jp.jrabot.domain.notify.KellyView
import jp.jrabot.domain.notify.LengthUnit
import jp.jrabot.domain.notify.MentionPolicy
import jp.jrabot.domain.notify.NoticeFormatter
import jp.jrabot.domain.notify.NoticeTickets
import jp.jrabot.domain.notify.RaceLabel
import jp.jrabot.domain.notify.RateLimitWait
import jp.jrabot.domain.notify.SignalView
import jp.jrabot.domain.notify.StrategyView
import jp.jrabot.domain.notify.TicketView
import jp.jrabot.domain.tenant.TenantId
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime

/**
 * Discord 通知のゴールデン（G8）の Kotlin 側。旧 `notify/discord.py` のネットワークを使わない部分（分割・429 の待機秒数・買い目の重複除外・本文・理由文）を、
 * **旧実装の癖を再現するモード**（`FormatMode.LEGACY_COMPAT`・文字数はコードポイント単位）で実行する。運用の通知は `FormatMode.CORRECTED`・UTF-16 単位（余裕のある側）。
 * 旧 `send_signal` が必須キー（`signal['odds']` など）を欠くと例外を握って False を返す挙動は再現しない（その入力は未対応にする）。
 */
internal object GoldenNotify {
    private val tenant = TenantId.of(1)

    fun splitContent(a: JsonNode): Any {
        val limit = a.get("kwargs")?.get("limit")?.asInt("limit") ?: ContentSplitter.SAFE_CHUNK_LIMIT
        return ContentSplitter.split(expandRepeat(a.get("content")), limit, LengthUnit.CODE_POINTS)
    }

    /** 入力の `{"repeat": [[文字列, 回数], …]}`（長い文字列をフィクスチャに置かない表現。`tools/golden/adapter.py` の `_expand_repeat` と同じ）を展開する。文字列はそのまま。 */
    private fun expandRepeat(n: JsonNode): String =
        if (n.isString) {
            n.stringValue()
        } else {
            n.get("repeat").values().joinToString("") { it.get(0).stringValue().repeat(it.get(1).asInt("count")) }
        }

    fun parseRetryAfter(a: JsonNode): Any {
        val body = a.get("body")?.takeUnless { it.isNull }?.stringValue()
        val headers = a.get("headers")
        fun header(name: String): String? = headers?.propertyNames()?.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.get(it).stringValue() }
        return RateLimitWait.parseSeconds(body, header("Retry-After"), header("X-RateLimit-Reset-After"))
    }

    /** 旧 `_deduplicate_tickets`（辞書のまま返す）。`kumi` が無い/空は空文字、`ev` が無いのは 0.0。 */
    fun dedupeTickets(a: JsonNode): Any {
        val nodes = a.get("tickets").values().toList()
        val views = nodes.mapIndexed { i, t -> TicketView(t.get("kumi")?.takeUnless { it.isNull }?.stringValue().orEmpty(), i.toString(), 0.0, num(t.get("ev")) ?: 0.0, null) }
        return NoticeTickets.deduplicated(views).map { nodes[it.oddsText.toInt()] }
    }

    fun sendSignalContent(a: JsonNode): Any {
        val mention = mention(a)
        val view = signalView(a.get("signal"), single = true)
        val content = NoticeFormatter.formatSingle(race(a.get("race")), view, mention)
        return sent(content, mention, raised = null)
    }

    fun sendSignalsGroupedContent(a: JsonNode): Any {
        val mention = mention(a)
        val views = a.get("signals").values().map { signalView(it, single = false) }
        val content = NoticeFormatter.formatGrouped(race(a.get("race")), views, mention, FormatMode.LEGACY_COMPAT)
        return sent(content, mention, raised = if (views.any(::legacyEvMismatch)) null else "UnboundLocalError")
    }

    fun reasonFromFeatures(a: JsonNode): Any {
        val source = GoldenMl.LegacySqliteSource(a.get("db"))
        val builder = LegacyFeatureBuilder(source, tenant, LocalDateTime.parse(a.get("now").stringValue()))
        return a.get("requests").values().map { q ->
            val raceId = q.get("race_id").stringValue()
            val withDb = q.get("with_db")?.booleanValue() ?: true
            if (!withDb || raceId.isEmpty()) {
                ""
            } else {
                val umaban = q.int("umaban")
                ReasonFormatter.format(builder.reasonFacts(raceId, umaban, source.legacyBamei(raceId, umaban)))
            }
        }
    }

    // ---- 入力 JSON（旧実装の辞書）→ 表示モデル ----------------------------------------------------------------------

    private fun mention(a: JsonNode): MentionPolicy = a.get("mention_user_id")?.takeUnless { it.isNull }?.let { MentionPolicy.forUser(it.stringValue()) } ?: MentionPolicy.NONE

    /**
     * 旧 `send_signals_grouped` の欠陥 N2 の再現条件: 関数内の `import logging; logger = …` は EV 検算が不一致のときの分岐にしかなく、`logger` は関数全体で局所変数なので、
     * どのシグナルも不一致でなければ、成功経路の末尾の `logger.info(...)` が UnboundLocalError になる（本文は既に送信済み）。新実装は例外にしない。
     */
    private fun legacyEvMismatch(v: SignalView): Boolean {
        val expReturn = if (v.odds > 0 && v.prob > 0) v.odds * v.prob else 0.0
        val verify = if (expReturn > 0) expReturn - 1.0 else 0.0
        return Math.abs(v.ev - verify) > 0.0001
    }

    private fun sent(
        content: String,
        mention: MentionPolicy,
        raised: String?,
    ): Any {
        val allowed = obj("parse" to emptyList<Any>()).also { o -> mention.userId?.let { o.set("users", arr(listOf(it))) } }
        return obj("returned" to if (raised == null) true else null, "raised" to raised, "sent" to listOf(obj("content" to content, "username" to "JRA EV Bot", "allowed_mentions" to allowed)))
    }

    private fun race(n: JsonNode): RaceLabel =
        RaceLabel(
            track = n.get("track")?.stringValue().orEmpty(),
            raceNo = n.get("race_no")?.takeUnless { it.isNull }?.asInt("race_no"),
            name = n.get("name")?.stringValue().orEmpty(),
        )

    /** JSON の数値（整数・小数どちらも）を Double に。null/欠損は null。 */
    private fun num(n: JsonNode?): Double? = n?.takeUnless { it.isNull }?.let { if (it.isNumber) it.doubleValue() else throw UnsupportedGoldenCall("数値でない: $it") }

    private fun str(n: JsonNode?): String? = n?.takeUnless { it.isNull }?.stringValue()

    private fun signalView(
        s: JsonNode,
        single: Boolean,
    ): SignalView {
        val market = str(s.get("market")) ?: "win"
        val betType =
            str(s.get("bet_type")) ?: if (single) {
                when (market) {
                    "wide" -> "ワイド"
                    "sanrenpuku" -> "三連複"
                    else -> "単勝"
                }
            } else {
                "三連複"
            }
        fun req(name: String) = num(s.get(name)) ?: if (single) throw UnsupportedGoldenCall("必須キー $name が無い") else 0.0
        return SignalView(
            betType = betType,
            umaban = s.get("umaban")?.asInt("umaban") ?: if (single) throw UnsupportedGoldenCall("umaban が無い") else 0,
            odds = req("odds"),
            prob = req("prob"),
            ev = req("ev"),
            pMkt = num(s.get("p_mkt")),
            ratio = num(s.get("ratio")),
            expectedReturn = num(s.get("expected_return")),
            probSource = str(s.get("prob_source")) ?: "N/A",
            coverage =
                CoverageView(
                    coverageScore = num(s.get("coverage_score")) ?: 1.0,
                    betaEff = num(s.get("beta_eff")),
                    hasHorseMaster = s.get("has_horse_master")?.booleanValue() ?: false,
                    hasPastRuns = s.get("has_past_runs")?.booleanValue() ?: false,
                    pastN = s.get("past_n")?.asInt("past_n") ?: 0,
                ),
            betAmount = num(s.get("bet_amount")),
            reason = str(s.get("reason")) ?: "",
            skipReason = str(s.get("skip_reason")),
            payoutRate = num(s.get("payout_rate")),
            showAdjustedMkt = s.get("show_adjusted_mkt")?.booleanValue() ?: false,
            bundle = s.get("bet_strategy")?.takeUnless { it.isNull }?.let(::bundle),
        )
    }

    private fun bundle(b: JsonNode): BundleView? {
        val strategies = b.get("strategies") ?: return null
        // フィクスチャの JSON はキーが整列されていて、旧 dict の挿入順が残らない。旧 `suggest_multiple_bet_strategies` は常に 単勝 → ワイド → 三連複 の順に作るので、その順に戻す
        val order = listOf("単勝", "ワイド", "三連複")
        val types = strategies.propertyNames().toList().sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) order.size else i } }, { it }))
        val views = types.map { type -> strategy(type, strategies.get(type)) }
        val best = b.get("best_strategy")?.takeUnless { it.isNull || it.size() == 0 }
        return BundleView(
            strategies = views,
            best = best?.let { BestView(str(it.get("type")).orEmpty(), num(it.get("ev")) ?: 0.0, num(it.get("suggested_bet")) ?: 0.0) },
            totalSuggestedBet = num(b.get("total_suggested_bet")) ?: 0.0,
            totalExpectedProfit = num(b.get("total_expected_profit")) ?: 0.0,
        )
    }

    private fun strategy(
        type: String,
        s: JsonNode,
    ): StrategyView {
        val kellyRaw = num(s.get("kelly_raw"))
        return StrategyView(
            type = type,
            suggestedBet = num(s.get("suggested_bet")) ?: 0.0,
            expectedProfit = num(s.get("expected_profit")) ?: 0.0,
            odds = num(s.get("odds")) ?: 0.0,
            prob = num(s.get("prob")) ?: 0.0,
            ev = num(s.get("ev")) ?: 0.0,
            note = str(s.get("note")),
            tickets = s.get("tickets")?.values()?.map(::ticket).orEmpty(),
            kelly =
                kellyRaw?.let {
                    KellyView(
                        raw = it,
                        adjusted = num(s.get("kelly_adjusted")) ?: 0.0,
                        betBeforeClip = num(s.get("kelly_bet_before_clip")) ?: 0.0,
                        clipped = s.get("clipped")?.booleanValue() ?: false,
                        skipReason = str(s.get("skip_reason")),
                        probUsedForBet = num(s.get("prob_used_for_bet")),
                    )
                },
        )
    }

    private fun ticket(t: JsonNode): TicketView =
        TicketView(
            kumi = str(t.get("kumi")).orEmpty(),
            oddsText = str(t.get("odds_note")) ?: throw UnsupportedGoldenCall("odds_note が無い買い目は対象外"),
            prob = num(t.get("prob_used")) ?: num(t.get("prob")) ?: 0.0,
            ev = num(t.get("ev")) ?: 0.0,
            stake = num(t.get("stake")),
        )
}
