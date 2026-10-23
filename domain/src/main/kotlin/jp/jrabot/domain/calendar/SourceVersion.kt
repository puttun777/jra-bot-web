package jp.jrabot.domain.calendar

import java.time.DateTimeException
import java.time.LocalDate

/**
 * RA（レース詳細）のデータ区分（RACE_CALENDAR_DESIGN §0・§2.2。意味は**二次情報**で、一次仕様は T1.17 で確認する）。
 *
 * T3.3 が状態へ反映するのは [ANNOUNCEMENT]・[CARD]・[CANCELLED]・[DELETED] だけ。成績系（3〜7）は版の比較には使えるが、
 * 状態の反映（RESULT_* ・SETTLED）は結果取込・決済（T3.4/T3.8/T3.9）の範囲なので [handledByCalendar] が false。
 * 未知の区分（A・B など）は**推測せず**拒否する（[fromCodeOrNull] が null）。
 */
enum class RaceDataKubun(
    val code: Char,
    /** 同一作成日での新しさの順位（大きいほど新しい）。[CANCELLED]・[DELETED] は強い値で、互いの優先は決めない（[strong]）。 */
    val rank: Int,
    val strong: Boolean,
    val handledByCalendar: Boolean,
) {
    ANNOUNCEMENT('1', 1, false, true),
    CARD('2', 2, false, true),
    RESULT_FLASH_3('3', 3, false, false),
    RESULT_FLASH_4('4', 4, false, false),
    RESULT_FLASH_5('5', 5, false, false),
    RESULT_6('6', 6, false, false),
    RESULT_7('7', 7, false, false),
    CANCELLED('9', 9, true, true),
    DELETED('0', 0, true, true),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCodeOrNull(code: Char): RaceDataKubun? = byCode[code]
    }
}

/** [SourceVersion] 同士の比較結果。**文字列の辞書順では決めない**（日付 → 区分の規則表で決める）。 */
enum class VersionOrder {
    /** 既存より新しい（反映してよい）。 */
    NEWER,

    /** 同一の版（再配信。内容が同じなら何もしない）。 */
    SAME,

    /** 既存より古い（反映しない。古い版で新しい予定を上書きしない）。 */
    OLDER,

    /** 同じ作成日の 9（中止）と 0（削除）のように、一次仕様なしに順序を決められない（反映しない）。 */
    UNDECIDABLE,
}

/**
 * 取得元の版 = データ作成年月日（8 桁）+ データ区分（1 桁）。DB の `source_version`（`yyyyMMdd` + 区分）と同じ形。
 *
 * 新旧の規則（設計書の記述に基づく暫定。同一作成日内の区分の順位は T1.17 で一次確認する）:
 * 1. 作成日が新しい方が新しい。作成日が違えば区分は見ない。
 * 2. 同じ作成日では、通常の区分（1〜7）は数字の昇順が新しい。強い区分（9 中止・0 削除）は通常の区分より新しい。
 * 3. 同じ作成日の 9 と 0 は順序を決めない（[VersionOrder.UNDECIDABLE]）。
 */
data class SourceVersion(
    val createdOn: LocalDate,
    val kubun: RaceDataKubun,
) {
    fun encode(): String = "%04d%02d%02d".format(createdOn.year, createdOn.monthValue, createdOn.dayOfMonth) + kubun.code

    /** この版（受信した側）が [existing]（保存済み）に対して新しいか。 */
    fun compareTo(existing: SourceVersion): VersionOrder {
        val byDate = createdOn.compareTo(existing.createdOn)
        if (byDate > 0) return VersionOrder.NEWER
        if (byDate < 0) return VersionOrder.OLDER
        if (kubun == existing.kubun) return VersionOrder.SAME
        if (kubun.strong && existing.kubun.strong) return VersionOrder.UNDECIDABLE
        if (kubun.strong) return VersionOrder.NEWER
        if (existing.kubun.strong) return VersionOrder.OLDER
        return if (kubun.rank > existing.kubun.rank) VersionOrder.NEWER else VersionOrder.OLDER
    }

    companion object {
        /** `yyyyMMdd` + 既知の区分 1 文字。それ以外（桁違い・実在しない日付・未知の区分）は null（補正しない）。 */
        fun parseOrNull(text: String): SourceVersion? {
            if (text.length != 9 || text.any { it !in '0'..'9' }) return null
            val date =
                try {
                    LocalDate.of(text.substring(0, 4).toInt(), text.substring(4, 6).toInt(), text.substring(6, 8).toInt())
                } catch (_: DateTimeException) {
                    return null
                }
            if (date.year < 1900) return null
            val kubun = RaceDataKubun.fromCodeOrNull(text[8]) ?: return null
            return SourceVersion(date, kubun)
        }
    }
}
