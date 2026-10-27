package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * **旧互換モード**の特徴量生成。旧 `ml/features.py` の `build_feature_vector` / `build_feature_vector_alpha` / `get_horse_feature_summary` を、
 * 演算・特徴量順序・欠損の扱いまで同じに再現する（ゴールデン G6 の比較対象。旧挙動の**検証用**で、新しい学習・推論には使わない）。
 *
 * 旧実装の欠陥を**そのまま**含む（修正しない。新方式は [PointInTimeFeatureBuilder]）:
 * - H3: 過去走を対象レース日で絞らない（同日以降の行・対象レース自身の結果を含み得る）。
 * - H8: `age_years` は [now]（推論時刻/データ生成時刻）基準。レース日基準ではない。`days_since_last_run` だけはレース日基準。
 * 時刻は引数 [now] で注入する（`datetime.now()` を読まない。ゴールデンでは固定時刻を渡す）。
 *
 * 旧実装で DB が無い（`db is None`）/ `race_id` が空の場合は、追加特徴量と血統が 0 埋めになる（[buildWithoutContext]）。
 */
class LegacyFeatureBuilder(
    private val source: FeatureSource,
    private val tenantId: TenantId,
    private val now: LocalDateTime,
) {
    /** 旧 `build_feature_vector`（40 次元）または `build_feature_vector_alpha`（35）/ 旧 5 次元（`MARKET_5`）を、DB ありで作る。 */
    fun build(
        schema: FeatureSchema,
        raceId: String?,
        umaban: Int,
        odds: Double,
        popularity: Int,
    ): FeatureVector {
        if (raceId.isNullOrEmpty()) return buildWithoutContext(schema, odds, popularity)
        val s = summary(raceId, umaban)
        val rc = conditions(raceId)
        val extra = FeatureMath.extra19(s, rc)
        val ped = PedigreeHash.accumulate(s.pedigreeNames, PedigreeHash.DEFAULT_DIM)
        return FeatureVector(schema, FeatureMath.assemble(schema, odds, popularity, extra, ped), s.meta)
    }

    /** DB なし / race_id 空: 追加特徴量・血統は 0 埋め、meta も 0。 */
    fun buildWithoutContext(
        schema: FeatureSchema,
        odds: Double,
        popularity: Int,
    ): FeatureVector =
        FeatureVector(
            schema,
            FeatureMath.assemble(schema, odds, popularity, DoubleArray(19), DoubleArray(PedigreeHash.DEFAULT_DIM)),
            FeatureMeta.EMPTY,
        )

    /**
     * 旧 `format_reason_from_features` の材料（旧 `get_horse_feature_summary` + `get_race_condition_summary`）。旧互換モード（H3 を含む）なので、
     * **ゴールデン比較用**。通知の運用には [PointInTimeFeatureBuilder.reasonFacts] を使う。レース条件が無いときの距離は 0（表示しない）。
     */
    fun reasonFacts(
        raceId: String,
        umaban: Int,
        bamei: String,
    ): HorseReasonFacts {
        val s = summary(raceId, umaban)
        val rc = conditions(raceId)
        return HorseReasonFacts(
            bamei = bamei.trim { it.isWhitespace() },
            hasPastRuns = s.hasPastRuns,
            pastN = s.pastN,
            pastAvgFinish = s.pastAvgFinish,
            pastWinRate = s.pastWinRate,
            pastPlaceRate = s.pastPlaceRate,
            daysSinceLastRun = s.daysSinceLastRun,
            styleHint = s.styleHint,
            aptitude = if (s.hasHorseMaster) Aptitude(s.distancePlaceRate, s.surfacePlaceRate, s.conditionPlaceRate) else null,
            conditions = rc,
            pedigreeNames = s.pedigreeNames,
        )
    }

    fun conditions(raceId: String): RaceConditions {
        val rid = raceId.take(12)
        return if (rid.isEmpty()) RaceConditions.EMPTY else source.raceConditions(tenantId, rid) ?: RaceConditions.EMPTY
    }

    /** 旧 `get_horse_feature_summary(db, race_id, umaban, past_n=5)`。 */
    internal fun summary(
        raceId: String,
        umaban: Int,
    ): HorseSummary {
        val rid = raceId.take(12)
        val kettoNum = source.entry(tenantId, rid, umaban)?.kettoNum?.let(PythonText::strip) ?: ""
        if (kettoNum.isEmpty()) return HorseSummary.NONE

        val h = source.horse(tenantId, kettoNum)
        var age = 0.0
        var style = ""
        var dist = 0.0
        var surf = 0.0
        var cond = 0.0
        var pedigree: List<String> = emptyList()
        if (h != null) {
            h.birthDate?.let { bd ->
                val days = ChronoUnit.DAYS.between(bd.atStartOfDay(), now).toDouble()
                age =
                    jp.jrabot.domain.decision.PythonCompat
                        .max(0.0, days / 365.25)
            }
            style = h.styleHint
            dist = h.distanceTable.placeRate()
            surf = h.surfaceTable.placeRate()
            cond = h.conditionTable.placeRate()
            pedigree = h.pedigreeNames
        }

        val rows = source.pastRuns(tenantId, kettoNum, before = null, limit = 5)
        var win = 0.0
        var place = 0.0
        var avg = 0.0
        var daysSince = 0.0
        if (rows.isNotEmpty()) {
            val stats = FeatureMath.runStats(rows.map { it.finish })
            win = stats.first
            place = stats.second
            avg = stats.third
            val last = rows[0].runDate
            val cur = parseRaceIdDate(raceId)
            if (last != null && cur != null) {
                daysSince = maxOf(0L, ChronoUnit.DAYS.between(last, cur)).toDouble()
            }
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

    private fun parseRaceIdDate(raceId: String): LocalDate? = LegacyDates.parseCompact(raceId.take(8))
}

/** 旧実装の `YYYYMMDD`（8 桁の ASCII 数字）の日付。不正は null（旧: 例外を握り潰して 0.0）。 */
object LegacyDates {
    private val FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun parseCompact(text: String?): LocalDate? {
        if (text == null || text.length != 8 || text.any { it !in '0'..'9' }) return null
        if (text.take(4).toInt() < 1) return null
        return try {
            LocalDate.parse(text, FORMAT)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
