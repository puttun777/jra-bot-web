package jp.jrabot.domain.ml.dataset

import jp.jrabot.domain.decision.PythonCompat
import jp.jrabot.domain.ml.DatasetId
import jp.jrabot.domain.ml.features.FeatureFlag
import jp.jrabot.domain.ml.features.FeatureRequest
import jp.jrabot.domain.ml.features.FeatureSchema
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.LeakageException
import jp.jrabot.domain.ml.features.MasterTimeline
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.PointInTimeFeatureBuilder
import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.tenant.TenantId
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/** 学習用データの 1 行の元（出走馬と確定着順）。[finish] が null・0 以下（取消・除外・結果なし）の行はラベルが無いので出力しない。 */
data class DatasetEntry(
    val raceId: String,
    val umaban: Int,
    val finish: Int?,
)

/** データセットの元データの読取りポート（`:persistence` が実装する。テストと合成データは [InMemoryDatasetSource]）。 */
interface DatasetSource {
    /** レース日が [from]〜[to]（両端を含む）の出走馬。 */
    fun entries(
        tenantId: TenantId,
        from: LocalDate,
        to: LocalDate,
    ): List<DatasetEntry>

    /**
     * 馬ごとのオッズの履歴のうち **[asOf] 以前（含む）のものだけ**（順不同でよい）。供給元（DB）は asOf より後の点を**返さない**
     * （SQL の条件。呼び出し側のフィルタに頼らない）。
     */
    fun oddsHistory(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
        asOf: Instant,
    ): List<OddsPoint>
}

class InMemoryDatasetSource : DatasetSource {
    private val entries = HashMap<Long, MutableList<Pair<LocalDate, DatasetEntry>>>()
    private val odds = HashMap<Triple<Long, String, Int>, List<OddsPoint>>()

    fun put(
        tenantId: TenantId,
        raceDate: LocalDate,
        entry: DatasetEntry,
        history: List<OddsPoint>,
    ) {
        entries.getOrPut(tenantId.value) { ArrayList() }.add(raceDate to entry)
        odds[Triple(tenantId.value, entry.raceId, entry.umaban)] = history
    }

    override fun entries(
        tenantId: TenantId,
        from: LocalDate,
        to: LocalDate,
    ): List<DatasetEntry> = (entries[tenantId.value] ?: emptyList()).filter { it.first in from..to }.map { it.second }

    override fun oddsHistory(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
        asOf: Instant,
    ): List<OddsPoint> = (odds[Triple(tenantId.value, raceId, umaban)] ?: emptyList()).filter { !it.ts.isAfter(asOf) }
}

/** 行を出力しなかった理由（件数を manifest に記録する。黙って捨てない）。 */
enum class SkipReason { NO_RESULT, RESULT_INCOMPLETE, RACE_NOT_FOUND, RACE_DATE_UNKNOWN, POST_TIME_UNKNOWN, AS_OF_NOT_BEFORE_POST_TIME, NO_ODDS_AT_AS_OF }

class ExportedDataset(
    val datasetId: DatasetId,
    csvGz: ByteArray,
    val csvBytes: Int,
    /** 非圧縮 CSV の SHA-256（小文字 16 進）。学習メタデータの `dataset_sha256`。gzip のバイト列は zlib の版で変わり得るので正本にしない。 */
    val csvSha256: String,
    val manifest: JsonObject,
    val csv: String,
) {
    private val gz = csvGz.copyOf()

    fun csvGz(): ByteArray = gz.copyOf()
}

/**
 * 学習用データセットの書出し（ML_DESIGN §4.1・CONTRACTS §5.1、Stage 3-F2 への引き継ぎ契約）。
 * 特徴量は **point-in-time**（[PointInTimeFeatureBuilder]）だけで作る（旧互換の生成は学習に使わない: H3/H8）。**学習側で特徴量を再実装しない**。
 *
 * 出力（gzip CSV + manifest）の列と意味は `docs/ML_DESIGN.md` §11。要点:
 * - 列: `tenant_id,race_id,umaban,race_date,as_of`、特徴量（`feature_schema` の順）、`label`（1着=1）、`split`（`train` / `valid`）。
 * - `split`: レース日の昇順で、先頭の約 (1 − [validFraction]) の**日**を train、残りを valid（同じ日のレースは分けない。時系列。Python 側で再分割しない）。
 * - `as_of` = 発走時刻 − [asOfLead]。オッズは `as_of` 以前の最新だけ。calibration は `as_of` 以前のオッズが無い行を出力しない（旧 dataset_builder と同じ）。
 * - 行は (レース日, race_id, 馬番) の昇順。同じ入力から同じバイト列（非圧縮 CSV）になる。
 * 実データ・DB に触れない（[FeatureSource]・[DatasetSource] のポート越し）。ここでは合成入力で契約を検証する。
 */
class DatasetExporter(
    private val features: FeatureSource,
    private val source: DatasetSource,
    masterTimeline: MasterTimeline = MasterTimeline.ZERO_CUMULATIVE_WHEN_NEWER,
) {
    private val builder = PointInTimeFeatureBuilder(features, masterTimeline)
    private val timeline = masterTimeline

    private class Row(
        val raceDate: LocalDate,
        val raceId: String,
        val umaban: Int,
        val asOf: Instant,
        val values: DoubleArray,
        val label: Int,
        val hasMaster: Boolean,
        val hasPast: Boolean,
        val flags: Set<FeatureFlag>,
    )

    fun export(
        tenantId: TenantId,
        datasetId: DatasetId,
        schema: FeatureSchema,
        from: LocalDate,
        to: LocalDate,
        createdAt: Instant,
        validFraction: Double = 0.2,
        asOfLead: Duration = Duration.ofMinutes(10),
        producer: String = "export-dataset/0",
    ): ExportedDataset {
        require(schema == FeatureSchema.CALIBRATION_40 || schema == FeatureSchema.ALPHA_35) { "学習用データは calibration(40) または alpha(35) のスキーマ" }
        require(!from.isAfter(to)) { "期間が逆" }
        require(validFraction > 0.0 && validFraction < 1.0) { "validFraction は (0, 1)" }
        require(!asOfLead.isNegative && !asOfLead.isZero) { "asOfLead は正" }

        val skipped = java.util.EnumMap<SkipReason, Int>(SkipReason::class.java)

        fun skip(r: SkipReason) {
            skipped.merge(r, 1, Int::plus)
        }

        val rows = ArrayList<Row>()
        val entries = source.entries(tenantId, from, to)
        // 1 着の馬が結果に 1 頭もいないレースは、勝敗が確定したものとして扱わない（全員を負例にしない）。同着は 1 着が複数でもよい
        val racesWithWinner = entries.filter { (it.finish ?: 0) == 1 }.map { it.raceId }.toSet()
        for (e in entries) {
            val rc = features.raceConditions(tenantId, e.raceId)
            if (rc == null) {
                skip(SkipReason.RACE_NOT_FOUND)
                continue
            }
            val date = rc.raceDate
            if (date == null) {
                skip(SkipReason.RACE_DATE_UNKNOWN)
                continue
            }
            val post = rc.postTime
            if (post == null) {
                skip(SkipReason.POST_TIME_UNKNOWN)
                continue
            }
            val finish = e.finish
            if (finish == null || finish <= 0) {
                skip(SkipReason.NO_RESULT)
                continue
            }
            if (e.raceId !in racesWithWinner) {
                skip(SkipReason.RESULT_INCOMPLETE)
                continue
            }
            val asOf = post.minus(asOfLead)
            val v =
                try {
                    builder.build(schema, FeatureRequest(tenantId, e.raceId, e.umaban, asOf, source.oddsHistory(tenantId, e.raceId, e.umaban, asOf)))
                } catch (ex: LeakageException) {
                    skip(SkipReason.AS_OF_NOT_BEFORE_POST_TIME)
                    continue
                }
            if (schema == FeatureSchema.CALIBRATION_40 && FeatureFlag.ODDS_MISSING in v.meta.flags) {
                skip(SkipReason.NO_ODDS_AT_AS_OF)
                continue
            }
            rows += Row(date, e.raceId, e.umaban, asOf, v.values(), if (finish == 1) 1 else 0, v.meta.hasHorseMaster, v.meta.hasPastRuns, v.meta.flags)
        }
        rows.sortWith(compareBy<Row> { it.raceDate }.thenBy { it.raceId }.thenBy { it.umaban })

        // 時系列の分割: 日付の昇順で、先頭の約 (1 − validFraction) の日を train にする
        val dates = rows.map { it.raceDate }.distinct()
        val nTrainDates = if (dates.size <= 1) dates.size else maxOf(1, minOf(dates.size - 1, kotlin.math.ceil(dates.size * (1.0 - validFraction)).toInt()))
        val trainDates = dates.take(nTrainDates).toSet()
        val boundary = dates.getOrNull(nTrainDates) // valid の最初の日（無ければ valid なし）

        val header = listOf("tenant_id", "race_id", "umaban", "race_date", "as_of") + schema.names + listOf("label", "split")
        val sb = StringBuilder()
        sb.append(header.joinToString(",")).append('\n')
        for (r in rows) {
            sb
                .append(tenantId.value)
                .append(',')
                .append(r.raceId)
                .append(',')
                .append(r.umaban)
                .append(',')
                .append(r.raceDate)
                .append(',')
                .append(r.asOf)
            for (x in r.values) sb.append(',').append(PythonCompat.repr(x))
            sb
                .append(',')
                .append(r.label)
                .append(',')
                .append(if (r.raceDate in trainDates) "train" else "valid")
                .append('\n')
        }
        val csv = sb.toString()
        val csvBytes = csv.toByteArray(Charsets.UTF_8)
        val csvSha = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(csvBytes))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(csvBytes) } }.toByteArray()

        val nTrain = rows.count { it.raceDate in trainDates }
        val manifest =
            manifest(
                tenantId,
                datasetId,
                schema,
                from,
                to,
                createdAt,
                validFraction,
                asOfLead,
                producer,
                rows,
                nTrain,
                boundary,
                csvSha,
                csvBytes.size,
                skipped,
                header,
            )
        return ExportedDataset(datasetId, gz, csvBytes.size, csvSha, manifest, csv)
    }

    private fun manifest(
        tenantId: TenantId,
        datasetId: DatasetId,
        schema: FeatureSchema,
        from: LocalDate,
        to: LocalDate,
        createdAt: Instant,
        validFraction: Double,
        asOfLead: Duration,
        producer: String,
        rows: List<Row>,
        nTrain: Int,
        boundary: LocalDate?,
        csvSha: String,
        csvBytes: Int,
        skipped: Map<SkipReason, Int>,
        header: List<String>,
    ): JsonObject {
        val n = rows.size
        val constant =
            schema.names.indices
                .filter { i -> n > 0 && rows.all { it.values[i] == rows[0].values[i] } }
                .map { JsonString(schema.names[it]) }
        val flagCounts =
            FeatureFlag.entries.associate { f -> f.name.lowercase() to JsonInt(rows.count { f in it.flags }.toLong()) }
        val body =
            JsonObject.of(
                "dataset_schema_version" to JsonInt(1),
                "dataset_id" to JsonString(datasetId.value),
                "tenant_id" to JsonInt(tenantId.value),
                "created_at" to JsonString(createdAt.toString()),
                "producer" to JsonString(producer),
                "feature_schema" to schema.toJson(),
                "columns" to JsonArray(header.map { JsonString(it) }),
                "csv" to
                    JsonObject.of(
                        "encoding" to JsonString("utf-8"),
                        "newline" to JsonString("lf"),
                        "compression" to JsonString("gzip"),
                        "float_format" to JsonString("python-repr"),
                        "sha256" to JsonString(csvSha),
                        "bytes" to JsonInt(csvBytes.toLong()),
                    ),
                "rows" to JsonInt(n.toLong()),
                "range" to JsonObject.of("from" to JsonString(from.toString()), "to" to JsonString(to.toString())),
                "label" to JsonObject.of("definition" to JsonString("finish == 1"), "positives" to JsonInt(rows.count { it.label == 1 }.toLong())),
                "as_of_policy" to JsonObject.of("lead_seconds" to JsonInt(asOfLead.seconds), "odds" to JsonString("latest snapshot with ts <= as_of")),
                "split" to
                    JsonObject.of(
                        "method" to JsonString("time_ordered_by_race_date"),
                        "valid_fraction" to JsonFloat(validFraction),
                        "n_train" to JsonInt(nTrain.toLong()),
                        "n_valid" to JsonInt((n - nTrain).toLong()),
                        "valid_starts_at" to (boundary?.let { JsonString(it.toString()) } ?: JsonNull),
                    ),
                "builder" to
                    JsonObject.of(
                        "mode" to JsonString("point_in_time"),
                        "master_timeline" to JsonString(timeline.name),
                        "legacy_compat_used" to JsonBool(false),
                    ),
                "quality" to
                    JsonObject.of(
                        "has_horse_master_ratio" to JsonFloat(if (n == 0) 0.0 else rows.count { it.hasMaster }.toDouble() / n),
                        "has_past_runs_ratio" to JsonFloat(if (n == 0) 0.0 else rows.count { it.hasPast }.toDouble() / n),
                        "constant_features" to JsonArray(constant),
                        "flags" to JsonObject(flagCounts),
                        "skipped" to JsonObject(SkipReason.entries.associate { it.name.lowercase() to JsonInt((skipped[it] ?: 0).toLong()) }),
                    ),
            )
        return body.with("sha256", JsonString(CanonicalJson.sha256Hex(body)))
    }
}
