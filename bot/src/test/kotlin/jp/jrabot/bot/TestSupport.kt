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
        )
    override.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
    return m
}

fun args(
    task: String = "calendar-check",
    tenant: String = "1",
    vararg more: String,
): Array<String> = arrayOf("--task=$task", "--tenant-id=$tenant", *more)
