package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

// ゴールデンの入力・出力の JSON 変換の補助（:domain の外。Jackson に依存してよい層）。

/** 旧 Python の挙動が Kotlin の型では表せない／まだ実装していない入力。呼び出し（ケース全体）を「未対応」として扱い、成功扱いにしない。 */
class UnsupportedGoldenCall(
    message: String,
) : RuntimeException(message)

internal val nodes: JsonNodeFactory = JsonNodeFactory.instance

internal fun obj(vararg pairs: Pair<String, Any?>): ObjectNode {
    val o = nodes.objectNode()
    for ((k, v) in pairs) o.set(k, node(v))
    return o
}

/**
 * Kotlin の値を JSON ノードにする。**Double は必ず小数の数値、Int/Long は整数**（比較器は型を厳密に見る: `1` と `1.0` は別）。
 * 非有限値は JSON に書けず、期待値側にも入らないので、出力せずに失敗させる。
 */
internal fun node(v: Any?): JsonNode =
    when (v) {
        null -> {
            nodes.nullNode()
        }

        is JsonNode -> {
            v
        }

        is Boolean -> {
            nodes.booleanNode(v)
        }

        is Int -> {
            nodes.numberNode(v)
        }

        is Long -> {
            nodes.numberNode(v)
        }

        is Double -> {
            check(v.isFinite()) { "非有限値は出力できない: $v" }
            nodes.numberNode(v)
        }

        is String -> {
            nodes.stringNode(v)
        }

        is List<*> -> {
            arr(v)
        }

        else -> {
            error("JSON にできない型: ${v::class}")
        }
    }

internal fun arr(items: List<Any?>): ArrayNode {
    val a = nodes.arrayNode()
    for (i in items) a.add(node(i))
    return a
}

// ---- 入力の取り出し。Python が int 演算になる入力（整数リテラルを float 引数に渡す）は結果の型が変わるので未対応にする

internal fun JsonNode.double(name: String): Double {
    val n = get(name) ?: throw UnsupportedGoldenCall("引数 $name が無い")
    return n.asDouble(name)
}

internal fun JsonNode.asDouble(what: String): Double {
    if (!isFloatingPointNumber) throw UnsupportedGoldenCall("$what は小数の数値でなければならない（整数は Python の int 演算になる）: $this")
    return doubleValue()
}

internal fun JsonNode.int(name: String): Int {
    val n = get(name) ?: throw UnsupportedGoldenCall("引数 $name が無い")
    return n.asInt(name)
}

internal fun JsonNode.asInt(what: String): Int {
    if (!isIntegralNumber || !canConvertToInt()) throw UnsupportedGoldenCall("$what は整数でなければならない: $this")
    return intValue()
}

internal fun JsonNode.doubleList(name: String): List<Double> {
    val n = get(name) ?: throw UnsupportedGoldenCall("引数 $name が無い")
    if (!n.isArray) throw UnsupportedGoldenCall("$name は配列でなければならない")
    return n.values().map { it.asDouble("$name[]") }
}

internal fun JsonNode.fieldNameList(): List<String> = propertyNames().toList()
