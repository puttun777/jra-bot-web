package jp.jrabot.dbmigration

/** CONTRACTS §4.2・§9.1 の終了コード。起動停止のワークフローがこの値で分岐するので、変更は契約の変更。 */
object ExitCodes {
    const val OK = 0

    /** migrate/validate の失敗（不整合・失敗したマイグレーション）。 */
    const val FAILURE = 1

    /** 引数・設定の誤り。 */
    const val USAGE = 2

    /** validate だけ: 整合性は正しいが未適用の版がある。 */
    const val PENDING = 10

    /** 一時的な失敗（DB に接続できないなど。EX_TEMPFAIL）。 */
    const val TEMPORARY_FAILURE = 75
}
