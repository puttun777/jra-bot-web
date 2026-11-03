package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import kotlin.math.abs

/**
 * 期待値（旧 Python 由来のフィクスチャ）と Kotlin の実行結果の比較（旧 tools/golden/compare.py と kotlin_compare.py の移植。D-258）。
 *
 * - 各フィクスチャ自身の `comparison_rules` で比べる: 最も長く一致する override（`*` は配列の添字）、無ければ既定（浮動小数の abs_tol）。
 *   型は厳密（`1` と `1.0` は別）、キーの集合・配列の長さと順序も比べる。非有限のタグ `{"$float": …}` はタグどうしの完全一致だけ。
 * - 比べるのは Kotlin が『対応』と申告したケースだけ。未対応は**成功に数えない**。申告（coverage）とカタログの対応表が食い違ったら形式不正。
 */
object GoldenCompare {
    class Diff(
        val path: String,
        val kind: String,
        val expected: JsonNode?,
        val actual: JsonNode?,
        val rule: JsonNode,
        val absDiff: Double? = null,
    )

    /** 形式不正（終了コード 2）。 */
    class Invalid(
        message: String,
    ) : RuntimeException(message)

    private fun matches(
        pattern: List<String>,
        segs: List<String>,
    ): Boolean = pattern.size <= segs.size && pattern.indices.all { pattern[it] == "*" || pattern[it] == segs[it] }

    fun resolveRule(
        rules: JsonNode,
        segs: List<String>,
    ): JsonNode {
        var best: Pair<List<String>, JsonNode>? = null
        for (o in rules.get("overrides").values()) {
            val pat = o.get("path").stringValue().split('.')
            if (matches(pat, segs) && (best == null || pat.size > best.first.size)) best = pat to o
        }
        if (best != null) return best.second
        return (rules.get("default").get("float") as ObjectNode).deepCopy().put("path", "(default)")
    }

    private fun fmt(segs: List<Any>): String {
        val sb = StringBuilder()
        for (s in segs) if (s is Int) sb.append("[$s]") else sb.append(if (sb.isEmpty()) "" else ".").append(s)
        return if (sb.isEmpty()) "$" else sb.toString()
    }

    fun typeOf(v: JsonNode): String =
        when {
            v.isBoolean -> "bool"
            v.isIntegralNumber -> "int"
            v.isFloatingPointNumber -> "float"
            v.isString -> "str"
            v.isNull -> "null"
            v.isArray -> "list"
            v.isObject -> "object"
            else -> v.nodeType.name
        }

    private fun isNonfiniteTag(v: JsonNode) = v.isObject && v.propertyNames().toSet() == setOf(GoldenVerify.NONFINITE_KEY)

    fun diff(
        expected: JsonNode,
        actual: JsonNode,
        rules: JsonNode,
        segs: List<Any> = emptyList(),
        out: MutableList<Diff> = ArrayList(),
    ): MutableList<Diff> {
        val keySegs = segs.map { if (it is Int) "*" else it.toString() }
        val rule = resolveRule(rules, keySegs)
        val path = fmt(segs)
        if (rule.get("mode")?.stringValue() == "ignore") return out
        val te = typeOf(expected)
        val ta = typeOf(actual)
        when {
            te != ta -> {
                out += Diff(path, "type_mismatch", expected, actual, rule)
            }

            te == "object" -> {
                if (isNonfiniteTag(expected) || isNonfiniteTag(actual)) {
                    if (expected != actual) out += Diff(path, "nonfinite_mismatch", expected, actual, rule)
                    return out
                }
                val ek = expected.propertyNames().toSortedSet(PyJson::compareCodePoints)
                val ak = actual.propertyNames().toSortedSet(PyJson::compareCodePoints)
                for (k in ek - ak) out += Diff(fmt(segs + k), "missing_key", expected.get(k), null, rule)
                for (k in ak - ek) out += Diff(fmt(segs + k), "unexpected_key", null, actual.get(k), rule)
                for (k in ek.filter { it in ak }) diff(expected.get(k), actual.get(k), rules, segs + k, out)
            }

            te == "list" -> {
                if (expected.size() != actual.size()) {
                    out += Diff(path, "length_mismatch", nodes.numberNode(expected.size()), nodes.numberNode(actual.size()), rule)
                }
                for (i in 0 until minOf(expected.size(), actual.size())) diff(expected.get(i), actual.get(i), rules, segs + i, out)
            }

            te == "float" -> {
                val e = expected.doubleValue()
                val a = actual.doubleValue()
                val tol = rule.get("abs_tol")?.doubleValue() ?: 0.0
                if (!(e.isFinite() && a.isFinite())) {
                    out += Diff(path, "nonfinite_mismatch", expected, actual, rule)
                } else if (rule.get("mode")?.stringValue() == "exact" || tol == 0.0) {
                    if (e != a) out += Diff(path, "value_mismatch", expected, actual, rule, abs(e - a))
                } else if (abs(e - a) > tol) {
                    out += Diff(path, "value_mismatch", expected, actual, rule, abs(e - a))
                }
            }

            te == "int" -> {
                if (expected.bigIntegerValue() != actual.bigIntegerValue()) out += Diff(path, "value_mismatch", expected, actual, rule)
            }

            else -> {
                if (expected != actual) out += Diff(path, "value_mismatch", expected, actual, rule)
            }
        }
        return out
    }

    /** 浮動小数どうしの絶対誤差（型が違う箇所は [diff] が報告する）。 */
    fun floatErrors(
        expected: JsonNode,
        actual: JsonNode,
        path: String = "",
    ): List<Pair<String, Double>> =
        when {
            expected.isFloatingPointNumber && actual.isFloatingPointNumber -> {
                val e = expected.doubleValue()
                val a = actual.doubleValue()
                if (e.isFinite() && a.isFinite()) listOf(path to abs(e - a)) else emptyList()
            }

            expected.isObject && actual.isObject -> {
                expected
                    .propertyNames()
                    .filter { actual.has(it) }
                    .sortedWith(PyJson::compareCodePoints)
                    .flatMap { k -> floatErrors(expected.get(k), actual.get(k), if (path.isEmpty()) k else "$path.$k") }
            }

            expected.isArray && actual.isArray -> {
                (0 until minOf(expected.size(), actual.size())).flatMap { i -> floatErrors(expected.get(i), actual.get(i), "$path[$i]") }
            }

            else -> {
                emptyList()
            }
        }

    class CaseReport(
        val caseId: String,
        val status: String,
        val calls: Int,
        val diffs: List<Diff>,
        val floatsCompared: Int,
        val maxAbsError: Double,
        val maxAbsErrorAt: String,
        val tolerance: Double,
    )

    class Report(
        val cases: List<CaseReport>,
        val supportedCalls: Int,
        val unsupported: List<Triple<String, Int, String>>,
        val totalCases: Int,
        val totalCalls: Int,
    ) {
        val passed: Int get() = cases.count { it.status == "passed" }
        val failed: Int get() = cases.count { it.status == "failed" }
        val passedCalls: Int get() = cases.filter { it.status == "passed" }.sumOf { it.calls }
        val worst: CaseReport? get() = cases.maxByOrNull { it.maxAbsError }
    }

    /** [fixtures]: case_id → フィクスチャ、[results]: 実行結果の `results`、[coverage]: 申告。問題があれば [Invalid]。 */
    fun build(
        fixtures: Map<String, JsonNode>,
        results: JsonNode,
        coverage: JsonNode,
        catalog: Catalog,
    ): Report {
        if (!coverage.isObject || coverage.get("schema_version")?.intValue() != 1) throw Invalid("coverage の形式が不正です（schema_version=1 が必要）")
        val supArr = coverage.get("supported")
        val unsArr = coverage.get("unsupported")
        if (supArr == null || !supArr.isArray || unsArr == null || !unsArr.isArray) throw Invalid("coverage に supported / unsupported の配列が必要です")
        for (e in supArr.values() + unsArr.values()) {
            if (!e.isObject || e.get("case_id")?.isString != true || e.get("calls")?.isIntegralNumber != true) throw Invalid("coverage の要素が不正です: $e")
        }
        for (e in unsArr.values()) if (e.get("reason")?.stringValue().isNullOrEmpty()) throw Invalid("未対応のケースに理由がありません: ${e.get("case_id").stringValue()}")
        val sup = supArr.values().associate { it.get("case_id").stringValue() to it.get("calls").intValue() }
        val uns = unsArr.values().associate { it.get("case_id").stringValue() to (it.get("calls").intValue() to it.get("reason").stringValue()) }
        if (!results.isObject) throw Invalid("出力の形式が不正です（schema_version=1 と results オブジェクトが必要）")
        val both = (sup.keys intersect uns.keys).sorted()
        if (both.isNotEmpty()) throw Invalid("対応と未対応の両方に載っているケース: $both")
        val classified = sup.keys + uns.keys
        if (classified !=
            fixtures.keys
        ) {
            throw Invalid("フィクスチャとの分類が一致しない: 未分類=${(fixtures.keys - classified).sorted()} 不明=${(classified - fixtures.keys).sorted()}")
        }
        val out = results.propertyNames().toSet()
        if (out != sup.keys) throw Invalid("出力のケースが『対応』の申告と違う: 出力のみ=${(out - sup.keys).sorted()} 申告のみ=${(sup.keys - out).sorted()}")
        for ((cid, n) in sup + uns.mapValues { it.value.first }) {
            val want =
                fixtures
                    .getValue(cid)
                    .get("input")
                    .get("calls")
                    .size()
            if (n != want) throw Invalid("$cid: 申告の呼び出し数 $n がフィクスチャの $want と違う")
        }
        val portSup = catalog.supported.toSet()
        val portUns = catalog.unsupported.keys
        if (sup.keys != portSup || uns.keys != portUns) {
            val supDiff = ((sup.keys - portSup) + (portSup - sup.keys)).sorted()
            val unsDiff = ((uns.keys - portUns) + (portUns - uns.keys)).sorted()
            throw Invalid("Kotlin の申告が catalog.json の kotlin_port と違う: 対応の差=$supDiff 未対応の差=$unsDiff")
        }
        val cases =
            sup.keys.sorted().map { cid ->
                val fx = fixtures.getValue(cid)
                val diffs = diff(fx.get("expected_output"), results.get(cid), fx.get("comparison_rules"))
                val errs = floatErrors(fx.get("expected_output"), results.get(cid))
                val worst = errs.maxByOrNull { it.second } ?: ("" to 0.0)
                CaseReport(
                    cid,
                    if (diffs.isEmpty()) "passed" else "failed",
                    sup.getValue(cid),
                    diffs,
                    errs.size,
                    worst.second,
                    worst.first,
                    fx
                        .get("comparison_rules")
                        .get("default")
                        .get("float")
                        .get("abs_tol")
                        .doubleValue(),
                )
            }
        return Report(
            cases,
            sup.values.sum(),
            uns.entries.sortedBy { it.key }.map { Triple(it.key, it.value.first, it.value.second) },
            fixtures.size,
            fixtures.values.sumOf { it.get("input").get("calls").size() },
        )
    }

    private fun sci(d: Double): String = "%.3e".format(d)

    fun format(
        r: Report,
        limit: Int = 20,
    ): String {
        val lines = ArrayList<String>()
        lines += "Kotlin と旧 Python（固定コミット）のゴールデン比較"
        for (c in r.cases) {
            lines +=
                "  [${c.status.uppercase().padEnd(
                    6,
                )}] ${c.caseId}  calls=${c.calls}  float=${c.floatsCompared}  最大絶対誤差=${sci(
                    c.maxAbsError,
                )}（許容 ${if (c.tolerance == 0.0) "0" else PyJson.repr(c.tolerance)}）"
            for (d in c.diffs.take(limit)) {
                lines += "      ${d.path}: ${d.kind} expected=${d.expected} actual=${d.actual}" + (d.absDiff?.let { " abs_diff=${sci(it)}" } ?: "")
            }
            if (c.diffs.size > limit) lines += "      ... 他 ${c.diffs.size - limit} 件"
        }
        lines += "未対応（比較していない。成功に数えない）:"
        for ((cid, calls, reason) in r.unsupported) lines += "  [NOT PORTED] $cid  calls=$calls  理由: $reason"
        lines += "対応: ${r.cases.size} ケース / ${r.supportedCalls} 呼び出し（一致 ${r.passed} ケース / ${r.passedCalls} 呼び出し、不一致 ${r.failed} ケース）"
        lines += "未対応: ${r.unsupported.size} ケース / ${r.unsupported.sumOf { it.second }} 呼び出し"
        lines += "フィクスチャ全体: ${r.totalCases} ケース / ${r.totalCalls} 呼び出し（検証済み ${r.passedCalls} = ${"%.1f".format(100.0 * r.passedCalls / r.totalCalls)}%）"
        val w = r.worst
        lines += "最大絶対誤差: ${sci(w?.maxAbsError ?: 0.0)}（${w?.caseId} ${w?.maxAbsErrorAt}）"
        return lines.joinToString("\n")
    }

    fun reportJson(r: Report): JsonNode =
        obj(
            "schema_version" to 1,
            "supported" to
                obj("cases" to r.cases.size, "calls" to r.supportedCalls, "passed" to r.passed, "failed" to r.failed, "passed_calls" to r.passedCalls),
            "unsupported" to
                obj(
                    "cases" to r.unsupported.size,
                    "calls" to r.unsupported.sumOf { it.second },
                    "items" to r.unsupported.map { obj("case_id" to it.first, "calls" to it.second, "reason" to it.third) },
                ),
            "total" to obj("cases" to r.totalCases, "calls" to r.totalCalls),
            "max_abs_error" to (r.worst?.maxAbsError ?: 0.0),
            "max_abs_error_case" to r.worst?.caseId,
            "max_abs_error_at" to r.worst?.maxAbsErrorAt,
            "cases" to
                r.cases.map { c ->
                    obj(
                        "case_id" to c.caseId,
                        "status" to c.status,
                        "calls" to c.calls,
                        "floats_compared" to c.floatsCompared,
                        "max_abs_error" to c.maxAbsError,
                        "max_abs_error_at" to c.maxAbsErrorAt,
                        "tolerance" to c.tolerance,
                        "diffs" to
                            c.diffs.map { d ->
                                obj(
                                    "path" to d.path,
                                    "kind" to d.kind,
                                    "expected" to d.expected,
                                    "actual" to d.actual,
                                    "abs_diff" to d.absDiff,
                                    "rule" to d.rule,
                                )
                            },
                    )
                },
        )
}
