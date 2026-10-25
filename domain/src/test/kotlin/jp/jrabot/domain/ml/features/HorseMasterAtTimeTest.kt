package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 馬マスタの時点の解決（UNRESOLVED G-17 ⑤）。合成データのみ。履歴が無い時点を現在値で補わないことを確認する。 */
class HorseMasterAtTimeTest {
    private val tenant = TenantId.of(1)
    private val raceId = "202601040511"
    private val raceDate = LocalDate.of(2026, 1, 4)
    private val postTime = Instant.parse("2026-01-04T06:25:00Z")
    private val asOf = Instant.parse("2026-01-04T06:15:00Z")
    private val t0 = Instant.parse("2025-06-01T00:00:00Z")
    private val t1 = Instant.parse("2025-10-01T00:00:00Z")
    private val t2 = Instant.parse("2026-03-01T00:00:00Z") // レースの後

    private fun table(vararg v: Long) = ChakuTable(listOf(v.toList()))

    // 現在の行（最終更新 t2 = レースの後）の累積値は『レース結果を含む』値（勝ち 9 回）
    private fun current(updatedAt: Instant = t2) =
        HorseMaster("2020100001", LocalDate.of(2022, 4, 1), "9", table(9L, 0L, 0L), table(9L, 0L, 0L), table(9L, 0L, 0L), listOf("sire"), updatedAt)

    private fun version(
        at: Instant,
        wins: Long,
    ) = HorseCumulative(at, "1", table(wins, 1L, 1L), table(wins, 1L, 1L), table(wins, 1L, 1L))

    @Test
    fun `馬マスタが無ければ null`() {
        assertNull(HorseMasterAtTime.resolve(null, version(t0, 1), asOf))
    }

    @Test
    fun `現在の行の最終更新が asOf 以前なら現在の行（それ以降に変更が無い）`() {
        val c = current(updatedAt = t1)
        assertSame(c, HorseMasterAtTime.resolve(c, null, asOf))
        assertSame(c, HorseMasterAtTime.resolve(c, version(t0, 1), asOf)) // 古い版より現在の行の方が新しい
    }

    @Test
    fun `現在の行が asOf より新しく履歴があるなら、asOf 以前で最新の版を使い、現在値は使わない`() {
        val r = HorseMasterAtTime.resolve(current(), version(t1, 3), asOf)!!
        assertEquals(table(3L, 1L, 1L).rows, r.distanceTable.rows)
        assertEquals("1", r.styleHint)
        assertEquals(t1, r.updatedAt) // 版の時刻 = asOf 以前なので PIT ビルダーは信頼する
        assertEquals(listOf("sire"), r.pedigreeNames) // 不変の属性は現在の行
    }

    @Test
    fun `asOf より後の版は使わない`() {
        val r = HorseMasterAtTime.resolve(current(), version(t2, 5), asOf)!!
        assertTrue(r.distanceTable.rows.isEmpty() && r.styleHint.isEmpty() && r.surfaceTable.rows.isEmpty() && r.conditionTable.rows.isEmpty())
    }

    @Test
    fun `履歴が無い過去時点の累積値は欠損（空）で、現在値で補完しない`() {
        val r = HorseMasterAtTime.resolve(current(), null, asOf)!!
        assertTrue(r.distanceTable.rows.isEmpty() && r.styleHint.isEmpty())
        assertEquals(t2, r.updatedAt) // asOf より後のまま → ビルダーが MASTER_NEWER_THAN_AS_OF を付ける
        assertEquals(LocalDate.of(2022, 4, 1), r.birthDate)
    }

    @Test
    fun `InMemoryFeatureSource 経由の特徴量でも、履歴の版の値が使われ、結果を含む現在値は使われない`() {
        val s = InMemoryFeatureSource()
        s.putRace(tenant, raceId, RaceConditions(raceDate, postTime, 1600, "11", "00", "1", "2", "3"))
        s.putEntry(tenant, raceId, 3, "2020100001")
        s.putHorse(tenant, current())
        val req = FeatureRequest(tenant, raceId, 3, asOf)
        val b = PointInTimeFeatureBuilder(s)

        // 履歴なし: 現在の行がレースより新しい → 欠損 + フラグ（従来どおり）
        val none = b.build(FeatureSchema.ALPHA_35, req)
        assertEquals(0.0, none.valueOf("distance_place_rate"))
        assertTrue(FeatureFlag.MASTER_NEWER_THAN_AS_OF in none.meta.flags)

        // 版を登録: t1 の版（勝ち 3、計 5 走の 3 着内率 = 5/5 = 1.0 ではなく (3+1+1)/(3+1+1)）の値が使われ、フラグは付かない
        s.putHorseHistory(tenant, "2020100001", version(t1, 3))
        val v = b.build(FeatureSchema.ALPHA_35, req)
        assertEquals(1.0, v.valueOf("distance_place_rate"))
        assertTrue(FeatureFlag.MASTER_NEWER_THAN_AS_OF !in v.meta.flags)
        assertEquals(1.0, v.valueOf("style_hint_code"))

        // 他テナントの履歴は見えない
        assertNull(s.horseAsOf(TenantId.of(2), "2020100001", asOf))
    }
}
