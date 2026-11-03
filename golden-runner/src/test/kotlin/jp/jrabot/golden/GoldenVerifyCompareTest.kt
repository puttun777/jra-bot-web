package jp.jrabot.golden

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** フィクスチャの静的検証と比較器（旧 tools/golden/tests の test_verify・test_compare・test_kotlin_compare・test_jsonutil の移植。D-258）。 */
class GoldenVerifyCompareTest {
    private val mapper = JsonMapper.builder().build()
    private val fixtures: Path = Path.of("..", "tools", "golden", "fixtures")
    private val catalog: Catalog = Catalog.load(Path.of("..", "tools", "golden", "catalog.json"))

    private fun copy(): Path {
        val d = createTempDirectory("golden")
        Files.list(fixtures).use { s -> s.forEach { Files.copy(it, d.resolve(it.name)) } }
        return d
    }

    private fun read(
        d: Path,
        name: String,
    ): ObjectNode = mapper.readTree(Files.readAllBytes(d.resolve(name))) as ObjectNode

    /** フィクスチャを書き換え、正準形式で保存し、[updateManifest] なら manifest の sha256 も合わせる（他の検査だけを効かせる）。 */
    private fun mutate(
        d: Path,
        name: String,
        updateManifest: Boolean = true,
        f: (ObjectNode) -> Unit,
    ) {
        val fx = read(d, name)
        f(fx)
        val bytes = PyJson.dumps(fx).toByteArray()
        Files.write(d.resolve(name), bytes)
        if (updateManifest) {
            val m = read(d, "manifest.json")
            for (e in m.get("fixtures").values()) if (e.get("path").stringValue() == name) (e as ObjectNode).put("sha256", GoldenVerify.sha256(bytes))
            Files.write(d.resolve("manifest.json"), PyJson.dumps(m).toByteArray())
        }
    }

    private fun errors(d: Path) = GoldenVerify.verify(d, catalog).errors

    private fun assertError(
        d: Path,
        needle: String,
    ) {
        val e = errors(d)
        assertTrue(e.any { needle in it }, "『$needle』が無い: $e")
    }

    private val first = "GD01-EV-001.json"

    @Test
    fun `committed fixtures pass and are written exactly in the python canonical form`() {
        val r = GoldenVerify.verify(fixtures, catalog)
        assertEquals(emptyList(), r.errors)
        assertEquals(35, r.byCase.size)
        for (f in GoldenVerify.loadDir(fixtures)) assertEquals(String(f.raw, Charsets.UTF_8), PyJson.dumps(f.json), f.file)
    }

    @Test
    fun `tampering is detected by the manifest hash, and other checks still run when the manifest is updated`() {
        val d = copy()
        mutate(d, first, updateManifest = false) {
            (
                it
                    .get("expected_output")
                    .get("results")
                    .get(0)
                    .get("outcome") as ObjectNode
            ).put("value", 0.123)
        }
        assertError(d, "manifest の sha256 と不一致")
        val d2 = copy()
        mutate(d2, first) { it.put("source_commit", "0".repeat(40)) }
        assertTrue(errors(d2).none { "sha256" in it })
        assertError(d2, "source_commit が固定値と異なる")
    }

    @Test
    fun `metadata, commit, schema version, synthetic only`() {
        mapOf<(ObjectNode) -> Unit, String>(
            { fx: ObjectNode ->
                fx.remove("generator")
                Unit
            } to "必須メタデータなし",
            { fx: ObjectNode ->
                fx.put("source_commit", "f7dbf69")
                Unit
            } to "完全 SHA でない",
            { fx: ObjectNode ->
                fx.put("schema_version", 2)
                Unit
            } to "未対応の schema_version",
            { fx: ObjectNode ->
                fx.put("data_classification", "real")
                Unit
            } to "synthetic でない",
            { fx: ObjectNode ->
                (fx.get("execution_parameters") as ObjectNode).put("seed", 1)
                Unit
            } to "seed/timezone",
            { fx: ObjectNode ->
                fx.putArray("source_function")
                Unit
            } to "source_function が空",
        ).forEach { (f, needle) ->
            val d = copy()
            mutate(d, first, f = f)
            assertError(d, needle)
        }
    }

    @Test
    fun `invalid json and nan literals are rejected`() {
        val d = copy()
        Files.writeString(d.resolve(first), "{ not json")
        assertError(d, "JSON として不正")
        val d2 = copy()
        Files.writeString(d2.resolve(first), Files.readString(d2.resolve(first)).replaceFirst("\"value\": ", "\"value\": NaN, \"x\": "))
        assertError(d2, "JSON として不正")
    }

    @Test
    fun `tagged nonfinite values, duplicate ids, manifest mismatch, bad rules`() {
        val d = copy()
        mutate(d, first) {
            (
                it
                    .get("expected_output")
                    .get("results")
                    .get(0)
                    .get("outcome") as ObjectNode
            ).putObject("value").put("\$float", "nan")
        }
        assertError(d, "NaN/Infinity（タグ付き）")
        val d2 = copy()
        Files.copy(d2.resolve(first), d2.resolve("GD01-EV-COPY.json"))
        assertError(d2, "manifest と実ファイルが不一致")
        assertError(d2, "ファイル名と case_id が不一致")
        assertError(d2, "case_id 重複")
        for (bad in listOf<(ObjectNode) -> Unit>(
            { r -> (r.get("default").get("float") as ObjectNode).put("abs_tol", 1e-3) },
            { r -> (r.get("default").get("float") as ObjectNode).put("abs_tol", 0) }, // 整数は不可（float）
            { r -> (r.get("default").get("float") as ObjectNode).put("mode", "loose") },
            { r -> (r.get("strict") as ObjectNode).put("types", false) },
            { r -> (r.get("overrides") as ArrayNode).addObject().put("path", "").put("mode", "exact") },
        )) {
            val d3 = copy()
            mutate(d3, first) { bad(it.get("comparison_rules") as ObjectNode) }
            assertError(d3, "comparison_rules が不正")
        }
    }

    @Test
    fun `forbidden content in the input is detected`() {
        for ((text, label) in listOf(
            "https://discord.com/api/webhooks/1/abc" to "Discord Webhook",
            "see http://x" to "URL",
            "owner@example.com" to "メールアドレス",
            "races.sqlite3" to "DB/モデルファイル名",
            "model.pkl" to "DB/モデルファイル名",
            "A".repeat(40) to "長いトークン様文字列",
            "-----BEGIN PRIVATE KEY" to "秘密鍵",
            "JV-Data の行" to "JV-Link/JV-Data",
            "ユーザー＠例え.日本" to "", // 全角の＠はメールにしない（旧版と同じ）
        )) {
            val d = copy()
            mutate(d, first) { (it.get("input").get("calls").get(0) as ObjectNode).put("note", text) }
            if (label.isEmpty()) assertTrue(errors(d).none { "禁止パターン" in it }, text) else assertError(d, "禁止パターン（$label")
        }
    }

    @Test
    fun `call ids must correspond and the format must be canonical`() {
        val d = copy()
        mutate(d, first) { (it.get("expected_output").get("results").get(0) as ObjectNode).put("call_id", "other") }
        assertError(d, "call_id が対応しない")
        val d2 = copy()
        val text = Files.readString(d2.resolve(first))
        val compact = mapper.writeValueAsString(mapper.readTree(text)) + "\n"
        Files.writeString(d2.resolve(first), compact)
        val m = read(d2, "manifest.json")
        for (e in m.get("fixtures").values()) {
            if (e.get("path").stringValue() ==
                first
            ) {
                (e as ObjectNode).put("sha256", GoldenVerify.sha256(compact.toByteArray()))
            }
        }
        Files.write(d2.resolve("manifest.json"), PyJson.dumps(m).toByteArray())
        assertError(d2, "正準 JSON 形式")
    }

    @Test
    fun `catalog consistency`() {
        val r = GoldenVerify.verify(fixtures, catalog)
        val broken = Catalog(catalog.pinned, catalog.validStatus, catalog.entries, catalog.supported.drop(1), catalog.unsupported)
        assertTrue(broken.check(r.byCase).any { "KOTLIN_PORT に分類されていない" in it })
        val dup = Catalog(catalog.pinned, catalog.validStatus, catalog.entries + catalog.entries.first(), catalog.supported, catalog.unsupported)
        assertTrue(dup.check(r.byCase).any { "id 重複" in it })
    }

    // ------------------------------------------------------------------ 比較

    private fun j(s: String): JsonNode = mapper.readTree(s)

    private val rules =
        j(
            """{"default":{"float":{"mode":"abs_tol","abs_tol":1e-9}},"overrides":[{"path":"x.exact","mode":"exact"},{"path":"msg","mode":"ignore"}],"strict":{}}""",
        )

    private fun d(
        e: String,
        a: String,
    ) = GoldenCompare.diff(j(e), j(a), rules)

    @Test
    fun `tolerance, exact override, types, keys, lists, nonfinite, ignore`() {
        assertEquals(0, d("""{"v":1.0}""", """{"v":1.0000000001}""").size)
        val out = d("""{"v":1.0}""", """{"v":1.1}""")
        assertEquals("value_mismatch" to "v", out.single().kind to out.single().path)
        assertEquals(1, d("""{"x":{"exact":1.0}}""", """{"x":{"exact":1.0000000001}}""").size)
        assertEquals("type_mismatch", d("""{"v":1}""", """{"v":1.0}""").single().kind)
        assertEquals("type_mismatch", d("""{"v":true}""", """{"v":1}""").single().kind)
        assertEquals(setOf("missing_key", "unexpected_key"), d("""{"a":1}""", """{"b":1}""").map { it.kind }.toSet())
        assertEquals("length_mismatch", d("""[1,2]""", """[1]""").first().kind)
        assertEquals(2, d("""[1,2]""", """[2,1]""").size)
        assertEquals("type_mismatch", d("""{"v":{"${'$'}float":"nan"}}""", """{"v":1.0}""").single().kind)
        assertEquals("nonfinite_mismatch", d("""{"v":{"${'$'}float":"nan"}}""", """{"v":{"${'$'}float":"inf"}}""").single().kind)
        assertEquals(0, d("""{"v":{"${'$'}float":"inf"}}""", """{"v":{"${'$'}float":"inf"}}""").size)
        assertEquals(0, d("""{"msg":"a"}""", """{"msg":"b"}""").size)
        assertEquals("[0].a", d("""[{"a":1}]""", """[{"a":2}]""").single().path)
    }

    @Test
    fun `self comparison passes for every case and a perturbed value is reported with its case`() {
        val byCase = GoldenVerify.verify(fixtures, catalog).byCase
        for ((cid, fx) in byCase) {
            assertEquals(
                0,
                GoldenCompare.diff(fx.get("expected_output"), fx.get("expected_output"), fx.get("comparison_rules")).size,
                cid,
            )
        }
        val output = GoldenRunner.run(fixtures)
        val report = GoldenCompare.build(byCase, output.results.get("results"), output.coverage, catalog)
        assertEquals(0, report.failed)
        assertEquals(34, report.passed)
        assertEquals(listOf("GD10-DEFAULTS-001"), report.unsupported.map { it.first })
        // 1 つの値をずらすと、そのケースだけ不一致
        val results = output.results.get("results").deepCopy() as ObjectNode
        val v =
            results
                .get("GD01-EV-001")
                .get("results")
                .get(0)
                .get("outcome") as ObjectNode
        v.put("value", v.get("value").doubleValue() + 1e-6)
        val bad = GoldenCompare.build(byCase, results, output.coverage, catalog)
        assertEquals(listOf("GD01-EV-001"), bad.cases.filter { it.status == "failed" }.map { it.caseId })
    }

    @Test
    fun `structural problems are invalid, not failures to hide`() {
        val byCase = GoldenVerify.verify(fixtures, catalog).byCase
        val output = GoldenRunner.run(fixtures)
        val res = output.results.get("results")
        val cov = output.coverage
        val missing = (res.deepCopy() as ObjectNode).also { it.remove("GD01-EV-001") }
        assertFailsWith<GoldenCompare.Invalid> { GoldenCompare.build(byCase, missing, cov, catalog) }
        val badCalls = cov.deepCopy() as ObjectNode
        (badCalls.get("supported").get(0) as ObjectNode).put("calls", 999)
        assertFailsWith<GoldenCompare.Invalid> { GoldenCompare.build(byCase, res, badCalls, catalog) }
        val noReason = cov.deepCopy() as ObjectNode
        (noReason.get("unsupported").get(0) as ObjectNode).put("reason", "")
        assertFailsWith<GoldenCompare.Invalid> { GoldenCompare.build(byCase, res, noReason, catalog) }
        val otherTable =
            Catalog(
                catalog.pinned,
                catalog.validStatus,
                catalog.entries,
                catalog.supported - "GD01-EV-001",
                catalog.unsupported + ("GD01-EV-001" to "x"),
            )
        assertFailsWith<GoldenCompare.Invalid> { GoldenCompare.build(byCase, res, cov, otherTable) }
        assertFailsWith<GoldenCompare.Invalid> { GoldenCompare.build(byCase, res, j("""{"schema_version":2}"""), catalog) }
    }

    @Test
    fun `float errors walk both trees`() {
        val e = GoldenCompare.floatErrors(j("""{"a":[1.0,2.0],"b":{"c":3.5},"d":1}"""), j("""{"a":[1.5,2.0],"b":{"c":3.0},"d":1}"""))
        assertEquals(listOf("a[0]" to 0.5, "a[1]" to 0.0, "b.c" to 0.5), e)
    }

    // ------------------------------------------------------------------ Python の json.dumps と同じ形式

    @Test
    fun `python repr and dumps`() {
        for ((v, want) in listOf(
            1.0 to "1.0",
            0.1 to "0.1",
            1e-5 to "1e-05",
            1e16 to "1e+16",
            1.5e300 to "1.5e+300",
            -0.0 to "-0.0",
            123456789012345.6 to "123456789012345.6",
            5e-324 to "5e-324",
        )) {
            assertEquals(want, PyJson.repr(v), v.toString())
        }
        assertEquals("{\n  \"a\": [\n    1,\n    2.5\n  ],\n  \"b\": {},\n  \"c\": []\n}\n", PyJson.dumps(j("""{"c":[],"b":{},"a":[1,2.5]}""")))
        assertEquals("\"\\\"\\\\\\n\\t\\u0001é \"\n", PyJson.dumps(j("\"\\\"\\\\\\n\\t\\u0001é\\u2028\"")))
        // キーはコードポイント順（U+FF5E < U+1F600。UTF-16 の比較では逆になる）
        assertEquals(listOf("～", "😀"), listOf("😀", "～").sortedWith(PyJson::compareCodePoints))
    }
}
