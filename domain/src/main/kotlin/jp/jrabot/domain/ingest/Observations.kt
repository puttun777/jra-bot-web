package jp.jrabot.domain.ingest

import jp.jrabot.domain.calendar.RaceDataKubun
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.odds.OddsEntry
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.Venue
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * 生レコードから取り出した、保存の単位に近い観測。**公式仕様で項目位置を確認できたレコード形式（T1.17）はまだ無い**ので、現時点で
 * これを作れるのは合成の正規化 JSON パーサ（:ingest。既定で無効）だけ。実際の JV-Data（cp932 固定長）のパーサは、一次仕様の確認後に
 * [RecordParser] として足す（それまで実データのレコードは [ParseOutcome.Unsupported] で保留され、解析成功にはならない）。
 */
sealed interface Observation

/** YSCH（開催スケジュール）由来の開催日。 */
data class ScheduleDayObservation(
    val date: LocalDate,
    val venue: Venue,
    val kai: Int,
    val nichime: Int,
    val status: RaceDayStatus,
    val replacedByDate: LocalDate?,
) : Observation

/** RACE（レース詳細）由来の予定。 */
data class RaceObservation(
    val raceId: RaceId,
    val kubun: RaceDataKubun,
    val createdOn: LocalDate,
    val postTime: LocalTime?,
    val raceName: String?,
    val kai: Int?,
    val nichime: Int?,
) : Observation

/** 0B14 の発走時刻変更（TC）とその取消。 */
data class TimeChangeObservation(
    val raceId: RaceId,
    val postAt: Instant,
    val revoked: Boolean,
    val snapshotKey: String,
) : Observation

data class WinOddsObservation(
    val raceId: RaceId,
    val entries: List<OddsEntry>,
) : Observation

data class WideOddsEntry(
    val a: Int,
    val b: Int,
    val low: Double,
    val high: Double?,
    val popularity: Int?,
)

data class WideOddsObservation(
    val raceId: RaceId,
    val entries: List<WideOddsEntry>,
) : Observation

data class SanrenpukuOddsEntry(
    val a: Int,
    val b: Int,
    val c: Int,
    val odds: Double,
    val popularity: Int?,
)

data class SanrenpukuOddsObservation(
    val raceId: RaceId,
    val entries: List<SanrenpukuOddsEntry>,
) : Observation

/** 着順の 1 行。[finish] が null は着順なし（取消・除外など。旧と同じく『着順なし』として保存し、勝ち馬券の判定では数えない）。 */
data class ResultEntry(
    val umaban: Int,
    val finish: Int?,
)

/** 確定した（または速報の）着順。馬ごとの行を**そのレース分まとめて**置き換える単位ではなく、馬番ごとに upsert する。 */
data class ResultObservation(
    val raceId: RaceId,
    val entries: List<ResultEntry>,
) : Observation

/** 払戻 1 行。[payout] は **100 円あたりの払戻金額（円）**（旧 `payouts.payout` と同じ。公式仕様での確認は G-5/G-16）。 */
data class PayoutRow(
    val comboKey: String,
    val payout: Double,
)

/** 払戻。[provisional] が true は速報（確定を上書きしない）。[market] は単勝・ワイド・三連複。 */
data class PayoutObservation(
    val raceId: RaceId,
    val market: jp.jrabot.domain.odds.Market,
    val rows: List<PayoutRow>,
    val provisional: Boolean,
) : Observation

sealed interface ParseOutcome {
    data class Parsed(
        val observations: List<Observation>,
    ) : ParseOutcome

    /** このレコード形式のパーサがまだ無い（仕様未確認・未実装・設定で無効）。**解析成功ではない**。生レコードは保留（PENDING）にして後で再解析できる。 */
    data class Unsupported(
        val reason: String,
    ) : ParseOutcome

    /** 形式のパーサはあるが、内容が不正。隔離（FAILED）して適用しない。 */
    data class Malformed(
        val reason: String,
    ) : ParseOutcome
}

class ParseContext(
    val dataspec: String,
    val batchClass: BatchClass,
    val observedAt: Instant,
)

/** 生レコード 1 件の解析。自分の扱うレコードでなければ null を返す（次のパーサへ）。例外を投げない。 */
fun interface RecordParser {
    fun parse(
        ctx: ParseContext,
        record: IngestRecordIn,
    ): ParseOutcome?
}

/** パーサの並び。どれも扱わないレコードは [ParseOutcome.Unsupported]（`NO_PARSER`）。パーサが例外を投げたら不正（Malformed）として隔離する。 */
class RecordParsers(
    private val parsers: List<RecordParser>,
) {
    fun parse(
        ctx: ParseContext,
        record: IngestRecordIn,
    ): ParseOutcome {
        for (p in parsers) {
            val out =
                try {
                    p.parse(ctx, record)
                } catch (e: RuntimeException) {
                    return ParseOutcome.Malformed("PARSER_ERROR:${e.javaClass.simpleName}")
                }
            if (out != null) return out
        }
        return ParseOutcome.Unsupported("NO_PARSER:${ctx.dataspec}/${record.id}")
    }

    companion object {
        /** 実データの解析は一次仕様の確認後（T1.17）。それまでは何も扱わない。 */
        val NONE = RecordParsers(emptyList())
    }
}
