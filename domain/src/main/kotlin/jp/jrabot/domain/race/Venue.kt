package jp.jrabot.domain.race

/**
 * JRA の競馬場（場コード 01..10。参照元 `jvlink_provider.py` の場名表、RACE_CALENDAR_DESIGN `venue_code`）。
 * 事実の定数であり、共有表 `code_venue` と一致させる。地方・海外のコードは対象外。
 */
enum class Venue(
    val code: String,
    val nameJa: String,
) {
    SAPPORO("01", "札幌"),
    HAKODATE("02", "函館"),
    FUKUSHIMA("03", "福島"),
    NIIGATA("04", "新潟"),
    TOKYO("05", "東京"),
    NAKAYAMA("06", "中山"),
    CHUKYO("07", "中京"),
    KYOTO("08", "京都"),
    HANSHIN("09", "阪神"),
    KOKURA("10", "小倉"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCodeOrNull(code: String): Venue? = byCode[code]

        fun fromCode(code: String): Venue = requireNotNull(fromCodeOrNull(code)) { "未知の場コード: '$code'（01〜10）" }
    }
}
