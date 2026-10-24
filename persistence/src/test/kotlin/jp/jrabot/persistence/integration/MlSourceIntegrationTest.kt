package jp.jrabot.persistence.integration

import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.dataset.DatasetExporter
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.features.FeatureFlag
import jp.jrabot.domain.ml.features.FeatureRequest
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.HistoryAppendOutcome
import jp.jrabot.domain.ml.features.HorseMasterHistoryStore
import jp.jrabot.domain.ml.features.HorseMasterVersion
import jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 学習用データセット・特徴量の入力（DB）。**point-in-time の安全性**を合成データで確認する: asOf より後のオッズ・馬マスタの版、レース当日以降の過去走、
 * 結果の無いレースを SQL が返さない／扱わない。テナント境界・権限（bot は読取りだけ、ingest は履歴の追記だけ）も確認する。
 */
@Tag("integration")
class MlSourceIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val bot = RepositoryHarness(db, user = "bot")
        private val ingest = RepositoryHarness(db, user = "ingest")

        @JvmStatic
        @AfterAll
        fun close() {
            bot.close()
            ingest.close()
        }
    }

    private val features: FeatureSource get() = bot.bean(FeatureSource::class.java)
    private val dataset: DatasetSource get() = bot.bean(DatasetSource::class.java)
    private val history: HorseMasterHistoryStore get() = ingest.bean(HorseMasterHistoryStore::class.java)

    private val now = "'2026-02-01 00:00:00.000000'"
    private val raceId = "202601040511" // 2026-01-04 東京 11R（post_time 15:25 JST = 06:25Z）
    private val raceId2 = "202601050511" // 結果が無いレース
    private val postTime = Instant.parse("2026-01-04T06:25:00Z")
    private val asOf = postTime.minusSeconds(600)
    private val ketto = "2020100001"

    @BeforeEach
    fun seed() {
        for (t in listOf("results", "horse_past_runs", "horse_master_history", "race_horses", "odds_snapshots", "races", "horses")) db.exec("DELETE FROM $t")
        db.exec("INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (2, 'second', $now)")
        for ((t, rid, date) in listOf(Triple(1, raceId, "2026-01-04"), Triple(1, raceId2, "2026-01-05"), Triple(2, raceId, "2026-01-04"))) {
            db.exec(
                "INSERT INTO races (tenant_id, race_id, race_date, venue_code, race_no, post_time, post_time_unknown, distance_m, track_type_cd, " +
                    "course_kubun_cd, tenko_cd, shiba_baba_cd, dirt_baba_cd, created_at, updated_at) " +
                    "VALUES ($t, '$rid', '$date', '05', 11, '15:25:00', 0, 1600, '11 ', '00', '1', '2', '3', $now, $now)",
            )
        }
        // 現在のマスタ行はレースの後（2026-03-01）に更新されている = 累積値は『レースの結果を含み得る』
        db.exec(
            "INSERT INTO horses (tenant_id, ketto_num, birth_date, pedigree_json, style_tendency_json, chaku_by_surface_json, " +
                "chaku_by_condition_json, chaku_by_distance_json, race_count, created_at, updated_at) " +
                "VALUES (1, '$ketto', '2022-04-01', '[{\"name\":\"sire\"},{\"name\":\" \"},{\"name\":\"dam\"}]', '[\"9\"]', '[[9,0,0]]', '[[9,0,0]]', '[[9,0,0]]', 9, $now, '2026-03-01 00:00:00.000000')",
        )
        db.exec(
            "INSERT INTO race_horses (tenant_id, race_id, umaban, ketto_num, created_at, updated_at) VALUES (1, '$raceId', 3, '$ketto', $now, $now), (1, '$raceId', 4, NULL, $now, $now), (1, '$raceId2', 3, '$ketto', $now, $now)",
        )
        // 過去走: 前走・前々走・レース当日(自分自身=ラベル)・未来・着順 0・日付なし
        for ((rid, date, finish) in listOf(
            Triple("202512280611", "'2025-12-28'", 1),
            Triple("202512070711", "'2025-12-07'", 4),
            Triple(raceId, "'2026-01-04'", 1),
            Triple("202601110511", "'2026-01-11'", 2),
            Triple("202511160811", "'2025-11-16'", 0),
            Triple("202510260511", "NULL", 3),
        )) {
            db.exec(
                "INSERT INTO horse_past_runs (tenant_id, ketto_num, race_id, run_date, finish, created_at, updated_at) VALUES (1, '$ketto', '$rid', $date, $finish, $now, $now)",
            )
        }
        // 単勝オッズ: 3 時間前 / 20 分前 / as_of(10 分前)より後の 5 分前
        for ((t, secondsBefore, odds) in listOf(Triple(1, 10800, 5.0), Triple(1, 1200, 4.0), Triple(1, 300, 99.0), Triple(2, 1200, 7.7))) {
            val ts =
                postTime
                    .minusSeconds(secondsBefore.toLong())
                    .toString()
                    .replace("T", " ")
                    .replace("Z", "")
            db.exec(
                "INSERT INTO odds_snapshots (tenant_id, race_id, market, observed_at, umaban, odds, popularity) VALUES ($t, '$raceId', 'win', '$ts', 3, $odds, 2)",
            )
        }
        db.exec(
            "INSERT INTO results (tenant_id, race_id, umaban, finish, created_at, updated_at) VALUES (1, '$raceId', 3, 1, $now, $now), (1, '$raceId', 4, 2, $now, $now)",
        )
    }

    private fun req(umaban: Int = 3) = FeatureRequest(T1, raceId, umaban, asOf, dataset.oddsHistory(T1, raceId, umaban, asOf))

    @Test
    fun `レース条件は JST の発走時刻を Instant にし、コードは前後の空白を除く（テナント境界あり）`() {
        val rc = assertNotNull(features.raceConditions(T1, raceId))
        assertEquals(postTime, rc.postTime)
        assertEquals(LocalDate.of(2026, 1, 4), rc.raceDate)
        assertEquals("11", rc.trackTypeCd)
        assertEquals(1600, rc.distanceM)
        assertNull(features.raceConditions(T1, "202601049999"))
        db.exec("UPDATE races SET post_time = NULL, post_time_unknown = 1 WHERE tenant_id = 2")
        assertNull(features.raceConditions(T2, raceId)!!.postTime)
        assertEquals(postTime, features.raceConditions(T1, raceId)!!.postTime) // T2 の更新は T1 に影響しない
    }

    @Test
    fun `過去走はレース日より前だけで、レース当日・未来・着順0・日付なしを DB が返さない`() {
        val runs = features.pastRuns(T1, ketto, LocalDate.of(2026, 1, 4), 5)
        assertEquals(listOf("202512280611", "202512070711"), runs.map { it.raceId })
        assertTrue(runs.all { it.runDate!!.isBefore(LocalDate.of(2026, 1, 4)) && it.finish > 0 })
        assertEquals(1, features.pastRuns(T1, ketto, LocalDate.of(2026, 1, 4), 1).size)
        assertEquals(emptyList(), features.pastRuns(T2, ketto, LocalDate.of(2026, 1, 4), 5)) // 他テナントの馬
        assertFailsWith<IllegalArgumentException> { features.pastRuns(T1, ketto, null, 0) }
    }

    @Test
    fun `オッズは as_of 以前の点だけを DB が返す（後のスナップショットは取り出さない）`() {
        val points = dataset.oddsHistory(T1, raceId, 3, asOf)
        assertEquals(listOf(5.0, 4.0), points.map { it.odds })
        assertTrue(points.all { !it.ts.isAfter(asOf) })
        assertEquals(listOf(5.0, 4.0, 99.0), dataset.oddsHistory(T1, raceId, 3, postTime).map { it.odds }) // 上限を上げれば出る = 条件は SQL にある
        assertEquals(emptyList(), dataset.oddsHistory(T1, raceId, 4, asOf))
        assertEquals(listOf(7.7), dataset.oddsHistory(T2, raceId, 3, asOf).map { it.odds }) // テナントごと
    }

    @Test
    fun `馬マスタは履歴が無い過去の時点では欠損で、現在値を使わない`() {
        val v = PointInTimeFeatureBuilder(features).build(FeatureSchema.CALIBRATION_40, req())
        assertEquals(0.0, v.valueOf("distance_place_rate"))
        assertEquals(0.0, v.valueOf("style_hint_code"))
        assertTrue(FeatureFlag.MASTER_NEWER_THAN_AS_OF in v.meta.flags)
        assertEquals(1.0, v.valueOf("has_horse_master"))
        assertEquals(2.0, v.valueOf("past_n")) // 前走・前々走だけ（当日・未来は除外）
        assertEquals(4.0, v.valueOf("odds")) // as_of 以前の最新（20 分前）
        assertTrue(v.values().takeLast(16).any { it != 0.0 }) // 血統は不変なので使う
    }

    @Test
    fun `馬マスタの履歴があれば asOf 以前の最新の版を使う（asOf より後の版は使わない）`() {
        val k1 = Instant.parse("2025-10-01T00:00:00Z")
        val k2 = Instant.parse("2025-12-30T00:00:00Z")
        val k3 = Instant.parse("2026-02-15T00:00:00Z") // レースの後

        fun ver(
            at: Instant,
            wins: Int,
        ) = HorseMasterVersion(at, "ingest", "[\"2\"]", "[[$wins,1,0]]", "[[$wins,1,0]]", "[[$wins,0,0,5]]", wins.toLong())
        assertEquals(HistoryAppendOutcome.CREATED, history.append(T1, ketto, ver(k1, 1)))
        assertEquals(HistoryAppendOutcome.CREATED, history.append(T1, ketto, ver(k2, 3)))
        assertEquals(HistoryAppendOutcome.CREATED, history.append(T1, ketto, ver(k3, 9)))
        val b = PointInTimeFeatureBuilder(features)

        val v = b.build(FeatureSchema.CALIBRATION_40, req())
        assertEquals(3.0 / 8.0, v.valueOf("distance_place_rate"), 1e-12) // k2 の版（3 勝 / 計 8）。k3(9 勝) でも現在値(9) でもない
        assertEquals(2.0, v.valueOf("style_hint_code"))
        assertFalse(FeatureFlag.MASTER_NEWER_THAN_AS_OF in v.meta.flags)

        val early = b.build(FeatureSchema.CALIBRATION_40, req().copy(asOf = Instant.parse("2025-11-01T00:00:00Z"), oddsHistory = emptyList()))
        // 発走後でない asOf ならよい（k1 の版: 1 勝 / 計 6）
        assertEquals(1.0 / 6.0, early.valueOf("distance_place_rate"), 1e-12)

        val before = b.build(FeatureSchema.CALIBRATION_40, req().copy(asOf = Instant.parse("2025-06-01T00:00:00Z"), oddsHistory = emptyList()))
        assertEquals(0.0, before.valueOf("distance_place_rate")) // どの版より前: 欠損
        assertTrue(FeatureFlag.MASTER_NEWER_THAN_AS_OF in before.meta.flags)
    }

    @Test
    fun `履歴は追記専用で、同じ版の再投入は UNCHANGED、内容が違えば CONFLICT（上書きしない）`() {
        val k = Instant.parse("2025-10-01T00:00:00.123456Z")
        val v = HorseMasterVersion(k, "ingest", "[\"2\"]", "[[1,1,0]]", null, "[[1]]", 3)
        assertEquals(HistoryAppendOutcome.CREATED, history.append(T1, ketto, v))
        assertEquals(HistoryAppendOutcome.UNCHANGED, history.append(T1, ketto, v))
        assertEquals(HistoryAppendOutcome.UNCHANGED, history.append(T1, ketto, v.copy(chakuBySurfaceJson = "[[ 1, 1, 0 ]]"))) // JSON の空白だけの違い
        assertEquals(HistoryAppendOutcome.CONFLICT, history.append(T1, ketto, v.copy(raceCount = 4)))
        val stored = history.versions(T1, ketto).single()
        assertEquals(k, stored.knownAt)
        assertEquals(3L, stored.raceCount)
        assertEquals(emptyList(), history.versions(T2, ketto)) // 他テナントの馬の履歴は見えない
        // 馬マスタが無い馬への追記は FK で失敗する
        assertFailsWith<RuntimeException> { history.append(T1, "9999999999", v) }
        // 更新・削除はできない（ingest も SELECT/INSERT のみ）。bot は INSERT もできない
        assertDeniedUpdateOrDelete()
        assertFailsWith<RuntimeException> { bot.bean(HorseMasterHistoryStore::class.java).append(T1, ketto, v.copy(knownAt = k.plusSeconds(1))) }
    }

    private fun assertDeniedUpdateOrDelete() {
        ingest.dataSource.connection.use { c ->
            c.createStatement().use { s ->
                assertDenied { s.executeUpdate("UPDATE horse_master_history SET race_count = 99") }
                assertDenied { s.executeUpdate("DELETE FROM horse_master_history") }
            }
        }
    }

    @Test
    fun `出走馬と確定着順では、結果の無いレースは finish が null で、勝敗が確定したものとして扱われない`() {
        val entries = dataset.entries(T1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        assertEquals(listOf("$raceId:3:1", "$raceId:4:2", "$raceId2:3:null"), entries.map { "${it.raceId}:${it.umaban}:${it.finish}" })
        assertEquals(
            listOf("$raceId:3", "$raceId:4"),
            dataset.entries(T1, LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 4)).map { "${it.raceId}:${it.umaban}" },
        )
        assertEquals(emptyList(), dataset.entries(T2, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))) // T2 に出走馬は無い
    }

    @Test
    fun `DB からのデータセットの書出しで、時点整合・ラベル・スキップの理由が揃う（H3 と H8 の再発防止）`() {
        val e =
            DatasetExporter(features, dataset).export(
                T1,
                DatasetId.of("it-calibration"),
                FeatureSchema.CALIBRATION_40,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                Instant.parse("2026-02-01T00:00:00Z"),
            )
        val lines = e.csv.trimEnd('\n').split('\n')
        val header = lines[0].split(',')
        val rows = lines.drop(1).map { it.split(',') }
        // umaban 3 は as_of 以前のオッズがある。umaban 4 は オッズが無い（calibration は出力しない）。raceId2 は結果が無い
        assertEquals(1, rows.size)
        val r = rows.single()

        fun col(name: String) = r[header.indexOf(name)]
        assertEquals("3", col("umaban"))
        assertEquals("1", col("label"))
        assertEquals("4.0", col("odds")) // as_of 後の 99.0 は使われない
        assertEquals("2.0", col("past_n")) // 当日・未来の過去走は使われない（ラベルリークなし）
        assertEquals(asOf.toString(), col("as_of"))
        assertEquals("0.0", col("distance_place_rate")) // 履歴が無いので現在値(9 勝)を使わない
        val skipped = (e.manifest["quality"] as JsonObject)["skipped"] as JsonObject
        assertEquals(JsonInt(1), skipped["no_odds_at_as_of"]) // umaban 4
        assertEquals(JsonInt(1), skipped["no_result"]) // raceId2 の馬（結果なし）
        assertEquals(JsonInt(0), skipped["result_incomplete"])
        val flags = (e.manifest["quality"] as JsonObject)["flags"] as JsonObject
        assertEquals(JsonInt(1), flags["master_newer_than_as_of"])
        assertContentEquals(
            e.csv.toByteArray(),
            java.util.zip
                .GZIPInputStream(e.csvGz().inputStream())
                .readAllBytes(),
        )
    }
}
