package jp.jrabot.dbmigration

/** 標準出力用の最小の JSON 書き出し（1 行）。ライブラリを増やさないための手書きで、文字列の制御文字・引用符を正しくエスケープする。 */
object Json {
    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) -> "${string(k)}:${value(v)}" }

    fun value(v: Any?): String =
        when (v) {
            null -> "null"
            is String -> string(v)
            is Boolean -> v.toString()
            is Int, is Long -> v.toString()
            is Iterable<*> -> v.joinToString(prefix = "[", postfix = "]", separator = ",") { value(it) }
            else -> throw IllegalArgumentException("JSON に変換できない型: ${v::class.simpleName}")
        }

    fun string(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
