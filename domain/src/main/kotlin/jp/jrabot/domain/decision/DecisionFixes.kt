package jp.jrabot.domain.decision

/**
 * 判定ロジックの版。**本番の既定は [LEGACY]**（旧 Python の数値挙動を再現する。MIGRATION_MATRIX §8、D-044・D-055）。
 * v2（市場確率の二重ブレンドの解消など）は旧新比較・バックテストの後に、ユーザーが判断する（T1.21）ので、ここには定義しない。
 */
enum class PipelineVersion { LEGACY, }

/**
 * 旧挙動の「数値を変える」改善案（分類 C）の切替。**すべて既定 false。未承認のため true にすると [UnsupportedDecisionScopeException] で拒否する**
 * （黙って旧挙動のまま動くことを避ける。実装は承認後・バックテスト後）。
 *
 * | フラグ | 項目 | 案 |
 * |---|---|---|
 * | [capRescaleRespectsMinBet] | E6 | `max_total_bet_per_race` の縮小後に `min_bet` を再判定する |
 * | [applyMaxFraction] | E12 | 設定値の `max_fraction` を判定の Kelly に適用する |
 * | [snapshotTrendFilter] | E14 | 急落フィルタが最古だけでなく連続する変化を見る |
 * | [kellyMultiplierBySourceEnum] | H10 | Kelly 係数を確率源の enum で決める（旧は文字列が一致せず常に 0.8） |
 *
 * 分類 R（E1〜E5 など。数値は変えずラベル・理由コードだけ直す）はフラグを持たず常に有効。
 */
data class DecisionFixes(
    val capRescaleRespectsMinBet: Boolean = false,
    val applyMaxFraction: Boolean = false,
    val snapshotTrendFilter: Boolean = false,
    val kellyMultiplierBySourceEnum: Boolean = false,
) {
    val anyEnabled: Boolean get() = capRescaleRespectsMinBet || applyMaxFraction || snapshotTrendFilter || kellyMultiplierBySourceEnum

    companion object {
        val NONE = DecisionFixes()
    }
}

/** この段階（T3.1）で未対応の入力・設定。黙って別の挙動にせず、呼び出し側へ明示する。 */
class UnsupportedDecisionScopeException(
    message: String,
) : UnsupportedOperationException(message)
