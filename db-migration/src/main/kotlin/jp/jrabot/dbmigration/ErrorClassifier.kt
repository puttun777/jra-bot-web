package jp.jrabot.dbmigration

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientException

/**
 * 「待てば直るかもしれない」失敗（終了コード 75）と、そうでない失敗（1）を分ける。
 * 接続できない・通信が切れた・サーバが落ちている/満杯のときが前者。認証失敗や SQL の誤りは後者（再試行しても直らない）。
 */
object ErrorClassifier {
    private const val MAX_DEPTH = 16

    /** MySQL のエラー番号: 1040 接続数超過、1053 シャットダウン中、2002/2003 接続不可、2006 切断、2013 通信断。 */
    private val TEMPORARY_ERROR_CODES = setOf(1040, 1053, 2002, 2003, 2006, 2013)

    fun isTemporary(error: Throwable): Boolean = causeChain(error).any { isTemporaryCause(it) }

    private fun isTemporaryCause(t: Throwable): Boolean =
        when (t) {
            is SocketTimeoutException, is UnknownHostException, is SocketException -> true
            is SQLTransientException, is SQLRecoverableException, is SQLTimeoutException -> true
            is SQLException -> t.sqlState?.startsWith("08") == true || t.errorCode in TEMPORARY_ERROR_CODES
            else -> t.javaClass.name.endsWith("CommunicationsException")
        }

    /** 原因の連鎖（循環に強く、長さを制限）。 */
    fun causeChain(error: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.size < MAX_DEPTH && seen.none { it === current }) {
            seen += current
            current = current.cause
        }
        return seen
    }
}
