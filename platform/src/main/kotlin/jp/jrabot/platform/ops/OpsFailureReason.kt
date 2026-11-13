package jp.jrabot.platform.ops

/**
 * 運用モードを確定できなかった理由（ログと分岐用の固定語彙）。値は [jp.jrabot.domain.ops.OpsMode.UNKNOWN] の原因を表すだけで、
 * ops-state.json の内容（JSON 全文・文字列の値）は含めない。
 */
enum class OpsFailureReason {
    SOURCE_NOT_FOUND,
    SOURCE_PERMISSION_DENIED,
    SOURCE_TIMEOUT,
    SOURCE_UNAVAILABLE,
    SOURCE_TOO_LARGE,
    INVALID_JSON,
    SCHEMA_VERSION_MISMATCH,
    MISSING_FIELD,
    INVALID_FIELD_TYPE,
    INVALID_MODE,
    INVALID_DESIRED,
    SEQ_ROLLBACK,
    SEQ_CONFLICT,
}
