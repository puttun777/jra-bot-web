package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** bot の実行 ID（`bot_run.run_id CHAR(36)`）。小文字・ハイフン付きの UUID 文字列。生成方法（UUIDv7 など）は T3.3。 */
@JvmInline
value class RunId private constructor(
    val value: String,
) {
    override fun toString(): String = "RunId($value)"

    companion object {
        private val pattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun parseOrNull(text: String): RunId? = if (pattern.matches(text)) RunId(text) else null

        fun parse(text: String): RunId = requireNotNull(parseOrNull(text)) { "run_id の形式が不正: '$text'" }

        fun of(uuid: UUID): RunId = RunId(uuid.toString())
    }
}

enum class BotRunTrigger { SCHEDULER, MANUAL, RECONCILE, BACKFILL }

/**
 * 実行の状態（RACE_CALENDAR_DESIGN §6.3 の状態遷移図と、CONTRACTS §9.2 の `INTERRUPTED`）。
 * `INTERRUPTED` は DDL 案の ENUM には無い。T2.3 の `V2` で加える（PROGRESS に記録）。遷移の検証は T3.3。
 */
enum class BotRunState(
    /** 実行中として排他（`active_slot = 1`）を持つ状態。 */
    val holdsActiveSlot: Boolean,
) {
    STARTING(true),
    RUNNING(true),
    DRAINING(true),
    SUCCEEDED(false),
    FAILED(false),
    ABORTED(false),
    STOPPED_BY_MODE(false),
    INTERRUPTED(false),
}

/**
 * bot の実行 1 回（`bot_run`）。二重実行は DB の UNIQUE(active_slot) とリースで防ぐ（D-026）。
 * [checkpoint] はレース別のステージなどを持つ不透明な文字列（中身の型付けは T3.3）。[version] は楽観ロック（新規は 0）。
 */
data class BotRun(
    override val tenantId: TenantId,
    val runId: RunId,
    val runDate: LocalDate,
    val trigger: BotRunTrigger,
    val state: BotRunState,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val heartbeatAt: Instant?,
    val checkpoint: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val exitReason: String?,
    val version: Int = 0,
) : TenantScoped {
    init {
        require(version >= 0) { "version は 0 以上: $version" }
        require(finishedAt == null || !finishedAt.isBefore(startedAt)) { "finishedAt が startedAt より前" }
        require(!(state.holdsActiveSlot && finishedAt != null)) { "実行中の状態で finishedAt は設定できない" }
        require(exitReason == null || exitReason.length <= 200) { "exitReason は 200 文字以下" }
    }
}
