package jp.jrabot.dbmigration

/** マイグレーションの状態（`info` の出力）。版は文字列（`"2"`）、Repeatable は `R__<説明>`。 */
data class InfoReport(
    /** 最後に成功した版付きマイグレーション。1 つも適用されていなければ null。 */
    val current: String?,
    /** このイメージが持つ最新の版付きマイグレーション。 */
    val latest: String?,
    /** 未適用（Repeatable は内容が変わって再適用が必要なものを含む）。 */
    val pending: List<String>,
    /** 失敗として記録されているもの。 */
    val failed: List<String>,
)

data class ValidateReport(
    val valid: Boolean,
    val info: InfoReport,
    val errors: List<String>,
)

data class MigrateReport(
    val applied: List<String>,
    val info: InfoReport,
)

/**
 * Flyway を隠すポート。失敗は例外で表す（DB に接続できない場合も含む）。
 * 分類（一時的な失敗 = 75 か、失敗 = 1 か）は呼び出し側の [ErrorClassifier]。
 */
interface MigrationEngine {
    fun info(): InfoReport

    /** 適用済みの整合性を検証する。未適用があること自体は不整合にしない（[InfoReport.pending] で返す）。 */
    fun validate(): ValidateReport

    /** 未適用の版を適用する。失敗したら例外（自動の修復はしない）。 */
    fun migrate(): MigrateReport
}
