package jp.jrabot.platform.ops

import jp.jrabot.domain.time.Clock
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 手動で進める固定の Clock（実時間を待たない）。 */
class MutableClock(
    start: Instant = Instant.parse("2026-11-01T00:00:00Z"),
) : Clock {
    @Volatile private var current: Instant = start

    override fun now(): Instant = current

    fun advance(d: Duration) {
        current = current.plus(d)
    }

    fun advanceMillis(ms: Long) = advance(Duration.ofMillis(ms))

    fun advanceSeconds(s: Long) = advance(Duration.ofSeconds(s))
}

/** 台本どおりに応答するフェイクの読取り元。台本が尽きたら直前の応答を繰り返す。読取り回数を数える。 */
class ScriptedSource(
    vararg steps: Step,
) : OpsStateSource {
    sealed interface Step {
        class Body(
            val bytes: ByteArray,
        ) : Step

        class Fail(
            val reason: OpsFailureReason,
        ) : Step
    }

    private val queue = ArrayDeque(steps.toList())
    private var last: Step? = null
    val reads = AtomicInteger()

    @Synchronized
    fun enqueue(step: Step) {
        queue.addLast(step)
    }

    @Synchronized
    override fun read(): ByteArray {
        reads.incrementAndGet()
        val step = queue.removeFirstOrNull() ?: last ?: error("台本が空")
        last = step
        return when (step) {
            is Step.Body -> step.bytes
            is Step.Fail -> throw OpsStateReadException(step.reason, "fake failure")
        }
    }
}

fun body(json: String): ScriptedSource.Step = ScriptedSource.Step.Body(json.toByteArray())

fun fail(reason: OpsFailureReason): ScriptedSource.Step = ScriptedSource.Step.Fail(reason)

/** 合成データだけの ops-state.json（infra OPERATIONS_DESIGN §2.2 の形。実データなし）。 */
fun opsStateJson(
    seq: Long = 1,
    mode: String = "NORMAL",
    desiredMode: String = "NORMAL",
    extra: String = "",
): String =
    """
    {
      "schema_version": 1,
      "seq": $seq,
      "mode": "$mode",
      "desired": { "mode": "$desiredMode", "set_by": "github:example", "set_at": "2026-11-01T10:00:00Z", "reason": "test" },
      "operation": { "id": "op-1", "type": "stop", "status": "SUCCEEDED", "force": false }
      $extra
    }
    """.trimIndent()

/** 出力を記録する SLF4J Logger。 */
class RecordingLogger : AbstractLogger() {
    data class Entry(
        val level: Level,
        val message: String,
        val hasThrowable: Boolean,
    )

    val entries = CopyOnWriteArrayList<Entry>()

    fun at(level: Level) = entries.filter { it.level == level }

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
        entries += Entry(level, MessageFormatter.arrayFormat(messagePattern, arguments).message, throwable != null)
    }
}
