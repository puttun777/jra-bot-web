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
        // ACTIVE の行を先頭に、続けて新しい順の最大 LIMIT 件（ModelCatalogReader）。画面は件数の上限を表示する
        val rows = catalog.recent(p.tenantId, LIMIT).map(::dto)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows)
    }

    companion object {
        const val LIMIT = 100

        /** 指標の JSON の大きさの上限（これより大きければ読まない。100 行を無制限に解析しない）。 */
        const val MAX_METRICS_CHARS = 65_536

        fun dto(e: ModelCatalogEntry): MlModelDto {
            val parsed =
                e.metricsJson?.takeIf { it.length <= MAX_METRICS_CHARS }?.let {
                    try {
                        StrictJson.parse(it) as? JsonObject
                    } catch (_: JsonFormatException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null // 解析器の将来の変更でも一覧の 1 行の失敗で済ませる
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
                modelId = e.modelId,
                kind = e.kind,
                status = e.status,
                schemaVersion = e.schemaVersion,
                featureSchemaHash = e.featureSchemaHash,
                trainedFrom = e.trainedFrom,
                trainedTo = e.trainedTo,
                nTrain = e.nTrain,
                createdAt = e.createdAt,
                activatedAt = e.activatedAt,
                activatedBy = e.activatedBy,
                previousModelId = e.previousModelId,
                metrics = metrics,
                gatePurpose = (parsed?.get("gate_purpose") as? JsonString)?.value,
                gateReasons = (parsed?.get("gate_reasons") as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value }.orEmpty(),
                metricsReadable = parsed != null,
            )
        }
    }
}
