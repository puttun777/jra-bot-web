package jp.jrabot.domain.decision

// 判定ロジックで**混同してはいけない量**の型。どれも素の `Double` を包むだけ（丸めも範囲検査もしない。旧実装の境界挙動を保つため）。
// 関数の引数・戻り値・データ項目で別の型にすることで、「生の確率を EV 計算に渡す」などの取り違えをコンパイルで防ぐ。

/**
 * 生の確率（`prob_raw`）。正規化 → 市場ブレンド → 上限クリップ → 再正規化を経た、`ev_shrink_factor` を掛ける**前**の単勝確率。
 * シグナルの `prob` / `p_model` / `p_final` と、`ratio`（= prob_raw ÷ 市場確率）の基準。
 */
@JvmInline
value class ProbRaw(
    val value: Double,
)

/**
 * 賭けの判断に使う確率（`prob_used` / `prob_used_for_bet`）。[ProbRaw] に `ev_shrink_factor`（[0,1] に収めた値）を掛け、[0, 0.99] に収めたもの。
 * 戦略の EV と Kelly の入力は**この確率**で、シグナルの表示用 `prob` は [ProbRaw]（旧欠陥 E4 の背景: 基準が違うので EV と ratio は一致しない）。
 */
@JvmInline
value class ProbUsed(
    val value: Double,
)

/** 期待利益率 EV = 確率 × odds − 1。どの確率で計算したかは、それを持つ項目の説明（戦略の `ev` は [ProbUsed] 基準、`calcEv` は [ProbRaw] 基準）に従う。 */
@JvmInline
value class Ev(
    val value: Double,
)

/** 市場との比 `ratio` = 生の確率 ÷ 市場確率（`1 / max(odds, 0.1)`）。[ProbRaw] 基準（`ev_shrink_factor` の影響を受けない）。 */
@JvmInline
value class Ratio(
    val value: Double,
)

/** 賭式。`label` は旧実装の日本語名（シグナル・見送り通知の表示と優先順位設定にそのまま使われる）。 */
enum class BetType(
    val label: String,
    val marketCode: String,
) {
    WIN("単勝", "win"),
    WIDE("ワイド", "wide"),
    SANRENPUKU("三連複", "sanrenpuku"),
    ;

    companion object {
        fun fromLabelOrNull(label: String): BetType? = entries.firstOrNull { it.label == label }
    }
}
