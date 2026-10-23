package jp.jrabot.domain.tenant

import java.time.Instant

/** テナント（共有表 `tenant`）。行はデータを含まない（ARCHITECTURE §5）。 */
data class Tenant(
    val id: TenantId,
    val name: String,
    val createdAt: Instant,
) {
    init {
        require(name.isNotBlank() && name.length <= 100) { "テナント名は 1〜100 文字" }
    }
}
