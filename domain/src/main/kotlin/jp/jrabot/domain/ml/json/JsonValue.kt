package jp.jrabot.domain.ml.json

/**
 * モデル JSON・`feature_schema`・データセット manifest を扱うための最小の JSON 木（ML_DESIGN §5.2）。
 *
 * `:domain` は Jackson に依存できない（D-106）うえ、**正準化（canonical JSON）で整数と浮動小数・重複キー・NaN を厳密に区別する必要がある**ので、
 * 自前の厳密なパーサ（[StrictJson]）と直列化（[CanonicalJson]）を持つ。Python の `json` と同じ意味になるのは次の範囲だけ（それ以外は拒否する）:
 * - 整数トークン（小数点・指数なし）は [JsonInt]（`Long` の範囲）、小数点または指数のあるトークンは [JsonFloat]（有限の `Double`）。Python の `int` / `float` と同じ。
 * - 重複キー、NaN / Infinity、先頭ゼロ、末尾カンマ、コメント、孤立サロゲートは拒否（Python の `json.loads` は一部を黙って受け入れる）。
 */
sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonBool(
    val value: Boolean,
) : JsonValue

/** 整数トークン。`-0` は 0 になる（Python の `int("-0")` と同じ）。 */
data class JsonInt(
    val value: Long,
) : JsonValue

/** 小数点または指数のあるトークン。有限のみ（NaN / Infinity は作れない）。 */
data class JsonFloat(
    val value: Double,
) : JsonValue {
    init {
        require(value.isFinite()) { "JSON の数値は有限: $value" }
    }
}

data class JsonString(
    val value: String,
) : JsonValue

data class JsonArray(
    val items: List<JsonValue>,
) : JsonValue {
    constructor(vararg items: JsonValue) : this(items.toList())
}

/** キーは重複しない。走査順は入力順（正準化では並べ替える）。 */
class JsonObject(
    fields: Map<String, JsonValue>,
) : JsonValue {
    val fields: Map<String, JsonValue> = LinkedHashMap(fields)

    operator fun get(key: String): JsonValue? = fields[key]

    fun has(key: String): Boolean = fields.containsKey(key)

    /** 新しいオブジェクト（元は変更しない）。 */
    fun with(
        key: String,
        value: JsonValue,
    ): JsonObject = JsonObject(LinkedHashMap(fields).also { it[key] = value })

    fun without(key: String): JsonObject = JsonObject(LinkedHashMap(fields).also { it.remove(key) })

    override fun equals(other: Any?): Boolean = other is JsonObject && other.fields == fields

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = "JsonObject(${fields.keys})"

    companion object {
        fun of(vararg pairs: Pair<String, JsonValue>): JsonObject {
            val m = LinkedHashMap<String, JsonValue>()
            for ((k, v) in pairs) require(m.put(k, v) == null) { "キーが重複: $k" }
            return JsonObject(m)
        }
    }
}

/** JSON として不正（構文・重複キー・範囲外の数値・不正な UTF-8 ほか）。メッセージに入力の内容は含めない（モデルや実データを漏らさない）。 */
class JsonFormatException(
    message: String,
    val offset: Int = -1,
) : IllegalArgumentException(if (offset >= 0) "$message (offset=$offset)" else message)
