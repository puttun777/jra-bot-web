package jp.jrabot.domain.decision

/** 急落・人気急上昇の判定に使う、ある時点の 1 頭分の単勝オッズと人気順位（参照元の `recent_snapshots` の 1 要素 `{"odds", "popularity"}`）。 */
data class OddsPopularitySnapshot(
    val odds: Double,
    val popularity: Int,
)

/**
 * 直近スナップショットの列。**新しい順**（先頭が最新、末尾が最古）であることを型で表す（旧欠陥 E14 の対策: 並びの取り違えを防ぐ）。
 * 旧実装のフィルタは末尾（= 最古）の 1 件だけを現在値と比べる。連続する変化は見ない（仕様として固定）。
 *
 * 取得元（`odds_snapshots` を観測時刻の降順で読む）が並びを保証する。古い順の列しか無い場合は [ofOldestFirst] で明示的に反転する。
 */
class SnapshotsNewestFirst private constructor(
    val items: List<OddsPopularitySnapshot>,
) {
    /** 最古のスナップショット。2 件未満のときは判定に使わないので null（[size] で判断する）。 */
    val oldest: OddsPopularitySnapshot? get() = items.lastOrNull()
    val size: Int get() = items.size

    override fun equals(other: Any?): Boolean = other is SnapshotsNewestFirst && other.items == items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "SnapshotsNewestFirst($items)"

    companion object {
        val EMPTY = SnapshotsNewestFirst(emptyList())

        /** 新しい順に並んだ列をそのまま受け取る（並びの検証はできない: 時刻を持たないため。呼び出し側の責務）。 */
        fun ofNewestFirst(items: List<OddsPopularitySnapshot>): SnapshotsNewestFirst = SnapshotsNewestFirst(items.toList())

        /** 古い順の列を反転して新しい順にする。 */
        fun ofOldestFirst(items: List<OddsPopularitySnapshot>): SnapshotsNewestFirst = SnapshotsNewestFirst(items.asReversed().toList())
    }
}

/** 直近スナップショットの取得（旧 `recent_snapshots_func(umaban)`）。馬番ごとに呼ばれる。 */
fun interface RecentSnapshotsProvider {
    fun recentSnapshots(umaban: Int): SnapshotsNewestFirst
}

/** 急落・人気急上昇フィルタ。参照元 `ev/decision.py` の `check_odds_drop` / `check_popularity_surge`。 */
object SnapshotFilters {
    /**
     * オッズ急落: スナップショットが 2 件未満なら false。最古のオッズ ≤ 0 なら false。
     * それ以外は `(最古 − 現在) / 最古 > dropThreshold`（**閾値ちょうどは急落ではない**）。
     */
    fun checkOddsDrop(
        recent: SnapshotsNewestFirst,
        currentOdds: Double,
        dropThreshold: Double = 0.20,
    ): Boolean {
        if (recent.size < 2) return false
        val oldestOdds = recent.items.last().odds
        if (oldestOdds <= 0) return false
        val dropRate = (oldestOdds - currentOdds) / oldestOdds
        return dropRate > dropThreshold
    }

    /**
     * 人気急上昇: スナップショットが 2 件未満なら false。それ以外は `最古の人気 − 現在の人気 >= surgeThreshold`
     * （順位の数字が小さいほど人気。**差が閾値ちょうどで急上昇**。急落の `>` とは境界の向きが違う）。
     */
    fun checkPopularitySurge(
        recent: SnapshotsNewestFirst,
        currentPopularity: Int,
        surgeThreshold: Int = 3,
    ): Boolean {
        if (recent.size < 2) return false
        val oldestPopularity = recent.items.last().popularity
        val popularityChange = oldestPopularity - currentPopularity
        return popularityChange >= surgeThreshold
    }
}
