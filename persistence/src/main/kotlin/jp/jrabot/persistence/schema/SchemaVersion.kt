package jp.jrabot.persistence.schema

/**
 * Flyway のバージョン（`flyway_schema_history.version`。`1`、`2`、`2.1` など。区切りは `.`）。数値の組として比較する
 * （`10` > `9`、`2.1` > `2`、`2.0` == `2`）。数字と `.` 以外・空の要素を含む文字列は null（呼び出し側は無視する）。
 */
class SchemaVersion private constructor(
    private val parts: List<Long>,
) : Comparable<SchemaVersion> {
    override fun compareTo(other: SchemaVersion): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val c = parts.getOrElse(i) { 0L }.compareTo(other.parts.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is SchemaVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0L }.hashCode()

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parseOrNull(raw: String?): SchemaVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text.length > 32) return null
            val parts = text.split('.').map { if (it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 9) it.toLong() else return null }
            return SchemaVersion(parts)
        }

        fun parse(raw: String): SchemaVersion = requireNotNull(parseOrNull(raw)) { "スキーマ版の形式が不正: '$raw'" }
    }
}
