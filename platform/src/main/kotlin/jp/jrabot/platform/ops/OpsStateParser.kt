package jp.jrabot.platform.ops

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.ops.WorkloadStatus
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** [OpsStateParser.parse] の結果。[Invalid.detail] は項目名などの固定語だけで、入力の値は含めない（ログに出せる）。 */
internal sealed interface ParseResult {
    data class Valid(
        val state: OpsState,
    ) : ParseResult

    data class Invalid(
        val reason: OpsFailureReason,
        val detail: String,
    ) : ParseResult
}

/**
 * `ops-state.json` の構文解析とスキーマ検証（CONTRACTS §5.2、infra OPERATIONS_DESIGN §2.2）。GCS 版・S3 版・ローカル版で共通。
 *
 * - 必須: `schema_version`（整数 1 のみ）、`seq`（0 以上の整数）、`mode`（NORMAL/STARTING/STOPPING/STOPPED）、
 *   `desired`（オブジェクト）の `mode`（NORMAL/STOPPED）。
 * - 任意（null・欠落可。あれば型を検査）: `desired.set_at`（ISO-8601 の日時）、`operation.type` / `operation.status`（文字列）。
 *   `operation.status` の値の種類は検査しない（infra が値を増やしても正しい状態を拒否しない）。
 * - その他のフィールドは無視する。重複キー・末尾の余分なトークン・整数でない数値（`42.0`）は不正。
 * - schema_version を最初に検査する（将来の版で形が変わっても「版の不一致」として扱う）。
 */
internal object OpsStateParser {
    const val SUPPORTED_SCHEMA_VERSION = 1L

    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()

    private val desiredModes = setOf(OpsMode.NORMAL, OpsMode.STOPPED)

    private class Reject(
        val reason: OpsFailureReason,
        val detail: String,
    ) : RuntimeException(null, null, false, false)

    fun parse(bytes: ByteArray): ParseResult =
        try {
            ParseResult.Valid(toState(readRoot(bytes)))
        } catch (e: Reject) {
            ParseResult.Invalid(e.reason, e.detail)
        }

    private fun readRoot(bytes: ByteArray): JsonNode {
        val root =
            try {
                mapper.readTree(bytes)
            } catch (_: JacksonException) {
                throw Reject(OpsFailureReason.INVALID_JSON, "JSON として解析できない")
            }
        if (root == null || !root.isObject) throw Reject(OpsFailureReason.INVALID_JSON, "ルートがオブジェクトではない")
        return root
    }

    private fun toState(root: JsonNode): OpsState {
        val schema = required(root, "schema_version")
        if (!schema.isIntegralNumber || !schema.canConvertToLong()) {
            throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, "schema_version")
        }
        if (schema.longValue() != SUPPORTED_SCHEMA_VERSION) {
            throw Reject(OpsFailureReason.SCHEMA_VERSION_MISMATCH, "schema_version=${schema.longValue()}")
        }

        val seqNode = required(root, "seq")
        if (!seqNode.isIntegralNumber || !seqNode.canConvertToLong() || seqNode.longValue() < 0) {
            throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, "seq")
        }

        val modeNode = required(root, "mode")
        if (!modeNode.isString) throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, "mode")
        val mode = OpsMode.fromSourceValueOrNull(modeNode.stringValue()) ?: throw Reject(OpsFailureReason.INVALID_MODE, "mode")

        val desired = required(root, "desired")
        if (!desired.isObject) throw Reject(OpsFailureReason.INVALID_DESIRED, "desired がオブジェクトではない")
        val desiredModeNode = desired.get("mode")
        val desiredMode =
            (if (desiredModeNode != null && desiredModeNode.isString) OpsMode.fromSourceValueOrNull(desiredModeNode.stringValue()) else null)
                ?.takeIf { it in desiredModes }
                ?: throw Reject(OpsFailureReason.INVALID_DESIRED, "desired.mode")

        val operation = optionalObject(root, "operation")
        return OpsState(
            seq = seqNode.longValue(),
            mode = mode,
            desiredMode = desiredMode,
            desiredSetAt = optionalInstant(desired, "desired.set_at", "set_at"),
            operationType = operation?.let { optionalString(it, "operation.type", "type") },
            operationStatus = operation?.let { optionalString(it, "operation.status", "status") },
            workloads = workloads(root),
        )
    }

    /**
     * `workloads`（任意）: あれば `raceday`・`batch` の両方が真偽値のオブジェクト（それ以外は不正 = UNKNOWN。部分的な指定で許可に倒さない）。
     */
    private fun workloads(root: JsonNode): WorkloadStatus {
        val node = optionalObject(root, "workloads") ?: return WorkloadStatus.Unspecified
        val raceday = node.get("raceday")
        val batch = node.get("batch")
        if (raceday == null || !raceday.isBoolean || batch == null || !batch.isBoolean) throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, "workloads")
        return WorkloadStatus.Specified(raceday.booleanValue(), batch.booleanValue())
    }

    private fun required(
        parent: JsonNode,
        name: String,
    ): JsonNode {
        val node = parent.get(name)
        if (node == null || node.isNull) throw Reject(OpsFailureReason.MISSING_FIELD, name)
        return node
    }

    private fun optionalObject(
        parent: JsonNode,
        name: String,
    ): JsonNode? {
        val node = parent.get(name)
        if (node == null || node.isNull) return null
        if (!node.isObject) throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, name)
        return node
    }

    private fun optionalString(
        parent: JsonNode,
        label: String,
        name: String,
    ): String? {
        val node = parent.get(name)
        if (node == null || node.isNull) return null
        if (!node.isString) throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, label)
        return node.stringValue()
    }

    private fun optionalInstant(
        parent: JsonNode,
        label: String,
        name: String,
    ) = optionalString(parent, label, name)?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (_: DateTimeParseException) {
            throw Reject(OpsFailureReason.INVALID_FIELD_TYPE, label)
        }
    }
}
