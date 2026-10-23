package jp.jrabot.domain.race

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant

/** 開催予定のレース単位の状態（RACE_CALENDAR_DESIGN §4 `race_schedule.status`）。遷移の規則は T3.3 で実装する。 */
enum class RaceScheduleStatus {
    ANNOUNCED,
    CARD,
    MONITORING,
    OFF,
    RESULT_PENDING,
    RESULT_PARTIAL,
    SETTLED,
    FINALIZED,
    CANCELLED,
    DELETED,
}

/**
 * レースの開催予定（`race_schedule`）。開催判定は DB の有効予定だけで行う（曜日・祝日は使わない。D-024）。
 *
 * [scheduledPostAt] は UTC の時刻。不明のとき null（[postTimeUnknown] が true のときは必ず null）。
 * [version] は楽観ロック（新規は 0）。`race_day`・`calendar_override`・`race_schedule_event` と有効予定の導出は T3.3。
 */
data class RaceSchedule(
    override val tenantId: TenantId,
    val raceId: RaceId,
    val scheduledPostAt: Instant?,
    val postTimeUnknown: Boolean,
    val status: RaceScheduleStatus,
    /** 直近に反映した RA のデータ区分（1 文字）。 */
    val dataKubun: Char,
    /** データ作成年月日 + データ区分（新旧比較キー。比較規則は T3.3）。 */
    val sourceVersion: String,
    val raceName: String?,
    val lastChangedAt: Instant,
    val version: Int = 0,
) : TenantScoped {
    init {
        require(!postTimeUnknown || scheduledPostAt == null) { "発走時刻が不明なら scheduledPostAt は null" }
        require(sourceVersion.isNotEmpty() && sourceVersion.length <= 32) { "sourceVersion は 1〜32 文字" }
        require(version >= 0) { "version は 0 以上: $version" }
        require(raceName == null || raceName.length <= 100) { "レース名は 100 文字以下" }
    }
}
