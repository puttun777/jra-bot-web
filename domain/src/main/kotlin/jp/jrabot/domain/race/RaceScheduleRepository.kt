package jp.jrabot.domain.race

import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.repository.UpdateResult
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
 * 開催予定の更新（calendar-check・ingest・管理操作）。変更は必ず `race_schedule_event` の追記と同じトランザクションで行う
 * （RACE_CALENDAR_DESIGN §4。イベントの型とその追記は T3.3 でこのポートを拡張する）。
 */
interface RaceScheduleWriter {
    /** 自然キー `(tenantId, raceId)` が無いときだけ挿入する。冪等（既にあれば変更しない）。 */
    fun insertIfAbsent(schedule: RaceSchedule): InsertOutcome

    /** 楽観ロック更新。`schedule.version` が保存値と一致するときだけ成功する（[UpdateResult] を参照）。 */
    fun update(schedule: RaceSchedule): UpdateResult<RaceSchedule>
}

interface RaceScheduleRepository :
    RaceScheduleReader,
    RaceScheduleWriter
