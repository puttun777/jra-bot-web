package jp.jrabot.domain.odds

/**
 * オッズ（JRA の decimal odds。払戻倍率で元本を含む。参照元 `ev_calc.py`）。
 *
 * 不変条件: 有限な正の数。NaN・±Infinity・0・負数は生成できない（0 は「データ無し」の意味で使われるため、オッズとして持たない。
 * 欠損は値ではなく null / 不在で表す）。
 *
 * **1.0 を無効扱いしない**。1.0 は元本が戻るだけの有効なオッズで（[PAR]）、Kelly で扱えないことへの対応（旧欠陥 E5 の `ODDS_AT_PAR`）は
 * 計算ロジック（Stage 3）で行う。上限は置かない。JRA が発表するオッズの下限（1.0 を下回るか）は公式仕様で未確認（T1.17）なので、
 * 型の不変条件としては「正の有限値」までに留め、市場の妥当性の判定はパース側（T3.4）に委ねる（D-060）。
 *
 * 丸めはしない（旧実装の数値と一致させる。丸めは表示層のみ。MIGRATION_MATRIX F05）。
 */
@JvmInline
value class Odds private constructor(
    val value: Double,
) : Comparable<Odds> {
    override fun compareTo(other: Odds): Int = value.compareTo(other.value)

    override fun toString(): String = "Odds($value)"

    companion object {
        /** 元本が戻るだけのオッズ（1.0 倍）。有効な値。 */
        val PAR: Odds = Odds(1.0)

        fun ofOrNull(value: Double): Odds? = if (value.isFinite() && value > 0.0) Odds(value) else null

        fun of(value: Double): Odds = requireNotNull(ofOrNull(value)) { "オッズは有限な正の数: $value" }
    }
}
