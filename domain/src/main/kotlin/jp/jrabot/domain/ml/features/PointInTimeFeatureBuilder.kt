package jp.jrabot.domain.ml.features

import jp.jrabot.domain.decision.PythonCompat
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 評価時点（asOf）より未来の情報を使ってしまう入力。特徴量を作らず、理由コードつきで拒否する（黙って補正しない）。 */
class LeakageException(
    val code: LeakageCode,
    message: String,
) : IllegalArgumentException(message)

enum class LeakageCode {
    /** asOf が発走時刻以降（発走後のオッズ・結果を見ている可能性）。 */
    AS_OF_NOT_BEFORE_POST_TIME,

    /** 発走時刻が不明で、asOf が発走前であることを確認できない。 */
    POST_TIME_UNKNOWN,

    /** レース日が不明で、過去走を「対象レースより前」に絞れない。 */
    RACE_DATE_UNKNOWN,

    /** レース条件が無い。 */
    RACE_NOT_FOUND,
}

/** ある時刻のオッズ（スナップショット）。 */
data class OddsPoint(
    val ts: Instant,
    val odds: Double,
    val popularity: Int,
) {
    init {
        require(odds.isFinite()) { "オッズは有限な数: $odds" }
        require(popularity >= 0) { "人気順位は 0 以上: $popularity" }
    }
}

/** 馬マスタの累積値（着回数の表・脚質傾向）が評価時点より新しいときの扱い。 */
enum class MasterTimeline {
    /** 累積値を 0 にして [FeatureFlag.MASTER_NEWER_THAN_AS_OF] を付ける（既定。結果を含み得る値を使わない）。 */
    ZERO_CUMULATIVE_WHEN_NEWER,

    /** 信頼してそのまま使う（累積値がレース結果を含み得る。推論時の最新マスタ向けで、学習用データには使わない）。 */
    TRUST_LATEST,
}

data class FeatureRequest(
    val tenantId: TenantId,
    /** 12 桁の race_id。 */
    val raceId: String,
    val umaban: Int,
    /** 評価時点。この時刻**より後**の情報は使わない。 */
    val asOf: Instant,
    /** この馬のオッズの履歴（順不同でよい）。`ts <= asOf` の最新だけを使う。calibration / market 特徴量にだけ必要。 */
    val oddsHistory: List<OddsPoint> = emptyList(),
)

/**
 * **新方式（point-in-time）**の特徴量生成（H3・H8 の是正）。旧互換の [LegacyFeatureBuilder] と式（[FeatureMath]）は同じで、**入力の選び方だけ**が違う:
 * - 過去走は `run_date < レース日`（レース日**当日を含まない**。対象レース自身も含まない）。未来の確定成績を参照しない。
 * - `age_years` は**レース日**基準（推論時刻でも学習データの生成時刻でもない）。
 * - オッズは `ts <= asOf` の最新だけ（asOf より後のスナップショットは見ない）。無ければ旧互換の既定値（odds=100.0・人気=0）で [FeatureFlag.ODDS_MISSING]。
 * - asOf が発走時刻以降なら [LeakageException]（発走時刻が不明でも拒否）。
 * - 馬マスタは [FeatureSource.horseAsOf]（asOf 時点で判明していた版。履歴の規則は [HorseMasterAtTime]）。累積値は [MasterTimeline] に従う。
 *   **限界**: 履歴が無い時点の累積値は欠損（空）になる。過去データの復元は、時点を特定できる実データが得られた後（UNRESOLVED）。
 * 時刻は [Instant] の引数で受け取り、現在時刻を読まない。旧互換の数値を上書きしない（別のクラス・別のテスト）。
 */
class PointInTimeFeatureBuilder(
    private val source: FeatureSource,
    private val masterTimeline: MasterTimeline = MasterTimeline.ZERO_CUMULATIVE_WHEN_NEWER,
) {
    fun build(
        schema: FeatureSchema,
        request: FeatureRequest,
    ): FeatureVector {
        val rc = checkedConditions(request)
        val raceDate = checkNotNull(rc.raceDate) { "checkedConditions がレース日を確認済み" }

        val flags = mutableSetOf<FeatureFlag>()
        val summary = summary(request, rc, raceDate, flags)

        var odds = 0.0
        var popularity = 0
        if (schema != FeatureSchema.ALPHA_35) {
            val point = request.oddsHistory.filter { !it.ts.isAfter(request.asOf) }.maxByOrNull { it.ts }
            if (point != null) {
                odds = point.odds
                popularity = point.popularity
            } else {
                flags += FeatureFlag.ODDS_MISSING
            }
        }

        val extra = FeatureMath.extra19(summary, rc)
        val ped = PedigreeHash.accumulate(summary.pedigreeNames, PedigreeHash.DEFAULT_DIM)
        return FeatureVector(schema, FeatureMath.assemble(schema, odds, popularity, extra, ped), summary.meta.copy(flags = flags))
    }

    /** レース条件を読み、未来の情報を使わないことを確認する（発走時刻・レース日が不明、asOf が発走時刻以降なら [LeakageException]）。 */
    private fun checkedConditions(request: FeatureRequest): RaceConditions {
        val rc =
            source.raceConditions(request.tenantId, request.raceId)
                ?: throw LeakageException(LeakageCode.RACE_NOT_FOUND, "レース条件が無い: ${request.raceId}")
        val postTime = rc.postTime ?: throw LeakageException(LeakageCode.POST_TIME_UNKNOWN, "発走時刻が不明: ${request.raceId}")
        if (!request.asOf.isBefore(postTime)) {
            throw LeakageException(LeakageCode.AS_OF_NOT_BEFORE_POST_TIME, "asOf が発走時刻以降: ${request.raceId}")
        }
        if (rc.raceDate == null) throw LeakageException(LeakageCode.RACE_DATE_UNKNOWN, "レース日が不明: ${request.raceId}")
        return rc
    }

    /**
     * 通知の理由文の材料（point-in-time）。[build] と同じ検査（[LeakageException]）と同じ入力の選び方で、**asOf 以前の情報だけ**を使う:
     * 過去走はレース日より前、馬マスタは [FeatureSource.horseAsOf]。累積値が asOf より後に更新されていたら適性・脚質は出さない（空）。
     * 血統は不変とみなす。[bamei] は出走表の馬名。
     */
    fun reasonFacts(
        request: FeatureRequest,
        bamei: String,
    ): HorseReasonFacts {
        val rc = checkedConditions(request)
        val raceDate = checkNotNull(rc.raceDate)
        val flags = mutableSetOf<FeatureFlag>()
        val s = summary(request, rc, raceDate, flags)
        val cumulativeKnown = s.hasHorseMaster && FeatureFlag.MASTER_NEWER_THAN_AS_OF !in flags
        return HorseReasonFacts(
            bamei = bamei.trim { it.isWhitespace() },
            hasPastRuns = s.hasPastRuns,
            pastN = s.pastN,
            pastAvgFinish = s.pastAvgFinish,
            pastWinRate = s.pastWinRate,
            pastPlaceRate = s.pastPlaceRate,
            daysSinceLastRun = s.daysSinceLastRun,
            styleHint = s.styleHint,
            aptitude = if (cumulativeKnown) Aptitude(s.distancePlaceRate, s.surfacePlaceRate, s.conditionPlaceRate) else null,
            conditions = rc,
            pedigreeNames = s.pedigreeNames,
        )
    }

    private fun summary(
        req: FeatureRequest,
        rc: RaceConditions,
        raceDate: java.time.LocalDate,
        flags: MutableSet<FeatureFlag>,
    ): HorseSummary {
        val kettoNum = source.entry(req.tenantId, req.raceId, req.umaban)?.kettoNum?.let(PythonText::strip) ?: ""
        if (kettoNum.isEmpty()) return HorseSummary.NONE

        val h = source.horseAsOf(req.tenantId, kettoNum, req.asOf)
        var age = 0.0
        var style = ""
        var dist = 0.0
        var surf = 0.0
        var cond = 0.0
        var pedigree: List<String> = emptyList()
        if (h != null) {
            h.birthDate?.let { bd -> age = PythonCompat.max(0.0, ChronoUnit.DAYS.between(bd, raceDate).toDouble() / 365.25) }
            pedigree = h.pedigreeNames
            val trusted = masterTimeline == MasterTimeline.TRUST_LATEST || !h.updatedAt.isAfter(req.asOf)
            if (trusted) {
                style = h.styleHint
                dist = h.distanceTable.placeRate()
                surf = h.surfaceTable.placeRate()
                cond = h.conditionTable.placeRate()
            } else {
                flags += FeatureFlag.MASTER_NEWER_THAN_AS_OF
            }
        }

        val fetched = source.pastRuns(req.tenantId, kettoNum, before = raceDate, limit = 5)
        // 供給元が絞り損ねても未来の行を使わない（多重防御）。順序と件数も再確認する。
        val rows =
            fetched
                .filter { it.runDate != null && it.runDate.isBefore(raceDate) && it.finish > 0 }
                .sortedWith(compareByDescending<PastRun> { it.runDate }.thenByDescending { it.raceId })
                .take(5)
        if (rows.size != fetched.size) flags += FeatureFlag.SOURCE_RETURNED_FUTURE_RUNS

        var win = 0.0
        var place = 0.0
        var avg = 0.0
        var daysSince = 0.0
        if (rows.isNotEmpty()) {
            val stats = FeatureMath.runStats(rows.map { it.finish })
            win = stats.first
            place = stats.second
            avg = stats.third
            daysSince = ChronoUnit.DAYS.between(rows[0].runDate, raceDate).toDouble()
        }
        return HorseSummary(
            hasHorseMaster = h != null,
            hasPastRuns = rows.isNotEmpty(),
            ageYears = age,
            pastN = rows.size,
            pastWinRate = win,
            pastPlaceRate = place,
            pastAvgFinish = avg,
            styleHint = style,
            distancePlaceRate = dist,
            surfacePlaceRate = surf,
            conditionPlaceRate = cond,
            pedigreeNames = pedigree,
            daysSinceLastRun = daysSince,
        )
    }
}
