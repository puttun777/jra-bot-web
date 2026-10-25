package jp.jrabot.domain.ml.features

import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson

/**
 * `horses` / `horse_master_history` の JSON 列（文字列）→ domain の値。旧 `features.py` の `_parse_json_list` と同じ寛容さ:
 * 読めない JSON・配列でない値は**空**として扱う（例外にしない）。数値でない着回数は 0、先頭 6 列まで、空の行は捨てる。
 * 重複キーなどの厳密さが要るのは署名つき成果物（[StrictJson]）の側で、DB の列は旧実装の読み方に合わせる。
 */
object HorseMasterJson {
    private fun list(text: String?): List<JsonValue> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            (StrictJson.parse(text) as? JsonArray)?.items ?: emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList() // JsonFormatException は IllegalArgumentException
        }
    }

    private val DIGITS = Regex("[0-9]{1,18}")

    /** 旧 `int(x) if isinstance(x, (int, float)) or (isinstance(x, str) and x.strip().isdigit()) else 0`（bool は int として 1 / 0）。 */
    private fun count(v: JsonValue): Long =
        when (v) {
            is JsonInt -> v.value
            is JsonFloat -> v.value.toLong()
            is JsonBool -> if (v.value) 1L else 0L
            is JsonString -> PythonText.strip(v.value).let { if (DIGITS.matches(it)) it.toLong() else 0L }
            else -> 0L
        }

    fun chakuTable(text: String?): ChakuTable =
        ChakuTable(
            list(text).mapNotNull { row ->
                (row as? JsonArray)
                    ?.items
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(6)
                    ?.map(::count)
            },
        )

    /** 脚質傾向の先頭が文字列ならその前後の空白を除いたもの。それ以外は空文字。 */
    fun styleHint(text: String?): String = (list(text).firstOrNull() as? JsonString)?.let { PythonText.strip(it.value) } ?: ""

    /** 祖先名（`name` が空でないものを先頭から。最大 [PedigreeHash.MAX_NAMES] 件）。 */
    fun pedigreeNames(text: String?): List<String> {
        val out = ArrayList<String>()
        for (p in list(text)) {
            if (p is JsonObject) {
                val name = (p["name"] as? JsonString)?.value?.let { PythonText.strip(it) } ?: ""
                if (name.isNotEmpty()) out += name
            }
            if (out.size >= PedigreeHash.MAX_NAMES) break
        }
        return out
    }
}
