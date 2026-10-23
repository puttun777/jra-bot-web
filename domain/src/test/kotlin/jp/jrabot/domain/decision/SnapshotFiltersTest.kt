package jp.jrabot.domain.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotFiltersTest {
    private fun newestFirst(vararg rows: Pair<Double, Int>) = SnapshotsNewestFirst.ofNewestFirst(rows.map { OddsPopularitySnapshot(it.first, it.second) })

    @Test
    fun `急落 - 閾値ちょうどは急落ではなく、超えたら急落`() {
        val s = newestFirst(9.0 to 3, 10.0 to 4) // 最古は 10.0
        assertFalse(SnapshotFilters.checkOddsDrop(s, 8.0)) // (10 - 8) / 10 = 0.2 ちょうど
        assertFalse(SnapshotFilters.checkOddsDrop(s, 8.01))
        assertTrue(SnapshotFilters.checkOddsDrop(s, 7.99))
        assertTrue(SnapshotFilters.checkOddsDrop(s, 5.0))
        assertFalse(SnapshotFilters.checkOddsDrop(s, 12.0)) // 上昇は急落ではない
        assertTrue(SnapshotFilters.checkOddsDrop(s, 5.0, dropThreshold = 0.4999))
        assertFalse(SnapshotFilters.checkOddsDrop(s, 5.0, dropThreshold = 0.5))
    }

    @Test
    fun `急落 - スナップショットが 0 件・1 件は判定しない（2 件から）`() {
        assertFalse(SnapshotFilters.checkOddsDrop(SnapshotsNewestFirst.EMPTY, 5.0))
        assertFalse(SnapshotFilters.checkOddsDrop(newestFirst(9.0 to 3), 1.0))
        assertTrue(SnapshotFilters.checkOddsDrop(newestFirst(9.0 to 3, 10.0 to 4), 1.0))
    }

    @Test
    fun `急落 - 最古のオッズが 0 以下は判定しない`() {
        assertFalse(SnapshotFilters.checkOddsDrop(newestFirst(9.0 to 3, 0.0 to 4), 5.0))
        assertFalse(SnapshotFilters.checkOddsDrop(newestFirst(9.0 to 3, -1.0 to 4), 5.0))
    }

    @Test
    fun `急落 - 比べるのは最古の 1 件だけで、途中の変化は見ない（E14）`() {
        // 新しい順で [1.0, 10.0, 20.0]: 最古は 20.0。現在 15.0 は (20 - 15) / 20 = 0.25 > 0.2 で急落
        assertTrue(SnapshotFilters.checkOddsDrop(newestFirst(1.0 to 1, 10.0 to 4, 20.0 to 9), 15.0))
        // 直前（10.0）からは上昇でも、最古（20.0）との差だけで判定される
        assertTrue(SnapshotFilters.checkOddsDrop(newestFirst(1.0 to 1, 10.0 to 4, 20.0 to 9), 15.5))
        assertFalse(SnapshotFilters.checkOddsDrop(newestFirst(1.0 to 1, 10.0 to 4, 20.0 to 9), 16.0)) // ちょうど 0.2
    }

    @Test
    fun `人気急上昇 - 差が閾値ちょうどで急上昇で、急落の境界とは向きが違う`() {
        val s = newestFirst(9.0 to 5, 10.0 to 6) // 最古の人気は 6
        assertFalse(SnapshotFilters.checkPopularitySurge(s, 4)) // 差 2
        assertTrue(SnapshotFilters.checkPopularitySurge(s, 3)) // 差 3 = 閾値
        assertTrue(SnapshotFilters.checkPopularitySurge(s, 1))
        assertFalse(SnapshotFilters.checkPopularitySurge(s, 7)) // 人気が落ちた
        assertTrue(SnapshotFilters.checkPopularitySurge(s, 4, surgeThreshold = 2))
        assertFalse(SnapshotFilters.checkPopularitySurge(s, 4, surgeThreshold = 3))
    }

    @Test
    fun `人気急上昇 - 人気順位の変化（最古 6 位 → 現在 1 位）と 0 位（欠損）の扱い`() {
        assertTrue(SnapshotFilters.checkPopularitySurge(newestFirst(9.0 to 5, 10.0 to 6), 1))
        assertFalse(SnapshotFilters.checkPopularitySurge(newestFirst(9.0 to 5, 10.0 to 0), 0)) // 差 0
        assertFalse(SnapshotFilters.checkPopularitySurge(SnapshotsNewestFirst.EMPTY, 1))
        assertFalse(SnapshotFilters.checkPopularitySurge(newestFirst(9.0 to 5), 1))
    }

    @Test
    fun `並びの型 - 新しい順の末尾が最古で、古い順の列は明示的に反転して使う`() {
        val oldestFirst = listOf(OddsPopularitySnapshot(10.0, 4), OddsPopularitySnapshot(9.0, 3))
        val s = SnapshotsNewestFirst.ofOldestFirst(oldestFirst)
        assertEquals(OddsPopularitySnapshot(10.0, 4), s.oldest)
        assertEquals(listOf(OddsPopularitySnapshot(9.0, 3), OddsPopularitySnapshot(10.0, 4)), s.items)
        assertEquals(s, newestFirst(9.0 to 3, 10.0 to 4))
        assertNull(SnapshotsNewestFirst.EMPTY.oldest)
        // 取り違えると結果が変わる（古い順の列をそのまま渡すと最古が別の要素になる）ことの確認
        val wrong = SnapshotsNewestFirst.ofNewestFirst(oldestFirst)
        assertEquals(OddsPopularitySnapshot(9.0, 3), wrong.oldest)
    }
}
