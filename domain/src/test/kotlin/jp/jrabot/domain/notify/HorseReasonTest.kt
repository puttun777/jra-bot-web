package jp.jrabot.domain.notify

import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.FeatureRequest
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.LeakageException
import jp.jrabot.domain.ml.features.LegacyFeatureBuilder
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.features.ReasonFormatter
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** G-17 ⑥ `format_reason_from_features`。合成の馬・レースだけを使う（実データ・実在の馬名ではない）。 */
class HorseReasonTest {
    private val tenant = TenantId.of(1)
    private val raceId = "202601040511"
    private val raceDate = LocalDate.of(2026, 1, 4)
    private val postTime = Instant.parse("2026-01-04T06:25:00Z")
    private val asOf = Instant.parse("2026-01-04T06:15:00Z")

    private fun source(
        masterUpdatedAt: Instant = Instant.parse("2025-12-01T00:00:00Z"),
        withFuture: Boolean = false,
    ): InMemoryFeatureSource {
        val s = InMemoryFeatureSource()
        s.putRace(tenant, raceId, RaceConditions(raceDate, postTime, 1600, "11", "00", "1", "2", "3"))
        s.putEntry(tenant, raceId, 3, "2020100001")
        s.putHorse(
            tenant,
            HorseMaster(
                "2020100001", LocalDate.of(2022, 4, 1), "2",
                ChakuTable(listOf(listOf(2L, 1L, 1L, 0L, 0L, 1L))), ChakuTable(listOf(listOf(1L, 1L, 0L))), ChakuTable(listOf(listOf(3L, 0L, 1L))),
                listOf("父サンプル", "母サンプル", "祖父サンプル", "祖母サンプル"), masterUpdatedAt,
            ),
        )
        listOf(
            PastRun("202512280611", LocalDate.of(2025, 12, 28), 1),
            PastRun("202512070711", LocalDate.of(2025, 12, 7), 4),
            PastRun("202511160811", LocalDate.of(2025, 11, 16), 2),
        ).forEach { s.putPastRun(tenant, "2020100001", it) }
        if (withFuture) s.putPastRun(tenant, "2020100001", PastRun("202601110511", LocalDate.of(2026, 1, 11), 1))
        return s
    }

    @Test
    fun `旧互換 全項目の書式`() {
        val b = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2026, 1, 4, 12, 0))
        val text = ReasonFormatter.format(b.reasonFacts(raceId, 3, " 合成一号 "))
        assertEquals(
            "馬名=合成一号 / 近走3走: 平均着順=2.3, 勝率=33%, 複勝圏=67% / 間隔=7日 / 脚質傾向=2 / " +
                "適性(3着内率): 距離=80%, 馬場=100%, 状態=100% / 条件: 距離=1600m track=11 天候=1 芝=2 ダ=3 / 血統=父サンプル,母サンプル,祖父サンプル",
            text,
        )
    }

    @Test
    fun `取得できた項目だけを使い 無い項目は出さない`() {
        val s = InMemoryFeatureSource()
        val b = LegacyFeatureBuilder(s, tenant, LocalDateTime.of(2026, 1, 4, 12, 0))
        assertEquals("", ReasonFormatter.format(b.reasonFacts(raceId, 3, "")))
        assertEquals("馬名=名無し", ReasonFormatter.format(b.reasonFacts(raceId, 3, "名無し")))
        s.putRace(tenant, raceId, RaceConditions(raceDate, postTime, 0, "", "", "", "", ""))
        assertEquals("", ReasonFormatter.format(b.reasonFacts(raceId, 3, "")), "距離 0 の条件は出さない")
    }

    @Test
    fun `旧互換は過去走を日付で絞らない H3 が point-in-time は未来の過去走を使わない`() {
        val src = source(withFuture = true)
        val legacy = ReasonFormatter.format(LegacyFeatureBuilder(src, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).reasonFacts(raceId, 3, "合成一号"))
        assertTrue(legacy.contains("近走4走"), "H3: 未来の行を含む（旧互換の欠陥の再現）: $legacy")
        val pit = ReasonFormatter.format(PointInTimeFeatureBuilder(src).reasonFacts(FeatureRequest(tenant, raceId, 3, asOf), "合成一号"))
        assertTrue(pit.contains("近走3走") && !pit.contains("近走4走"), pit)
        assertTrue(pit.contains("間隔=7日"))
    }

    @Test
    fun `point-in-time はレース後に更新された馬マスタの累積値を使わない`() {
        val updatedAfter = Instant.parse("2026-01-05T00:00:00Z")
        val pit = ReasonFormatter.format(PointInTimeFeatureBuilder(source(masterUpdatedAt = updatedAfter)).reasonFacts(FeatureRequest(tenant, raceId, 3, asOf), "合成一号"))
        assertFalse(pit.contains("適性") || pit.contains("脚質傾向"), "累積値は空（0% などの架空の値を出さない）: $pit")
        assertTrue(pit.contains("血統=父サンプル") && pit.contains("近走3走"), "不変の血統と過去走は使える")
        val legacy = ReasonFormatter.format(LegacyFeatureBuilder(source(masterUpdatedAt = updatedAfter), tenant, LocalDateTime.of(2026, 1, 6, 0, 0)).reasonFacts(raceId, 3, "合成一号"))
        assertTrue(legacy.contains("適性(3着内率)"), "旧互換は現在の馬マスタを使う（比較用）")
    }

    @Test
    fun `point-in-time は発走後の asOf を拒否する`() {
        assertFailsWith<LeakageException> {
            PointInTimeFeatureBuilder(source()).reasonFacts(FeatureRequest(tenant, raceId, 3, postTime), "合成一号")
        }
    }

    @Test
    fun `他テナントの馬は見えない`() {
        val other = TenantId.of(2)
        val text = ReasonFormatter.format(LegacyFeatureBuilder(source(), other, LocalDateTime.of(2026, 1, 4, 12, 0)).reasonFacts(raceId, 3, "合成一号"))
        assertEquals("馬名=合成一号", text)
    }

    @Test
    fun `馬場と天候のコードが表示に入る`() {
        val s = source()
        s.putRace(tenant, raceId, RaceConditions(raceDate, postTime, 2400, "12", "00", "4", "1", "3"))
        val text = ReasonFormatter.format(LegacyFeatureBuilder(s, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).reasonFacts(raceId, 3, ""))
        assertTrue(text.contains("条件: 距離=2400m track=12 天候=4 芝=1 ダ=3"), text)
    }
}
