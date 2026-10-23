package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentHeartbeat
import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.domain.agent.MirrorOutcome
import jp.jrabot.domain.calendar.EventJson
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/** 検証済みのハートビート（CONTRACTS §3.5）。本文・トークン・IP は持たない。JSON 列は平らな文字列/整数/真偽の表だけ。 */
class HeartbeatReport(
    val agentVersion: String,
    val clockSkewMs: Int?,
    val queueDepth: Long?,
    val oldestQueuedAt: Instant?,
    val ackedTsJson: String?,
    val lastJvResultJson: String?,
    val diskFreeMb: Long?,
    val lastError: String?,
    val localMode: String?,
)

sealed interface HeartbeatResult {
    data class Valid(
        val report: HeartbeatReport,
    ) : HeartbeatResult

    data class Invalid(
        val rejection: IngestRejection,
    ) : HeartbeatResult
}

object HeartbeatValidator {
    private val KEYS = setOf("agentVersion", "clockSkewMs", "queueDepth", "oldestQueuedAt", "ackedTs", "lastJvResult", "diskFreeMb", "lastError", "mode")
    private val MODES = setOf("NORMAL", "STARTING", "STOPPING", "STOPPED", "UNKNOWN")
    private val MAP_KEY = Regex("[A-Za-z0-9_.:-]{1,32}")
    private const val MAX_ENTRIES = 16
    private const val MAX_TEXT = 64

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun validate(tree: Any?): HeartbeatResult {
        fun bad(
            status: Int,
            code: String,
        ) = HeartbeatResult.Invalid(IngestRejection(status, code))

        @Suppress("UNCHECKED_CAST")
        val o = tree as? Map<String, Any?> ?: return bad(400, "INVALID_TYPE")
        if (o.keys.any { it !in KEYS }) return bad(422, "UNKNOWN_FIELD")
        val version = o["agentVersion"] as? String ?: return bad(400, "MISSING_FIELD")
        if (!Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,31}").matches(version)) return bad(422, "INVALID_AGENT_VERSION")

        fun long(key: String): Long? = (o[key] as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
        if (o["clockSkewMs"] != null && long("clockSkewMs") == null) return bad(422, "INVALID_NUMBER")
        if (o["queueDepth"] != null && (long("queueDepth") ?: -1) < 0) return bad(422, "INVALID_NUMBER")
        if (o["diskFreeMb"] != null && (long("diskFreeMb") ?: -1) < 0) return bad(422, "INVALID_NUMBER")
        val skew = long("clockSkewMs")
        if (skew != null && skew !in Int.MIN_VALUE..Int.MAX_VALUE) return bad(422, "INVALID_NUMBER")
        val oldest =
            (o["oldestQueuedAt"] as? String)?.let {
                try {
                    Instant.parse(it)
                } catch (_: DateTimeParseException) {
                    return bad(422, "INVALID_TIMESTAMP")
                }
            }
        if (o["oldestQueuedAt"] != null && o["oldestQueuedAt"] !is String) return bad(400, "INVALID_TYPE")
        val acked = flatJson(o["ackedTs"], stringsOnly = true) ?: if (o["ackedTs"] == null) null else return bad(422, "INVALID_ACKED_TS")
        val jv = flatJson(o["lastJvResult"], stringsOnly = false) ?: if (o["lastJvResult"] == null) null else return bad(422, "INVALID_LAST_JV_RESULT")
        val mode =
            when (val m = o["mode"]) {
                null -> null
                is String -> m
                is Map<*, *> -> m["mode"] as? String
                else -> return bad(400, "INVALID_TYPE")
            }
        if (mode != null && mode !in MODES) return bad(422, "INVALID_MODE")
        val error = (o["lastError"] as? String)?.filter { it >= ' ' }?.take(500) // 制御文字を除き、長さを切る
        if (o["lastError"] != null && o["lastError"] !is String) return bad(400, "INVALID_TYPE")
        return HeartbeatResult.Valid(HeartbeatReport(version, skew?.toInt(), long("queueDepth"), oldest, acked, jv, long("diskFreeMb"), error, mode))
    }

    /** 平らな表（キーは安全な名前、値は文字列・整数・真偽・null）を、正規形の JSON 文字列にする。条件に合わなければ null。 */
    private fun flatJson(
        node: Any?,
        stringsOnly: Boolean,
    ): String? {
        val m = node as? Map<*, *> ?: return null
        if (m.size > MAX_ENTRIES) return null
        val entries =
            m.entries.map { (k, v) ->
                if (k !is String || !MAP_KEY.matches(k)) return null
                val value: Any? =
                    when {
                        v == null -> if (stringsOnly) return null else null
                        v is String -> v.takeIf { it.length <= MAX_TEXT } ?: return null
                        stringsOnly -> return null
                        v is Boolean -> v
                        v is Number && v.toDouble() == v.toLong().toDouble() -> v.toLong()
                        else -> return null
                    }
                k to value
            }
        return EventJson.obj(*entries.toTypedArray())
    }
}

interface AgentHeartbeatStore {
    /** 1 エージェント 1 行の upsert（最終接続の状態。履歴は持たない）。 */
    fun upsert(
        tenantId: TenantId,
        agentId: AgentId,
        report: HeartbeatReport,
        now: Instant,
    )
}

/** ハートビートの記録。DB が正本で、外部ストアへの写し（DB 停止中の status 用）は失敗しても応答に影響させない。 */
class HeartbeatService(
    private val store: AgentHeartbeatStore,
    private val mirror: HeartbeatMirror,
    private val clock: Clock,
) {
    fun record(
        caller: IngestCaller,
        report: HeartbeatReport,
    ): MirrorOutcome {
        val now = clock.now()
        store.upsert(caller.tenantId, caller.agentId, report, now)
        return try {
            mirror.record(caller.tenantId, AgentHeartbeat(caller.agentId, now, report.agentVersion))
        } catch (_: RuntimeException) {
            MirrorOutcome.FAILED
        }
    }
}
