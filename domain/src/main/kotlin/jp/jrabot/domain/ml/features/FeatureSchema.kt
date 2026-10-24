package jp.jrabot.domain.ml.features

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString

/**
 * 特徴量スキーマ（ML_DESIGN §4.1）。**特徴量の順序と意味の唯一の定義**で、モデル JSON・データセット（`feature_schema.json`）・Python 学習側は
 * これを**再実装せず**に JSON として受け取る。
 *
 * JSON（`feature_schema`）:
 * ```json
 * {"version":1,"names":["…"],"pedigree_dim":16,"hash_algo":"md5-le32-mod16","missing_policy":"zero_with_flags","hash":"sha256:<64 桁の小文字 16 進>"}
 * ```
 * [hash] = `"sha256:" + hex(SHA-256(canonical(上のオブジェクトから hash を除いたもの)))`（正準化は [CanonicalJson]）。ML_DESIGN §5.2 の `feature_schema.hash`、
 * `ml_model.feature_schema_hash CHAR(71)` と同じ値。名前の順序が 1 つでも変わるとハッシュが変わる。
 *
 * ハッシュ特徴を持たないスキーマ（[MARKET_5]）は `pedigree_dim=0`・`hash_algo="none"`。
 */
class FeatureSchema private constructor(
    val version: Int,
    val names: List<String>,
    val pedigreeDim: Int,
    val hashAlgo: String,
    val missingPolicy: String,
) {
    val size: Int get() = names.size

    /** `hash` を除いた正準化の対象。 */
    fun bodyJson(): JsonObject =
        JsonObject.of(
            "version" to JsonInt(version.toLong()),
            "names" to JsonArray(names.map { JsonString(it) }),
            "pedigree_dim" to JsonInt(pedigreeDim.toLong()),
            "hash_algo" to JsonString(hashAlgo),
            "missing_policy" to JsonString(missingPolicy),
        )

    val hash: String = "sha256:" + CanonicalJson.sha256Hex(bodyJson())

    fun toJson(): JsonObject = bodyJson().with("hash", JsonString(hash))

    override fun equals(other: Any?): Boolean = other is FeatureSchema && other.hash == hash

    override fun hashCode(): Int = hash.hashCode()

    override fun toString(): String = "FeatureSchema(size=$size, $hash)"

    companion object {
        const val VERSION = 1
        const val HASH_ALGO_MD5 = "md5-le32-mod16"
        const val HASH_ALGO_NONE = "none"
        const val MISSING_POLICY = "zero_with_flags"
        private val HASH_FORMAT = Regex("sha256:[0-9a-f]{64}")

        fun isValidHash(text: String): Boolean = HASH_FORMAT.matches(text)

        fun create(
            names: List<String>,
            pedigreeDim: Int,
            hashAlgo: String,
            missingPolicy: String = MISSING_POLICY,
            version: Int = VERSION,
        ): FeatureSchema {
            require(names.isNotEmpty()) { "特徴量名が空" }
            require(names.toSet().size == names.size) { "特徴量名が重複している" }
            require(pedigreeDim >= 0) { "pedigree_dim は 0 以上" }
            return FeatureSchema(version, names.toList(), pedigreeDim, hashAlgo, missingPolicy)
        }

        /** 旧 `WinProbabilityModel.feature_names`（5 市場特徴量）。旧 5 次元モデル用（`legacy_compat`）。 */
        val MARKET_5: FeatureSchema by lazy { create(FeatureNames.MARKET_BASE, 0, HASH_ALGO_NONE) }

        /** calibration / legacy 学習の 40 次元（`get_feature_names()`）。 */
        val CALIBRATION_40: FeatureSchema by lazy {
            create(FeatureNames.calibration(), PedigreeHash.DEFAULT_DIM, HASH_ALGO_MD5)
        }

        /** alpha の 35 次元（`get_feature_names_alpha()`。odds / popularity 系を含まない）。 */
        val ALPHA_35: FeatureSchema by lazy { create(FeatureNames.alpha(), PedigreeHash.DEFAULT_DIM, HASH_ALGO_MD5) }

        /** このランタイムが特徴量を作れるスキーマ。モデルのスキーマがこのどれとも一致しなければロードを拒否する（暗黙の補正をしない）。 */
        val KNOWN: List<FeatureSchema> by lazy { listOf(MARKET_5, CALIBRATION_40, ALPHA_35) }

        /**
         * JSON から読む。形式が不正、または記載の `hash` が内容と一致しなければ [FeatureSchemaException]。
         * 未知のキーは拒否（黙って無視しない）。
         */
        fun fromJson(json: JsonObject): FeatureSchema {
            val allowed = setOf("version", "names", "pedigree_dim", "hash_algo", "missing_policy", "hash")
            val unknown = json.fields.keys - allowed
            if (unknown.isNotEmpty()) throw FeatureSchemaException("feature_schema に未知のキー: ${unknown.sorted()}")
            val version = (json["version"] as? JsonInt)?.value ?: throw FeatureSchemaException("feature_schema.version が整数でない")
            val names =
                (json["names"] as? JsonArray)?.items?.map {
                    (it as? JsonString)?.value ?: throw FeatureSchemaException("feature_schema.names に文字列以外")
                } ?: throw FeatureSchemaException("feature_schema.names が配列でない")
            val dim = (json["pedigree_dim"] as? JsonInt)?.value ?: throw FeatureSchemaException("feature_schema.pedigree_dim が整数でない")
            val algo = (json["hash_algo"] as? JsonString)?.value ?: throw FeatureSchemaException("feature_schema.hash_algo が文字列でない")
            val policy = (json["missing_policy"] as? JsonString)?.value ?: throw FeatureSchemaException("feature_schema.missing_policy が文字列でない")
            val declared = (json["hash"] as? JsonString)?.value ?: throw FeatureSchemaException("feature_schema.hash が文字列でない")
            if (version != VERSION.toLong()) throw FeatureSchemaException("未対応の feature_schema.version: $version")
            if (dim < 0 || dim > 4096) throw FeatureSchemaException("pedigree_dim が範囲外: $dim")
            if (names.isEmpty() || names.size > 4096) throw FeatureSchemaException("特徴量の数が範囲外: ${names.size}")
            if (names.toSet().size != names.size) throw FeatureSchemaException("特徴量名が重複している")
            if (!isValidHash(declared)) throw FeatureSchemaException("feature_schema.hash の形式が不正")
            val schema = FeatureSchema(version.toInt(), names, dim.toInt(), algo, policy)
            if (schema.hash != declared) throw FeatureSchemaException("feature_schema.hash が内容と一致しない")
            return schema
        }
    }
}

class FeatureSchemaException(
    message: String,
) : IllegalArgumentException(message)

/** 特徴量名（順序が契約）。旧 `get_feature_names()` / `get_feature_names_alpha()` と同じ。 */
object FeatureNames {
    val MARKET_BASE: List<String> = listOf("odds", "popularity", "log_odds", "inv_popularity", "odds_popularity_interaction")

    /** 馬・過去走・条件の 19 次元（calibration と alpha で共通）。 */
    val EXTRA_19: List<String> =
        listOf(
            "has_horse_master",
            "has_past_runs",
            "age_years",
            "past_n",
            "past_win_rate",
            "past_place_rate",
            "past_avg_finish",
            "style_hint_code",
            "distance_place_rate",
            "surface_place_rate",
            "condition_place_rate",
            "days_since_last_run",
            "distance_km",
            "is_turf",
            "is_dirt",
            "is_obstacle",
            "tenko_cd",
            "baba_cd",
            "course_kubun_cd",
        )

    fun pedigree(dim: Int = PedigreeHash.DEFAULT_DIM): List<String> = List(dim) { "pedigree_hash_$it" }

    fun calibration(dim: Int = PedigreeHash.DEFAULT_DIM): List<String> = MARKET_BASE + EXTRA_19 + pedigree(dim)

    fun alpha(dim: Int = PedigreeHash.DEFAULT_DIM): List<String> = EXTRA_19 + pedigree(dim)
}
