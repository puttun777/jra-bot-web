package jp.jrabot.mltrain.legacy

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.mltrain.job.ExitCode
import jp.jrabot.mltrain.job.JobLog
import jp.jrabot.mltrain.job.ModelJson
import jp.jrabot.mltrain.job.UsageError
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * `ml-train convert-pkl`: **利用者本人のローカル PC 専用**（クラウドでは実行しない。実 pkl をクラウドに置かない）。旧 legacy_pkl/cli.py の移植。
 *
 *     ml-train convert-pkl --pkl OLD.pkl --out-dir OUT --tenant-id 1 --model-id legacy-001 --confirm-own-local-file [--created-at 2026-11-02T00:00:00Z]
 *
 * MODEL_SIGNING_KEY: ローカルの鍵。出力は OUT/{model_id}.json と .sig と .conversion.json（上書きしない）。リポジトリ・クラウドに置かない。
 * 旧版の `--schema-dir` は廃止（既知のスキーマは Kotlin の定義から取る。指定されたら引数の誤り）。
 */
class ConvertPklCommand(
    private val log: JobLog,
    private val now: () -> Instant = Instant::now,
) {
    private val iso: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun run(
        args: List<String>,
        env: Map<String, String>,
    ): Int {
        val values = HashMap<String, String>()
        var confirmed = false
        val withValue = setOf("--pkl", "--out-dir", "--tenant-id", "--model-id", "--created-at")
        var i = 0
        while (i < args.size) {
            val k = args[i]
            when {
                k == "--confirm-own-local-file" -> {
                    confirmed = true
                    i++
                }

                k in withValue -> {
                    if (i + 1 >= args.size) throw UsageError("USAGE", "$k に値が無い")
                    if (values.put(k, args[i + 1]) != null) throw UsageError("USAGE", "$k が重複")
                    i += 2
                }

                else -> {
                    throw UsageError("USAGE", "不明な引数: $k")
                }
            }
        }
        for (k in listOf("--pkl", "--out-dir", "--tenant-id", "--model-id")) if (k !in values) throw UsageError("USAGE", "$k は必須")
        if (!confirmed) throw UsageError("CONFIRMATION_REQUIRED", "--confirm-own-local-file が必要（利用者本人のローカルのファイルだけを読む）")
        if (env["APP_ENV"] == "prod" || !env["OPS_MODE_SOURCE"].isNullOrEmpty() || !env["K_SERVICE"].isNullOrEmpty() || !env["CLOUD_RUN_JOB"].isNullOrEmpty()) {
            throw UsageError("LOCAL_ONLY", "クラウドの実行環境では pkl を読まない（convert-pkl は利用者のローカル専用）")
        }
        val tenant = TenantId.parseOrNull(values.getValue("--tenant-id")) ?: throw UsageError("USAGE", "--tenant-id は 1 以上")
        val modelId = values.getValue("--model-id")
        if (ModelId.ofOrNull(modelId) == null) throw UsageError("USAGE", "--model-id の形式が不正")
        val createdAt =
            values["--created-at"]?.let {
                try {
                    iso.format(Instant.parse(it))
                } catch (_: DateTimeParseException) {
                    throw UsageError("USAGE", "--created-at は ISO-8601 の UTC")
                }
            } ?: iso.format(now())
        val seed = ModelJson.loadSigningSeed(env["MODEL_SIGNING_KEY"])
        val data =
            try {
                Files.readAllBytes(Path.of(values.getValue("--pkl")))
            } catch (_: Exception) {
                throw UsageError("PKL_UNREADABLE", "pkl を読めない")
            }
        val shell =
            try {
                RestrictedPickle.loadLegacyModel(data)
            } catch (e: UnpickleRefused) {
                throw ConversionRefused("PICKLE_${e.reason}", e.message ?: e.reason)
            }
        val conv = LegacyConverter.convert(shell, modelId, tenant.value, createdAt, ModelJson.sha256Hex(data))
        val raw = CanonicalJson.encodeBytes(conv.model)
        val sig = ModelJson.sign(seed, raw)
        // 変換結果の検証: 推論側と同じ ModelLoader で読み戻す
        ModelJson.verify(
            raw,
            sig,
            listOf(ModelJson.publicKeyOf(seed)),
            tenant.value,
            modelId,
            (conv.model["kind"] as jp.jrabot.domain.ml.json.JsonString).value,
            null,
            null,
        )
        val out = Path.of(values.getValue("--out-dir"))
        Files.createDirectories(out)
        for ((name, content) in listOf(
            "$modelId.json" to raw,
            "$modelId.sig" to sig,
            "$modelId.conversion.json" to (LegacyConverter.reportJson(conv) + "\n").toByteArray(),
        )) {
            try {
                Files.write(out.resolve(name), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) // 上書きしない
            } catch (_: FileAlreadyExistsException) {
                throw UsageError("OUTPUT_EXISTS", "出力ファイルが既にある（上書きしない）")
            }
        }
        for (w in conv.warnings) log.log("convert_warning", fields = mapOf("message" to w))
        log.log(
            "JOB_RESULT",
            fields =
                linkedMapOf(
                    "exit_code" to ExitCode.OK,
                    "reason" to "CONVERTED",
                    "model_id" to modelId,
                    "kind" to (conv.model["kind"] as jp.jrabot.domain.ml.json.JsonString).value,
                    "dims" to (conv.model["weights"] as jp.jrabot.domain.ml.json.JsonArray).items.size,
                ),
        )
        return ExitCode.OK
    }
}
