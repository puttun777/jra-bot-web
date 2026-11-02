package jp.jrabot.mltrain.job

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 試験用の合成データセット（旧 ml/tests/synth.py の移植。`DatasetExporter` と同じ出力契約: ML_DESIGN §11.7）。実データ・実 DB は使わない。
 * 乱数は java.util.Random（旧版の numpy とは値が違う。この試験は値ではなく不変条件を見る）。
 */
object SynthDataset {
    class Row(
        val raceDate: LocalDate,
        var raceId: String,
        val umaban: Int,
        var asOf: String,
        val values: DoubleArray,
        var label: Int,
    )

    private val RES: Path = Path.of("..", "domain", "src", "test", "resources", "ml")

    fun schema(kind: String): JsonObject {
        val name = mapOf("calibration" to "calibration40", "alpha" to "alpha35").getValue(kind)
        return StrictJson.parse(Files.readAllBytes(RES.resolve("feature_schema.$name.json"))) as JsonObject
    }

    /** Python の repr(float)（最短の 10 進表現。10 進指数が -4 以上 16 未満なら位取り、それ以外は `1.5e-05` 形式）。 */
    fun repr(v: Double): String {
        require(v.isFinite())
        if (v == 0.0) return if (1.0 / v < 0) "-0.0" else "0.0"
        val bd = java.math.BigDecimal(v.toString()).stripTrailingZeros()
        val exp = bd.precision() - bd.scale() - 1
        if (exp in -4..15) {
            val plain = bd.toPlainString()
            return if ('.' in plain) plain else "$plain.0"
        }
        val digits = bd.unscaledValue().abs().toString()
        val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val sign = if (bd.signum() < 0) "-" else ""
        return sign + mant + "e" + (if (exp < 0) "-" else "+") + "%02d".format(kotlin.math.abs(exp))
    }

    fun make(
        kind: String = "calibration",
        tenantId: Long = 1,
        datasetId: String = "ds-synth-1",
        nDays: Int = 24,
        racesPerDay: Int = 6,
        horses: Int = 8,
        seed: Long = 7,
        signal: Double = 1.2,
        validFraction: Double = 0.25,
        start: LocalDate = LocalDate.of(2026, 1, 3),
        oneClass: Boolean = false,
        mutate: ((MutableList<Row>, MutableMap<String, JsonValue>) -> Unit)? = null,
    ): Pair<ByteArray, ByteArray> {
        val rng = Random(seed)
        val sch = schema(kind)
        val names = (sch["names"] as JsonArray).items.map { (it as JsonString).value }
        val rows = ArrayList<Row>()
        for (d in 0 until nDays) {
            val day = start.plusDays(d.toLong())
            for (r in 1..racesPerDay) {
                val s = DoubleArray(horses) { rng.nextGaussian() }
                val pTrue = s.map { exp(signal * it) }.let { e -> e.map { it / e.sum() } }
                var u = rng.nextDouble()
                var winner = horses - 1
                for (h in 0 until horses) {
                    u -= pTrue[h]
                    if (u <= 0) {
                        winner = h
                        break
                    }
                }
                if (oneClass) winner = -1
                val pMkt = s.map { exp(0.8 * it + 0.5 * rng.nextGaussian()) }.let { e -> e.map { it / e.sum() } }
                val odds = pMkt.map { max(1.1, round(0.8 / it * 10) / 10) }
                val order = odds.indices.sortedBy { odds[it] }
                val pop = DoubleArray(horses).also { p -> order.forEachIndexed { rank, h -> p[h] = (rank + 1).toDouble() } }
                for (h in 0 until horses) {
                    val vals =
                        mutableMapOf(
                            "odds" to odds[h],
                            "popularity" to pop[h],
                            "log_odds" to ln(max(odds[h], 0.1)),
                            "inv_popularity" to 1.0 / max(pop[h], 1.0),
                            "odds_popularity_interaction" to odds[h] * pop[h],
                            "has_horse_master" to 1.0,
                            "has_past_runs" to 1.0,
                            "age_years" to (3 + h % 4).toDouble(),
                            "past_n" to 5.0,
                            "past_win_rate" to clip(0.1 + 0.05 * s[h] + 0.03 * rng.nextGaussian()),
                            "past_place_rate" to clip(0.3 + 0.08 * s[h] + 0.05 * rng.nextGaussian()),
                            "past_avg_finish" to 5.0 - s[h] + 0.5 * rng.nextGaussian(),
                            "days_since_last_run" to (14 + 7 * (h % 5)).toDouble(),
                            "distance_km" to if (r % 2 == 1) 1.6 else 2.0,
                            "is_turf" to if (r % 2 == 1) 1.0 else 0.0,
                            "is_dirt" to if (r % 2 == 1) 0.0 else 1.0,
                            "distance_place_rate" to clip(0.3 + 0.05 * s[h]),
                        )
                    for (k in 0 until 16) vals["pedigree_hash_$k"] = if (k % 2 == 0) (((h * 7 + k * 3) % 5) - 2).toDouble() else 0.0
                    rows +=
                        Row(
                            day,
                            day.format(DateTimeFormatter.BASIC_ISO_DATE) + "05" + "%02d".format(r),
                            h + 1,
                            "${day}T05:50:00Z",
                            DoubleArray(names.size) { vals[names[it]] ?: 0.0 },
                            if (h == winner) 1 else 0,
                        )
                }
            }
        }
        val extra = LinkedHashMap<String, JsonValue>()
        mutate?.invoke(rows, extra)
        return finalize(rows, sch, names, tenantId, datasetId, validFraction, extra)
    }

    private fun clip(x: Double): Double = min(1.0, max(0.0, x))

    fun sha256(b: ByteArray): String = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(b))

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun finalize(
        rows0: List<Row>,
        sch: JsonObject,
        names: List<String>,
        tenantId: Long,
        datasetId: String,
        validFraction: Double,
        extra: Map<String, JsonValue>,
    ): Pair<ByteArray, ByteArray> {
        val rows = rows0.sortedWith(compareBy<Row>({ it.raceDate }, { it.raceId }, { it.umaban }))
        val dates = rows.map { it.raceDate }.toSortedSet().toList()
        val nTrainDays = if (dates.size > 1) max(1, min(dates.size - 1, ceil(dates.size * (1 - validFraction)).toInt())) else dates.size
        val trainDates = dates.subList(0, nTrainDays).toSet()
        val header = listOf("tenant_id", "race_id", "umaban", "race_date", "as_of") + names + listOf("label", "split")
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (r in rows) {
            val split = if (r.raceDate in trainDates) "train" else "valid"
            sb.append(listOf(tenantId.toString(), r.raceId, r.umaban.toString(), r.raceDate.toString(), r.asOf).joinToString(","))
            for (v in r.values) sb.append(',').append(repr(v))
            sb.append(',').append(r.label).append(',').append(split).append('\n')
        }
        val text = sb.toString().toByteArray()
        val n = rows.size
        val validRows = rows.filter { it.raceDate !in trainDates }
        val constant = if (n == 0) emptyList() else names.filterIndexed { j, _ -> rows.all { it.values[j] == rows[0].values[j] } }
        fun s(v: String) = JsonString(v)
        fun i(v: Long) = JsonInt(v)
        val zeroFlags = JsonObject.of("master_newer_than_as_of" to i(0), "odds_missing" to i(0), "source_returned_future_runs" to i(0))
        val skipped =
            JsonObject(
                listOf("as_of_not_before_post_time", "no_odds_at_as_of", "no_result", "result_incomplete", "post_time_unknown", "race_date_unknown", "race_not_found")
                    .associateWith { i(0) },
            )
        val m = LinkedHashMap<String, JsonValue>()
        m["dataset_schema_version"] = i(1)
        m["dataset_id"] = s(datasetId)
        m["tenant_id"] = i(tenantId)
        m["created_at"] = s("2026-02-01T00:00:00Z")
        m["producer"] = s("export-dataset/0")
        m["feature_schema"] = sch
        m["columns"] = JsonArray(header.map(::s))
        m["csv"] =
            JsonObject.of(
                "encoding" to s("utf-8"),
                "newline" to s("lf"),
                "compression" to s("gzip"),
                "float_format" to s("python-repr"),
                "sha256" to s(sha256(text)),
                "bytes" to i(text.size.toLong()),
            )
        m["rows"] = i(n.toLong())
        m["range"] = JsonObject.of("from" to s(dates.first().toString()), "to" to s(dates.last().toString()))
        m["label"] = JsonObject.of("definition" to s("finish == 1"), "positives" to i(rows.sumOf { it.label }.toLong()))
        m["as_of_policy"] = JsonObject.of("lead_seconds" to i(600), "odds" to s("latest snapshot with ts <= as_of"))
        m["split"] =
            JsonObject.of(
                "method" to s("time_ordered_by_race_date"),
                "valid_fraction" to JsonFloat(validFraction),
                "n_train" to i((n - validRows.size).toLong()),
                "n_valid" to i(validRows.size.toLong()),
                "valid_starts_at" to (validRows.minOfOrNull { it.raceDate }?.let { s(it.toString()) } ?: JsonNull),
            )
        m["builder"] = JsonObject.of("mode" to s("point_in_time"), "master_timeline" to s("ZERO_CUMULATIVE_WHEN_NEWER"), "legacy_compat_used" to JsonBool(false))
        m["quality"] =
            JsonObject.of(
                "has_horse_master_ratio" to JsonFloat(1.0),
                "has_past_runs_ratio" to JsonFloat(1.0),
                "constant_features" to JsonArray(constant.map(::s)),
                "flags" to zeroFlags,
                "skipped" to skipped,
            )
        m.putAll(extra)
        val manifest = JsonObject(m)
        val signed = manifest.with("sha256", s(CanonicalJson.sha256Hex(manifest)))
        return gzip(text) to (CanonicalJson.encodeBytes(signed) + "\n".toByteArray())
    }
}
