package jp.jrabot.persistence.repository

import org.springframework.transaction.support.TransactionOperations
import java.sql.SQLException

/** MySQL のエラー番号 1062（重複キー）。 */
internal const val DUPLICATE_ENTRY = 1062

/** 原因の連鎖にある最初の [SQLException]（Doma が包む前のもの）。 */
internal fun Throwable.sqlException(): SQLException? = generateSequence(this) { it.cause }.filterIsInstance<SQLException>().firstOrNull()

/**
 * **特定の UNIQUE 制約**による重複キー（1062）か。他の制約（主キーなど）の重複や別の DB エラーは false にして、呼び出し側が再送出する。
 * MySQL のメッセージは `Duplicate entry '…' for key 'テーブル.キー名'`（8.4・9.7）。
 */
internal fun Throwable.isDuplicateOn(
    table: String,
    key: String,
): Boolean {
    val sql = sqlException() ?: return false
    if (sql.errorCode != DUPLICATE_ENTRY) return false
    val message = sql.message ?: return false
    return Regex("for key '(?:${Regex.escape(table)}\\.)?${Regex.escape(key)}'").containsMatchIn(message)
}

/** [block] を 1 つのトランザクションで実行する（呼び出し元が既にトランザクション内なら、それに参加する）。 */
internal fun <T> TransactionOperations.inTransaction(block: () -> T): T {
    var result: Any? = null
    executeWithoutResult { result = block() }

    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** 更新件数 [count] を [UpsertOutcome][jp.jrabot.domain.repository.UpsertOutcome] の判定に使う（MySQL: 1 挿入 / 2 更新 / 0 変更なし）。 */
internal fun upsertOutcomeOf(count: Int): jp.jrabot.domain.repository.UpsertOutcome =
    when (count) {
        1 -> jp.jrabot.domain.repository.UpsertOutcome.CREATED
        2 -> jp.jrabot.domain.repository.UpsertOutcome.UPDATED
        0 -> jp.jrabot.domain.repository.UpsertOutcome.UNCHANGED
        else -> error("upsert の更新件数が想定外: $count（useAffectedRows=true が前提）")
    }
