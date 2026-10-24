package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureBuilderTest {
    private val tenant = TenantId.of(1)
    private val otherTenant = TenantId.of(2)
    private val raceId = "202601040511"
    private val raceDate = LocalDate.of(2026, 1, 4)
    private val postTime = Instant.parse("2026-01-04T06:25:00Z") // 15:25 JST
    private val asOf = Instant.parse("2026-01-04T06:15:00Z")

    private fun source(
        masterUpdatedAt: Instant = Instant.parse("2025-12-01T00:00:00Z"),
        extraRuns: List<PastRun> = emptyList(),
    ): InMemoryFeatureSource {
        val s = InMemoryFeatureSource()
        s.putRace(tenant, raceId, RaceConditions(raceDate, postTime, 1600, "11", "00", "1", "2", "3"))
        s.putEntry(tenant, raceId, 3, " 2020100001 ")
        s.putHorse(
            tenant,
            HorseMaster(
                kettoNum = "2020100001",
                birthDate = LocalDate.of(2022, 4, 1),
                styleHint = "2",
                distanceTable = ChakuTable(listOf(listOf(2L, 1L, 1L, 0L, 0L, 1L), listOf(0L, 0L, 0L, 3L))),
                surfaceTable = ChakuTable(listOf(listOf(1L, 1L, 0L))),
                conditionTable = ChakuTable(emptyList()),
                pedigreeNames = listOf("シンセティックサイアー001", "ＡＢＣ", "ABC", "dup", "dup"),
                updatedAt = masterUpdatedAt,
            ),
        )
        // 過去走（新しい順に finish: 1, 4, 2, 5, 3, 9）と、レース当日・未来の行（H3）
        listOf(
            PastRun("202512280611", LocalDate.of(2025, 12, 28), 1),
            PastRun("202512070711", LocalDate.of(2025, 12, 7), 4),
            PastRun("202511160811", LocalDate.of(2025, 11, 16), 2),
            PastRun("202510260511", LocalDate.of(2025, 10, 26), 5),
            PastRun("202510050611", LocalDate.of(2025, 10, 5), 3),
            PastRun("202509140511", LocalDate.of(2025, 9, 14), 9),
            PastRun("202508100111", LocalDate.of(2025, 8, 10), 0), // 着順 0（除外競走）
        ).plus(extraRuns).forEach { s.putPastRun(tenant, "2020100001", it) }
        return s
    }

    private val sameDay = PastRun(raceId, raceDate, 1) // 対象レース自身（結果がラベル）
    private val future = PastRun("202601110511", LocalDate.of(2026, 1, 11), 2)

    @Test
    fun `旧互換 DB なしは追加特徴量と血統が 0 埋めで odds は 0 以下なら 100`() {
        val b = LegacyFeatureBuilder(InMemoryFeatureSource(), tenant, LocalDateTime.of(2026, 1, 1, 0, 0))
        val v = b.buildWithoutContext(FeatureSchema.CALIBRATION_40, 0.0, 0)
        assertEquals(40, v.size)
        assertEquals(100.0, v.valueOf("odds"))
        assertEquals(0.0, v.valueOf("popularity"))
        assertEquals(ln(100.0), v.valueOf("log_odds"))
        assertEquals(1.0, v.valueOf("inv_popularity"))
        assertEquals(0.0, v.valueOf("odds_popularity_interaction"))
        assertTrue(v.values().drop(5).all { it == 0.0 })
        val v2 = b.buildWithoutContext(FeatureSchema.CALIBRATION_40, 4.0, 2)
        assertEquals(listOf(4.0, 2.0, ln(4.0), 0.5, 8.0), v2.values().take(5))
        assertEquals(35, b.buildWithoutContext(FeatureSchema.ALPHA_35, 4.0, 2).size)
        assertEquals(FeatureMeta.EMPTY, v.meta)
    }

    @Test
    fun `旧互換 全特徴量の値と順序`() {
        val b = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2026, 1, 4, 12, 0))
        val v = b.build(FeatureSchema.CALIBRATION_40, raceId, 3, 3.5, 2)
        assertEquals(40, v.size)
        assertEquals(3.5, v.valueOf("odds"))
        assertEquals(1.0, v.valueOf("has_horse_master"))
        assertEquals(1.0, v.valueOf("has_past_runs"))
        // 過去走の直近 5 走 finish=[1,4,2,5,3] → 勝率 1/5・複勝率 3/5・平均 3.0
        assertEquals(5.0, v.valueOf("past_n"))
        assertEquals(0.2, v.valueOf("past_win_rate"))
        assertEquals(0.6, v.valueOf("past_place_rate"))
        assertEquals(3.0, v.valueOf("past_avg_finish"))
        assertEquals(2.0, v.valueOf("style_hint_code"))
        // 距離表: total=2+1+1+0+0+1+3=8、top3=(2+1+1)+(0+0+0)=4 → 0.5
        assertEquals(0.5, v.valueOf("distance_place_rate"))
        assertEquals(1.0, v.valueOf("surface_place_rate")) // [1,1,0] → total 2、top3 2
        assertEquals(0.0, v.valueOf("condition_place_rate"))
        assertEquals(7.0, v.valueOf("days_since_last_run")) // 2025-12-28 → 2026-01-04
        assertEquals(1.6, v.valueOf("distance_km"))
        assertEquals(1.0, v.valueOf("is_turf"))
        assertEquals(0.0, v.valueOf("is_dirt"))
        assertEquals(0.0, v.valueOf("tenko_cd") - 1.0) // tenko_cd="1"
        assertEquals(2.0, v.valueOf("baba_cd")) // 芝なので shiba_baba_cd="2"
        assertEquals(0.0, v.valueOf("course_kubun_cd"))
        // 血統ハッシュは 8 件まで（5 名）。dup は 2 回足す
        val ped = PedigreeHash.accumulate(listOf("シンセティックサイアー001", "ＡＢＣ", "ABC", "dup", "dup"), 16)
        assertContentEquals(ped, v.values().takeLast(16).toDoubleArray())
        assertEquals(FeatureMeta(true, true, 5), v.meta)
    }

    @Test
    fun `旧互換 age_years は渡した now 基準で、レース日ではない（H8 を再現）`() {
        val at2026 = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2026, 4, 1, 0, 0)).build(FeatureSchema.ALPHA_35, raceId, 3, 0.0, 0)
        val at2030 = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2030, 4, 1, 0, 0)).build(FeatureSchema.ALPHA_35, raceId, 3, 0.0, 0)
        // 生年月日 2022-04-01。2026-04-01 まで 1461 日、2030-04-01 まで 2922 日
        assertEquals(1461.0 / 365.25, at2026.valueOf("age_years"))
        assertEquals(2922.0 / 365.25, at2030.valueOf("age_years"))
        // 生年月日より前の now は max(0, …) で 0
        val before = LegacyFeatureBuilder(source(), tenant, LocalDateTime.of(2021, 1, 1, 0, 0)).build(FeatureSchema.ALPHA_35, raceId, 3, 0.0, 0)
        assertEquals(0.0, before.valueOf("age_years"))
    }

    @Test
    fun `旧互換 H3 過去走はレース当日・未来の行も含む（旧欠陥を再現）`() {
        val withLeak = source(extraRuns = listOf(sameDay, future))
        val v = LegacyFeatureBuilder(withLeak, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).build(FeatureSchema.ALPHA_35, raceId, 3, 0.0, 0)
        // 直近 5 走は future(2) → 当日(1) → 12/28(1) → 12/7(4) → 11/16(2)。対象レースの結果（当日=1着）が勝率に漏れる
        assertEquals(2.0 / 5.0, v.valueOf("past_win_rate"))
        assertEquals((2 + 1 + 1 + 4 + 2) / 5.0, v.valueOf("past_avg_finish"))
        // 競走間隔は max(0, 差) なので、最新の行が未来なら 0 日
        assertEquals(0.0, v.valueOf("days_since_last_run"))
    }

    @Test
    fun `馬が出走表に無い・ketto_num が空なら馬の特徴は 0 だがレース条件は入る`() {
        val s = source()
        s.putEntry(tenant, raceId, 9, "   ")
        val v = LegacyFeatureBuilder(s, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).build(FeatureSchema.ALPHA_35, raceId, 9, 0.0, 0)
        assertEquals(0.0, v.valueOf("has_horse_master"))
        assertEquals(1.6, v.valueOf("distance_km"))
        assertEquals(FeatureMeta.EMPTY, v.meta)
        val none = LegacyFeatureBuilder(s, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).build(FeatureSchema.ALPHA_35, raceId, 12, 0.0, 0)
        assertEquals(1.0, none.valueOf("is_turf"))
    }

    @Test
    fun `旧互換 他テナントの馬は見えない`() {
        val v = LegacyFeatureBuilder(source(), otherTenant, LocalDateTime.of(2026, 1, 4, 12, 0)).build(FeatureSchema.ALPHA_35, raceId, 3, 0.0, 0)
        assertEquals(FeatureMeta.EMPTY, v.meta)
        assertEquals(0.0, v.valueOf("distance_km"))
    }

    // ---- point-in-time ----

    private fun req(
        oddsHistory: List<OddsPoint> = listOf(OddsPoint(Instant.parse("2026-01-04T05:00:00Z"), 3.5, 2)),
        at: Instant = asOf,
    ) = FeatureRequest(tenant, raceId, 3, at, oddsHistory)

    @Test
    fun `PIT 過去走はレース日より前だけで、当日と未来の行を使わない（H3）`() {
        val v = PointInTimeFeatureBuilder(source(extraRuns = listOf(sameDay, future))).build(FeatureSchema.ALPHA_35, req())
        assertEquals(0.2, v.valueOf("past_win_rate")) // 当日の 1 着は漏れない
        assertEquals(3.0, v.valueOf("past_avg_finish"))
        assertEquals(7.0, v.valueOf("days_since_last_run"))
        assertEquals(5.0, v.valueOf("past_n"))
        assertTrue(v.meta.flags.isEmpty())
    }

    @Test
    fun `PIT age_years はレース日基準で現在時刻に依らない（H8）`() {
        val v = PointInTimeFeatureBuilder(source()).build(FeatureSchema.ALPHA_35, req())
        assertEquals(
            java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.of(2022, 4, 1), raceDate) / 365.25,
            v.valueOf("age_years"),
        )
        // asOf を変えても（発走前なら）age は変わらない
        val v2 = PointInTimeFeatureBuilder(source()).build(FeatureSchema.ALPHA_35, req(at = Instant.parse("2026-01-03T00:00:00Z")))
        assertEquals(v.valueOf("age_years"), v2.valueOf("age_years"))
    }

    @Test
    fun `PIT オッズは asOf 以前の最新だけ`() {
        val history =
            listOf(
                OddsPoint(Instant.parse("2026-01-04T05:00:00Z"), 3.5, 2),
                OddsPoint(Instant.parse("2026-01-04T06:00:00Z"), 4.0, 3),
                OddsPoint(Instant.parse("2026-01-04T06:20:00Z"), 99.0, 15), // asOf(06:15) より後 → 見ない
            )
        val v = PointInTimeFeatureBuilder(source()).build(FeatureSchema.CALIBRATION_40, req(history))
        assertEquals(4.0, v.valueOf("odds"))
        assertEquals(3.0, v.valueOf("popularity"))
        // asOf ちょうどのスナップショットは使える
        val exact = PointInTimeFeatureBuilder(source()).build(FeatureSchema.CALIBRATION_40, req(history, at = Instant.parse("2026-01-04T06:00:00Z")))
        assertEquals(4.0, exact.valueOf("odds"))
    }

    @Test
    fun `PIT asOf 以前のオッズが無ければ旧互換の既定値とフラグ、alpha はオッズを使わない`() {
        val future = listOf(OddsPoint(Instant.parse("2026-01-04T06:20:00Z"), 9.0, 5))
        val v = PointInTimeFeatureBuilder(source()).build(FeatureSchema.CALIBRATION_40, req(future))
        assertEquals(100.0, v.valueOf("odds"))
        assertEquals(0.0, v.valueOf("popularity"))
        assertTrue(FeatureFlag.ODDS_MISSING in v.meta.flags)
        val a = PointInTimeFeatureBuilder(source()).build(FeatureSchema.ALPHA_35, req(future))
        assertFalse(FeatureFlag.ODDS_MISSING in a.meta.flags)
    }

    @Test
    fun `PIT asOf が発走時刻以降・発走時刻不明・レース無しは拒否する`() {
        val b = PointInTimeFeatureBuilder(source())
        assertEquals(LeakageCode.AS_OF_NOT_BEFORE_POST_TIME, assertFailsWith<LeakageException> { b.build(FeatureSchema.ALPHA_35, req(at = postTime)) }.code)
        assertEquals(
            LeakageCode.AS_OF_NOT_BEFORE_POST_TIME,
            assertFailsWith<LeakageException> {
                b.build(FeatureSchema.ALPHA_35, req(at = postTime.plusSeconds(60)))
            }.code,
        )
        val noPost = source().also { it.putRace(tenant, raceId, RaceConditions(raceDate, null, 1600, "11", "", "", "", "")) }
        assertEquals(
            LeakageCode.POST_TIME_UNKNOWN,
            assertFailsWith<LeakageException> { PointInTimeFeatureBuilder(noPost).build(FeatureSchema.ALPHA_35, req()) }.code,
        )
        val noDate = source().also { it.putRace(tenant, raceId, RaceConditions(null, postTime, 1600, "11", "", "", "", "")) }
        assertEquals(
            LeakageCode.RACE_DATE_UNKNOWN,
            assertFailsWith<LeakageException> { PointInTimeFeatureBuilder(noDate).build(FeatureSchema.ALPHA_35, req()) }.code,
        )
        assertEquals(
            LeakageCode.RACE_NOT_FOUND,
            assertFailsWith<LeakageException> { b.build(FeatureSchema.ALPHA_35, req().copy(raceId = "202601040512")) }.code,
        )
    }

    @Test
    fun `PIT 馬マスタが asOf より新しいと累積値を 0 にしてフラグを付ける`() {
        val newer = source(masterUpdatedAt = Instant.parse("2026-01-05T00:00:00Z"))
        val v = PointInTimeFeatureBuilder(newer).build(FeatureSchema.ALPHA_35, req())
        assertEquals(0.0, v.valueOf("distance_place_rate"))
        assertEquals(0.0, v.valueOf("surface_place_rate"))
        assertEquals(0.0, v.valueOf("style_hint_code"))
        assertEquals(1.0, v.valueOf("has_horse_master"))
        assertTrue(FeatureFlag.MASTER_NEWER_THAN_AS_OF in v.meta.flags)
        // 血統は不変なので使う
        assertTrue(v.values().takeLast(16).any { it != 0.0 })
        val trust = PointInTimeFeatureBuilder(newer, MasterTimeline.TRUST_LATEST).build(FeatureSchema.ALPHA_35, req())
        assertEquals(0.5, trust.valueOf("distance_place_rate"))
        assertTrue(trust.meta.flags.isEmpty())
    }

    @Test
    fun `PIT 供給元がレース日以降の行を返しても使わず、フラグを付ける`() {
        val leaky =
            object : FeatureSource by source() {
                override fun pastRuns(
                    tenantId: TenantId,
                    kettoNum: String,
                    before: LocalDate?,
                    limit: Int,
                ): List<PastRun> = listOf(sameDay, future, PastRun("202512280611", LocalDate.of(2025, 12, 28), 1))
            }
        val v = PointInTimeFeatureBuilder(leaky).build(FeatureSchema.ALPHA_35, req())
        assertEquals(1.0, v.valueOf("past_n"))
        assertEquals(7.0, v.valueOf("days_since_last_run"))
        assertTrue(FeatureFlag.SOURCE_RETURNED_FUTURE_RUNS in v.meta.flags)
    }

    @Test
    fun `PIT と旧互換は同じ式で、過去走・age 以外の特徴量は一致する`() {
        val s = source()
        val legacy = LegacyFeatureBuilder(s, tenant, LocalDateTime.of(2026, 1, 4, 12, 0)).build(FeatureSchema.CALIBRATION_40, raceId, 3, 3.5, 2)
        val pit = PointInTimeFeatureBuilder(s).build(FeatureSchema.CALIBRATION_40, req())
        // 当日・未来の過去走が無いこの入力では、age（now=レース当日の昼 vs レース日）の小数以外は完全に一致する
        for (name in FeatureSchema.CALIBRATION_40.names) {
            if (name == "age_years") continue
            assertEquals(legacy.valueOf(name), pit.valueOf(name), name)
        }
    }

    @Test
    fun `カバレッジは旧式の評価順で計算される`() {
        assertEquals(0.5 * 1.0 + 0.3 * 1.0 + 0.2 * 1.0, FeatureMeta(true, true, 5).coverageScore())
        assertEquals(0.5 * 1.0 + 0.3 * 0.0 + 0.2 * (3.0 / 5.0), FeatureMeta(true, false, 3).coverageScore())
        assertEquals(0.0, FeatureMeta.EMPTY.coverageScore())
        assertEquals(0.5 * 1.0 + 0.3 * 1.0 + 0.2 * 1.0, FeatureMeta(true, true, 9).coverageScore())
    }

    @Test
    fun `スキーマと異なる長さのベクトルは作れず、非有限値も拒否する`() {
        assertFailsWith<IllegalArgumentException> { FeatureVector(FeatureSchema.ALPHA_35, DoubleArray(34), FeatureMeta.EMPTY) }
        assertFailsWith<IllegalArgumentException> { FeatureVector(FeatureSchema.ALPHA_35, DoubleArray(35) { Double.NaN }, FeatureMeta.EMPTY) }
    }
}
