package jp.jrabot.domain.value

/**
 * 確率。0.0 以上 1.0 以下の有限値（両端を含む）。NaN・±Infinity・範囲外は生成できない（黙って 0〜1 に丸めない）。
 * `-0.0` は `0.0` に正規化する（等価性・hashCode を一致させるため）。
 *
 * 加減算は型として提供しない（和は 1 を超え得る）。必要な演算だけを用意する:
 * - [complement]: 余事象 `1 - p`
 * - [times]: 独立な事象の積（積は必ず 0〜1 に収まる）
 * - [normalize]: 非負の重みを合計 1 の確率にする（丸めはしない）
 *
 * 数値の挙動（クリップ・市場ブレンド・丸め）は Stage 3 で旧実装と比較して決める。ここでは変更しない。
 */
@JvmInline
value class Probability private constructor(
    val value: Double,
) : Comparable<Probability> {
    val isZero: Boolean get() = value == 0.0
    val isOne: Boolean get() = value == 1.0

    fun complement(): Probability = Probability(1.0 - value)

    operator fun times(other: Probability): Probability = Probability(value * other.value)

    override fun compareTo(other: Probability): Int = value.compareTo(other.value)

    override fun toString(): String = "Probability($value)"

    companion object {
        val ZERO: Probability = Probability(0.0)
        val ONE: Probability = Probability(1.0)

        fun ofOrNull(value: Double): Probability? = if (value.isFinite() && value in 0.0..1.0) Probability(value + 0.0) else null

        fun of(value: Double): Probability = requireNotNull(ofOrNull(value)) { "確率は 0.0〜1.0 の有限値: $value" }

        /**
         * 非負で有限な重みを、合計が 1 になる確率に直す。各要素は `重み / 合計`。
         * 空・負数・NaN/Infinity・合計 0（0 除算）は [IllegalArgumentException]（黙って一様分布にしない）。
         */
        fun normalize(weights: List<Double>): List<Probability> {
            require(weights.isNotEmpty()) { "重みが空" }
            require(weights.all { it.isFinite() && it >= 0.0 }) { "重みは非負の有限値: $weights" }
            val total = weights.sum()
            require(total.isFinite() && total > 0.0) { "重みの合計は有限な正の数: $total" }
            return weights.map { of(it / total) }
        }
    }
}
