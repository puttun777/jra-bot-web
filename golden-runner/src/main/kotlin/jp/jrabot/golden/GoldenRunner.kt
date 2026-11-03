package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * ゴールデンテストの Kotlin 側ランナー。`tools/golden/fixtures` 配下の各ケース JSON の `input.calls` を :domain の計算で実行し、次の 2 つを書き出す。
 * - `--out`: `{"schema_version":1,"results":{<case_id>:{"results":[{call_id,outcome}…]}}}`（`check` の比較の入力。旧 Python の compare と同じ形）。
 *   **実行できたケースだけ**を含める（未対応のケースを空で埋めて成功に見せない）。
 * - `--coverage`: `{"schema_version":1,"supported":[{case_id,calls}],"unsupported":[{case_id,calls,reason}]}`。呼び出し数つき。
 *
 * ケース内に 1 つでも未対応の呼び出しがあれば、そのケース全体を未対応にする（一部だけ出力すると比較器が『件数の不一致』になる）。
 * 使い方: `golden-runner --fixtures tools/golden/fixtures --out build/golden/kotlin_out.json --coverage build/golden/kotlin_coverage.json`
 */
private val mapper: JsonMapper = JsonMapper.builder().build()

/** 全フィクスチャの実行結果。[results] は実行できたケースだけ、[coverage] は対応/未対応（呼び出し数つき）。 */
class RunOutput(
    val results: JsonNode,
    val coverage: JsonNode,
)

object GoldenRunner {
    /** [fixtures] 配下の各ケース JSON を実行する。 */
    fun run(fixtures: Path): RunOutput {
        val files =
            Files.list(fixtures).use { s ->
                s.filter { it.extension == "json" && it.name != "manifest.json" }.sorted().toList()
            }
        require(files.isNotEmpty()) { "フィクスチャが無い: $fixtures" }

        val results = nodes.objectNode()
        val supported = nodes.arrayNode()
        val unsupported = nodes.arrayNode()
        for (f in files) {
            val fx = mapper.readTree(Files.readAllBytes(f))
            val caseId = fx.get("case_id").stringValue()
            val calls = fx.get("input").get("calls")
            when (val r = runCase(calls)) {
                is CaseResult.Done -> {
                    results.set(caseId, obj("results" to r.results))
                    supported.add(obj("case_id" to caseId, "calls" to calls.size()))
                }

                is CaseResult.Unsupported -> {
                    unsupported.add(obj("case_id" to caseId, "calls" to calls.size(), "reason" to r.reason))
                }
            }
        }
        return RunOutput(
            obj("schema_version" to 1, "results" to results),
            obj("schema_version" to 1, "supported" to supported, "unsupported" to unsupported),
        )
    }

    fun toJson(node: JsonNode): String = mapper.writeValueAsString(node)
}

/**
 * サブコマンド（D-258。旧 Python の verify / kotlin_compare を Kotlin へ移した）:
 * - `verify --fixtures DIR --catalog FILE`: フィクスチャの静的検証（来歴・sha256・合成データ・正準形式・カタログ）。0 = 適合 / 1 = 不適合
 * - `check --fixtures DIR --catalog FILE [--report FILE] [--out FILE] [--coverage FILE]`: verify → 実行 → 比較。0 = 対応ケースがすべて一致 / 1 = 不適合・差分 / 2 = 形式不正
 * - 引数がオプションで始まるときは従来の実行だけ（`--fixtures --out --coverage`）
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "verify" -> exitProcess(verifyCommand(parseArgs(args.drop(1).toTypedArray(), setOf("--fixtures", "--catalog"), emptySet())))
        "check" -> exitProcess(checkCommand(parseArgs(args.drop(1).toTypedArray(), setOf("--fixtures", "--catalog"), setOf("--report", "--out", "--coverage"))))
        else -> runCommand(args)
    }
}

private fun verifyAndPrint(opts: Map<String, String>): Pair<Int, GoldenVerify.Result?> {
    val catalog =
        try {
            Catalog.load(Path.of(opts.getValue("--catalog")))
        } catch (e: Exception) {
            System.err.println("ERROR: カタログを読めない: ${e.message}")
            return 1 to null
        }
    val r = GoldenVerify.verify(Path.of(opts.getValue("--fixtures")), catalog)
    if (r.errors.isNotEmpty()) {
        System.err.println("フィクスチャ検証に失敗しました:")
        for (e in r.errors) System.err.println("  - $e")
        return 1 to r
    }
    val calls = r.byCase.values.sumOf { it.get("input").get("calls").size() }
    val st = catalog.statusCounts()
    println("OK: ${r.byCase.size} ケース / $calls 呼び出し。カタログ ${catalog.entries.size} 項目: " + st.entries.joinToString(" ") { "${it.key}=${it.value}" })
    return 0 to r
}

internal fun verifyCommand(opts: Map<String, String>): Int = verifyAndPrint(opts).first

internal fun checkCommand(opts: Map<String, String>): Int {
    val (rc, verified) = verifyAndPrint(opts)
    if (rc != 0 || verified == null) return 1
    val catalog = Catalog.load(Path.of(opts.getValue("--catalog")))
    val output = GoldenRunner.run(Path.of(opts.getValue("--fixtures")))
    opts["--out"]?.let { write(Path.of(it), output.results) }
    opts["--coverage"]?.let { write(Path.of(it), output.coverage) }
    val report =
        try {
            GoldenCompare.build(verified.byCase, output.results.get("results"), output.coverage, catalog)
        } catch (e: GoldenCompare.Invalid) {
            System.err.println("ERROR: ${e.message}")
            return 2
        }
    println(GoldenCompare.format(report))
    opts["--report"]?.let { write(Path.of(it), GoldenCompare.reportJson(report)) }
    return if (report.failed > 0) 1 else 0
}

private fun runCommand(args: Array<String>) {
    val opts = parseArgs(args, setOf("--fixtures", "--out", "--coverage"), emptySet())
    val out = Path.of(opts.getValue("--out"))
    val coveragePath = Path.of(opts.getValue("--coverage"))
    val output = GoldenRunner.run(Path.of(opts.getValue("--fixtures")))
    write(out, output.results)
    write(coveragePath, output.coverage)
    println("golden-runner: 対応 ${output.coverage.get("supported").size()} ケース / 未対応 ${output.coverage.get("unsupported").size()} ケース → $out, $coveragePath")
}

private sealed interface CaseResult {
    class Done(
        val results: JsonNode,
    ) : CaseResult

    class Unsupported(
        val reason: String,
    ) : CaseResult
}

private fun runCase(calls: JsonNode): CaseResult =
    try {
        CaseResult.Done(arr(calls.values().map { c -> obj("call_id" to c.get("call_id").stringValue(), "outcome" to GoldenCalls.run(c)) }))
    } catch (e: UnsupportedGoldenCall) {
        CaseResult.Unsupported(e.message ?: "未対応")
    }

private fun write(
    path: Path,
    node: JsonNode,
) {
    path.parent?.let { Files.createDirectories(it) }
    Files.writeString(path, GoldenRunner.toJson(node) + "\n")
}

private fun parseArgs(
    args: Array<String>,
    required: Set<String>,
    optional: Set<String>,
): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i]
        if ((k !in required && k !in optional) || i + 1 >= args.size || k in m) usage("不明・重複・値の無い引数: $k")
        m[k] = args[i + 1]
        i += 2
    }
    val missing = required - m.keys
    if (missing.isNotEmpty()) usage("必須引数が無い: $missing")
    return m
}

private fun usage(msg: String): Nothing {
    System.err.println(
        "ERROR: $msg\n使い方: golden-runner [verify|check] --fixtures DIR --catalog FILE … / golden-runner --fixtures DIR --out FILE --coverage FILE",
    )
    exitProcess(2)
}
