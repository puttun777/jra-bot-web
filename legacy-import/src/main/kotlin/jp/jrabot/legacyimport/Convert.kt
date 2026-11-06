package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 行を変換できない（隔離する）。[code] は `import_quarantine.reason_code`、[detail] は列名と理由だけ（値は含めない）。 */
class Reject(
    val code: String,
    val detail: String,
) : RuntimeException("$code: $detail")

/**
 * 旧 SQLite の値 → MySQL の列の値（D-274。DATA_MIGRATION_DESIGN §1.2・§2）。変換できなければ [Reject]。値を黙って変えない
 * （例外は §5.6 の表に書いたものだけ）。時刻は UTC の `yyyy-MM-dd HH:mm:ss.SSSSSS`、日付は `yyyy-MM-dd`、時刻（時分）は `HH:mm:ss`。
 */
class Convert(
    /** `T` 区切りでタイムゾーンの無い時刻（旧アプリの `datetime.now().isoformat()`）の地域。既定は Asia/Tokyo。 */
    private val naiveZone: ZoneId,
) {
    fun zoneId(): String = naiveZone.id

    fun text(
        col: String,
        v: JsonValue,
        maxChars: Int,
        ascii: Boolean = false,
    ): String? {
        val s =
            when (v) {
                JsonNull -> return null

                is JsonString -> v.value

                // TEXT 親和性の列に数値で入った値（コード列の 5 など）は文字列にする（先頭のゼロは元から無い）
                is JsonInt -> v.value.toString()

                is JsonObject -> throw special(col, v)

                else -> throw Reject("type", "$col: 文字列ではない")
            }
        if (s.codePointCount(0, s.length) > maxChars) throw Reject("too_long", "$col: $maxChars 文字を超える")
        if (ascii && s.any { it.code !in 0x20..0x7e }) throw Reject("not_ascii", "$col: ASCII の印字可能文字ではない")
        return s
    }

    fun requiredText(
        col: String,
        v: JsonValue,
        maxChars: Int,
        ascii: Boolean = false,
    ): String = text(col, v, maxChars, ascii) ?: throw Reject("null", "$col: 必須の値が無い")

    fun long(
        col: String,
        v: JsonValue,
        range: LongRange,
    ): Long? {
        val n =
            when (v) {
                JsonNull -> return null

                is JsonInt -> v.value

                // REAL 親和性・計算の結果で 3.0 のように入った整数値は受け付ける（小数部があれば隔離）
                is JsonFloat -> if (v.value % 1.0 == 0.0 && kotlin.math.abs(v.value) < 9.0e15) v.value.toLong() else throw Reject("type", "$col: 整数ではない")

                is JsonString -> v.value.takeIf { INT_TEXT.matches(it) }?.toLongOrNull() ?: throw Reject("type", "$col: 整数ではない")

                else -> throw Reject("type", "$col: 整数ではない")
            }
        if (n !in range) throw Reject("out_of_range", "$col: 範囲 ${range.first}..${range.last} の外")
        return n
    }

    fun requiredLong(
        col: String,
        v: JsonValue,
        range: LongRange,
    ): Long = long(col, v, range) ?: throw Reject("null", "$col: 必須の値が無い")

    /** 0/1（NULL は [ifNull]）。 */
    fun flag(
        col: String,
        v: JsonValue,
        ifNull: Long?,
    ): Long? = long(col, v, 0L..1L) ?: ifNull

    fun double(
        col: String,
        v: JsonValue,
    ): Double? =
        when (v) {
            JsonNull -> {
                null
            }

            is JsonFloat -> {
                v.value
            }

            is JsonInt -> {
                v.value.toDouble()
            }

            // 10 進の数字の形だけ（Java の parseDouble は "1.5f"・"0x1p3"・前後の空白も受け付けるため: レビュー 中）
            is JsonString -> {
                v.value
                    .takeIf { NUM_TEXT.matches(it) }
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() } ?: throw Reject("type", "$col: 数値ではない")
            }

            is JsonObject -> {
                throw special(col, v)
            }

            else -> {
                throw Reject("type", "$col: 数値ではない")
            }
        }

    fun requiredDouble(
        col: String,
        v: JsonValue,
    ): Double = double(col, v) ?: throw Reject("null", "$col: 必須の値が無い")

    /** 時刻 → UTC。`T` 区切りの naive は [naiveZone]、空白区切り（CURRENT_TIMESTAMP）は UTC、時差付きはその時差。 */
    fun time(
        col: String,
        v: JsonValue,
    ): String? {
        val s =
            when (v) {
                JsonNull -> return null
                is JsonString -> v.value
                else -> throw Reject("time_format", "$col: 時刻の文字列ではない")
            }
        val instant =
            try {
                when {
                    NAIVE_T.matches(s) -> {
                        // 夏時間の切替で無い・重なる時刻は解釈が一つに決まらないので隔離（レビュー 中。Asia/Tokyo には無い）
                        val ldt = LocalDateTime.parse(s, ISO_LOCAL)
                        val offsets = naiveZone.rules.getValidOffsets(ldt)
                        if (offsets.size != 1) throw Reject("time_format", "$col: 夏時間の切替で無い・重なる時刻")
                        ldt.toInstant(offsets[0])
                    }

                    SPACE_UTC.matches(s) -> {
                        LocalDateTime.parse(s.replace(' ', 'T'), ISO_LOCAL).toInstant(ZoneOffset.UTC)
                    }

                    WITH_OFFSET.matches(s) -> {
                        OffsetDateTime.parse(s).toInstant()
                    }

                    else -> {
                        throw Reject("time_format", "$col: 判別できない時刻の形式")
                    }
                }
            } catch (e: DateTimeException) {
                throw Reject("time_format", "$col: 暦にない時刻")
            }
        val utc = instant.atOffset(ZoneOffset.UTC)
        // MySQL の DATETIME の範囲（1000〜9999 年）に収める（レビュー 低）
        if (utc.year !in 1000..9999) throw Reject("time_format", "$col: 年が 1000..9999 の外")
        return OUT.format(utc)
    }

    fun requiredTime(
        col: String,
        v: JsonValue,
    ): String = time(col, v) ?: throw Reject("null", "$col: 必須の値が無い")

    /** `YYYYMMDD`（文字列・整数）または `YYYY-MM-DD`。暦にない日付は [Reject]。 */
    fun date(
        col: String,
        v: JsonValue,
    ): String? {
        val s =
            when (v) {
                JsonNull -> return null
                is JsonString -> v.value
                is JsonInt -> v.value.toString()
                else -> throw Reject("date_format", "$col: 日付ではない")
            }
        val d =
            try {
                when {
                    YMD8.matches(s) -> LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE)
                    YMD10.matches(s) -> LocalDate.parse(s)
                    else -> throw Reject("date_format", "$col: 判別できない日付の形式")
                }
            } catch (e: DateTimeException) {
                throw Reject("date_format", "$col: 暦にない日付")
            }
        if (d.year !in 1000..9999) throw Reject("date_format", "$col: 年が 1000..9999 の外")
        return d.toString()
    }

    /** 不正な日付を NULL にする列（設計 §1.2: `birth_date`・`horse_past_runs.date`）。NULL にしたら [onNulled]。 */
    fun dateOrNull(
        col: String,
        v: JsonValue,
        onNulled: (String) -> Unit,
    ): String? =
        try {
            date(col, v)
        } catch (e: Reject) {
            onNulled(col)
            null
        }

    /** 発走時刻 `HH:MM`（00:00〜23:59。`00:00`・範囲外・形式外は不明 = null。設計 §1.2）。 */
    fun postTime(v: JsonValue): String? {
        val s = (v as? JsonString)?.value ?: return null
        val m = HHMM.matchEntire(s) ?: return null
        val (h, mi) = m.destructured.toList().map { it.toInt() }
        if (h > 23 || mi > 59 || (h == 0 && mi == 0)) return null
        return LocalTime.of(h, mi).format(DateTimeFormatter.ISO_LOCAL_TIME)
    }

    /** JSON の文字列 → 正準化した JSON（MySQL の JSON 列へ）。読めなければ [Reject]（`--lenient-json` なら呼び出し側で NULL に）。 */
    fun json(
        col: String,
        v: JsonValue,
    ): String? {
        val s =
            when (v) {
                JsonNull -> return null
                is JsonString -> v.value
                else -> throw Reject("json", "$col: JSON の文字列ではない")
            }
        return try {
            CanonicalJson.encode(StrictJson.parse(s))
        } catch (e: JsonFormatException) {
            throw Reject("json", "$col: JSON として読めない")
        } catch (e: IllegalArgumentException) {
            throw Reject("json", "$col: JSON として読めない")
        }
    }

    fun market(
        col: String,
        v: JsonValue,
    ): String {
        val s = requiredText(col, v, 16, ascii = true)
        if (s !in MARKETS) throw Reject("enum", "$col: 未知の賭式")
        return s
    }

    /** extract の印付きの値（BLOB・無限大）。 */
    private fun special(
        col: String,
        v: JsonObject,
    ): Reject =
        when {
            v.has("\$blob") -> Reject("blob", "$col: BLOB")
            v.has("\$bytes") -> Reject("invalid_utf8", "$col: UTF-8 として読めない文字列")
            else -> Reject("non_finite", "$col: 有限の数値ではない")
        }

    companion object {
        val MARKETS = setOf("win", "wide", "sanrenpuku")
        val VENUES = (1..10).map { "%02d".format(it) }.toSet()
        private val NAIVE_T = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?""")
        private val SPACE_UTC = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
        private val WITH_OFFSET = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:\d{2})""")
        private val YMD8 = Regex("""\d{8}""")
        private val YMD10 = Regex("""\d{4}-\d{2}-\d{2}""")
        private val HHMM = Regex("""(\d{2}):(\d{2})""")
        private val ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(java.time.format.ResolverStyle.STRICT)
        private val OUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")
        val RACE_ID = Regex("""\d{12}""")
        private val INT_TEXT = Regex("""-?\d{1,18}""")
        private val NUM_TEXT = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    }
}
