package jp.jrabot.domain.ml.features

import jp.jrabot.domain.decision.PythonCompat

/** 距離・馬場・状態の 3 着内率（馬マスタの累積値。**累積値が判明していた時点のものだけ**で、不明なら [HorseReasonFacts.aptitude] が null）。 */
data class Aptitude(
    val distancePlaceRate: Double,
    val surfacePlaceRate: Double,
    val conditionPlaceRate: Double,
)

/**
 * 通知の理由文（旧 `format_reason_from_features`）の材料。取得できた項目だけを持ち、無い項目は null/空（0 で埋めない）。
 * 作り方は 2 通りで、**互換用と安全用を分けている**:
 * - [LegacyFeatureBuilder.reasonFacts]: 旧実装と同じ読み方（H3: 過去走をレース日で絞らない。現在の馬マスタ）。ゴールデン比較用で、**通知の運用には使わない**。
 * - [PointInTimeFeatureBuilder.reasonFacts]: 評価時点（asOf）以前の情報だけ。レース後に更新された馬マスタの累積値は使わない（[aptitude]・[styleHint] は空になる）。
 * [bamei] は出走表の馬名（呼び出し側が渡す。`FeatureSource` は馬名を持たない）。
 */
data class HorseReasonFacts(
    val bamei: String,
    val hasPastRuns: Boolean,
    val pastN: Int,
    val pastAvgFinish: Double,
    val pastWinRate: Double,
    val pastPlaceRate: Double,
    val daysSinceLastRun: Double,
    val styleHint: String,
    val aptitude: Aptitude?,
    val conditions: RaceConditions?,
    val pedigreeNames: List<String>,
)

object ReasonFormatter {
    private const val PEDIGREE_SHOWN = 3

    /** 旧 `format_reason_from_features` と同じ書式（` / ` 区切り）。材料が何も無ければ空文字。 */
    fun format(f: HorseReasonFacts): String {
        val parts = mutableListOf<String>()
        if (f.bamei.isNotEmpty()) parts += "馬名=${f.bamei}"
        if (f.hasPastRuns) {
            parts +=
                "近走${f.pastN}走: 平均着順=${PythonCompat.fixed(f.pastAvgFinish, 1)}, " +
                "勝率=${PythonCompat.percent(f.pastWinRate, 0)}, 複勝圏=${PythonCompat.percent(f.pastPlaceRate, 0)}"
            if (f.daysSinceLastRun > 0) parts += "間隔=${f.daysSinceLastRun.toLong()}日"
        }
        if (f.styleHint.isNotEmpty()) parts += "脚質傾向=${f.styleHint}"
        f.aptitude?.let {
            parts +=
                "適性(3着内率): 距離=${PythonCompat.percent(it.distancePlaceRate, 0)}, " +
                "馬場=${PythonCompat.percent(it.surfacePlaceRate, 0)}, 状態=${PythonCompat.percent(it.conditionPlaceRate, 0)}"
        }
        f.conditions?.takeIf { it.distanceM != 0 }?.let {
            parts += "条件: 距離=${it.distanceM}m track=${it.trackTypeCd} 天候=${it.tenkoCd} 芝=${it.shibaBabaCd} ダ=${it.dirtBabaCd}"
        }
        if (f.pedigreeNames.isNotEmpty()) parts += "血統=" + f.pedigreeNames.take(PEDIGREE_SHOWN).joinToString(",")
        return parts.joinToString(" / ")
    }
}
