package jp.jrabot.ingest.agent

import jp.jrabot.domain.calendar.RaceDataKubun
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.ingest.IngestRecordIn
import jp.jrabot.domain.ingest.ParseContext
import jp.jrabot.domain.ingest.ParseOutcome
import jp.jrabot.domain.ingest.PayoutObservation
import jp.jrabot.domain.ingest.PayoutRow
import jp.jrabot.domain.ingest.RaceObservation
import jp.jrabot.domain.ingest.RecordParser
import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.ResultObservation
import jp.jrabot.domain.ingest.SanrenpukuOddsEntry
import jp.jrabot.domain.ingest.SanrenpukuOddsObservation
import jp.jrabot.domain.ingest.ScheduleDayObservation
import jp.jrabot.domain.ingest.TimeChangeObservation
import jp.jrabot.domain.ingest.WideOddsEntry
import jp.jrabot.domain.ingest.WideOddsObservation
import jp.jrabot.domain.ingest.WinOddsObservation
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * **合成の正規化 JSON レコード**のパーサ（UTF-8 の JSON）。JV-Data（cp932 固定長）ではない。公式仕様で項目位置を確認できるまで
 * 実データのパーサは作らず（T1.17）、保存・適用の経路をこの正規化形式で先に検証する。**本番では有効にできない**
 * （`INGEST_ACCEPT_NORMALIZED_RECORDS` は APP_ENV=local だけ）。レコード ID は 3 文字で JV-Data の 2 文字 ID と衝突しない。
 *
 * | id | 内容 | キー（すべて必須。余分なキーは不正） |
 * |---|---|---|
 * | `NYS` | 開催日（YSCH 相当） | date(`yyyyMMdd`) venue(`01`〜`10`) kai nichime status(`PLANNED`/`CANCELLED`/`POSTPONED`) replacedBy(null 可) |
 * | `NRA` | レース（RACE 相当） | raceId kubun(`0`〜`9`) createdOn postTime(`HHmm` null 可) raceName(null 可) kai(null 可) nichime(null 可) |
 * | `NTC` | 発走時刻変更（0B14 TC 相当） | raceId postAt(ISO UTC) revoked snapshotKey |
 * | `NOW` | 単勝オッズ | raceId entries:[{umaban, odds, pop(null 可)}] |
 * | `NOD` | ワイドオッズ | raceId entries:[{a, b, low, high(null 可), pop(null 可)}] |
 * | `NOS` | 三連複オッズ | raceId entries:[{a, b, c, odds, pop(null 可)}] |
 * | `NRS` | 着順（T3.9） | raceId entries:[{umaban, finish(null 可: 着順なし)}] |
 * | `NHR` | 払戻（T3.9。100 円あたりの円） | raceId market(`win`/`wide`/`sanrenpuku`) provisional(bool) entries:[{key(0 埋めの昇順 `07` / `02-07` / `02-07-09`), payout}] |
 */
class NormalizedJsonRecordParser(
    private val json: JsonTrees,
) : RecordParser {
    override fun parse(
        ctx: ParseContext,
        record: IngestRecordIn,
    ): ParseOutcome? {
        if (record.id !in IDS) return null
        val tree =
            try {
                json.parse(record.bytes)
            } catch (_: MalformedBodyException) {
                return ParseOutcome.Malformed("NORMALIZED_JSON_INVALID")
            }

        @Suppress("UNCHECKED_CAST")
        val o = tree as? Map<String, Any?> ?: return ParseOutcome.Malformed("NORMALIZED_NOT_OBJECT")
        return try {
            ParseOutcome.Parsed(listOf(build(record.id, o)))
        } catch (e: BadRecord) {
            ParseOutcome.Malformed("NORMALIZED_${e.message}")
        }
    }

    private class BadRecord(
        message: String,
    ) : RuntimeException(message, null, false, false)

    private fun build(
        id: String,
        o: Map<String, Any?>,
    ) = when (id) {
        "NYS" -> {
            keys(o, "date", "venue", "kai", "nichime", "status", "replacedBy")
            val status = RaceDayStatus.entries.firstOrNull { it.name == str(o, "status") }?.takeIf { it in YSCH_STATUSES } ?: bad("STATUS")
            ScheduleDayObservation(date(o, "date"), venue(o), int(o, "kai"), int(o, "nichime"), status, optDate(o, "replacedBy"))
        }

        "NRA" -> {
            keys(o, "raceId", "kubun", "createdOn", "postTime", "raceName", "kai", "nichime")
            val kubun = str(o, "kubun").singleOrNull()?.let { RaceDataKubun.fromCodeOrNull(it) } ?: bad("KUBUN")
            RaceObservation(
                raceId(o),
                kubun,
                date(o, "createdOn"),
                (o["postTime"] as String?)?.let { t ->
                    if (!Regex("[0-9]{4}").matches(t)) bad("POST_TIME")
                    try {
                        LocalTime.of(t.substring(0, 2).toInt(), t.substring(2).toInt())
                    } catch (_: java.time.DateTimeException) {
                        bad("POST_TIME")
                    }
                },
                o["raceName"] as String?,
                optInt(o, "kai"),
                optInt(o, "nichime"),
            )
        }

        "NTC" -> {
            keys(o, "raceId", "postAt", "revoked", "snapshotKey")
            val at =
                try {
                    Instant.parse(str(o, "postAt"))
                } catch (_: DateTimeParseException) {
                    bad("POST_AT")
                }
            TimeChangeObservation(
                raceId(o),
                at,
                o["revoked"] as? Boolean ?: bad("REVOKED"),
                str(o, "snapshotKey").takeIf { it.isNotBlank() } ?: bad("SNAPSHOT_KEY"),
            )
        }

        "NOW" -> {
            keys(o, "raceId", "entries")
            val entries =
                list(o).map { e ->
                    keys(e, "umaban", "odds", "pop")
                    OddsEntry(
                        HorseNumber.ofOrNull(int(e, "umaban")) ?: bad("UMABAN"),
                        Odds.ofOrNull(dbl(e, "odds")) ?: bad("ODDS"),
                        optInt(e, "pop")?.also { if (it < 0) bad("POP") },
                    )
                }
            if (entries.map { it.umaban }.toSet().size != entries.size) bad("DUPLICATE_UMABAN")
            WinOddsObservation(raceId(o), entries)
        }

        "NOD" -> {
            keys(o, "raceId", "entries")
            WideOddsObservation(
                raceId(o),
                list(o).map { e ->
                    keys(e, "a", "b", "low", "high", "pop")
                    WideOddsEntry(int(e, "a"), int(e, "b"), dbl(e, "low"), optDbl(e, "high"), optInt(e, "pop"))
                },
            )
        }

        "NRS" -> {
            keys(o, "raceId", "entries")
            val entries =
                list(o).map { e ->
                    keys(e, "umaban", "finish")
                    ResultEntry(int(e, "umaban"), optInt(e, "finish"))
                }
            ResultObservation(raceId(o), entries)
        }

        "NHR" -> {
            keys(o, "raceId", "market", "provisional", "entries")
            PayoutObservation(
                raceId(o),
                Market.fromCodeOrNull(str(o, "market")) ?: bad("MARKET"),
                list(o).map { e ->
                    keys(e, "key", "payout")
                    PayoutRow(str(e, "key"), dbl(e, "payout"))
                },
                o["provisional"] as? Boolean ?: bad("PROVISIONAL"),
            )
        }

        else -> {
            keys(o, "raceId", "entries")
            SanrenpukuOddsObservation(
                raceId(o),
                list(o).map { e ->
                    keys(e, "a", "b", "c", "odds", "pop")
                    SanrenpukuOddsEntry(int(e, "a"), int(e, "b"), int(e, "c"), dbl(e, "odds"), optInt(e, "pop"))
                },
            )
        }
    }

    private fun bad(what: String): Nothing = throw BadRecord(what)

    private fun keys(
        o: Map<String, Any?>,
        vararg expected: String,
    ) {
        if (o.keys != expected.toSet()) bad("KEYS")
    }

    private fun str(
        o: Map<String, Any?>,
        k: String,
    ): String = o[k] as? String ?: bad(k.uppercase())

    private fun int(
        o: Map<String, Any?>,
        k: String,
    ): Int = optInt(o, k) ?: bad(k.uppercase())

    private fun optInt(
        o: Map<String, Any?>,
        k: String,
    ): Int? {
        val v = o[k] ?: return null
        if (v !is Number || v.toDouble() != v.toLong().toDouble() || v.toLong() !in Int.MIN_VALUE..Int.MAX_VALUE) bad(k.uppercase())
        return v.toInt()
    }

    private fun dbl(
        o: Map<String, Any?>,
        k: String,
    ): Double = optDbl(o, k) ?: bad(k.uppercase())

    private fun optDbl(
        o: Map<String, Any?>,
        k: String,
    ): Double? {
        val v = o[k] ?: return null
        if (v !is Number) bad(k.uppercase())
        return v.toDouble().also { if (!it.isFinite()) bad(k.uppercase()) }
    }

    private fun date(
        o: Map<String, Any?>,
        k: String,
    ): LocalDate = optDate(o, k) ?: bad(k.uppercase())

    private fun optDate(
        o: Map<String, Any?>,
        k: String,
    ): LocalDate? {
        val v = o[k] ?: return null
        val s = v as? String ?: bad(k.uppercase())
        if (!Regex("[0-9]{8}").matches(s)) bad(k.uppercase())
        return try {
            LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6).toInt())
        } catch (_: java.time.DateTimeException) {
            bad(k.uppercase())
        }
    }

    private fun venue(o: Map<String, Any?>): Venue = Venue.fromCodeOrNull(str(o, "venue")) ?: bad("VENUE")

    private fun raceId(o: Map<String, Any?>): RaceId = RaceId.parseOrNull(str(o, "raceId")) ?: bad("RACE_ID")

    @Suppress("UNCHECKED_CAST")
    private fun list(o: Map<String, Any?>): List<Map<String, Any?>> {
        val l = o["entries"] as? List<*> ?: bad("ENTRIES")
        if (l.isEmpty() || l.size > MAX_ENTRIES) bad("ENTRIES")
        return l.map { it as? Map<String, Any?> ?: bad("ENTRY") }
    }

    private companion object {
        val IDS = setOf("NYS", "NRA", "NTC", "NOW", "NOD", "NOS", "NRS", "NHR")
        val YSCH_STATUSES = setOf(RaceDayStatus.PLANNED, RaceDayStatus.CANCELLED, RaceDayStatus.POSTPONED)
        const val MAX_ENTRIES = 5000
    }
}
