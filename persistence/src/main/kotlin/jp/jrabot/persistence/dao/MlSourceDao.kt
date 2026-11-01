package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// 学習用データセット・特徴量の入力の**読取り**（T3.6-B）。どの SQL も `tenant_id` と時刻の上限（asOf・レース日）を SQL 自体に持つ:
// 学習の時点でまだ判明していないデータ（asOf より後のオッズ・馬マスタの版、レース当日以降の過去走）を DB から取り出さない。
// 書込みは馬マスタ履歴の追記（[HorseMasterHistoryDao]）だけ。

/** レース条件（`races` の特徴量に使う列だけ）。`post_time` は JST の現地時刻、`post_time_unknown` が 1 なら不明。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "races")
internal data class RaceConditionRow(
    @Id val tenantId: Long,
    @Id val raceId: String,
    val raceDate: LocalDate,
    val postTime: LocalTime?,
    val postTimeUnknown: Boolean,
    val distanceM: Int?,
    val trackTypeCd: String?,
    val courseKubunCd: String?,
    val tenkoCd: String?,
    val shibaBabaCd: String?,
    val dirtBabaCd: String?,
    val updatedAt: LocalDateTime,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "race_horses")
internal data class EntryRow(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val umaban: Int,
    val kettoNum: String?,
)

/** 馬マスタの現在の行（JSON 列は文字列で受け取り、domain の型への変換は [jp.jrabot.persistence.repository.MlJsonParsing] が行う）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "horses")
internal data class HorseRow(
    @Id val tenantId: Long,
    @Id val kettoNum: String,
    val birthDate: LocalDate?,
    val pedigreeJson: String?,
    val styleTendencyJson: String?,
    val chakuBySurfaceJson: String?,
    val chakuByConditionJson: String?,
    val chakuByDistanceJson: String?,
    val updatedAt: LocalDateTime,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "horse_master_history")
internal data class HorseHistoryRow(
    @Id val tenantId: Long,
    @Id val kettoNum: String,
    @Id val knownAt: LocalDateTime,
    val source: String,
    val styleTendencyJson: String?,
    val chakuBySurfaceJson: String?,
    val chakuByConditionJson: String?,
    val chakuByDistanceJson: String?,
    val raceCount: Long?,
    val createdAt: LocalDateTime,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "horse_past_runs")
internal data class PastRunRow(
    @Id val tenantId: Long,
    @Id val kettoNum: String,
    @Id val raceId: String,
    val runDate: LocalDate?,
    val finish: Int?,
)

/** `odds_snapshots` の 1 点（win）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "odds_snapshots")
internal data class OddsPointRow(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val market: String,
    @Id val observedAt: LocalDateTime,
    @Id val umaban: Int,
    val odds: Double,
    val popularity: Int?,
)

/** 期間内のレースの出走馬（race_id・馬番。結果は別の問い合わせ）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
internal data class RaceEntryKeyRow(
    val raceId: String,
    val umaban: Int,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "results")
internal data class ResultRow(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val umaban: Int,
    val finish: Int?,
)

@Dao
internal interface MlSourceDao {
    @Select
    fun selectRace(
        tenantId: Long,
        raceId: String,
    ): RaceConditionRow?

    @Select
    fun selectEntry(
        tenantId: Long,
        raceId: String,
        umaban: Int,
    ): EntryRow?

    @Select
    fun selectHorse(
        tenantId: Long,
        kettoNum: String,
    ): HorseRow?

    /** `known_at <= asOf` の最新の 1 版（無ければ null）。asOf より後の版は SQL の条件で除く。 */
    @Select
    fun selectHistoryAsOf(
        tenantId: Long,
        kettoNum: String,
        asOf: LocalDateTime,
    ): HorseHistoryRow?

    /** 着順が 1 以上の過去走を日付の降順 → race_id の降順で [limit] 件。[before] が null でなければ `run_date < before`（その日を含まない。日付なしは返さない）。 */
    @Select
    fun selectPastRuns(
        tenantId: Long,
        kettoNum: String,
        before: LocalDate?,
        limit: Int,
    ): List<PastRunRow>

    /** 期間内（両端を含む）のレースの出走馬。レース日・race_id・馬番の昇順。 */
    @Select
    fun selectRaceEntries(
        tenantId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<RaceEntryKeyRow>

    @Select
    fun selectResults(
        tenantId: Long,
        raceId: String,
    ): List<ResultRow>

    /** 単勝オッズのうち `observed_at <= asOf` の点だけ（時刻の昇順）。asOf より後のスナップショットは SQL の条件で除く。 */
    @Select
    fun selectWinOddsAsOf(
        tenantId: Long,
        raceId: String,
        umaban: Int,
        asOf: LocalDateTime,
    ): List<OddsPointRow>
}

@Dao
internal interface HorseMasterHistoryDao {
    /** 追記専用（`INSERT IGNORE` 相当にしない）。同じ (tenant, 馬, known_at) は主キーで拒否される（呼び出し側が重複を扱う）。 */
    @Insert(sqlFile = true)
    fun insert(entity: HorseHistoryRow): Result<HorseHistoryRow>

    @Select
    fun selectAll(
        tenantId: Long,
        kettoNum: String,
    ): List<HorseHistoryRow>
}
