package jp.jrabot.domain.race

import jp.jrabot.domain.calendar.ChangeResult
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate

/** 開催予定の読取り。すべての操作が [TenantId] を必須とする。 */
interface RaceScheduleReader {
    fun find(
        tenantId: TenantId,
        raceId: RaceId,
    ): RaceSchedule?

    /** 開催日（JST の暦日）の予定を、発走時刻（不明は最後）・レース ID の昇順で返す。無ければ空。 */
    fun listByDate(
        tenantId: TenantId,
        date: LocalDate,
    ): List<RaceSchedule>
}

/**
 * 開催予定の更新（calendar-check・ingest・管理操作）。**予定の変更は必ず [ScheduleEvent] の追記と同じトランザクションで行う**
 * （RACE_CALENDAR_DESIGN §4）。履歴なしで予定だけを書き換える操作は提供しない。
 * 版の新旧・状態遷移の検証は呼び出し側（`RaceScheduleUpdater`）が行い、ここは永続化の原子性・冪等性・楽観ロックだけを担う。
 */
interface RaceScheduleWriter {
    /**
     * 自然キー `(tenantId, raceId)` が無いときだけ挿入し、[event] を追記する。既にあれば [ChangeResult.AlreadyExists]（変更なし）、
     * 同じ履歴が既にあれば [ChangeResult.Duplicate]（挿入も取り消す）。[event] はこの予定のもの（テナント・レースが一致）でなければならない。
     */
    fun insertIfAbsent(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule>

    /** 楽観ロック更新。`schedule.version` が保存値と一致するときだけ成功し、[event] を同じトランザクションで追記する（[ChangeResult] を参照）。 */
    fun update(
        schedule: RaceSchedule,
        event: ScheduleEvent,
    ): ChangeResult<RaceSchedule>
}

interface RaceScheduleRepository :
    RaceScheduleReader,
    RaceScheduleWriter
