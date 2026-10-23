package jp.jrabot.domain.decision

/**
 * ワイド・三連複の**実オッズ**（JRA が公表したオッズ）。推定オッズ（[ComboOddsEstimate]）とは別物で、混同しない。
 * 旧実装は `wide_odds_map: {(馬番小, 馬番大): (低, 高)}` / `sanrenpuku_odds_map: {(昇順の 3 頭): オッズ}` を引数で受け取る。
 * この版も同じく**呼び出し側が注入する**（DB の取得は T3.4 以降。`:domain` は DB に依存しない）。
 *
 * - キーは馬番を**昇順に正規化**する（[WideKey]・[SanrenpukuKey]）。旧実装は呼び出し側が昇順で作らないと一致しない（逆順のキーは黙って見つからない）が、
 *   この版は作成時に正規化するので逆順で渡しても同じ買い目として扱う（旧実装との意図的な差。旧実装が検索に使うキーは常に昇順）。
 * - 買い目ごとに引く。**一部の組合せだけ**あってもよい（無い買い目は『実オッズ未取得』）。実オッズが無い買い目は、
 *   `require_real_odds_for_combo=true` では買い目にならず、`false` のときだけ推定オッズになる（旧実装のまま）。
 * - 鮮度（取得時刻）の判定は既存の契約に無いので持たない（時間の閾値を推測で足さない）。古いデータを渡さないのは呼び出し側（T3.4）の責務。
 * - ワイドは旧実装と同じく**低い方**で EV を計算する（保守的）。三連複のオッズ `0.0` は旧実装の `if odds_val:` で『無い』扱い（推定または見送り）。
 */
class ComboOdds(
    wide: Map<WideKey, WideOddsRange> = emptyMap(),
    sanrenpuku: Map<SanrenpukuKey, Double> = emptyMap(),
) {
    private val wideMap: Map<WideKey, WideOddsRange> = LinkedHashMap(wide)
    private val sanrenpukuMap: Map<SanrenpukuKey, Double> = LinkedHashMap(sanrenpuku)

    init {
        sanrenpukuMap.values.forEach {
            require(it.isFinite() && it >= 0.0) { "三連複の実オッズは 0 以上の有限な数: $it" }
        }
    }

    /** 旧 `if wide_odds_map` の真偽（メモの文言が変わる）。 */
    val hasWide: Boolean get() = wideMap.isNotEmpty()
    val hasSanrenpuku: Boolean get() = sanrenpukuMap.isNotEmpty()

    /** 2 頭の実オッズ（順不同）。無い、または馬番が不正（0 以下・同じ馬番）なら null（旧実装の辞書検索は一致しないだけで例外にならない）。 */
    fun wide(
        a: Int,
        b: Int,
    ): WideOddsRange? = if (a > 0 && b > 0 && a != b) wideMap[WideKey.of(a, b)] else null

    /** 3 頭の実オッズ（順不同）。無い、または馬番が不正なら null。 */
    fun sanrenpuku(
        a: Int,
        b: Int,
        c: Int,
    ): Double? = if (a > 0 && b > 0 && c > 0 && a != b && b != c && a != c) sanrenpukuMap[SanrenpukuKey.of(a, b, c)] else null

    companion object {
        val EMPTY = ComboOdds()
    }
}

/** ワイドの買い目キー（2 頭・順不同。昇順に正規化）。 */
class WideKey private constructor(
    val first: Int,
    val second: Int,
) {
    override fun equals(other: Any?) = other is WideKey && other.first == first && other.second == second

    override fun hashCode() = 31 * first + second

    override fun toString() = "$first-$second"

    companion object {
        fun of(
            a: Int,
            b: Int,
        ): WideKey {
            require(a > 0 && b > 0) { "馬番は正: $a, $b" }
            require(a != b) { "ワイドの馬番は異なること: $a, $b" }
            return if (a < b) WideKey(a, b) else WideKey(b, a)
        }
    }
}

/** 三連複の買い目キー（3 頭・順不同。昇順に正規化）。 */
class SanrenpukuKey private constructor(
    val first: Int,
    val second: Int,
    val third: Int,
) {
    override fun equals(other: Any?) = other is SanrenpukuKey && other.first == first && other.second == second && other.third == third

    override fun hashCode() = 31 * (31 * first + second) + third

    override fun toString() = "$first-$second-$third"

    companion object {
        fun of(
            a: Int,
            b: Int,
            c: Int,
        ): SanrenpukuKey {
            require(a > 0 && b > 0 && c > 0) { "馬番は正: $a, $b, $c" }
            require(a != b && b != c && a != c) { "三連複の馬番は互いに異なること: $a, $b, $c" }
            val s = intArrayOf(a, b, c).also { it.sort() }
            return SanrenpukuKey(s[0], s[1], s[2])
        }
    }
}

/** ワイドの実オッズの範囲（払戻の下限・上限。単位は倍）。EV は [low] で計算する（旧実装）。`low == 0.0` も旧実装は受け付ける（EV が −1 になり買い目にならない）。 */
data class WideOddsRange(
    val low: Double,
    val high: Double,
) {
    init {
        require(low.isFinite() && high.isFinite()) { "ワイドの実オッズは有限な数: $low〜$high" }
        require(low >= 0.0) { "ワイドの実オッズは 0 以上: $low" }
        require(low <= high) { "ワイドの実オッズは 下限 <= 上限: $low〜$high" }
    }
}
