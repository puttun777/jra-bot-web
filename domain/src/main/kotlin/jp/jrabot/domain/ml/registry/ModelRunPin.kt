package jp.jrabot.domain.ml.registry

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Instant

/**
 * bot-run の開始時にモデルを**固定**する（ML_DESIGN §6「切替時の整合性」）。run の間に管理者が別のモデルを ACTIVE にしても、この run は固定した model_id を使い続ける
 * （run 単位で不変。mtime 監視の自動再読込はしない）。使った `model_id` 群は `race_evaluations` に保存する（T3.8）。
 *
 * T3.6-A は契約と部品まで。bot-run の本格的な配線は T3.8（この型を run の開始時に 1 回だけ [capture] する）。
 */
class ModelRunPin private constructor(
    val tenantId: TenantId,
    val pinnedAt: Instant,
    records: Map<ModelKind, ModelRecord>,
) {
    private val records: Map<ModelKind, ModelRecord> = LinkedHashMap(records)

    fun record(kind: ModelKind): ModelRecord? = records[kind]

    /** 固定した model_id（記録用。種別 → ID）。 */
    fun modelIds(): Map<ModelKind, ModelId> = records.mapValues { it.value.modelId }

    companion object {
        /**
         * 各種別の ACTIVE を 1 回だけ読んで固定する。レジストリが返した行が ACTIVE でない・別のテナント・別の種別なら（実装の不具合として）拒否する。
         */
        fun capture(
            registry: ModelRegistryReader,
            tenantId: TenantId,
            clock: Clock,
        ): ModelRunPin {
            val found = LinkedHashMap<ModelKind, ModelRecord>()
            for (kind in ModelKind.entries) {
                val r = registry.active(tenantId, kind) ?: continue
                require(r.tenantId == tenantId) { "他テナントのモデルが返された" }
                require(r.kind == kind) { "種別が違うモデルが返された" }
                require(r.status == ModelStatus.ACTIVE) { "ACTIVE でないモデルが返された" }
                found[kind] = r
            }
            return ModelRunPin(tenantId, clock.now(), found)
        }
    }
}
