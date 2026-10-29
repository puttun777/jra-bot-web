package jp.jrabot.domain.notify

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.signal.SignalId

/** 保存した通知内容が壊れている・版が違う・ハッシュが合わない。**この内容は通知に使わない**（別の値で補わない）。 */
class NoticeSnapshotException(
    message: String,
) : RuntimeException(message)

/**
 * 通知内容の不変のスナップショット（V5 `race_timing_notice.notice_json`）。通知は**保存したこの内容から**作る（再計算しない）。
 *
 * - 中身はレース表示（[RaceLabel]）と、Signal の ID + 表示モデル（[SignalView]。ワイド・三連複の買い目〈[BundleView]〉、理由文、Kelly の内訳を含む）。
 *   表示モデルの数値は判定サービスの出力をそのまま運ぶだけで、ここでも再計算しない。値が無いものは**キーごと省く**（0 などの架空の値で埋めない）。
 * - 正準 JSON（[CanonicalJson]。キー昇順、浮動小数は最短の往復表現、整数と小数は別物）にして SHA-256 を取る。DB から読み戻した JSON はキーの順序や
 *   空白が変わり得るので、**読んだ内容を再度正準化してハッシュを照合する**（[decode]）。照合に失敗したら使わない。
 * - [SCHEMA] は版。形を変えるときは版を上げ、古い版の読み方を残す（保存済みの内容は書き換えない）。
 */
class NoticeSnapshot(
    val race: RaceLabel,
    val items: List<NoticeItem>,
) {
    fun toJsonValue(): JsonObject =
        JsonObject.of(
            "race" to race(race),
            "items" to JsonArray(items.map { item(it) }),
        )

    fun canonicalJson(): String = CanonicalJson.encode(toJsonValue())

    fun sha256(): String = CanonicalJson.sha256Hex(toJsonValue())

    companion object {
        const val SCHEMA = 1

        /** 保存済みの JSON と記録したハッシュから復元する。版・形・ハッシュのどれかが合わなければ [NoticeSnapshotException]。 */
        fun decode(
            schema: Int,
            json: String,
            expectedSha256: String,
        ): NoticeSnapshot {
            if (schema != SCHEMA) throw NoticeSnapshotException("通知内容の版が未対応: $schema")
            val root =
                try {
                    StrictJson.parse(json)
                } catch (e: JsonFormatException) {
                    throw NoticeSnapshotException("通知内容が JSON として不正")
                } as? JsonObject ?: throw NoticeSnapshotException("通知内容がオブジェクトでない")
            if (CanonicalJson.sha256Hex(root) != expectedSha256) throw NoticeSnapshotException("通知内容のハッシュが記録と違う")
            return try {
                NoticeSnapshot(readRace(root.obj("race")), root.arr("items").map { readItem(it.asObject()) })
            } catch (e: NoticeSnapshotException) {
                throw e
            } catch (e: RuntimeException) {
                throw NoticeSnapshotException("通知内容の形が不正: ${e.javaClass.simpleName}")
            }
        }

        // ---- 書き出し（null は省く）

        /** 組み立て用。[opt] は null ならキーごと省く。 */
        private class B {
            private val m = LinkedHashMap<String, JsonValue>()

            fun put(
                key: String,
                v: JsonValue,
            ) {
                m[key] = v
            }

            fun opt(
                key: String,
                v: JsonValue?,
            ) {
                if (v != null) m[key] = v
            }

            fun done() = JsonObject(m)
        }

        private fun f(v: Double?): JsonValue? = v?.let { JsonFloat(it) }

        private fun s(v: String?): JsonValue? = v?.let { JsonString(it) }

        private fun race(r: RaceLabel) =
            build {
                put("track", JsonString(r.track))
                opt("raceNo", r.raceNo?.let { JsonInt(it.toLong()) })
                put("name", JsonString(r.name))
            }

        private fun item(i: NoticeItem) =
            build {
                put("signalId", JsonInt(i.signalId.value))
                put("view", view(i.view))
            }

        private fun view(v: SignalView) =
            build {
                put("betType", JsonString(v.betType))
                put("umaban", JsonInt(v.umaban.toLong()))
                put("odds", JsonFloat(v.odds))
                put("prob", JsonFloat(v.prob))
                put("ev", JsonFloat(v.ev))
                opt("pMkt", f(v.pMkt))
                opt("ratio", f(v.ratio))
                opt("expectedReturn", f(v.expectedReturn))
                opt("probSource", s(v.probSource))
                opt(
                    "coverage",
                    v.coverage?.let { c ->
                        build {
                            put("coverageScore", JsonFloat(c.coverageScore))
                            opt("betaEff", f(c.betaEff))
                            put("hasHorseMaster", JsonBool(c.hasHorseMaster))
                            put("hasPastRuns", JsonBool(c.hasPastRuns))
                            put("pastN", JsonInt(c.pastN.toLong()))
                        }
                    },
                )
                opt("betAmount", f(v.betAmount))
                opt("reason", s(v.reason))
                opt("skipReason", s(v.skipReason))
                opt("payoutRate", f(v.payoutRate))
                put("showAdjustedMkt", JsonBool(v.showAdjustedMkt))
                opt("bundle", v.bundle?.let { bundle(it) })
            }

        private fun bundle(b: BundleView) =
            build {
                put("strategies", JsonArray(b.strategies.map { strategy(it) }))
                opt(
                    "best",
                    b.best?.let { x ->
                        build {
                            put("type", JsonString(x.type))
                            put("ev", JsonFloat(x.ev))
                            put("suggestedBet", JsonFloat(x.suggestedBet))
                        }
                    },
                )
                put("totalSuggestedBet", JsonFloat(b.totalSuggestedBet))
                put("totalExpectedProfit", JsonFloat(b.totalExpectedProfit))
            }

        private fun strategy(x: StrategyView) =
            build {
                put("type", JsonString(x.type))
                put("suggestedBet", JsonFloat(x.suggestedBet))
                put("expectedProfit", JsonFloat(x.expectedProfit))
                put("odds", JsonFloat(x.odds))
                put("prob", JsonFloat(x.prob))
                put("ev", JsonFloat(x.ev))
                opt("note", s(x.note))
                put(
                    "tickets",
                    JsonArray(
                        x.tickets.map { t ->
                            build {
                                put("kumi", JsonString(t.kumi))
                                put("oddsText", JsonString(t.oddsText))
                                put("prob", JsonFloat(t.prob))
                                put("ev", JsonFloat(t.ev))
                                opt("stake", f(t.stake))
                            }
                        },
                    ),
                )
                opt(
                    "kelly",
                    x.kelly?.let { k ->
                        build {
                            put("raw", JsonFloat(k.raw))
                            put("adjusted", JsonFloat(k.adjusted))
                            put("betBeforeClip", JsonFloat(k.betBeforeClip))
                            put("clipped", JsonBool(k.clipped))
                            opt("skipReason", s(k.skipReason))
                            opt("probUsedForBet", f(k.probUsedForBet))
                        }
                    },
                )
            }

        private fun build(block: B.() -> Unit): JsonObject = B().also(block).done()

        // ---- 読み込み
        private fun JsonValue.asObject(): JsonObject = this as? JsonObject ?: throw NoticeSnapshotException("オブジェクトが必要")

        private fun JsonObject.obj(k: String): JsonObject = (this[k] ?: throw NoticeSnapshotException("キーが無い: $k")).asObject()

        private fun JsonObject.arr(k: String): List<JsonValue> = (this[k] as? JsonArray)?.items ?: throw NoticeSnapshotException("配列が必要: $k")

        private fun JsonObject.str(k: String): String = (this[k] as? JsonString)?.value ?: throw NoticeSnapshotException("文字列が必要: $k")

        private fun JsonObject.strOrNull(k: String): String? = if (this[k] == null) null else str(k)

        private fun JsonObject.long(k: String): Long = (this[k] as? JsonInt)?.value ?: throw NoticeSnapshotException("整数が必要: $k")

        private fun JsonObject.int(k: String): Int = Math.toIntExact(long(k))

        private fun JsonObject.dbl(k: String): Double = (this[k] as? JsonFloat)?.value ?: throw NoticeSnapshotException("小数が必要: $k")

        private fun JsonObject.dblOrNull(k: String): Double? = if (this[k] == null) null else dbl(k)

        private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonBool)?.value ?: throw NoticeSnapshotException("真偽値が必要: $k")

        private fun readRace(o: JsonObject) = RaceLabel(o.str("track"), if (o["raceNo"] == null) null else o.int("raceNo"), o.str("name"))

        private fun readItem(o: JsonObject) = NoticeItem(SignalId.of(o.long("signalId")), readView(o.obj("view")))

        private fun readView(o: JsonObject) =
            SignalView(
                betType = o.str("betType"),
                umaban = o.int("umaban"),
                odds = o.dbl("odds"),
                prob = o.dbl("prob"),
                ev = o.dbl("ev"),
                pMkt = o.dblOrNull("pMkt"),
                ratio = o.dblOrNull("ratio"),
                expectedReturn = o.dblOrNull("expectedReturn"),
                probSource = o.strOrNull("probSource"),
                coverage =
                    o["coverage"]?.asObject()?.let { c ->
                        CoverageView(c.dbl("coverageScore"), c.dblOrNull("betaEff"), c.bool("hasHorseMaster"), c.bool("hasPastRuns"), c.int("pastN"))
                    },
                betAmount = o.dblOrNull("betAmount"),
                reason = o.strOrNull("reason"),
                skipReason = o.strOrNull("skipReason"),
                payoutRate = o.dblOrNull("payoutRate"),
                showAdjustedMkt = o.bool("showAdjustedMkt"),
                bundle = o["bundle"]?.asObject()?.let { readBundle(it) },
            )

        private fun readBundle(o: JsonObject) =
            BundleView(
                strategies = o.arr("strategies").map { readStrategy(it.asObject()) },
                best = o["best"]?.asObject()?.let { BestView(it.str("type"), it.dbl("ev"), it.dbl("suggestedBet")) },
                totalSuggestedBet = o.dbl("totalSuggestedBet"),
                totalExpectedProfit = o.dbl("totalExpectedProfit"),
            )

        private fun readStrategy(o: JsonObject) =
            StrategyView(
                type = o.str("type"),
                suggestedBet = o.dbl("suggestedBet"),
                expectedProfit = o.dbl("expectedProfit"),
                odds = o.dbl("odds"),
                prob = o.dbl("prob"),
                ev = o.dbl("ev"),
                note = o.strOrNull("note"),
                tickets =
                    o.arr("tickets").map { t ->
                        t.asObject().let { TicketView(it.str("kumi"), it.str("oddsText"), it.dbl("prob"), it.dbl("ev"), it.dblOrNull("stake")) }
                    },
                kelly =
                    o["kelly"]?.asObject()?.let { k ->
                        KellyView(
                            k.dbl("raw"),
                            k.dbl("adjusted"),
                            k.dbl("betBeforeClip"),
                            k.bool("clipped"),
                            k.strOrNull("skipReason"),
                            k.dblOrNull("probUsedForBet"),
                        )
                    },
            )
    }
}
