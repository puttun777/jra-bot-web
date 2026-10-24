package jp.jrabot.domain.ml.store

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.tenant.TenantId

/**
 * 合成データ・単体テスト用のレジストリ。**昇格の操作は持たない**（[seed] は行をそのまま置くテスト用の入口で、ACTIVE の行を置く＝管理者の昇格が済んだ状態を作る。
 * 同じ（テナント, 種別）に ACTIVE を 2 つ置けない: DB の `UNIQUE(tenant_id, kind, active_flag)` と同じ）。
 */
class InMemoryModelRegistry : ModelRegistryReader {
    private val rows = LinkedHashMap<Pair<Long, String>, ModelRecord>()

    fun seed(record: ModelRecord) {
        if (record.status == ModelStatus.ACTIVE) {
            require(rows.values.none { it.tenantId == record.tenantId && it.kind == record.kind && it.isActive && it.modelId != record.modelId }) {
                "同じ種別に ACTIVE が 2 つ"
            }
        }
        rows[record.tenantId.value to record.modelId.value] = record
    }

    override fun find(
        tenantId: TenantId,
        modelId: ModelId,
    ): ModelRecord? = rows[tenantId.value to modelId.value]

    override fun active(
        tenantId: TenantId,
        kind: ModelKind,
    ): ModelRecord? = rows.values.firstOrNull { it.tenantId == tenantId && it.kind == kind && it.isActive }
}
