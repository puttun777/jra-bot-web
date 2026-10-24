package jp.jrabot.bot

import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** bot イメージの `--task=`（CONTRACTS §1・§9。`ml-train` は別イメージなので含めない）。名前は [LaunchableJob.cliName] と同じ。 */
enum class BotTask(
    val cliName: String,
    /** DB 接続プールの既定の最大数（CONTRACTS §8.3）。`DB_POOL_MAX` で上書きできる。 */
    val defaultPoolMax: Int,
    /** このタスクだけが受け付ける追加の引数。 */
    val options: Set<String>,
) {
    BOT_RUN("bot-run", 4, setOf("date", "trigger")),
    CALENDAR_CHECK("calendar-check", 1, setOf("date")),
    RECONCILE("reconcile", 1, setOf("date")),
    RETENTION("retention", 1, setOf("dry-run")),
    EXPORT_DATASET("export-dataset", 2, setOf("dataset-id", "from", "to", "feature-schema", "valid-fraction", "as-of-lead-minutes")),
    ;

    init {
        check(LaunchableJob.entries.any { it.cliName == cliName }) { "LaunchableJob に $cliName が無い" }
    }

    companion object {
        fun fromCliName(name: String): BotTask? = entries.firstOrNull { it.cliName == name }
    }
}

/** `--trigger` の値（RACE_CALENDAR_DESIGN §4 の `trigger_type`）。 */
enum class TriggerType { SCHEDULER, MANUAL, RECONCILE, BACKFILL }

/** `export-dataset` の `--feature-schema`（学習できるスキーマ。calibration=40 次元で odds を含む / alpha=35 次元）。 */
enum class ExportSchema(
    val cliName: String,
    val schema: FeatureSchema,
) {
    CALIBRATION("calibration", FeatureSchema.CALIBRATION_40),
    ALPHA("alpha", FeatureSchema.ALPHA_35),
}

/** `export-dataset` の検証済み引数（ML_DESIGN §11.7）。 */
data class ExportArgs(
    val datasetId: DatasetId,
    val from: LocalDate,
    val to: LocalDate,
    val schema: ExportSchema,
    /** valid にする日の割合（時系列の末尾）。 */
    val validFraction: Double = DEFAULT_VALID_FRACTION,
    /** `as_of` = 発走時刻 − この時間。 */
    val asOfLead: Duration = Duration.ofMinutes(DEFAULT_AS_OF_LEAD_MINUTES),
) {
    init {
        require(!to.isBefore(from)) { "to が from より前" }
    }

    companion object {
        const val DEFAULT_VALID_FRACTION = 0.2
        const val DEFAULT_AS_OF_LEAD_MINUTES = 10L

        /** 1 回の書出しの期間の上限（日）。長すぎる期間は分けて書き出す（メモリ・実行時間の上限）。 */
        const val MAX_RANGE_DAYS = 800L
    }
}

/** 検証済みの Job 引数。 */
data class BotCommand(
    val task: BotTask,
    val tenantId: TenantId,
    val date: LocalDate? = null,
    val trigger: TriggerType? = null,
    val dryRun: Boolean = false,
    /** `export-dataset` のときだけ非 null。 */
    val export: ExportArgs? = null,
)

sealed interface ParsedArgs {
    data class Valid(
        val command: BotCommand,
    ) : ParsedArgs

    /** 引数の誤り（終了コード 2）。[message] は引数の**値を含めない**（名前と理由だけ）。 */
    data class Invalid(
        val message: String,
    ) : ParsedArgs
}

/**
 * Job の引数の解析（CONTRACTS §9。`--task` 不明・必須の欠落・未知の引数はすべて終了コード 2）。
 *
 * 形式は `--名前=値`（Cloud Run Jobs の args に 1 要素ずつ渡す）と、値なしのフラグ `--dry-run`。位置引数・`--名前 値` の分割・重複は不可。
 * 共通: `--task`（必須）と `--tenant-id`（必須。すべてのタスクがテナント単位の実行）。タスク別の追加の引数は [BotTask.options]:
 *
 * | タスク | 追加の引数（すべて任意） |
 * |---|---|
 * | `bot-run` | `--date=YYYY-MM-DD`、`--trigger=SCHEDULER\|MANUAL\|RECONCILE\|BACKFILL`（JobLauncher が渡す上書き） |
 * | `calendar-check` / `reconcile` | `--date=YYYY-MM-DD` |
 * | `retention` | `--dry-run`（削除せず対象だけを数える。D-043） |
 * | `export-dataset` | **必須**: `--dataset-id=ID`（`[A-Za-z0-9_-]{1,64}`。データセットは不変なので内容ごとに別の ID）、`--from=YYYY-MM-DD`、`--to=YYYY-MM-DD`（レース日、両端を含む。最大 800 日）、`--feature-schema=calibration\|alpha`。任意: `--valid-fraction=0.2`（0 < x < 1）、`--as-of-lead-minutes=10`（1〜1440） |
 *
 * この表は T2.8 時点の暫定の契約（D-081）に、Stage 3-F2（T3.6-B）で `export-dataset` の引数を追加した（CONTRACTS 0.4.10）。
 */
object BotArgsParser {
    private val FLAGS = setOf("dry-run")

    fun parse(args: Array<String>): ParsedArgs {
        val values = linkedMapOf<String, String>()
        for (arg in args) {
            if (!arg.startsWith("--")) return invalid("位置引数は指定できません")
            val body = arg.removePrefix("--")
            val name = body.substringBefore('=')
            if (name.isEmpty() || !name.all { it.isLowerCase() || it == '-' }) return invalid("引数の形式が不正です")
            val hasValue = '=' in body
            if (name in FLAGS) {
                if (hasValue) return invalid("--$name は値を取りません")
            } else if (!hasValue || body.substringAfter('=').isEmpty()) {
                return invalid("--$name には値が必要です（--$name=値）")
            }
            if (values.put(name, body.substringAfter('=', "")) != null) return invalid("--$name が重複しています")
        }
        val taskName = values.remove("task") ?: return invalid("--task が必須です")
        val task = BotTask.fromCliName(taskName) ?: return invalid("--task が不明です（${BotTask.entries.joinToString("|") { it.cliName }}）")
        val tenantRaw = values.remove("tenant-id") ?: return invalid("--tenant-id が必須です")
        val tenant = TenantId.parseOrNull(tenantRaw) ?: return invalid("--tenant-id は 1 以上の整数です")
        val unknown = values.keys.filter { it !in task.options }
        if (unknown.isNotEmpty()) return invalid("--${task.cliName} で指定できない引数です: ${unknown.joinToString(", ") { "--$it" }}")
        val date =
            values["date"]?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    return invalid("--date は YYYY-MM-DD です")
                }
            }
        val export =
            if (task == BotTask.EXPORT_DATASET) {
                when (val r = parseExport(values)) {
                    is ExportParse.Ok -> r.args
                    is ExportParse.Bad -> return invalid(r.message)
                }
            } else {
                null
            }
        val trigger =
            values["trigger"]?.let { v ->
                TriggerType.entries.firstOrNull { it.name == v }
                    ?: return invalid("--trigger が不正です（${TriggerType.entries.joinToString("|")}）")
            }
        return ParsedArgs.Valid(BotCommand(task, tenant, date, trigger, dryRun = "dry-run" in values, export = export))
    }

    private sealed interface ExportParse {
        data class Ok(
            val args: ExportArgs,
        ) : ExportParse

        data class Bad(
            val message: String,
        ) : ExportParse
    }

    /** メッセージには引数の値を含めない（名前と理由だけ）。 */
    private fun parseExport(values: Map<String, String>): ExportParse {
        fun bad(m: String) = ExportParse.Bad(m)
        val id = values["dataset-id"] ?: return bad("--dataset-id が必須です")
        val datasetId = DatasetId.ofOrNull(id) ?: return bad("--dataset-id は英数字・_・- の 1〜64 文字です")
        fun date(name: String): LocalDate? =
            values[name]?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        if ("from" !in values || "to" !in values) return bad("--from と --to が必須です")
        val from = date("from") ?: return bad("--from は YYYY-MM-DD です")
        val to = date("to") ?: return bad("--to は YYYY-MM-DD です")
        if (to.isBefore(from)) return bad("--to は --from 以降です")
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= ExportArgs.MAX_RANGE_DAYS) return bad("期間は ${ExportArgs.MAX_RANGE_DAYS} 日未満です")
        val schemaName = values["feature-schema"] ?: return bad("--feature-schema が必須です")
        val schema = ExportSchema.entries.firstOrNull { it.cliName == schemaName } ?: return bad("--feature-schema は ${ExportSchema.entries.joinToString("|") { it.cliName }} です")
        val fraction =
            values["valid-fraction"]?.let {
                it.toDoubleOrNull()?.takeIf { v -> v > 0.0 && v < 1.0 } ?: return bad("--valid-fraction は 0 より大きく 1 より小さい数です")
            } ?: ExportArgs.DEFAULT_VALID_FRACTION
        val lead =
            values["as-of-lead-minutes"]?.let {
                it.toLongOrNull()?.takeIf { v -> v in 1..1440 } ?: return bad("--as-of-lead-minutes は 1〜1440 です")
            } ?: ExportArgs.DEFAULT_AS_OF_LEAD_MINUTES
        return ExportParse.Ok(ExportArgs(datasetId, from, to, schema, fraction, Duration.ofMinutes(lead)))
    }

    private fun invalid(message: String) = ParsedArgs.Invalid(message)
}
