package jp.jrabot.persistence.integration

import jp.jrabot.persistence.repository.inTransactionRetryingDeadlock
import jp.jrabot.persistence.repository.isDeadlock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionOperations
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 実際の MySQL のデッドロック（1213）の検出と、トランザクション単位の再試行（有限回・他のエラーは隠さない）。 */
@Tag("integration")
class DeadlockRetryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, poolMax = 4)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private val tx: TransactionOperations get() = h.bean(TransactionOperations::class.java)
    private val jdbc: JdbcTemplate get() = JdbcTemplate(h.dataSource)

    /** 2 つのトランザクションが tenant 1 と 2 の行を逆順に更新する（各自の最初の試行だけ互いを待ち合わせて、確実にデッドロックさせる）。 */
    private fun crossUpdate(
        first: Int,
        second: Int,
        barrier: CyclicBarrier,
        attempts: AtomicInteger,
    ) = tx.inTransactionRetryingDeadlock {
        val n = attempts.incrementAndGet()
        jdbc.update("UPDATE tenant SET name = CONCAT(name, 'x') WHERE tenant_id = ?", first)
        if (n == 1) barrier.await(10, TimeUnit.SECONDS)
        jdbc.update("UPDATE tenant SET name = CONCAT(name, 'y') WHERE tenant_id = ?", second)
    }

    @Test
    fun `実際のデッドロックは isDeadlock で検出でき、再試行で両方が成功する`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val attemptsA = AtomicInteger()
            val attemptsB = AtomicInteger()
            val a = pool.submit { crossUpdate(1, 2, barrier, attemptsA) }
            val b = pool.submit { crossUpdate(2, 1, barrier, attemptsB) }
            a.get(60, TimeUnit.SECONDS)
            b.get(60, TimeUnit.SECONDS)
            // 片方が犠牲になり、1 回だけ再試行された（合計 3 回）。両方とも最終的に成功している
            assertEquals(3, attemptsA.get() + attemptsB.get())
            // 犠牲になった側は巻き戻されて再実行されたので、各行に x と y が 1 回ずつ（二重適用なし）
            assertEquals(
                2,
                db.count(
                    "SELECT COUNT(*) FROM tenant WHERE name LIKE '%x%' AND name LIKE '%y%' AND CHAR_LENGTH(name) = CHAR_LENGTH(REPLACE(REPLACE(name, 'x', ''), 'y', '')) + 2",
                ),
                "両方の更新が 1 回ずつ反映されている",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `デッドロックを検出する述語が本物の例外（Spring・Doma が包む前後）に対して働く`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val errors =
                (0..1)
                    .map { i ->
                        pool.submit<Throwable?> {
                            try {
                                tx.execute {
                                    jdbc.update("UPDATE tenant SET name = name WHERE tenant_id = ?", if (i == 0) 1 else 2)
                                    barrier.await(5, TimeUnit.SECONDS)
                                    jdbc.update("UPDATE tenant SET name = name WHERE tenant_id = ?", if (i == 0) 2 else 1)
                                }
                                null
                            } catch (e: Throwable) {
                                e
                            }
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            val victims = errors.filterNotNull()
            assertEquals(1, victims.size, "$errors")
            assertTrue(victims.single().isDeadlock(), "${victims.single()}")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `デッドロック以外の SQL エラーは再試行せず、そのまま投げる`() {
        val attempts = AtomicInteger()
        val e =
            assertFailsWith<RuntimeException> {
                tx.inTransactionRetryingDeadlock {
                    attempts.incrementAndGet()
                    jdbc.update("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1, 'dup', NOW(6))")
                }
            }
        assertEquals(1, attempts.get())
        assertEquals(MySqlError.DUPLICATE_ENTRY, e.mysqlErrorCode())
    }
}
