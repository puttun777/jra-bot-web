package jp.jrabot.domain.tenant

/**
 * テナント（= JRA-VAN 契約者）の識別子。DB の `tenant_id BIGINT`（D-023・D-039）。
 *
 * - 1 以上の値だけを生成できる（0・負数は DB 内部の仮値や未設定と混同しやすいので拒否）。
 * - 他の Long（run の ID、馬番、金額など）とは型で区別される。Repository は第一引数に必ずこの型（または [TenantScoped]）を取る。
 * - 通常クラスにしている（value class にしない）。Repository の引数型を JVM のリフレクションで検査できるようにするため。
 *   実行時のコストは無視できる（テナントは 1〜2 件、生成は境界でのみ）。
 */
class TenantId private constructor(
    val value: Long,
) {
    override fun equals(other: Any?): Boolean = other is TenantId && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "TenantId($value)"

    companion object {
        /** 不正値は [IllegalArgumentException]。信頼できる境界（DB・認証結果）から作る場合に使う。 */
        fun of(value: Long): TenantId {
            require(value >= 1) { "tenant_id は 1 以上: $value" }
            return TenantId(value)
        }

        fun ofOrNull(value: Long): TenantId? = if (value >= 1) TenantId(value) else null

        /** 引数・環境変数（`--tenant-id 1`）用。10 進数字のみ、先頭 0・符号・空白は不可。範囲外は null。 */
        fun parseOrNull(text: String): TenantId? {
            if (text.isEmpty() || text.length > 19) return null
            if (text.any { it !in '0'..'9' } || text[0] == '0') return null
            return text.toLongOrNull()?.let(::ofOrNull)
        }

        fun parse(text: String): TenantId = requireNotNull(parseOrNull(text)) { "tenant_id の形式が不正: '$text'" }
    }
}

/** テナントに属するデータ。Repository の書込みは、引数のこの [tenantId] を使って対象を限定する。 */
interface TenantScoped {
    val tenantId: TenantId
}
