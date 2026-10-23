package jp.jrabot.persistence.sql

import jp.jrabot.persistence.repository.inTransactionRetryingDeadlock
import jp.jrabot.persistence.repository.isDeadlock
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** デッドロック（1213）の再試行の規則（DB なし）。実際のデッドロックでの検証は統合テスト `DeadlockRetryIntegrationTest`。 */
class DeadlockRetryTest {
    private class DirectTx : TransactionOperations {
        var begins = 0

        override fun <T : Any?> execute(action: TransactionCallback<T>): T {
            begins++
            return action.doInTransaction(SimpleTransactionStatus())
        }
    }

    private fun deadlock() = RuntimeException("wrapped", SQLException("Deadlock found", "40001", 1213))

    @Test
    fun `isDeadlock は 1213 と SQLSTATE 40001 だけ（他の SQL エラーは false）`() {
        assertTrue(RuntimeException(SQLException("x", "40001", 1213)).isDeadlock())
        assertTrue(RuntimeException(RuntimeException(SQLException("x", "40001", 0))).isDeadlock())
        assertTrue(RuntimeException(SQLException("x", "HY000", 1213)).isDeadlock())
        assertFalse(RuntimeException(SQLException("dup", "23000", 1062)).isDeadlock())
        assertFalse(RuntimeException(SQLException("lock wait timeout", "HY000", 1205)).isDeadlock())
        assertFalse(RuntimeException("no sql").isDeadlock())
    }

    @Test
    fun `デッドロックは有限回だけ再実行し、成功したらその結果を返す`() {
        val tx = DirectTx()
        var calls = 0
        val result =
            tx.inTransactionRetryingDeadlock {
                calls++
                if (calls < 3) throw deadlock()
                "ok"
            }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(3, tx.begins)
    }

    @Test
    fun `上限を超えたデッドロックは握りつぶさず元の例外を投げる（成功扱いにしない）`() {
        val tx = DirectTx()
        var calls = 0
        val e = deadlock()
        val thrown =
            assertFailsWith<RuntimeException> {
                tx.inTransactionRetryingDeadlock {
                    calls++
                    throw e
                }
            }
        assertSame(e, thrown)
        assertEquals(3, calls)
    }

    @Test
    fun `デッドロック以外のエラーは再試行せずそのまま投げる`() {
        val tx = DirectTx()
        var calls = 0
        val dup = RuntimeException(SQLException("dup", "23000", 1062))
        val thrown =
            assertFailsWith<RuntimeException> {
                tx.inTransactionRetryingDeadlock {
                    calls++
                    throw dup
                }
            }
        assertSame(dup, thrown)
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> { tx.inTransactionRetryingDeadlock { error("bug") } }
    }

    @Test
    fun `試行回数は指定でき、1 以上でなければならない`() {
        val tx = DirectTx()
        var calls = 0
        assertFailsWith<RuntimeException> {
            tx.inTransactionRetryingDeadlock(attempts = 1) {
                calls++
                throw deadlock()
            }
        }
        assertEquals(1, calls)
        assertFailsWith<IllegalArgumentException> { tx.inTransactionRetryingDeadlock(attempts = 0) { 1 } }
    }
}
