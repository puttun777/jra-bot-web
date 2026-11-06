package jp.jrabot.dashboard.api

import jakarta.servlet.http.HttpServletRequest
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.ml.registry.ModelCatalogEntry
import jp.jrabot.domain.ml.registry.ModelCatalogReader
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/** ML モデルの一覧の 1 行（R22。表示だけ）。成果物の URI・ハッシュは出さない（画面に不要）。 */
data class MlModelDto(
    val modelId: String,
    val kind: String,
    val status: String,
    val schemaVersion: Int,
    val featureSchemaHash: String,
    val trainedFrom: LocalDate?,
    val trainedTo: LocalDate?,
    val nTrain: Long?,
    val createdAt: Instant,
    val activatedAt: Instant?,
    val activatedBy: String?,
    val previousModelId: String?,
    /** `metrics_json` の最上位の有限の数だけ（キーの辞書順）。 */
    val metrics: Map<String, Double>,
    val gatePurpose: String?,
    val gateReasons: List<String>,
    /** `metrics_json` を読めたか（無い・壊れていれば false。画面は『指標を読めない』と表示する）。 */
    val metricsReadable: Boolean,
)

/**
 * ML モデルの一覧（R22。D-268）: `GET /api/v1/admin/ml-models`。`/api/v1/admin` の下なので読取りも ADMIN だけ（[ApiAuthFilter]）。
 * **表示だけ**で、有効化（ACTIVE への昇格）・退役の操作は持たない（品質ゲートの閾値が未承認のため本人の判断事項: COMPLETION R22 の B）。
 */
@RestController
@RequestMapping("/api/v1/admin")
class MlModelApi(
    private val catalog: ModelCatalogReader,
) {
    @GetMapping("/ml-models")
    fun list(request: HttpServletRequest): ResponseEntity<List<MlModelDto>> {
        val p = ApiAuthFilter.principalOf(request)
        val rows = catalog.recent(p.tenantId, LIMIT).map(::dto)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows)
    }

    companion object {
        const val LIMIT = 100

        fun dto(e: ModelCatalogEntry): MlModelDto {
            val r = e.record
            val parsed =
                e.metricsJson?.let {
                    try {
                        StrictJson.parse(it) as? JsonObject
                    } catch (_: JsonFormatException) {
                        null
                    }
                }
            val metrics =
                parsed
                    ?.fields
                    ?.mapNotNull { (k, v) ->
                        when (v) {
                            is JsonInt -> k to v.value.toDouble()
                            is JsonFloat -> v.value.takeIf { it.isFinite() }?.let { k to it }
                            else -> null
                        }
                    }?.sortedBy { it.first }
                    ?.toMap()
                    .orEmpty()
            return MlModelDto(
                modelId = r.modelId.value,
                kind = r.kind.json,
                status = r.status.name,
                schemaVersion = r.schemaVersion,
                featureSchemaHash = r.featureSchemaHash,
                trainedFrom = r.trainedFrom,
                trainedTo = r.trainedTo,
                nTrain = r.nTrain,
                createdAt = r.createdAt,
                activatedAt = r.activatedAt,
                activatedBy = r.activatedBy,
                previousModelId = r.previousModelId?.value,
                metrics = metrics,
                gatePurpose = (parsed?.get("gate_purpose") as? JsonString)?.value,
                gateReasons = (parsed?.get("gate_reasons") as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value }.orEmpty(),
                metricsReadable = parsed != null,
            )
        }
    }
}
