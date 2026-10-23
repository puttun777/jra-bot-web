package jp.jrabot.persistence.sql

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.persistence.dao.BotRunDao
import jp.jrabot.persistence.dao.BotRunEntity
import jp.jrabot.persistence.repository.BotRunRepositoryImpl
import org.seasar.doma.jdbc.Result
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 取得の競合が決着しない場合の振る舞い（DB なし。偽の DAO で「重複と判定されたが稼働中の行が読めない」を再現する）。 */
class BotRunAcquireContentionTest {
    private class DirectTx : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T = action.doInTransaction(SimpleTransactionStatus())
    }

    private val duplicate = RuntimeException("insert failed", SQLException("Duplicate entry 'x' for key 'bot_run.uq_bot_run_active'", "23000", 1062))

    private fun entity(
        version: Int = 0,
        expiresAt: Instant? = Instant.parse("2026-10-24T00:00:00Z"),
    ) = BotRunEntity(
        tenantId = 1,
        runId = UUID.randomUUID().toString(),
        runDate = LocalDate.of(2026, 10, 24),
        triggerType = "SCHEDULER",
        state = "RUNNING",
        leaseOwner = "old",
        leaseExpiresAt = expiresAt?.let { java.time.LocalDateTime.ofInstant(it, java.time.ZoneOffset.UTC) },
        heartbeatAt = null,
        activeSlot = 1,
        checkpoint = null,
        exitReason = null,
        startedAt = java.time.LocalDateTime.of(2026, 10, 23, 23, 0),
        finishedAt = null,
        version = version,
    )

    private fun stable(e: BotRunEntity = entity()): () -> BotRunEntity? = { e }

    private class FakeDao(
        val active: () -> BotRunEntity?,
        val updateCount: Int = 1,
        val insertFails: Boolean = true,
    ) : BotRunDao {
        var inserts = 0
        var lockedReads = 0
        var dup: RuntimeException? = null

        override fun selectById(
            tenantId: Long,
            runId: String,
        ): BotRunEntity? = active()?.takeIf { it.runId == runId }

        override fun selectActive(
            tenantId: Long,
            runDate: LocalDate,
        ): BotRunEntity? = active()

        override fun selectByIdForUpdate(
            tenantId: Long,
            runId: String,
        ): BotRunEntity? {
            lockedReads++
            return active()?.takeIf { it.runId == runId }
        }

        override fun selectByDate(
            tenantId: Long,
            runDate: LocalDate,
        ): List<BotRunEntity> = emptyList()

        override fun selectRecent(
            tenantId: Long,
            limit: Int,
        ): List<BotRunEntity> = emptyList()

        override fun insert(entity: BotRunEntity): Result<BotRunEntity> {
            inserts++
            if (insertFails) throw checkNotNull(dup)
            return Result(1, entity)
        }

        override fun updateIfVersion(entity: BotRunEntity): Result<BotRunEntity> = Result(updateCount, entity)
    }

    private fun request() =
        jp.jrabot.domain.bot.LeaseRequest(
            jp.jrabot.domain.tenant.TenantId
                .of(1),
            jp.jrabot.domain.bot.RunId
                .of(UUID.randomUUID()),
            LocalDate.of(2026, 10, 24),
            jp.jrabot.domain.bot.BotRunTrigger.SCHEDULER,
            "new",
            Instant.parse("2026-10-24T00:00:00Z"),
            Duration.ofMinutes(5),
        )

    @Test
    fun `重複と判定されたのに稼働中の行が読めない状態が続けば、有限回で Contended（成功扱いにしない）`() {
        val dao = FakeDao(active = { null }).also { it.dup = duplicate }
        val result = BotRunRepositoryImpl(dao, DirectTx()).acquire(request())
        assertEquals(AcquireResult.Contended, result)
        assertEquals(4, dao.inserts)
        assertEquals(0, dao.lockedReads, "稼働中の行が読めなければロックまで進まない")
    }

    @Test
    fun `奪取の更新が入らない（他が先に更新した）状態が続いても Contended で、挿入に進まない`() {
        val dao = FakeDao(active = stable(), updateCount = 0).also { it.dup = duplicate }
        val result = BotRunRepositoryImpl(dao, DirectTx()).acquire(request())
        assertEquals(AcquireResult.Contended, result)
        assertEquals(4, dao.inserts, "各試行の最初の挿入だけ。奪取の挿入までは進まない")
    }

    @Test
    fun `UNIQUE（uq_bot_run_active）以外の重複や SQL エラーは AlreadyActive にせず投げる`() {
        val pk = RuntimeException("x", SQLException("Duplicate entry 'x' for key 'bot_run.PRIMARY'", "23000", 1062))
        val dao = FakeDao(active = stable()).also { it.dup = pk }
        assertFailsWith<RuntimeException> { BotRunRepositoryImpl(dao, DirectTx()).acquire(request()) }
        val fk = RuntimeException("x", SQLException("Cannot add or update a child row", "23000", 1452))
        val dao2 = FakeDao(active = stable()).also { it.dup = fk }
        assertFailsWith<RuntimeException> { BotRunRepositoryImpl(dao2, DirectTx()).acquire(request()) }
    }

    @Test
    fun `リースの期限が無い稼働中の行は、期限切れと証明できないので奪取せず AlreadyActive`() {
        val dao = FakeDao(active = stable(entity(expiresAt = null))).also { it.dup = duplicate }
        val result = BotRunRepositoryImpl(dao, DirectTx()).acquire(request())
        val active = assertIs<AcquireResult.AlreadyActive>(result)
        assertEquals(null, active.leaseExpiresAt)
        assertEquals(1, dao.inserts)
    }

    @Test
    fun `奪取の直後の挿入が衝突したら全体を巻き戻して読み直す（旧行の更新は確定させない）`() {
        // 1 回目: 挿入が重複 → 期限切れを奪取（更新 OK）→ 挿入がまた重複（RetryAcquire）→ 再試行。以降も同じ → 上限で Contended
        val dao = FakeDao(active = stable()).also { it.dup = duplicate }
        val result = BotRunRepositoryImpl(dao, DirectTx()).acquire(request())
        assertEquals(AcquireResult.Contended, result)
        assertEquals(8, dao.inserts, "各試行で、最初の挿入と奪取の挿入の 2 回")
    }
}
