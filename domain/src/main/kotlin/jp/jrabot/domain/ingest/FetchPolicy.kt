package jp.jrabot.domain.ingest

import java.time.Instant
import java.time.LocalDate

/** 1 バッチの集計（取得セッションの進捗に足す）。 */
class BatchStats(
    val recordCount: Int,
    val unparsed: Int,
    val failedParse: Int,
    val maxRaceDate: LocalDate?,
)

/**
 * 取得セッションの進行と**完了判定**（D-134）。取得の事実（「予定なしを確認した」の根拠）を作れるのは、次をすべて満たしたときだけ:
 * 1. バッチが連番（0 始まり）で**欠けなく**届いた（[FetchRecord.nextSeq] が連続）
 * 2. EOF のバッチを受けた（途中のバッチで成功にしない）
 * 3. YSCH は対象範囲（targetFrom〜targetTo）の申告があり、**全レコードが解析・適用できた**（保留・不正が 1 件でもあれば失敗）
 * 4. 事実が及ぶ範囲は `[targetFrom, min(targetTo, この取得で見た最後の開催日)]`。それより先は「まだ公表されていない」と区別できないので、
 *    予定なしを確認したことにしない（範囲が空なら成功でも事実にならない）
 */
object FetchPolicy {
    const val FACT_DATASPEC = "YSCH"

    fun advance(
        f: FetchRecord,
        env: IngestEnvelope,
        stats: BatchStats,
        now: Instant,
    ): FetchRecord {
        val fetch = requireNotNull(env.fetch) { "fetch の無い封筒は進められない" }
        require(f.status == FetchStatus.IN_PROGRESS) { "終了した取得セッションは進められない" }
        require(fetch.seq == f.nextSeq) { "連番が連続していない: ${fetch.seq} != ${f.nextSeq}" }
        val progressed =
            f.copy(
                nextSeq = f.nextSeq + 1,
                recordCount = f.recordCount + stats.recordCount,
                unparsedCount = f.unparsedCount + stats.unparsed,
                failedParseCount = f.failedParseCount + stats.failedParse,
                maxRaceDate = listOfNotNull(f.maxRaceDate, stats.maxRaceDate).maxOrNull(),
                lastFileTimestamp = env.lastFileTimestamp ?: f.lastFileTimestamp,
                lastBatchAt = now,
            )
        if (!env.eof) return progressed
        return finish(progressed, now)
    }

    private fun finish(
        f: FetchRecord,
        now: Instant,
    ): FetchRecord {
        if (f.dataspec == FACT_DATASPEC) {
            if (f.failedParseCount > 0) return fail(f, "PARSE_FAILURES", now)
            if (f.unparsedCount > 0) return fail(f, "UNPARSED_RECORDS", now)
            val from = f.targetFrom
            val to = f.targetTo
            val last = f.maxRaceDate
            val coveredTo = if (to != null && last != null) minOf(to, last) else null
            val covered = from != null && coveredTo != null && !coveredTo.isBefore(from)
            return f.copy(
                status = FetchStatus.SUCCEEDED,
                coveredFrom = if (covered) from else null,
                coveredTo = if (covered) coveredTo else null,
                completedAt = now,
            )
        }
        return f.copy(status = FetchStatus.SUCCEEDED, completedAt = now)
    }

    private fun fail(
        f: FetchRecord,
        reason: String,
        now: Instant,
    ) = f.copy(status = FetchStatus.FAILED, failReason = reason, completedAt = now)
}
