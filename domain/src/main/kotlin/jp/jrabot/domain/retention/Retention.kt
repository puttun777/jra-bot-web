package jp.jrabot.domain.retention

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 保持期間を過ぎたデータの削除対象（DATA_MIGRATION_DESIGN §9.2。D-043）。**削除してよいのは再生成・再取得できる、または役目を終えた記録だけ**:
 * 成績の元になる表（races・results・payouts・signals・evaluations・odds_snapshots〔単勝〕）は対象外（無期限。単勝オッズの間引きは別の判断）。
 */
enum class RetentionTarget(
    val table: String,
    val description: String,
) {
    /** 組オッズ（ワイド・三連複）。既定では保存しない設計で、保存しても 14 日で足りる。 */
    COMBO_ODDS("combo_odds_snapshots", "組オッズの履歴"),

    /** 取込の生レコード。**解析待ち（PENDING）は残す**（保留の再適用に必要）。 */
    INGEST_RAW("ingest_raw", "取込の生レコード（解析済み・失敗）"),

    /** 開催・レース予定の変更履歴。 */
    SCHEDULE_EVENTS("race_schedule_event", "開催予定の変更履歴"),

    /** bot-run の実行記録。**実行中（枠を持つ）ものは残す**。 */
    BOT_RUNS("bot_run", "bot-run の実行記録（終了したもの）"),
}

/** 保持日数。既定は DATA_MIGRATION_DESIGN §9.2 の案（組オッズ 14・生レコード 30・履歴 365・実行記録 365）。 */
data class RetentionPolicy(
    val days: Map<RetentionTarget, Long> =
        mapOf(
            RetentionTarget.COMBO_ODDS to 14L,
            RetentionTarget.INGEST_RAW to 30L,
            RetentionTarget.SCHEDULE_EVENTS to 365L,
            RetentionTarget.BOT_RUNS to 365L,
        ),
) {
    init {
        require(days.keys == RetentionTarget.entries.toSet()) { "すべての対象に保持日数が必要" }
        require(days.values.all { it in MIN_DAYS..MAX_DAYS }) { "保持日数は $MIN_DAYS〜$MAX_DAYS 日" }
    }

    fun cutoff(
        target: RetentionTarget,
        now: Instant,
    ): Instant = now.minus(Duration.ofDays(days.getValue(target)))

    companion object {
        const val MIN_DAYS = 7L
        const val MAX_DAYS = 3650L
    }
}

interface RetentionStore {
    /** [before] より古い削除対象の行数（テナント単位）。 */
    fun count(
        tenantId: TenantId,
        target: RetentionTarget,
        before: Instant,
    ): Long

    /** [before] より古い削除対象を最大 [limit] 行削除して、削除した行数を返す（テナント単位）。 */
    fun delete(
        tenantId: TenantId,
        target: RetentionTarget,
        before: Instant,
        limit: Int,
    ): Int
}

data class RetentionLine(
    val target: RetentionTarget,
    val cutoff: Instant,
    /** 実行前に数えた削除対象の行数。 */
    val eligible: Long,
    /** 実際に削除した行数（ドライランは 0）。 */
    val deleted: Long,
    /** 1 回の上限に達して残りがある。 */
    val truncated: Boolean,
)

data class RetentionReport(
    val tenantId: TenantId,
    val dryRun: Boolean,
    val lines: List<RetentionLine>,
)

/**
 * `--task=retention`。**既定はドライラン**（対象の件数を数えるだけ）。削除するのは [execute] が明示されたときだけ（実データの削除は本人の承認事項: CLAUDE.md）。
 * 削除は [CHUNK] 行ずつ・1 対象あたり最大 [MAX_PER_TARGET] 行で、[shouldStop] が真になったら（SIGTERM・運用停止）区切りで止める。冪等（何度実行しても同じ行は 2 度消えない）。
 */
class RetentionService(
    private val clock: Clock,
    private val store: RetentionStore,
    private val policy: RetentionPolicy,
    private val shouldStop: () -> Boolean = { false },
) {
    fun run(
        tenantId: TenantId,
        execute: Boolean,
    ): RetentionReport {
        val now = clock.now()
        val lines =
            RetentionTarget.entries.map { target ->
                val cutoff = policy.cutoff(target, now)
                val eligible = store.count(tenantId, target, cutoff)
                var deleted = 0L
                var truncated = false
                if (execute) {
                    while (deleted < eligible && !shouldStop()) {
                        if (deleted >= MAX_PER_TARGET) {
                            truncated = true
                            break
                        }
                        val n = store.delete(tenantId, target, cutoff, CHUNK)
                        if (n <= 0) break
                        deleted += n
                    }
                }
                RetentionLine(target, cutoff, eligible, deleted, truncated || (execute && deleted < eligible && shouldStop()))
            }
        return RetentionReport(tenantId, !execute, lines)
    }

    companion object {
        const val CHUNK = 5_000
        const val MAX_PER_TARGET = 500_000L
    }
}
