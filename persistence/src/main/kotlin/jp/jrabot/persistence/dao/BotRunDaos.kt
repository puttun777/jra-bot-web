package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDateTime

/** `race_evaluations`（判定の記録。T3.8 の bot-run が書く）。`id` は AUTO_INCREMENT なので挿入しない（`entity.id` は無視される）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_evaluations")
internal data class RaceEvaluationEntity(
    @Id val id: Long,
    val tenantId: Long,
    val raceId: String,
    val snapshotAt: LocalDateTime,
    val modelTypeUsed: String?,
    val modelId: String?,
    val fallbackReason: String?,
    val betTypes: String?,
    val priorityMode: String?,
    val evThreshold: Double?,
    val winOddsMax: Double?,
    val winProbMin: Double?,
    val winRatioMin: Double?,
    val bestEv: Double?,
    val rejectionSummaryJson: String?,
    val signalSent: Boolean,
    val createdAt: LocalDateTime,
)

@Dao
internal interface RaceEvaluationDao {
    /**
     * 新方式の行（`legacy_id IS NULL`）は `(tenant_id, race_id, snapshot_at)` が UNIQUE（V5 の生成列 `uq_native`）。`ON DUPLICATE KEY UPDATE tenant_id = tenant_id` で
     * 既存なら何も変えない（`useAffectedRows=true` で 件数 1 = 挿入、0 = 既存）。旧データ（`legacy_id` あり）の重複には触れない（D-066）。
     */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: RaceEvaluationEntity): Result<RaceEvaluationEntity>

    /** 新方式の行の ID（自然キー）。 */
    @Select
    fun selectNativeId(
        tenantId: Long,
        raceId: String,
        snapshotAt: LocalDateTime,
    ): Long?

    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<RaceEvaluationEntity>
}

/** `race_timing_notice`（V5。通知タイミングの確定と通知内容のスナップショット。追記専用）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_timing_notice")
internal data class RaceTimingNoticeEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val timingCenti: Long,
    val runId: String,
    val evaluationId: Long,
    val oddsObservedAt: LocalDateTime,
    val noticeSchema: Int,
    val noticeJson: String,
    val noticeSha256: String,
    val firedAt: LocalDateTime,
)

@Dao
internal interface RaceTimingNoticeDao {
    /**
     * 通常の INSERT。同じ PK は重複キー（1062）で拒否される（呼び出し側が確定済みとして扱う）。`ON DUPLICATE KEY UPDATE` は使わない:
     * それは UPDATE 権限を要求し、追記専用の表に bot へ UPDATE を付けることになるため。
     */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: RaceTimingNoticeEntity): Result<RaceTimingNoticeEntity>

    @Select
    fun selectByRace(
        tenantId: Long,
        raceId: String,
    ): List<RaceTimingNoticeEntity>

    @Select
    fun selectOne(
        tenantId: Long,
        raceId: String,
        timingCenti: Long,
    ): RaceTimingNoticeEntity?
}

/** 出走表の馬名（`race_horses.bamei`）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
internal data class RaceHorseNameRow(
    val umaban: Int,
    val bamei: String?,
)

@Dao
internal interface RaceHorseNameDao {
    @Select
    fun selectNames(
        tenantId: Long,
        raceId: String,
    ): List<RaceHorseNameRow>
}
