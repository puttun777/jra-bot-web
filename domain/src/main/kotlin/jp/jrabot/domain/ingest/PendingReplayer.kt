package jp.jrabot.domain.ingest

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId

/**
 * [PendingReplayer.replay] の結果（呼び出し側が記録・観測するための数。判定には使わない）。
 *
 * - [scanned]: 走査した保留の行数（キーセットで最後まで読んだ数）
 * - [settled]: PARSED / FAILED に確定した行
 * - [stillPending]: 再解析できない・依存するデータがまだ無いため PENDING のまま残した行
 * - [rowErrors]: 1 行の再適用が例外で失敗した行（PENDING のまま残る。次の再適用の契機で再試行される）
 * - [aborted]: 保留の一覧の取得が失敗して走査を途中でやめた（残りは次の契機で再試行される）
 */
data class ReplayReport(
    val scanned: Int,
    val settled: Int,
    val stillPending: Int,
    val rowErrors: Int,
    val aborted: Boolean,
) {
    /** 走査は完了したが、PENDING のまま残った行がある（次の契機まで再処理されない）。 */
    val leftover: Boolean get() = aborted || stillPending > 0 || rowErrors > 0

    companion object {
        val NONE = ReplayReport(0, 0, 0, 0, false)
    }
}

/**
 * レース待ちで保留にした生レコードの再適用（D-227）。
 *
 * **全件の走査と、失敗行の再処理の保証は別である**。
 * - 走査: 新しい順のキーセット（`received_at, batch_id, seq`。`(batch_id, seq)` は取込の構造上テナント内で一意）で最後まで読む。位置は必ず古い側へ進むので、
 *   再適用に失敗して PENDING のまま残る行があっても**同じ走査で同じ行を選び直さない**。走査の総数に上限を置かない（以前の 10,000 件上限は、超えた残件を放置した）。
 *   走査中に追加された行は新しい側（位置より前）に入るので今回の走査には現れないが、走査は有限で終わる（位置より古い行は有限で、減る一方）。
 * - 再処理: 失敗した行（例外・依存データ未着）は PENDING のまま残り、**次にそのレースが入った／確認できたバッチ**（または完了済みバッチの再送。[BatchIngestor]）で再試行される。
 *   それ以外に再試行を起動するものは無い（定期ジョブは無い）。レースの観測がもう来なければ、その行は PENDING のまま残る。
 *
 * 保留の取得・更新は常にこのテナントの行だけ。同じ観測の再適用は冪等（オッズは自然キーで upsert）。1 行ごとに別のトランザクション。
 */
class PendingReplayer(
    private val raw: RawRecordStore,
    private val parsers: RecordParsers,
    private val apply: (TenantId, Observation, ApplyContext) -> ApplyOutcome,
    private val uow: UnitOfWork,
    private val pageSize: Int = REPLAY_PAGE,
) {
    init {
        require(pageSize in 1..1000) { "pageSize は 1〜1000" }
    }

    fun replay(
        tenantId: TenantId,
        races: Set<RaceId>,
    ): ReplayReport {
        if (races.isEmpty()) return ReplayReport.NONE
        val reasons = races.sortedBy { it.value }.map(::raceNotFoundReason) + REASON_RACE_NOT_FOUND
        var scanned = 0
        var settled = 0
        var pending = 0
        var errors = 0
        var after: PendingCursor? = null
        while (true) {
            val page =
                try {
                    raw.listPending(tenantId, reasons, pageSize, after)
                } catch (_: RuntimeException) {
                    return ReplayReport(scanned, settled, pending, errors, aborted = true)
                }
            for (row in page) {
                scanned++
                try {
                    if (uow.run { replayOne(tenantId, row) }) settled++ else pending++
                } catch (_: RuntimeException) {
                    errors++ // PENDING のまま残す。次の契機で再試行される
                }
            }
            if (page.size < pageSize) return ReplayReport(scanned, settled, pending, errors, aborted = false)
            after = page.last().cursor()
        }
    }

    /** 確定（PARSED / FAILED）できたら true。再解析できない・依存するデータがまだ無いなら false（PENDING のまま）。 */
    private fun replayOne(
        tenantId: TenantId,
        row: PendingRawRecord,
    ): Boolean {
        val parsed =
            parsers.parse(
                ParseContext(row.dataspec, row.batchClass, row.observedAt),
                IngestRecordIn(row.recordId, row.payload, Hashing.sha256Hex(row.payload)),
            )
        if (parsed !is ParseOutcome.Parsed) return false
        val ctx = ApplyContext(row.observedAt, row.batchId, row.dataspec)
        var failed: String? = null
        var deferred = false
        for (obs in parsed.observations) {
            when (val r = apply(tenantId, obs, ctx)) {
                is ApplyOutcome.Quarantined -> {
                    failed = r.reason
                }

                is ApplyOutcome.Deferred -> {
                    deferred = true
                }

                else -> {}
            }
        }
        return when {
            failed != null -> {
                raw.settlePending(tenantId, row.recordHash, ParseStatus.FAILED, failed.take(200))
                true
            }

            deferred -> {
                false
            }

            else -> {
                raw.settlePending(tenantId, row.recordHash, ParseStatus.PARSED, null)
                true
            }
        }
    }

    companion object {
        /** 保留の再適用の 1 ページの件数（走査の総数には上限を置かない）。 */
        const val REPLAY_PAGE = 200
    }
}
