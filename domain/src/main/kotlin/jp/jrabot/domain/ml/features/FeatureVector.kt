package jp.jrabot.domain.ml.features

/** 特徴量ベクトルの付帯情報。旧 `meta_info`（`has_horse_master` / `has_past_runs` / `past_n`）に、point-in-time の注意事項を加えたもの。 */
data class FeatureMeta(
    val hasHorseMaster: Boolean,
    val hasPastRuns: Boolean,
    val pastN: Int,
    val flags: Set<FeatureFlag> = emptySet(),
) {
    /**
     * ensemble の特徴量カバレッジ（旧 `0.5*has_master + 0.3*has_past + 0.2*min(1, past_n/5)`）。式の評価順は旧実装のまま。
     * [weights] は ML_DESIGN §5.2 の `coverage_weights`（既定 [0.5, 0.3, 0.2]）。
     */
    fun coverageScore(weights: List<Double> = DEFAULT_COVERAGE_WEIGHTS): Double {
        require(weights.size == 3) { "coverage_weights は 3 要素" }
        val hm = if (hasHorseMaster) 1.0 else 0.0
        val pr = if (hasPastRuns) 1.0 else 0.0
        val pn = minOf(1.0, pastN.toDouble() / 5.0)
        return weights[0] * hm + weights[1] * pr + weights[2] * pn
    }

    companion object {
        val DEFAULT_COVERAGE_WEIGHTS = listOf(0.5, 0.3, 0.2)
        val EMPTY = FeatureMeta(false, false, 0)
    }
}

enum class FeatureFlag {
    /** 馬マスタの更新がレースの評価時点より後。累積値（着回数の表・脚質傾向）を 0 にした。 */
    MASTER_NEWER_THAN_AS_OF,

    /** 過去走の供給元が、レース日以降の行を返した。除外した（供給元の不具合の可能性）。 */
    SOURCE_RETURNED_FUTURE_RUNS,

    /** 評価時点以前のオッズが無く、旧互換の既定値（odds=100.0・人気=0）を使った。 */
    ODDS_MISSING,
}

/** スキーマに沿った 1 頭分の特徴量。値は不変（コピーして返す）。 */
class FeatureVector(
    val schema: FeatureSchema,
    values: DoubleArray,
    val meta: FeatureMeta,
) {
    private val data = values.copyOf()

    init {
        require(data.size == schema.size) { "特徴量の数がスキーマと違う: ${data.size} / ${schema.size}" }
        require(data.all { it.isFinite() }) { "特徴量に非有限の値" }
    }

    val size: Int get() = data.size

    operator fun get(i: Int): Double = data[i]

    fun values(): DoubleArray = data.copyOf()

    /** 名前で引く（テスト・エクスポート用）。 */
    fun valueOf(name: String): Double = data[schema.names.indexOf(name).also { require(it >= 0) { "特徴量名が無い: $name" } }]
}
