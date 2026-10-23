package jp.jrabot.bot

import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.tenant.TenantId
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
    EXPORT_DATASET("export-dataset", 2, emptySet()),
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

/** 検証済みの Job 引数。 */
data class BotCommand(
    val task: BotTask,
    val tenantId: TenantId,
    val date: LocalDate? = null,
    val trigger: TriggerType? = null,
    val dryRun: Boolean = false,
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
 * | `export-dataset` | なし |
 *
 * この表は T2.8 時点の暫定の契約（D-081）。業務ロジックを実装する Stage 3 で、必要な引数が増えたらここと CONTRACTS を更新する。
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
        val trigger =
            values["trigger"]?.let { v ->
                TriggerType.entries.firstOrNull { it.name == v }
                    ?: return invalid("--trigger が不正です（${TriggerType.entries.joinToString("|")}）")
            }
        return ParsedArgs.Valid(BotCommand(task, tenant, date, trigger, dryRun = "dry-run" in values))
    }

    private fun invalid(message: String) = ParsedArgs.Invalid(message)
}
