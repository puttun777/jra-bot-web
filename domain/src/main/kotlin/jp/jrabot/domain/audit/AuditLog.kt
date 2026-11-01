package jp.jrabot.domain.audit

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

/**
 * 管理操作の監査記録（`audit_log`。追記専用。CONTRACTS §11.1）。who（[actor] = 認可済みの本人のメール）/ when / what（[action]・[targetType]・[targetId]）/ before / after / 理由。
 * [beforeJson]・[afterJson] は JSON 文字列で、**秘密・トークン・Webhook などを入れない**（呼び出し側の責務）。
 */
data class AuditEntry(
    val tenantId: TenantId,
    val actor: String,
    val action: String,
    val targetType: String,
    val targetId: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String?,
    val requestId: String?,
) {
    init {
        require(actor.isNotBlank() && actor.length <= 254 && actor.none { it.isISOControl() }) { "actor は 1〜254 文字" }
        require(Regex("[A-Z][A-Z0-9_]{0,63}").matches(action)) { "action は大文字の英数字と _（64 文字以下）" }
        require(Regex("[a-z][a-z0-9_]{0,63}").matches(targetType)) { "targetType は小文字の英数字と _（64 文字以下）" }
        require(targetId == null || targetId.length <= 100) { "targetId は 100 文字以下" }
        require(reason == null || reason.length <= 500) { "reason は 500 文字以下" }
        require(requestId == null || Regex("[A-Za-z0-9._-]{1,64}").matches(requestId)) { "requestId の形式が不正" }
    }
}

fun interface AuditLogWriter {
    /** 1 件を追記する（失敗は例外。呼び出し側は監査に失敗した操作を成功として扱わない）。 */
    fun append(
        entry: AuditEntry,
        at: Instant,
    )
}
