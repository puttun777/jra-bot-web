package jp.jrabot.domain.decision

import kotlin.math.pow

/**
 * 順位依存の確率（Plackett–Luce）。参照元 `ev/bet_strategy.py` の `suggest_multiple_bet_strategies` 内の
 * `_score` / `_pl_order_prob` / `_top3_set_prob` / `_wide_pair_prob` を、**同じ演算順序**で写したもの（E8・E10）。
 *
 * - 重み: 馬の確率 p を `[1e-12, 0.99]` に収め、`p^α`（α は `[0.1, 5]` に収める。`α == 0.0` は旧実装の `alpha or 1.0` で 1.0 になる）。
 * - 着順確率: `w[x1]/W × w[x2]/(W−w[x1]) × w[x3]/(W−w[x1]−w[x2])`（途中で重みまたは残りが 0 以下なら 0。結果は `[0, 0.99]` に収める）。
 * - 三連複 [top3SetProb]: 6 通りの着順確率の和（`itertools.permutations` の順に**素の `+` で**足す。**引数の順で最後の数桁が変わる**ので、
 *   旧実装が渡す順＝軸・相手の順／BOX 候補の順をそのまま渡すこと）。結果は `[0, 0.99]` に収める。
 * - ワイド [widePairProb]: 2 頭以外の全馬 c について [top3SetProb]`(a, b, c)` を出走順に足す（`[0, 0.99]` に収める）。
 *
 * **性能（E10）**: 全馬の重みの合計 W（旧実装は着順確率ごとに `sum(weights.values())` を取り直す）は構築時に 1 度だけ求める。
 * 同じ引数なら同じ値になる純粋関数なので、(a, b, c) / (a, b) ごとの結果もメモ化する。**演算の順序と値は旧実装と同一**（数値の近似・省略はしない）。
 *
 * 入力の検査（旧実装は黙って流す箇所の境界）: 馬番は正で一意（重複・0 以下は [IllegalArgumentException]。旧実装は重複を辞書で上書きし、
 * ワイドの合計では二重に数える）、確率は有限、α は有限。呼び出しの引数の馬番は互いに異なること。
 * 状態（キャッシュ）を持つのでスレッド間で共有しない（1 レース・1 呼び出しの間だけ使う）。
 */
class PlackettLuce private constructor(
    private val weights: Map<Int, Double>,
    private val entrants: List<Int>,
    val alpha: Double,
) {
    /** 旧 `remaining = float(sum(weights.values()))`（CPython 3.12 以降の補償加算。辞書の挿入順＝出走順）。 */
    private val total: Double = PythonCompat.sum(entrants.map { weights.getValue(it) })

    private val top3Cache = HashMap<Triple<Int, Int, Int>, Double>()
    private val wideCache = HashMap<Pair<Int, Int>, Double>()

    /** 出走馬の馬番（出走順）。 */
    val umabans: List<Int> get() = entrants

    /** 馬の重み `p^α`（旧 `_score`）。出走馬でなければ null。 */
    fun weightOf(umaban: Int): Double? = weights[umaban]

    /** 全馬の重みの合計 W。 */
    val totalWeight: Double get() = total

    /** 1 着→2 着→3 着の順に与えた着順の確率（旧 `_pl_order_prob`）。出走馬でない馬番は重み 0 として 0.0（旧実装のまま）。 */
    fun orderProb(
        x: Int,
        y: Int,
        z: Int,
    ): Double {
        var remaining = total
        if (remaining <= 0) return 0.0
        var p = 1.0
        for (u in intArrayOf(x, y, z)) {
            val w = weights[u] ?: 0.0
            if (w <= 0 || remaining <= 0) return 0.0
            p *= w / remaining
            remaining -= w
        }
        return PythonCompat.clamp(p, 0.0, MAX_PROB)
    }

    /** 3 頭が 3 着以内に（順不同で）入る確率（旧 `_top3_set_prob`）。 */
    fun top3SetProb(
        a: Int,
        b: Int,
        c: Int,
    ): Double {
        require(a != b && b != c && a != c) { "三連複の馬番は互いに異なること: $a, $b, $c" }
        return top3Cache.getOrPut(Triple(a, b, c)) {
            // itertools.permutations([a, b, c], 3) の順
            var s = 0.0
            s += orderProb(a, b, c)
            s += orderProb(a, c, b)
            s += orderProb(b, a, c)
            s += orderProb(b, c, a)
            s += orderProb(c, a, b)
            s += orderProb(c, b, a)
            PythonCompat.clamp(s, 0.0, MAX_PROB)
        }
    }

    /** 2 頭がどちらも 3 着以内に入る確率（旧 `_wide_pair_prob`）＝ 他の各馬 c について 3 頭 {a, b, c} が上位 3 着になる確率の和。 */
    fun widePairProb(
        a: Int,
        b: Int,
    ): Double {
        require(a != b) { "ワイドの馬番は異なること: $a, $b" }
        return wideCache.getOrPut(Pair(a, b)) {
            var sum = 0.0
            for (u in entrants) {
                if (u == a || u == b) continue
                sum += top3SetProb(a, b, u)
            }
            PythonCompat.clamp(sum, 0.0, MAX_PROB)
        }
    }

    companion object {
        const val P_MIN = 1e-12
        const val MAX_PROB = 0.99
        const val ALPHA_MIN = 0.1
        const val ALPHA_MAX = 5.0

        /** 旧 `_score`: `min(max(p, 1e-12), 0.99) ** min(max(alpha or 1.0, 0.1), 5.0)`。 */
        fun weight(
            p: Double,
            alpha: Double,
        ): Double {
            val clippedP = PythonCompat.clamp(p, P_MIN, MAX_PROB)
            return clippedP.pow(effectiveAlpha(alpha))
        }

        /** 旧 `float(alpha or 1.0)` を `[0.1, 5]` に収めた値（`0.0` は falsy で 1.0。負値は 0.1）。 */
        fun effectiveAlpha(alpha: Double): Double = PythonCompat.clamp(if (alpha == 0.0) 1.0 else alpha, ALPHA_MIN, ALPHA_MAX)

        /** 馬番（出走順）と各馬の確率から作る。 */
        fun of(
            umabans: List<Int>,
            probs: List<Double>,
            alpha: Double,
        ): PlackettLuce {
            require(umabans.size == probs.size) { "馬番と確率の数が違う: ${umabans.size} / ${probs.size}" }
            require(alpha.isFinite()) { "plackett_luce_alpha は有限な数: $alpha" }
            require(umabans.all { it > 0 }) { "Plackett–Luce の馬番は正: $umabans" }
            require(umabans.toSet().size == umabans.size) { "Plackett–Luce の馬番が重複している: $umabans" }
            require(probs.all { it.isFinite() }) { "Plackett–Luce の確率は有限な数: $probs" }
            val weights = LinkedHashMap<Int, Double>()
            for ((u, p) in umabans.zip(probs)) weights[u] = weight(p, alpha)
            return PlackettLuce(weights, umabans, alpha)
        }
    }
}
