package jp.jrabot.domain.race

import java.time.DateTimeException
import java.time.LocalDate

/**
 * レース識別子 `YYYYMMDD` + 場コード 2 桁 + レース番号 2 桁 = 12 桁（半角数字）。
 * 例: `202401010611` = 2024-01-01 / 中山(06) / 11R。DB は `race_id CHAR(12)`（D-040）。
 *
 * 旧データには回・日目が無いので、ここには含めない（`kai` / `nichime` は [Race] の NULL 可項目）。16 桁の `rt_key16` は [rtKey16] で導出する。
 *
 * 検証（不正は拒否し、補正しない）:
 * - 半角 0〜9 のちょうど 12 文字（全角数字・空白・符号は不可）。
 * - 日付は実在する暦日で、年は [MIN_YEAR] 以上（`00000000` のような欠損値を弾く）。
 * - 場コードは 01〜10（[Venue]）。
 * - レース番号は 01〜99（2 桁で 00 は不可）。JRA の 1 日の最大レース数は設計文書に無いので上限は置いていない（T1.17 で確認、D-060）。
 *
 * 文字列のままなので、辞書順 = 日付・場・レース番号の順になる。
 */
@JvmInline
value class RaceId private constructor(
    val value: String,
) : Comparable<RaceId> {
    val date: LocalDate get() = LocalDate.of(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
    val venue: Venue get() = Venue.fromCode(value.substring(8, 10))
    val raceNo: Int get() = value.substring(10, 12).toInt()

    /** `YYYYMMDD` + 場 + 回(2桁) + 日目(2桁) + レース番号 の 16 桁（D-040）。回・日目は 1〜99。 */
    fun rtKey16(
        kai: Int,
        nichime: Int,
    ): String {
        require(kai in 1..99) { "kai は 1〜99: $kai" }
        require(nichime in 1..99) { "nichime は 1〜99: $nichime" }
        return value.substring(0, 10) + two(kai) + two(nichime) + value.substring(10, 12)
    }

    override fun compareTo(other: RaceId): Int = value.compareTo(other.value)

    override fun toString(): String = "RaceId($value)"

    companion object {
        const val LENGTH = 12
        const val MIN_YEAR = 1900

        fun parseOrNull(text: String): RaceId? {
            if (text.length != LENGTH || text.any { it !in '0'..'9' }) return null
            val year = text.substring(0, 4).toInt()
            if (year < MIN_YEAR) return null
            try {
                LocalDate.of(year, text.substring(4, 6).toInt(), text.substring(6, 8).toInt())
            } catch (_: DateTimeException) {
                return null
            }
            if (Venue.fromCodeOrNull(text.substring(8, 10)) == null) return null
            if (text.substring(10, 12).toInt() == 0) return null
            return RaceId(text)
        }

        fun parse(text: String): RaceId = requireNotNull(parseOrNull(text)) { "race_id の形式が不正: '$text'" }

        fun of(
            date: LocalDate,
            venue: Venue,
            raceNo: Int,
        ): RaceId {
            require(date.year in MIN_YEAR..9999) { "年が範囲外: ${date.year}" }
            require(raceNo in 1..99) { "raceNo は 1〜99: $raceNo" }
            return RaceId("%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth) + venue.code + two(raceNo))
        }

        private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()
    }
}
