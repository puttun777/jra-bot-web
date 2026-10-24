package jp.jrabot.domain.ml.features

import jp.jrabot.domain.decision.PythonCompat
import kotlin.math.ln

/** 馬の特徴量の要約（旧 `HorseFeatureSummary`）。 */
internal class HorseSummary(
    val hasHorseMaster: Boolean,
    val hasPastRuns: Boolean,
    val ageYears: Double,
    val pastN: Int,
    val pastWinRate: Double,
    val pastPlaceRate: Double,
    val pastAvgFinish: Double,
    val styleHint: String,
    val distancePlaceRate: Double,
    val surfacePlaceRate: Double,
    val conditionPlaceRate: Double,
    val pedigreeNames: List<String>,
    val daysSinceLastRun: Double,
) {
    val meta get() = FeatureMeta(hasHorseMaster, hasPastRuns, pastN)

    companion object {
        /** 馬も馬マスタも過去走も無い（旧: ketto_num が空）。 */
        val NONE = HorseSummary(false, false, 0.0, 0, 0.0, 0.0, 0.0, "", 0.0, 0.0, 0.0, emptyList(), 0.0)
    }
}

/**
 * 特徴量の算術（旧 `build_feature_vector` / `build_feature_vector_alpha` と同じ式・同じ順序）。旧互換モードと point-in-time モードで**共有**する
 * （入力の選び方だけが違い、式は 1 か所）。
 */
internal object FeatureMath {
    /**
     * 旧 `base`（5 次元）。`odds <= 0` は 100.0 に置換（置換後の値で `log_odds` と交互作用も計算する）。
     * `log_odds = ln(max(odds, 0.1))`、`inv_popularity = 1 / max(popularity, 1)`、交互作用 = `odds * popularity`。
     */
    fun marketBase(
        oddsIn: Double,
        popularity: Int,
    ): DoubleArray {
        require(oddsIn.isFinite()) { "オッズは有限な数: $oddsIn" }
        require(popularity >= 0) { "人気順位は 0 以上: $popularity" }
        val odds = if (oddsIn <= 0) 100.0 else oddsIn
        val pop = popularity
        val logOdds = ln(PythonCompat.max(odds, 0.1))
        val invPop = 1.0 / maxOf(pop, 1).toDouble()
        val inter = odds * pop.toDouble()
        return doubleArrayOf(odds, pop.toDouble(), logOdds, invPop, inter)
    }

    /** 旧 `extra_dense`（19 次元）。レース条件が無いときは [RaceConditions.EMPTY]（距離 0・コード空）。 */
    fun extra19(
        s: HorseSummary,
        rc: RaceConditions,
    ): DoubleArray {
        val distanceKm = if (rc.distanceM != 0) rc.distanceM.toDouble() / 1000.0 else 0.0
        val isTurf = if (rc.trackTypeCd == "11") 1.0 else 0.0
        val isDirt = if (rc.trackTypeCd == "12") 1.0 else 0.0
        val isObstacle = if (rc.trackTypeCd == "13") 1.0 else 0.0
        val tenko = PythonText.safeIntAsDouble(rc.tenkoCd)
        val baba = PythonText.safeIntAsDouble(if (isTurf != 0.0) rc.shibaBabaCd else rc.dirtBabaCd)
        val courseKubun = PythonText.safeIntAsDouble(rc.courseKubunCd)
        return doubleArrayOf(
            if (s.hasHorseMaster) 1.0 else 0.0,
            if (s.hasPastRuns) 1.0 else 0.0,
            s.ageYears,
            s.pastN.toDouble(),
            s.pastWinRate,
            s.pastPlaceRate,
            s.pastAvgFinish,
            PythonText.safeIntAsDouble(s.styleHint),
            s.distancePlaceRate,
            s.surfacePlaceRate,
            s.conditionPlaceRate,
            s.daysSinceLastRun,
            distanceKm,
            isTurf,
            isDirt,
            isObstacle,
            tenko,
            baba,
            courseKubun,
        )
    }

    /** 過去走の要約（勝率・複勝率・平均着順）。旧 `get_horse_feature_summary` の集計部分。着順は 1 以上。 */
    fun runStats(finishes: List<Int>): Triple<Double, Double, Double> {
        if (finishes.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val n = finishes.size
        val win = finishes.count { it == 1 }.toDouble() / n
        val place = finishes.count { it <= 3 }.toDouble() / n
        val avg = finishes.sum().toDouble() / n
        return Triple(win, place, avg)
    }

    fun assemble(
        schema: FeatureSchema,
        odds: Double,
        popularity: Int,
        extra: DoubleArray,
        pedigree: DoubleArray,
    ): DoubleArray =
        when (schema) {
            FeatureSchema.CALIBRATION_40 -> marketBase(odds, popularity) + extra + pedigree
            FeatureSchema.ALPHA_35 -> extra + pedigree
            FeatureSchema.MARKET_5 -> marketBase(odds, popularity)
            else -> throw IllegalArgumentException("特徴量を作れないスキーマ: $schema")
        }
}
