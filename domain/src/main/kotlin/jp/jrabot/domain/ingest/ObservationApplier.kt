package jp.jrabot.domain.ingest

import jp.jrabot.domain.calendar.ApplyResult
import jp.jrabot.domain.calendar.RaceDataKubun
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.calendar.RaceDayStatus
import jp.jrabot.domain.calendar.RaceDayUpdater
import jp.jrabot.domain.calendar.RaceScheduleObservation
import jp.jrabot.domain.calendar.RaceScheduleUpdater
import jp.jrabot.domain.calendar.ScheduleEventSource
import jp.jrabot.domain.odds.ComboKeys
import jp.jrabot.domain.odds.ComboOddsRow
import jp.jrabot.domain.odds.ComboOddsSnapshot
import jp.jrabot.domain.odds.ComboOddsSnapshotRepository
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.odds.OddsSnapshot
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.Race
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalTime

/**
 * 取込境界でのオッズの扱い（**承認待ちの仕様 P-3。D-136**）。JRA のオッズは 1.0 倍以上のはずだが、公式仕様で下限を確認できていない（T1.17）。
 * 0 < オッズ < 1 は元本割れで不自然なので、推奨は隔離（[QUARANTINE_BELOW_PAR]）。ただし**計算側（`Odds` 型・旧 Python との数値互換）は変えない**:
 * `Odds` は 0 より大きい有限値を受け入れたままで、ここは取込の入口の判断だけ。[ACCEPT_ALL] は旧 Python と同じく何も弾かない。
 */
enum class OddsBoundaryPolicy { QUARANTINE_BELOW_PAR, ACCEPT_ALL }

data class ApplyPolicy(
    val oddsBelowPar: OddsBoundaryPolicy = OddsBoundaryPolicy.QUARANTINE_BELOW_PAR,
    /** ワイド・三連複の保存（D-043: 既定では保存しない。有効化は運用判断）。 */
    val storeComboOdds: Boolean = false,
)

class ApplyContext(
    val observedAt: Instant,
    val batchId: String,
    val dataspec: String,
)

sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome

    data object NoChange : ApplyOutcome

    /** 規則により反映しなかった（古い版・遷移不可など）。入力の誤りではない。 */
    data class Rejected(
        val reason: String,
    ) : ApplyOutcome

    /** 取込境界の検査で不正と判断した。生レコードは隔離（FAILED）し、保存しない。 */
    data class Quarantined(
        val reason: String,
    ) : ApplyOutcome

    /** 依存するデータがまだ無い（例: レース未登録）。生レコードは保留（PENDING）にして後で再適用できる。 */
    data class Deferred(
        val reason: String,
    ) : ApplyOutcome
}

/** 楽観ロックの競合が尽きた等、再試行すれば成功し得る失敗。バッチ全体を巻き戻し、エージェントのリトライ（5xx）に任せる。 */
class IngestRetryableException(
    message: String,
) : RuntimeException(message)

/** 観測を既存の更新部品（T3.1〜T3.3）へ渡す。テナントは引数で明示し、観測は持たない。 */
class ObservationApplier(
    private val days: RaceDayUpdater,
    private val dayReader: RaceDayReader,
    private val schedules: RaceScheduleUpdater,
    private val races: RaceRepository,
    private val odds: OddsSnapshotRepository,
    private val combos: ComboOddsSnapshotRepository,
    private val policy: ApplyPolicy = ApplyPolicy(),
) {
    fun apply(
        tenantId: TenantId,
        obs: Observation,
        ctx: ApplyContext,
    ): ApplyOutcome =
        when (obs) {
            is ScheduleDayObservation -> applyDay(tenantId, obs, ctx)
            is RaceObservation -> applyRace(tenantId, obs, ctx)
            is TimeChangeObservation -> applyTimeChange(tenantId, obs, ctx)
            is WinOddsObservation -> applyWin(tenantId, obs, ctx)
            is WideOddsObservation -> applyWide(tenantId, obs, ctx)
            is SanrenpukuOddsObservation -> applySanrenpuku(tenantId, obs, ctx)
        }

    private fun applyDay(
        tenantId: TenantId,
        o: ScheduleDayObservation,
        ctx: ApplyContext,
    ): ApplyOutcome {
        val existing = dayReader.find(tenantId, o.date, o.venue)
        // YSCH が「予定どおり（PLANNED）」と再び示した日が、RA などで既に先の状態（CONFIRMED など）に進んでいても、状態は戻さず、
        // 取得で確認できた時刻だけを進める（さもないと確認済みの日は YSCH で再確認されず古くなる）
        if (o.status == RaceDayStatus.PLANNED && existing != null && existing.status in BEYOND_PLANNED) {
            days.confirm(tenantId, o.date, o.venue, ctx.observedAt)
            return ApplyOutcome.NoChange
        }
        return try {
            map(
                days.apply(
                    tenantId,
                    o.date,
                    o.venue,
                    o.kai,
                    o.nichime,
                    o.status,
                    o.replacedByDate,
                    ctx.observedAt,
                    ScheduleEventSource.YSCH,
                    "${o.status.name}:${o.replacedByDate ?: "-"}",
                ),
            )
        } catch (_: IllegalArgumentException) {
            ApplyOutcome.Rejected("KAI_NICHIME_CHANGED") // 開催回・日目は変えられない（別の開催日の行）
        }
    }

    private fun applyRace(
        tenantId: TenantId,
        o: RaceObservation,
        ctx: ApplyContext,
    ): ApplyOutcome {
        val result =
            schedules.apply(
                RaceScheduleObservation(
                    tenantId,
                    o.raceId,
                    o.kubun,
                    o.createdOn,
                    o.postTime,
                    o.raceName,
                    ctx.observedAt,
                    ScheduleEventSource.RACE,
                    ctx.batchId,
                ),
            )
        val usable = result is ApplyResult.Applied || (result is ApplyResult.NoChange && o.kubun != RaceDataKubun.DELETED)
        if (usable && o.kubun != RaceDataKubun.DELETED && o.kubun != RaceDataKubun.CANCELLED && races.find(tenantId, o.raceId) == null) {
            // オッズ等が参照する races の最小行（コース条件は T3.6 以降）。古い版では作らない（上の usable）
            val post = o.postTime?.takeIf { it != LocalTime.MIDNIGHT && o.kubun == RaceDataKubun.CARD }
            races.upsert(Race(tenantId, o.raceId, o.kai, o.nichime, post, o.raceName?.take(100), null))
        }
        // RA が届いた（出走馬名表・出馬表）= その場の開催が確定した。開催日（race_day）も CONFIRMED にし、確認の時刻を進める
        // （開催日の行が無い・古いと、有効な予定の導出で STALE になる）。中止・削除では動かさない。回・日目が無い RA は開催日を作れないので触れない
        if (usable && (o.kubun == RaceDataKubun.ANNOUNCEMENT || o.kubun == RaceDataKubun.CARD) && o.kai != null && o.nichime != null) {
            try {
                days.apply(
                    tenantId,
                    o.raceId.date,
                    o.raceId.venue,
                    o.kai,
                    o.nichime,
                    RaceDayStatus.CONFIRMED,
                    null,
                    ctx.observedAt,
                    ScheduleEventSource.RACE,
                    "CONFIRMED:-",
                )
            } catch (_: IllegalArgumentException) {
                // 開催回・日目の食い違いは開催日を更新しない（予定自体の反映は済んでいる）
            }
        }
        return map(result)
    }

    private fun applyTimeChange(
        tenantId: TenantId,
        o: TimeChangeObservation,
        ctx: ApplyContext,
    ): ApplyOutcome =
        map(
            if (o.revoked) {
                schedules.revokeTimeChange(tenantId, o.raceId, o.postAt, ctx.observedAt, o.snapshotKey, ctx.batchId)
            } else {
                schedules.applyTimeChange(tenantId, o.raceId, o.postAt, ctx.observedAt, o.snapshotKey, ctx.batchId)
            },
        )

    private fun applyWin(
        tenantId: TenantId,
        o: WinOddsObservation,
        ctx: ApplyContext,
    ): ApplyOutcome {
        if (belowPar(o.entries.map { it.odds.value })) return ApplyOutcome.Quarantined("ODDS_BELOW_PAR")
        if (races.find(tenantId, o.raceId) == null) return ApplyOutcome.Deferred("RACE_NOT_FOUND")
        val snapshot =
            try {
                OddsSnapshot(tenantId, o.raceId, Market.WIN, ctx.observedAt, o.entries)
            } catch (_: IllegalArgumentException) {
                return ApplyOutcome.Quarantined("INVALID_SNAPSHOT")
            }
        return upserted(odds.upsert(snapshot))
    }

    private fun applyWide(
        tenantId: TenantId,
        o: WideOddsObservation,
        ctx: ApplyContext,
    ): ApplyOutcome {
        val rows = linkedMapOf<String, ComboOddsRow>()
        for (e in o.entries) {
            val key = ComboKeys.wide(e.a, e.b) ?: return ApplyOutcome.Quarantined("INVALID_COMBO_KEY")
            if (belowPar(listOfNotNull(e.low, e.high))) return ApplyOutcome.Quarantined("ODDS_BELOW_PAR")
            val low = Odds.ofOrNull(e.low) ?: return ApplyOutcome.Quarantined("INVALID_ODDS")
            val high = e.high?.let { Odds.ofOrNull(it) ?: return ApplyOutcome.Quarantined("INVALID_ODDS") }
            if (high != null && high < low) return ApplyOutcome.Quarantined("INVALID_ODDS_RANGE")
            val row = ComboOddsRow(key, low, high, e.popularity)
            if (rows.putIfAbsent(key, row)?.let { it != row } == true) return ApplyOutcome.Quarantined("DUPLICATE_COMBO_KEY")
        }
        return storeCombos(tenantId, o.raceId, Market.WIDE, rows.values.toList(), ctx)
    }

    private fun applySanrenpuku(
        tenantId: TenantId,
        o: SanrenpukuOddsObservation,
        ctx: ApplyContext,
    ): ApplyOutcome {
        val rows = linkedMapOf<String, ComboOddsRow>()
        for (e in o.entries) {
            val key = ComboKeys.sanrenpuku(e.a, e.b, e.c) ?: return ApplyOutcome.Quarantined("INVALID_COMBO_KEY")
            if (belowPar(listOf(e.odds))) return ApplyOutcome.Quarantined("ODDS_BELOW_PAR")
            val odds = Odds.ofOrNull(e.odds) ?: return ApplyOutcome.Quarantined("INVALID_ODDS")
            val row = ComboOddsRow(key, odds, null, e.popularity)
            if (rows.putIfAbsent(key, row)?.let { it != row } == true) return ApplyOutcome.Quarantined("DUPLICATE_COMBO_KEY")
        }
        return storeCombos(tenantId, o.raceId, Market.SANRENPUKU, rows.values.toList(), ctx)
    }

    private fun storeCombos(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
        rows: List<ComboOddsRow>,
        ctx: ApplyContext,
    ): ApplyOutcome {
        if (rows.isEmpty()) return ApplyOutcome.Quarantined("EMPTY_SNAPSHOT")
        if (!policy.storeComboOdds) return ApplyOutcome.Rejected("COMBO_ODDS_STORAGE_DISABLED")
        if (races.find(tenantId, raceId) == null) return ApplyOutcome.Deferred("RACE_NOT_FOUND")
        return upserted(combos.upsert(ComboOddsSnapshot(tenantId, raceId, market, ctx.observedAt, rows, ctx.dataspec)))
    }

    private fun belowPar(values: List<Double>): Boolean = policy.oddsBelowPar == OddsBoundaryPolicy.QUARANTINE_BELOW_PAR && values.any { it > 0.0 && it < 1.0 }

    private fun upserted(o: UpsertOutcome): ApplyOutcome = if (o == UpsertOutcome.UNCHANGED) ApplyOutcome.NoChange else ApplyOutcome.Applied

    private companion object {
        /** YSCH の PLANNED で状態を戻してはいけない日（RA などで先へ進んだ日）。 */
        val BEYOND_PLANNED =
            setOf(
                RaceDayStatus.UNCONFIRMED,
                RaceDayStatus.CONFIRMED,
                RaceDayStatus.RUNNING,
                RaceDayStatus.SETTLING,
                RaceDayStatus.COMPLETED,
                RaceDayStatus.INCOMPLETE,
            )
    }

    private fun <T> map(r: ApplyResult<T>): ApplyOutcome =
        when (r) {
            is ApplyResult.Applied -> ApplyOutcome.Applied
            is ApplyResult.NoChange -> ApplyOutcome.NoChange
            is ApplyResult.Rejected -> ApplyOutcome.Rejected(r.reason.name)
            ApplyResult.Conflict -> throw IngestRetryableException("楽観ロックの競合が続いた")
        }
}
