package jp.jrabot.domain.decision

/**
 * 1 レース分の、ワイド・三連複の買い目生成が共有する入力（全馬・最終確率・実オッズ・設定）。馬ごとの戦略生成（[BetStrategyBuilder.build]）の間で使い回し、
 * Plackett–Luce の重みと確率のメモ（[PlackettLuce]）・BOX 候補を 1 レースにつき 1 度だけ作る（E10）。
 *
 * - [probs] は旧実装が `probs_by_umaban` に渡す最終確率（市場ブレンド・クリップ・再正規化後）。[horses] と同じ並び。
 * - [PlackettLuce] は**遅延生成**: ワイド・三連複で確率が必要になった時だけ作り、そのとき馬番が正で一意であることを検査する
 *   （単勝だけ・実オッズ未取得の見送りだけの経路は、馬番が不正でも旧実装と同じ結果）。不正なら [IllegalArgumentException]。
 */
class ComboRaceContext(
    val horses: List<DecisionHorse>,
    val probs: List<Double>,
    val realOdds: ComboOdds,
    val settings: ComboSettings,
) {
    init {
        require(horses.size == probs.size) { "馬と確率の数が違う: ${horses.size} / ${probs.size}" }
    }

    val pl: PlackettLuce by lazy { PlackettLuce.of(horses.map { it.umaban }, probs, settings.plackettLuceAlpha) }

    /**
     * BOX 候補（旧 `_pick_box_candidates`）: 人気順位が正の馬を人気昇順（同順位は出走順）、1 頭もいなければ オッズが正の馬を オッズ昇順（同値は出走順）で、
     * 上位 `max(3, box_size)` 頭の馬番。
     */
    val boxCandidates: List<Int> by lazy {
        val byPopularity = horses.filter { it.popularity > 0 }
        val base =
            if (byPopularity.isNotEmpty()) {
                byPopularity.sortedBy { it.popularity }
            } else {
                horses.filter { it.odds > 0 }.sortedBy { it.odds }
            }
        base.map { it.umaban }.take(maxOf(3, settings.boxSize))
    }

    /** 馬番が一致する最初の馬の単勝オッズ（旧 `next((o for o in odds_list if o.umaban == a), None)`）。 */
    fun winOddsOf(umaban: Int): Double? = horses.firstOrNull { it.umaban == umaban }?.odds

    /** 軸以外で人気順位が正の馬を人気昇順（同順位は出走順）に並べた馬番。 */
    fun partnersByPopularity(axis: Int): List<Int> =
        horses
            .filter { it.umaban != axis && it.popularity > 0 }
            .sortedBy { it.popularity }
            .map { it.umaban }
}
