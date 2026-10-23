package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.OpsMode
import java.time.Instant

/**
 * `ops-state.json`（schema_version 1）のうち、アプリが使うフィールドだけ（CONTRACTS §5.2）。それ以外は無視する（前方互換）。
 *
 * [mode] は正本の 4 値（NORMAL / STARTING / STOPPING / STOPPED）、[desiredMode] は NORMAL / STOPPED のどちらか。
 * UNKNOWN はこの型には現れない（検証に失敗したときは [OpsState] を作らない）。
 */
internal data class OpsState(
    val seq: Long,
    val mode: OpsMode,
    val desiredMode: OpsMode,
    val desiredSetAt: Instant?,
    val operationType: String?,
    val operationStatus: String?,
)
