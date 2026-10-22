package jp.jrabot.domain.value

/**
 * 金額。**単位は円**、整数（Long）、**0 以上**。購入金額・払戻金・回収率の分母/分子に使う。
 *
 * - 浮動小数にしない（円未満は存在しないので、誤差を持ち込まない）。
 * - 演算は桁あふれと負の結果を黙って許さない: [plus]・[times] の桁あふれ、[minus] の負の結果は [ArithmeticException]。
 *   損益（負になり得る）は [differenceYen] で符号付きの Long として得る。
 * - **丸めの仕様は未承認のため持たない**。除算・割合の乗算・Double との変換は提供しない（回収率などの比は、呼び出し側で明示的に計算する。
 *   端数処理は業務上必要になった時点で、承認された仕様として追加する）。
 * - 旧実装の資金・最小/最大投資額は「点」の Double（`bankroll: 100.0` など）で、円ではない。点は [Money] にしない（Stage 3 の判断）。
 */
@JvmInline
value class Money private constructor(
    val yen: Long,
) : Comparable<Money> {
    val isZero: Boolean get() = yen == 0L

    operator fun plus(other: Money): Money = Money(Math.addExact(yen, other.yen))

    /** 結果が負になる場合は [ArithmeticException]（損益は [differenceYen]）。 */
    operator fun minus(other: Money): Money {
        if (yen < other.yen) throw ArithmeticException("金額が負になる: $yen - ${other.yen}")
        return Money(yen - other.yen)
    }

    /** 回数・口数などの非負の整数倍。負の係数は [IllegalArgumentException]、桁あふれは [ArithmeticException]。 */
    operator fun times(factor: Long): Money {
        require(factor >= 0) { "係数は 0 以上: $factor" }
        return Money(Math.multiplyExact(yen, factor))
    }

    /** `this - other` を符号付きで返す（両方 0 以上の Long なのでオーバーフローしない）。 */
    fun differenceYen(other: Money): Long = yen - other.yen

    override fun compareTo(other: Money): Int = yen.compareTo(other.yen)

    override fun toString(): String = "Money(${yen}円)"

    companion object {
        val ZERO: Money = Money(0)

        fun ofYenOrNull(yen: Long): Money? = if (yen >= 0) Money(yen) else null

        fun ofYen(yen: Long): Money = requireNotNull(ofYenOrNull(yen)) { "金額は 0 円以上: $yen" }

        fun sum(amounts: Iterable<Money>): Money = amounts.fold(ZERO) { acc, m -> acc + m }
    }
}
