package jp.jrabot.bot

import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 固定のモードを返し、呼ばれた回数を数える ModeProvider。 */
class FixedMode(
    private val mode: OpsMode,
) : ModeProvider {
    val calls = AtomicInteger()

    override fun current(): OpsMode = mode.also { calls.incrementAndGet() }
}

/** 出力を記録する SLF4J Logger。 */
class RecordingLogger : AbstractLogger() {
    val lines = CopyOnWriteArrayList<String>()

    fun text(): String = lines.joinToString("\n")

    override fun isTraceEnabled() = true

    override fun isTraceEnabled(marker: Marker?) = true

    override fun isDebugEnabled() = true

    override fun isDebugEnabled(marker: Marker?) = true

    override fun isInfoEnabled() = true

    override fun isInfoEnabled(marker: Marker?) = true

    override fun isWarnEnabled() = true

    override fun isWarnEnabled(marker: Marker?) = true

    override fun isErrorEnabled() = true

    override fun isErrorEnabled(marker: Marker?) = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        lines += "$level ${MessageFormatter.arrayFormat(messagePattern, arguments).message}"
    }
}

/** 合成データだけの ops-state.json を置く（APP_ENV=local のときだけ使える）。 */
fun writeOpsState(
    dir: Path,
    mode: String = "NORMAL",
    seq: Long = 1,
): Path =
    dir.resolve("ops-state.json").also {
        Files.writeString(
            it,
            """{"schema_version":1,"seq":$seq,"mode":"$mode","desired":{"mode":"${if (mode == "NORMAL") "NORMAL" else "STOPPED"}",""" +
                """"set_by":"github:synthetic","set_at":"2026-11-01T10:00:00Z","reason":"test"},""" +
                """"operation":{"id":"op-1","type":"stop","status":"SUCCEEDED","force":false}}""",
        )
    }

/** 起動に必要な環境変数の一式（DB は接続を受け付けないポート 1。実 DB・実 GCP には接続しない）。 */
fun baseEnv(
    opsSource: String,
    vararg override: Pair<String, String?>,
): Map<String, String> {
    val m =
        mutableMapOf(
            "APP_ENV" to "local",
            "DB_URL" to "jdbc:mysql://127.0.0.1:1/jrabot",
            "DB_USER" to "bot",
            "DB_PASSWORD" to "synthetic-password-never-logged",
            "OPS_MODE_SOURCE" to opsSource,
            // export-dataset の保管先（APP_ENV=local のローカルのパス。実バケットではない）
            "MODEL_STORE_URI" to "file:///tmp/jrabot-test-store",
            // calendar-check の設定（テスト用の固定値。運用の既定値ではない）
            "CALENDAR_WATCH_START_MINUTES" to "120",
            "CALENDAR_PRESTART_MINUTES" to "10",
            "CALENDAR_SETTLE_DEADLINE_MINUTES" to "90",
            "CALENDAR_STALE_AFTER_MINUTES" to "120",
        )
    override.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
    return m
}

fun args(
    task: String = "calendar-check",
    tenant: String = "1",
    vararg more: String,
): Array<String> = arrayOf("--task=$task", "--tenant-id=$tenant", *more) + exportDefaults(task, more)

/** export-dataset は引数が必須。テストの既定値（呼び出し側が --dataset-id を指定していなければ補う）。 */
fun exportDefaults(
    task: String,
    more: Array<out String> = emptyArray(),
): Array<String> =
    if (task == "export-dataset" && more.none { it.startsWith("--dataset-id") }) {
        arrayOf("--dataset-id=ds-test", "--from=2026-01-01", "--to=2026-01-31", "--feature-schema=calibration")
    } else {
        emptyArray()
    }
