package jp.jrabot.domain.ml.dataset

import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.features.ChakuTable
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.InMemoryFeatureSource
import jp.jrabot.domain.ml.features.MasterTimeline
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 学習用データセットの書出し契約（合成データだけ）。`domain/src/test/resources/ml/dataset/` の合成データセットは Python 側（`tools/ml`）が独立に検証する。
 * 更新: `ML_UPDATE_FIXTURES=1 ./gradlew :domain:test --tests '*DatasetExporterTest'`（もう一度、環境変数なしで実行して確認）。
 */
class DatasetExporterTest {
    private val tenant = TenantId.of(1)
    private val update = System.getenv("ML_UPDATE_FIXTURES") == "1"
    private val created = Instant.parse("2026-02-01T00:00:00Z")

    /** 10 日 × 1 レース × 6 頭。結果は日ごとの巡回置換。 */
    private fun world(): Pair<InMemoryFeatureSource, InMemoryDatasetSource> {
        val f = InMemoryFeatureSource()
        val d = InMemoryDatasetSource()
        for (u in 1..6) {
            f.putHorse(
                tenant,
                HorseMaster(
                    "2020100%03d".format(u),
                    LocalDate.of(2021 + u % 3, 1 + u, 10),
                    "${u % 4}",
                    ChakuTable(listOf(listOf(u.toLong(), 1L, 1L, 2L))),
                    ChakuTable(listOf(listOf(1L, u.toLong(), 0L))),
                    ChakuTable.EMPTY,
                    listOf("sire$u", "dam$u", "ＡＢＣ", "dup", "dup"),
                    Instant.parse("2025-12-01T00:00:00Z"),
                ),
            )
            for (k in 1..u + 2) {
                f.putPastRun(
                    tenant,
                    "2020100%03d".format(u),
                    PastRun(
                        "2025%02d%02d0511".format(12 - k % 3, 1 + k),
                        LocalDate.of(
                            2025,
                            12 - k % 3,
                            1 + k,
                        ),
                        (u + k) % 7 + 1,
                    ),
                )
            }
        }
        for (day in 0 until 10) {
            val date = LocalDate.of(2026, 1, 4).plusDays(day.toLong())
            val raceId = "%04d%02d%02d0511".format(date.year, date.monthValue, date.dayOfMonth)
            val post = date.atTime(15, 25).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
            f.putRace(tenant, raceId, RaceConditions(date, post, 1600 + 200 * (day % 3), "11", "00", "1", "2", "3"))
            for (u in 1..6) {
                f.putEntry(tenant, raceId, u, "2020100%03d".format(u))
                val finish = (u + day) % 6 + 1
                val odds =
                    listOf(
                        OddsPoint(post.minusSeconds(3 * 3600), 2.0 + u * 1.5, u),
                        OddsPoint(post.minusSeconds(1200), 2.5 + u * 1.5 + day * 0.1, u),
                        OddsPoint(post.minusSeconds(300), 99.0, 18), // as_of(発走 10 分前) より後 → 使われない
                    )
                d.put(tenant, date, DatasetEntry(raceId, u, finish), odds)
            }
        }
        return f to d
    }

    private fun export(
        schema: FeatureSchema,
        f: InMemoryFeatureSource = world().first,
        d: InMemoryDatasetSource = world().second,
    ) = DatasetExporter(f, d).export(
        tenant,
        DatasetId.of("synthetic-${if (schema == FeatureSchema.ALPHA_35) "alpha" else "calibration"}"),
        schema,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31),
        created,
    )

    @Test
    fun `列は meta・特徴量(スキーマの順)・label・split で、行は日付・race_id・馬番の昇順`() {
        val e = export(FeatureSchema.CALIBRATION_40)
        val lines = e.csv.trimEnd('\n').split('\n')
        val header = lines[0].split(',')
        assertEquals(listOf("tenant_id", "race_id", "umaban", "race_date", "as_of"), header.take(5))
        assertEquals(FeatureSchema.CALIBRATION_40.names, header.subList(5, 45))
        assertEquals(listOf("label", "split"), header.takeLast(2))
        assertEquals(60, lines.size - 1)
        val keys = lines.drop(1).map { it.split(',').let { c -> Triple(c[3], c[1], c[2].toInt()) } }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), keys)
        assertTrue(lines.drop(1).all { it.split(',').size == header.size })
    }

    @Test
    fun `train と valid は日付で分かれ、同じ日のレースは分割されない（時系列・リークなし）`() {
        val e = export(FeatureSchema.CALIBRATION_40)
        val rows =
            e.csv
                .trimEnd('\n')
                .split('\n')
                .drop(1)
                .map { it.split(',') }
        val trainDates = rows.filter { it.last() == "train" }.map { it[3] }.toSet()
        val validDates = rows.filter { it.last() == "valid" }.map { it[3] }.toSet()
        assertEquals(8, trainDates.size)
        assertEquals(2, validDates.size)
        assertTrue(trainDates.all { t -> validDates.all { v -> t < v } }, "valid は常に train より後の日")
        assertTrue(trainDates.intersect(validDates).isEmpty())
        val split = e.manifest["split"] as JsonObject
        assertEquals(JsonInt(48), split["n_train"])
        assertEquals(JsonInt(12), split["n_valid"])
        assertEquals(JsonString(validDates.min()), split["valid_starts_at"])
    }

    @Test
    fun `ラベルは 1 着のみ 1 で、各レースにちょうど 1 頭`() {
        val e = export(FeatureSchema.ALPHA_35)
        val rows =
            e.csv
                .trimEnd('\n')
                .split('\n')
                .drop(1)
                .map { it.split(',') }
        val byRace = rows.groupBy { it[1] }
        assertEquals(10, byRace.size)
        assertTrue(byRace.values.all { r -> r.count { it[it.size - 2] == "1" } == 1 })
    }

    @Test
    fun `オッズは as_of 以前だけを使い、as_of は発走 10 分前`() {
        val e = export(FeatureSchema.CALIBRATION_40)
        val header =
            e.csv
                .lineSequence()
                .first()
                .split(',')
        val row =
            e.csv
                .lineSequence()
                .drop(1)
                .first()
                .split(',')
        val odds = row[header.indexOf("odds")].toDouble()
        assertEquals(4.0, odds, "as_of(発走 10 分前)以前の最新 = 発走 20 分前のスナップショット 2.5 + 1.5 + 0.0")
        assertTrue(odds < 10.0, "発走 5 分前の 99.0 を使っていない")
        assertEquals("2026-01-04T06:15:00Z", row[4])
    }

    @Test
    fun `出力しなかった行は理由別に manifest へ記録する（黙って捨てない）`() {
        val (f, d) = world()
        d.put(tenant, LocalDate.of(2026, 1, 4), DatasetEntry("202601040511", 7, 0), emptyList()) // 結果なし
        d.put(tenant, LocalDate.of(2026, 1, 4), DatasetEntry("202601040511", 8, 3), emptyList()) // オッズなし（calibration は出力しない）
        f.putEntry(tenant, "202601040511", 8, "2020100001")
        d.put(tenant, LocalDate.of(2026, 1, 5), DatasetEntry("202601059999", 1, 1), emptyList()) // レース条件なし
        val e = export(FeatureSchema.CALIBRATION_40, f, d)
        val skipped = (e.manifest["quality"] as JsonObject)["skipped"] as JsonObject
        assertEquals(JsonInt(1), skipped["no_result"])
        assertEquals(JsonInt(1), skipped["no_odds_at_as_of"])
        assertEquals(JsonInt(1), skipped["race_not_found"])
        assertEquals(JsonInt(60), e.manifest["rows"])
        // alpha はオッズを使わないので、オッズなしの行も出力する
        val a = export(FeatureSchema.ALPHA_35, f, d)
        assertEquals(JsonInt(61), a.manifest["rows"])
    }

    @Test
    fun `発走時刻が不明のレースと asOf が発走後になる入力は出力しない`() {
        val (f, d) = world()
        f.putRace(tenant, "202601050511", RaceConditions(LocalDate.of(2026, 1, 5), null, 1600, "11", "", "", "", ""))
        val e = export(FeatureSchema.ALPHA_35, f, d)
        val skipped = (e.manifest["quality"] as JsonObject)["skipped"] as JsonObject
        assertEquals(JsonInt(6), skipped["post_time_unknown"])
        assertEquals(JsonInt(54), e.manifest["rows"])
    }

    @Test
    fun `races 行が asOf より後に更新されたレースは 天候と馬場を復元できないので学習対象から外す 0 件なら manifest に出さない`() {
        val (f, d) = world()
        val ok = export(FeatureSchema.ALPHA_35, f, d)
        assertTrue(((ok.manifest["quality"] as JsonObject)["skipped"] as JsonObject).fields.keys.none { it == "conditions_newer_than_as_of" })
        // 1 日だけ、行が発走の後（as_of=発走 10 分前より後）に更新された
        val date = LocalDate.of(2026, 1, 6)
        val post = date.atTime(15, 25).atZone(java.time.ZoneId.of("Asia/Tokyo")).toInstant()
        f.putRace(tenant, "202601060511", RaceConditions(date, post, 1600, "11", "00", "1", "2", "3", post.plusSeconds(3600)))
        val e = export(FeatureSchema.ALPHA_35, f, d)
        val skipped = (e.manifest["quality"] as JsonObject)["skipped"] as JsonObject
        assertEquals(JsonInt(6), skipped["conditions_newer_than_as_of"])
        assertEquals(JsonInt(54), e.manifest["rows"])
    }

    @Test
    fun `manifest は sha256 の自己検証ができ、特徴量スキーマと CSV の sha256 を持つ`() {
        val e = export(FeatureSchema.CALIBRATION_40)
        val m = e.manifest
        assertEquals(JsonString(CanonicalJson.sha256Hex(m.without("sha256"))), m["sha256"])
        assertEquals(FeatureSchema.CALIBRATION_40.toJson(), m["feature_schema"])
        val csv = m["csv"] as JsonObject
        assertEquals(JsonString(e.csvSha256), csv["sha256"])
        assertEquals(
            e.csvSha256,
            CanonicalJson.hex(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(e.csv.toByteArray()),
            ),
        )
        assertEquals(e.csv, String(GZIPInputStream(ByteArrayInputStream(e.csvGz())).readAllBytes(), Charsets.UTF_8))
        val builder = m["builder"] as JsonObject
        assertEquals(JsonString("point_in_time"), builder["mode"])
        assertEquals(
            jp.jrabot.domain.ml.json
                .JsonBool(false),
            builder["legacy_compat_used"],
        )
    }

    @Test
    fun `同じ入力から同じバイト列になる（決定的）`() {
        assertEquals(export(FeatureSchema.CALIBRATION_40).csv, export(FeatureSchema.CALIBRATION_40).csv)
        assertEquals(export(FeatureSchema.ALPHA_35).manifest, export(FeatureSchema.ALPHA_35).manifest)
    }

    @Test
    fun `馬マスタが asOf より新しいときの累積値は 0 でフラグの件数が manifest に残る`() {
        val (f, d) = world()
        val newer = f.horse(tenant, "2020100001")!!.copy(updatedAt = Instant.parse("2026-03-01T00:00:00Z"))
        f.putHorse(tenant, newer)
        val e = export(FeatureSchema.ALPHA_35, f, d)
        val flags = (e.manifest["quality"] as JsonObject)["flags"] as JsonObject
        assertEquals(JsonInt(10), flags["master_newer_than_as_of"]) // 馬 1 の 10 レース分
        val trust =
            DatasetExporter(f, d, MasterTimeline.TRUST_LATEST).export(
                tenant,
                DatasetId.of("t"),
                FeatureSchema.ALPHA_35,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                created,
            )
        assertEquals(JsonInt(0), ((trust.manifest["quality"] as JsonObject)["flags"] as JsonObject)["master_newer_than_as_of"])
        assertEquals(JsonString("TRUST_LATEST"), (trust.manifest["builder"] as JsonObject)["master_timeline"])
    }

    @Test
    fun `引数の検証`() {
        val (f, d) = world()
        val x = DatasetExporter(f, d)
        for (bad in listOf<() -> Unit>(
            { x.export(tenant, DatasetId.of("a"), FeatureSchema.MARKET_5, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), created) },
            { x.export(tenant, DatasetId.of("a"), FeatureSchema.ALPHA_35, LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 2), created) },
            { x.export(tenant, DatasetId.of("a"), FeatureSchema.ALPHA_35, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), created, validFraction = 1.0) },
            {
                x.export(
                    tenant,
                    DatasetId.of("a"),
                    FeatureSchema.ALPHA_35,
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 2),
                    created,
                    asOfLead = Duration.ZERO,
                )
            },
        )) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { bad() }
        }
    }

    // ---- Python 側が独立に検証する合成データセット ----

    @Test
    fun `合成データセットの契約ファイルは Kotlin の出力と一致する`() {
        for ((schema, name) in listOf(FeatureSchema.CALIBRATION_40 to "synthetic-calibration", FeatureSchema.ALPHA_35 to "synthetic-alpha")) {
            val e = export(schema)
            val manifest = CanonicalJson.encodeBytes(e.manifest) + "\n".toByteArray()
            if (update) {
                val dir = Path.of("src/test/resources/ml/dataset")
                Files.createDirectories(dir)
                Files.write(dir.resolve("$name.csv"), e.csv.toByteArray(Charsets.UTF_8)) // 実データ混入の防止で *.csv.gz は Git に入れない（hygiene）。非圧縮の合成 CSV を置く
                Files.write(dir.resolve("$name.manifest.json"), manifest)
                continue
            }
            val csv = javaClass.getResourceAsStream("/ml/dataset/$name.csv")?.readAllBytes() ?: error("fixture が無い: $name.csv")
            assertEquals(e.csv, String(csv, Charsets.UTF_8), "$name.csv の中身")
            val mf = javaClass.getResourceAsStream("/ml/dataset/$name.manifest.json")?.readAllBytes() ?: error("fixture が無い: $name.manifest.json")
            assertEquals(String(manifest, Charsets.UTF_8), String(mf, Charsets.UTF_8), "$name.manifest.json")
        }
    }
}
