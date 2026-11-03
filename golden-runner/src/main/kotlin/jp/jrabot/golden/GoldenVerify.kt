package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * コミット済みのゴールデンフィクスチャの静的検証（旧 tools/golden/verify.py の移植。D-258）。旧実装は不要で、フィクスチャを書き換えない。
 * 来歴（固定コミット・リポジトリ）・manifest の sha256・必須メタデータ・合成データだけであること（禁止パターン）・正準形式（Python の json.dumps と同じバイト列）・
 * 比較規則の範囲・カタログ（`tools/golden/catalog.json`）との整合を検査する。
 */
object GoldenVerify {
    const val MANIFEST = "manifest.json"
    private val mapper: JsonMapper = JsonMapper.builder().build()

    private val REQUIRED =
        listOf(
            "fixture_id",
            "case_id",
            "schema_version",
            "source_repository",
            "source_commit",
            "source_function",
            "input",
            "expected_output",
            "execution_parameters",
            "comparison_rules",
            "data_classification",
            "scenario_ids",
            "known_defects",
            "generator",
        )
    private val REQUIRED_EXEC = listOf("seed", "fixed_time", "timezone", "libraries")
    private const val FLAGS = Pattern.UNICODE_CHARACTER_CLASS
    private const val FLAGS_I = Pattern.UNICODE_CHARACTER_CLASS or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** 合成入力に現れてはならないパターン（秘密情報・実データの混入検知。旧版と同じ。Python の re は Unicode の \w・\b なので同じ旗を立てる）。 */
    private val FORBIDDEN: List<Pair<Pattern, String>> =
        listOf(
            Pattern.compile("discord(app)?\\.com/api/webhooks", FLAGS_I) to "Discord Webhook",
            Pattern.compile("https?://", FLAGS_I) to "URL",
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+", FLAGS) to "メールアドレス",
            Pattern.compile("\\.(sqlite3?|db|pkl|pickle)\\b", FLAGS_I) to "DB/モデルファイル名",
            Pattern.compile("[A-Za-z0-9_\\-]{40,}", FLAGS) to "長いトークン様文字列",
            Pattern.compile("-----BEGIN", FLAGS) to "秘密鍵",
            Pattern.compile("\\bJV[A-Z]?[-_]?(DATA|LINK)\\b", FLAGS_I) to "JV-Link/JV-Data 由来の記述",
        )
    private val SHA40 = Regex("^[0-9a-f]{40}$")
    const val NONFINITE_KEY = "\$float"

    class Fixture(
        val file: String,
        val raw: ByteArray,
        val json: JsonNode,
    )

    class Result(
        val errors: List<String>,
        val byCase: Map<String, JsonNode>,
    )

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** `*.json`（manifest を除く）。JSON として不正なら例外（呼び出し側で失敗にする）。 */
    fun loadDir(dir: Path): List<Fixture> =
        Files.list(dir).use { s -> s.filter { it.extension == "json" && it.name != MANIFEST }.sorted().toList() }.map {
            val raw = Files.readAllBytes(it)
            Fixture(it.name, raw, mapper.readTree(raw))
        }

    private fun walkStrings(
        n: JsonNode,
        path: String,
        out: MutableList<Pair<String, String>>,
    ) {
        when {
            n.isString -> out += path to n.stringValue()
            n.isObject -> for (k in n.propertyNames()) walkStrings(n.get(k), "$path.$k", out)
            n.isArray -> n.values().forEachIndexed { i, v -> walkStrings(v, "$path[$i]", out) }
        }
    }

    /** タグ付きの非有限値 `{"$float": …}` の場所。 */
    fun findNonfinite(
        n: JsonNode,
        path: String = "$",
    ): List<String> =
        when {
            n.isObject && n.propertyNames().toSet() == setOf(NONFINITE_KEY) -> listOf(path)
            n.isObject -> n.propertyNames().flatMap { findNonfinite(n.get(it), "$path.$it") }
            n.isArray -> n.values().flatMapIndexed { i, v -> findNonfinite(v, "$path[$i]") }
            else -> emptyList()
        }

    private fun isFloatInRange(n: JsonNode?): Boolean = n != null && n.isFloatingPointNumber && n.doubleValue() in 0.0..1e-6

    fun checkRules(
        rules: JsonNode?,
        errs: MutableList<String>,
        where: String,
    ) {
        val ok =
            run {
                val d = rules?.get("default")?.get("float") ?: return@run false
                if (d.get("mode")?.stringValue() !in setOf("abs_tol", "exact") || !isFloatInRange(d.get("abs_tol"))) return@run false
                val overrides = rules.get("overrides") ?: return@run false
                if (!overrides.isArray) return@run false
                for (o in overrides.values()) {
                    val mode = o.get("mode")?.stringValue()
                    val path = o.get("path")
                    if (mode !in setOf("abs_tol", "exact", "ignore") || path == null || !path.isString || path.stringValue().isEmpty()) return@run false
                    if (mode == "abs_tol" && !isFloatInRange(o.get("abs_tol"))) return@run false
                }
                val strict = rules.get("strict") ?: return@run false
                strict == mapper.readTree("""{"types":true,"key_set":true,"list_order":true,"nonfinite":"reject"}""")
            }
        if (!ok) errs += "$where: comparison_rules が不正（abs_tol は 0〜1e-6 の float、strict 設定は固定）"
    }

    fun verify(
        dir: Path,
        catalog: Catalog,
    ): Result {
        val errs = ArrayList<String>()
        val manifestPath = dir.resolve(MANIFEST)
        if (!Files.exists(manifestPath)) return Result(listOf("$MANIFEST がありません"), emptyMap())
        val manifest: JsonNode
        val loaded: List<Fixture>
        try {
            manifest = mapper.readTree(Files.readAllBytes(manifestPath))
            loaded = loadDir(dir)
        } catch (e: Exception) {
            return Result(listOf("JSON として不正: ${e.javaClass.simpleName}"), emptyMap())
        }
        val pinned = catalog.pinned
        if (manifest.get("schema_version")?.let { it.isIntegralNumber && it.intValue() in pinned.schemaVersions } !=
            true
        ) {
            errs += "manifest: 未対応の schema_version"
        }
        if (manifest.get("source_commit")?.stringValue() != pinned.sourceCommit) errs += "manifest: source_commit が固定値と異なる: ${manifest.get("source_commit")}"
        val entries =
            manifest
                .get("fixtures")
                ?.values()
                ?.toList()
                .orEmpty()
        val mf = entries.associateBy { it.get("path")?.stringValue().orEmpty() }
        val files = loaded.map { it.file }.toSet()
        if (mf.keys != files) errs += "manifest と実ファイルが不一致: manifest のみ=${(mf.keys - files).sorted()} 実ファイルのみ=${(files - mf.keys).sorted()}"
        if (mf.size != entries.size) errs += "manifest: パス重複"

        val ids = HashSet<String>()
        val fxIds = HashSet<String>()
        val byCase = LinkedHashMap<String, JsonNode>()
        for (f in loaded) {
            val w = f.file
            val fx = f.json
            mf[w]?.let { if (sha256(f.raw) != it.get("sha256")?.stringValue()) errs += "$w: manifest の sha256 と不一致（改ざん、または再生成後に manifest 未更新）" }
            if (!fx.isObject) {
                errs += "$w: トップレベルがオブジェクトでない"
                continue
            }
            val missing = REQUIRED.filter { !fx.has(it) }
            if (missing.isNotEmpty()) {
                errs += "$w: 必須メタデータなし $missing"
                continue
            }
            if (!(fx.get("schema_version").isIntegralNumber && fx.get("schema_version").intValue() in pinned.schemaVersions)) {
                errs += "$w: 未対応の schema_version=${fx.get("schema_version")}"
            }
            if (fx.get("source_repository").stringValue() != pinned.sourceRepository) errs += "$w: source_repository が想定外"
            val commit = fx.get("source_commit")
            if (!(commit.isString && SHA40.matches(commit.stringValue()))) {
                errs += "$w: source_commit が完全 SHA でない"
            } else if (commit.stringValue() != pinned.sourceCommit) {
                errs += "$w: source_commit が固定値と異なる"
            }
            val caseId = fx.get("case_id").stringValue() ?: ""
            if (w != "$caseId.json") errs += "$w: ファイル名と case_id が不一致"
            if (!ids.add(caseId)) errs += "$w: case_id 重複"
            if (!fxIds.add(fx.get("fixture_id").stringValue() ?: "")) errs += "$w: fixture_id 重複"
            byCase[caseId] = fx
            if (fx.get("data_classification").stringValue() != "synthetic") errs += "$w: data_classification が synthetic でない"
            if (fx.get("source_function").let { !it.isArray || it.isEmpty }) errs += "$w: source_function が空"
            val ep = fx.get("execution_parameters")
            if (!ep.isObject || REQUIRED_EXEC.any { !ep.has(it) }) {
                errs += "$w: execution_parameters に $REQUIRED_EXEC が必要"
            } else if (ep.get("timezone").stringValue() != pinned.timezone || !(ep.get("seed").isIntegralNumber && ep.get("seed").longValue() == pinned.seed)) {
                errs += "$w: seed/timezone が固定値と異なる"
            }
            val calls = fx.get("input").get("calls")
            val results = fx.get("expected_output").get("results")
            if (calls == null || results == null || !calls.isArray || !results.isArray || calls.isEmpty || results.isEmpty) {
                errs += "$w: input.calls / expected_output.results が空または不正"
            } else if (calls.values().map { it.get("call_id") } != results.values().map { it.get("call_id") }) {
                errs += "$w: input.calls と expected_output.results の call_id が対応しない"
            } else {
                if (calls
                        .values()
                        .map { it.get("call_id") }
                        .toSet()
                        .size != calls.size()
                ) {
                    errs += "$w: call_id 重複"
                }
                for (r in results.values()) {
                    val oc = r.get("outcome")
                    val status = oc?.get("status")?.stringValue()
                    val ok = (status == "ok" && oc.has("value")) || (status == "exception" && oc.has("exception_type"))
                    if (!ok) errs += "$w: ${r.get("call_id")?.stringValue()} の outcome が不正"
                }
            }
            for (p in findNonfinite(fx.get("expected_output")) + findNonfinite(fx.get("input"))) errs += "$w: $p に NaN/Infinity（タグ付き）。許可されていない"
            checkRules(fx.get("comparison_rules"), errs, w)
            if (String(f.raw, Charsets.UTF_8) != PyJson.dumps(fx)) errs += "$w: 正準 JSON 形式（キー昇順・indent=2・末尾改行）でない"
            val strings = ArrayList<Pair<String, String>>()
            walkStrings(fx.get("input"), "$", strings)
            for ((path, s) in strings) {
                for ((rx, label) in FORBIDDEN) if (rx.matcher(s).find()) errs += "$w: input の $path に禁止パターン（$label）"
            }
        }
        errs += catalog.check(byCase)
        return Result(errs, byCase)
    }
}
