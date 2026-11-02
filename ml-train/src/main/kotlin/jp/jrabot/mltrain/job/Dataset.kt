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
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * 学習用データセット（`export-dataset` の出力）の契約検証つき読込み（旧 tools/ml/dataset_contract.py と ml/src/ml_train/dataset.py の移植）。
 * **検証に失敗したデータセットでは学習を始めない**（[DatasetInvalid]・終了コード 1）。特徴量の名前・順序・hash は Kotlin の `FeatureSchema` が唯一の定義で、
 * ここでは manifest から読んで検証する（一覧を再実装しない）。
 *
 * 検証項目（ML_DESIGN §11）: manifest の正準形・自己ハッシュ・feature_schema の hash、列の構成と順序、CSV の SHA-256・バイト数・行数・LF、
 * 数値の有限性、ラベルが 0/1、split の整合（valid は train より後の日・同じ日を分けない・件数）、as_of が JST でレース日と同じ日、
 * `legacy_compat_used=false`、テナント・dataset_id が引数と一致、gzip 展開後の大きさが宣言を超えない（展開爆弾）、constant_features・positives の一致。
 */
class Dataset(
    val tenantId: Long,
    val datasetId: String,
    val manifest: JsonObject,
    val manifestSha256: String,
    val csvSha256: String,
    val schemaNames: List<String>,
    val schemaHash: String,
    val featureSchema: JsonObject,
    val kind: String,
    val raceId: List<String>,
    val raceDate: List<LocalDate>,
    val x: Array<DoubleArray>,
    val y: IntArray,
    val isTrain: BooleanArray,
) {
    val hasOdds: Boolean get() = "odds" in schemaNames

    fun rows(idx: IntArray): Array<DoubleArray> = Array(idx.size) { x[idx[it]] }

    fun labels(idx: IntArray): IntArray = IntArray(idx.size) { y[idx[it]] }

    companion object {
        val META_COLUMNS = listOf("tenant_id", "race_id", "umaban", "race_date", "as_of")
        val TAIL_COLUMNS = listOf("label", "split")
        const val MAX_CSV_BYTES = 1L shl 30 // manifest が宣言する非圧縮サイズの上限（1 GiB）

        /** スキーマの次元から種別を決める（calibration=40 で先頭が odds / alpha=35 で odds を含まない）。それ以外は未対応。 */
        fun kindForSchema(names: List<String>): String =
            when {
                names.size == 40 && names[0] == "odds" -> "calibration"
                names.size == 35 && "odds" !in names -> "alpha"
                else -> throw DatasetInvalid("未対応の feature_schema（${names.size} 次元）。学習できるのは calibration(40) と alpha(35)")
            }

        private fun require(
            cond: Boolean,
            message: () -> String,
        ) {
            if (!cond) throw DatasetInvalid(message())
        }

        private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: throw DatasetInvalid("manifest.$key がオブジェクトでない")

        private fun JsonObject.str(key: String): String = (this[key] as? JsonString)?.value ?: throw DatasetInvalid("manifest.$key が文字列でない")

        private fun JsonObject.long(key: String): Long = (this[key] as? JsonInt)?.value ?: throw DatasetInvalid("manifest.$key が整数でない")

        fun sha256Hex(bytes: ByteArray): String = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun boundedGunzip(
            gz: ByteArray,
            declared: Long,
        ): ByteArray {
            require(declared in 0..MAX_CSV_BYTES) { "manifest が宣言する CSV のサイズが不正・上限を超えている" }
            val out =
                try {
                    GZIPInputStream(ByteArrayInputStream(gz)).use { it.readNBytes(declared.toInt() + 1) }
                } catch (e: java.io.IOException) {
                    throw DatasetInvalid("gzip として展開できない: ${e.javaClass.simpleName}")
                }
            require(out.size <= declared) { "gzip の展開後サイズが manifest の宣言を超えている（展開爆弾の疑い）" }
            return out
        }

        fun load(
            csvGz: ByteArray,
            manifestRaw: ByteArray,
            tenantId: Long,
            datasetId: String,
        ): Dataset {
            val manifest =
                try {
                    StrictJson.parse(manifestRaw) as? JsonObject ?: throw DatasetInvalid("manifest がオブジェクトでない")
                } catch (e: jp.jrabot.domain.ml.json.JsonFormatException) {
                    throw DatasetInvalid("manifest が JSON として不正: ${e.message}")
                }
            require(manifest["dataset_schema_version"] == JsonInt(1)) { "未対応の dataset_schema_version" }
            require(manifestRaw.toString(Charsets.UTF_8).trimEnd('\n').toByteArray(Charsets.UTF_8).contentEquals(CanonicalJson.encodeBytes(manifest))) {
                "manifest が正準 JSON でない"
            }
            val manifestSha = manifest.str("sha256")
            require(CanonicalJson.sha256Hex(manifest.without("sha256")) == manifestSha) { "manifest の sha256 が内容と一致しない" }
            require(manifest.long("tenant_id") == tenantId) { "manifest の tenant_id が引数と違う（テナント越境）" }
            require(manifest.str("dataset_id") == datasetId) { "manifest の dataset_id が引数と違う" }
            val schema = manifest.obj("feature_schema")
            val schemaHash = schema.str("hash")
            require("sha256:" + CanonicalJson.sha256Hex(schema.without("hash")) == schemaHash) { "feature_schema の hash が内容と一致しない" }
            val names = (schema["names"] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: throw DatasetInvalid("feature_schema.names が文字列の配列でない") }
                ?: throw DatasetInvalid("feature_schema.names が無い")
            val kind = kindForSchema(names)
            val columns = META_COLUMNS + names + TAIL_COLUMNS
            val manifestColumns = (manifest["columns"] as? JsonArray)?.items?.map { (it as? JsonString)?.value }
            require(manifestColumns == columns) { "manifest.columns が『meta + feature_schema の順 + label + split』でない" }
            val builder = manifest.obj("builder")
            require(builder["mode"] == JsonString("point_in_time") && builder["legacy_compat_used"] == JsonBool(false)) {
                "旧互換モードで作られたデータセットは学習に使わない（H3/H8）"
            }
            val csvMeta = manifest.obj("csv")
            val text = boundedGunzip(csvGz, csvMeta.long("bytes"))
            val csvSha = csvMeta.str("sha256")
            require(sha256Hex(text) == csvSha) { "CSV の sha256 が manifest と一致しない" }
            require(text.size.toLong() == csvMeta.long("bytes")) { "CSV のバイト数が manifest と一致しない" }
            require(!text.contains('\r'.code.toByte()) && text.isNotEmpty() && text.last() == '\n'.code.toByte()) { "改行は LF で、末尾に改行が必要" }
            val str = text.toString(Charsets.UTF_8)
            require(!str.contains('"')) { "CSV に引用符がある（export-dataset は引用しない。想定外の形式）" }
            val lines = str.dropLast(1).split('\n')
            require(lines.isNotEmpty() && lines[0].split(',') == columns) { "CSV のヘッダが manifest.columns と違う" }
            val body = lines.drop(1).map { it.split(',') }
            require(body.size.toLong() == manifest.long("rows")) { "行数が manifest と一致しない" }

            val nFeat = names.size
            val f0 = META_COLUMNS.size
            val lead = manifest.obj("as_of_policy").long("lead_seconds")
            val x = Array(body.size) { DoubleArray(nFeat) }
            val y = IntArray(body.size)
            val isTrain = BooleanArray(body.size)
            val raceIds = ArrayList<String>(body.size)
            val raceDates = ArrayList<LocalDate>(body.size)
            val first = DoubleArray(nFeat)
            val constant = BooleanArray(nFeat) { true }
            val trainDates = HashSet<LocalDate>()
            val validDates = HashSet<LocalDate>()
            var positives = 0L
            var prev: Triple<String, String, Int>? = null
            for ((k, r) in body.withIndex()) {
                val line = k + 2
                require(r.size == columns.size) { "$line 行目: 列数が違う" }
                require(r[0].toLongOrNull() == tenantId) { "$line 行目: tenant_id が manifest と違う（テナント越境）" }
                val race = r[1]
                require(race.length == 12 && race.all { it in '0'..'9' }) { "$line 行目: race_id の形式" }
                val umaban = r[2].takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toIntOrNull()
                require(umaban != null && umaban >= 1) { "$line 行目: umaban" }
                val d =
                    runCatching { LocalDate.parse(r[3]) }.getOrNull()
                        ?: throw DatasetInvalid("$line 行目: race_date の形式")
                require(race.substring(0, 8) == d.format(DateTimeFormatter.BASIC_ISO_DATE)) { "$line 行目: race_id の先頭 8 桁とレース日が違う" }
                val ts = runCatching { OffsetDateTime.parse(r[4]) }.getOrNull() ?: throw DatasetInvalid("$line 行目: as_of にタイムゾーンが無い/形式が不正")
                require(ts.withOffsetSameInstant(ZoneOffset.ofHours(9)).toLocalDate() == d) { "$line 行目: as_of（JST の日付）がレース日と違う（未来の情報を見ている可能性）" }
                val key = Triple(r[3], race, checkNotNull(umaban))
                require(prev == null || compareKey(key, checkNotNull(prev)) > 0) { "$line 行目: 行が (レース日, race_id, 馬番) の昇順でない/重複" }
                prev = key
                for (j in 0 until nFeat) {
                    val v = r[f0 + j].toDoubleOrNull() ?: throw DatasetInvalid("$line 行目: ${names[j]} が数値でない")
                    require(v.isFinite()) { "$line 行目: ${names[j]} が有限でない" }
                    x[k][j] = v
                    if (k == 0) first[j] = v else if (v != first[j]) constant[j] = false
                }
                val label = r[r.size - 2]
                require(label == "0" || label == "1") { "$line 行目: label が 0/1 でない" }
                y[k] = label.toInt()
                positives += y[k]
                val split = r[r.size - 1]
                require(split == "train" || split == "valid") { "$line 行目: split が train/valid でない" }
                isTrain[k] = split == "train"
                (if (isTrain[k]) trainDates else validDates).add(d)
                raceIds += race
                raceDates += d
            }
            require(trainDates.intersect(validDates).isEmpty()) { "同じ日が train と valid に分かれている" }
            require(trainDates.isEmpty() || validDates.isEmpty() || trainDates.max() < validDates.min()) { "valid が train より前の日を含む（時系列の分割になっていない）" }
            val split = manifest.obj("split")
            val nValid = isTrain.count { !it }.toLong()
            require(split.long("n_train") == body.size - nValid && split.long("n_valid") == nValid) { "split の件数が manifest と違う" }
            val validStarts = split["valid_starts_at"]
            require(if (validDates.isEmpty()) validStarts == JsonNull else validStarts == JsonString(validDates.min().toString())) { "valid_starts_at が違う" }
            require(manifest.obj("label").long("positives") == positives) { "positives が manifest と違う" }
            val declaredConstant = (manifest.obj("quality")["constant_features"] as? JsonArray)?.items?.map { (it as? JsonString)?.value }
            val actualConstant = if (body.isEmpty()) emptyList() else names.filterIndexed { j, _ -> constant[j] }
            require(declaredConstant == actualConstant) { "constant_features が実データと違う" }
            require(lead > 0) { "as_of のリードが正でない" }
            return Dataset(tenantId, datasetId, manifest, manifestSha, csvSha, names, schemaHash, schema, kind, raceIds, raceDates, x, y, isTrain)
        }

        private fun compareKey(
            a: Triple<String, String, Int>,
            b: Triple<String, String, Int>,
        ): Int = compareValuesBy(a, b, { it.first }, { it.second }, { it.third })

        /** 構造的に学習・評価できない入力を拒否する（閾値ではない。サンプル数の下限は品質ゲートの設定で決める）。 */
        fun requireTrainable(ds: Dataset) {
            val nTrain = ds.isTrain.count { it }
            val nValid = ds.isTrain.size - nTrain
            if (nTrain == 0 || nValid == 0) throw DatasetInsufficient("train または valid が空")
            for ((name, want) in listOf("train" to true, "valid" to false)) {
                val idx = ds.isTrain.indices.filter { ds.isTrain[it] == want }
                val pos = idx.sumOf { ds.y[it] }
                if (pos == 0 || pos == idx.size) throw DatasetInsufficient("$name が片方のクラスしか含まない（positives=$pos, rows=${idx.size}）")
            }
            if (ds.raceDate.indices.filter { !ds.isTrain[it] }.map { ds.raceDate[it] }.toSet().size < 2) {
                throw DatasetInsufficient("valid の日数が 2 日未満（校正用と評価用に分けられない）")
            }
        }
    }
}

/** JSON の値の簡単な取り出し（manifest の数値）。 */
internal fun JsonValue?.asDouble(): Double? =
    when (this) {
        is JsonInt -> value.toDouble()
        is JsonFloat -> value
        else -> null
    }
