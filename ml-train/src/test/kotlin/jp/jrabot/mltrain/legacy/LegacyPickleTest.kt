package jp.jrabot.mltrain.legacy

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.mltrain.job.JobLog
import jp.jrabot.mltrain.job.MlTrainError
import jp.jrabot.mltrain.job.ModelJson
import jp.jrabot.mltrain.job.UsageError
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 旧モデル pickle の変換（convert-pkl）を、**削除前の Python 実装で固定した判定・出力**（`legacy-pkl/expected.json`。合成の pickle 42 件。
 * 実 pkl ではない）と比べる。変換できるものは、producer を旧版の値にしたモデル JSON が**バイト単位で一致**すること、拒否するものは理由コードが一致すること。
 */
class LegacyPickleTest {
    private val dir: Path = Path.of(requireNotNull(javaClass.getResource("/legacy-pkl/expected.json")).toURI()).parent
    private val expected: JsonNode = ObjectMapper().readTree(dir.resolve("expected.json").toFile())

    private fun outcome(data: ByteArray): Pair<String, Any> =
        try {
            val shell = RestrictedPickle.loadLegacyModel(data)
            val c = LegacyConverter.convert(shell, "legacy-001", 1, "2026-11-02T00:00:00Z", ModelJson.sha256Hex(data), producer = "ml-train/0.1.0/convert-pkl")
            "CONVERTED" to c
        } catch (e: UnpickleRefused) {
            "REFUSED" to "PICKLE_${e.reason}"
        } catch (e: ConversionRefused) {
            "REFUSED" to e.reason
        }

    @Test
    fun `every synthetic pickle gets the same verdict and output as the Python implementation`() {
        val cases = expected["cases"]
        assertEquals(42, cases.size())
        var converted = 0
        for ((name, e) in cases.properties()) {
            val data = Files.readAllBytes(dir.resolve("cases/$name.pkl"))
            assertEquals(e["sha256"].asString(), ModelJson.sha256Hex(data), "$name: フィクスチャが変わっている")
            val (kind, value) = outcome(data)
            assertEquals(e["outcome"].asString(), kind, name)
            if (kind == "CONVERTED") {
                val c = value as Conversion
                assertEquals(e["model_json"].asString(), CanonicalJson.encode(c.model), "$name: モデル JSON がバイト単位で一致しない")
                assertEquals((0 until e["warnings"].size()).map { e["warnings"][it].asString() }, c.warnings, name)
                converted++
            } else {
                assertEquals(e["reason"].asString(), value, name)
            }
        }
        assertTrue(converted >= 12, "変換できる例 $converted")
    }

    @Test
    fun `nothing is executed - a reduce of os system is refused when the global is read`() {
        val data = Files.readAllBytes(dir.resolve("cases/refused_os_system.pkl"))
        val err = assertFailsWith<UnpickleRefused> { RestrictedPickle.loadLegacyModel(data) }
        assertEquals("UNKNOWN_CLASS", err.reason)
    }

    @Test
    fun `size and opcode limits`() {
        assertEquals("SIZE", assertFailsWith<UnpickleRefused> { RestrictedPickle.loadLegacyModel(ByteArray(RestrictedPickle.MAX_BYTES + 1)) }.reason)
        // 200,001 個の opcode（MARK の繰り返し）は解釈の前に拒否
        val many = ByteArray(RestrictedPickle.MAX_OPCODES + 2) { '('.code.toByte() }.also { it[it.size - 1] = '.'.code.toByte() }
        assertEquals("TOO_MANY_OPCODES", assertFailsWith<UnpickleRefused> { RestrictedPickle.loadLegacyModel(many) }.reason)
        assertEquals("MALFORMED", assertFailsWith<UnpickleRefused> { RestrictedPickle.loadLegacyModel(byteArrayOf(0x80.toByte(), 4, 0xff.toByte())) }.reason)
        // STOP の無い（途中で切れた）入力
        assertEquals("MALFORMED", assertFailsWith<UnpickleRefused> { RestrictedPickle.loadLegacyModel(byteArrayOf(0x80.toByte(), 4, 'N'.code.toByte())) }.reason)
    }

    private fun cli(
        args: List<String>,
        env: Map<String, String>,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = ConvertPklCommand(JobLog(PrintStream(out, true, Charsets.UTF_8))).run(args, env)
        return code to out.toString(Charsets.UTF_8)
    }

    private val seed = ByteArray(32) { (it * 3 + 1).toByte() }
    private val env = mapOf("MODEL_SIGNING_KEY" to Base64.getEncoder().encodeToString(seed))

    private fun args(
        out: Path,
        vararg extra: String,
    ) = listOf("--pkl", dir.resolve("cases/valid_calibration40_p5.pkl").toString(), "--out-dir", out.toString(), "--tenant-id", "1", "--model-id", "legacy-001") + extra

    @Test
    fun `cli requires confirmation and refuses cloud environments`() {
        val out = createTempDirectory("convert")
        assertEquals("CONFIRMATION_REQUIRED", assertFailsWith<UsageError> { cli(args(out), env) }.reason)
        for (cloud in listOf("APP_ENV" to "prod", "OPS_MODE_SOURCE" to "gs://b/o", "K_SERVICE" to "x", "CLOUD_RUN_JOB" to "x")) {
            assertEquals("LOCAL_ONLY", assertFailsWith<UsageError> { cli(args(out, "--confirm-own-local-file"), env + cloud) }.reason, cloud.toString())
        }
        assertEquals("USAGE", assertFailsWith<UsageError> { cli(args(out, "--confirm-own-local-file", "--schema-dir", "x"), env) }.reason)
        assertEquals("MODEL_SIGNING_KEY_MISSING", assertFailsWith<UsageError> { cli(args(out, "--confirm-own-local-file"), emptyMap()) }.reason)
        assertEquals(0, Files.list(out).count())
    }

    @Test
    fun `cli converts, writes signed files verifiable by the inference loader, and never overwrites`() {
        val out = createTempDirectory("convert")
        val (code, log) = cli(args(out, "--confirm-own-local-file", "--created-at", "2026-11-02T00:00:00Z"), env)
        assertEquals(0, code)
        assertTrue("\"reason\":\"CONVERTED\"" in log && "\"kind\":\"legacy_compat\"" in log)
        val raw = Files.readAllBytes(out.resolve("legacy-001.json"))
        val sig = Files.readAllBytes(out.resolve("legacy-001.sig"))
        ModelJson.verify(raw, sig, listOf(ModelJson.publicKeyOf(seed)), 1, "legacy-001", "legacy_compat", null, null)
        val model = StrictJson.parse(raw) as JsonObject
        assertEquals(JsonString("ml-train/1.0.0/convert-pkl"), model["producer"])
        val report = StrictJson.parse(Files.readAllBytes(out.resolve("legacy-001.conversion.json"))) as JsonObject
        assertEquals(model["sha256"], report["sha256"])
        val before = Files.readAllBytes(out.resolve("legacy-001.json"))
        assertEquals("OUTPUT_EXISTS", assertFailsWith<UsageError> { cli(args(out, "--confirm-own-local-file"), env) }.reason)
        assertContentEquals(before, Files.readAllBytes(out.resolve("legacy-001.json")))
        // 拒否される pickle は何も書かない
        val out2 = createTempDirectory("convert")
        val bad = listOf("--pkl", dir.resolve("cases/refused_os_system.pkl").toString(), "--out-dir", out2.toString(), "--tenant-id", "1", "--model-id", "x", "--confirm-own-local-file")
        assertEquals("PICKLE_UNKNOWN_CLASS", assertFailsWith<MlTrainError> { cli(bad, env) }.reason)
        assertEquals(0, Files.list(out2).count())
    }
}
