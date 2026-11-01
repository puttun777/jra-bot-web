package jp.jrabot.bot

import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.retention.RetentionPolicy
import jp.jrabot.domain.retention.RetentionService
import jp.jrabot.domain.retention.RetentionStore
import jp.jrabot.domain.retention.RetentionTarget
import jp.jrabot.domain.tenant.TenantId
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionTaskHandlerTest {
    private class Counting(
        val eligible: Long = 3,
        val failure: Exception? = null,
    ) : RetentionStore {
        val deletes = AtomicInteger()
        private var left = eligible

        override fun count(
            tenantId: TenantId,
            target: RetentionTarget,
            before: Instant,
        ): Long {
            failure?.let { throw it }
            return left
        }

        override fun delete(
            tenantId: TenantId,
            target: RetentionTarget,
            before: Instant,
            limit: Int,
        ): Int {
            deletes.incrementAndGet()
            val n = minOf(left, limit.toLong()).toInt()
            left -= n
            return n
        }
    }

    private val clock = ManualClock(Instant.parse("2026-11-01T00:00:00Z"))

    private fun handler(
        store: RetentionStore,
        execute: Boolean,
    ) = RetentionTaskHandler(
        RetentionSettings(RetentionPolicy(), execute),
        { stop -> RetentionService(clock, store, RetentionPolicy(), stop) },
        StopFlag(),
        SignalInstaller.NONE,
    )

    private fun cmd(dryRun: Boolean = false) = BotCommand(BotTask.RETENTION, TenantId.of(1), dryRun = dryRun)

    @Test
    fun `既定はドライラン 削除しない`() {
        val s = Counting()
        val r = handler(s, execute = false).execute(cmd())
        assertEquals(JobResult(JobExit.OK, "RETENTION_DRY_RUN"), r)
        assertEquals(0, s.deletes.get())
    }

    @Test
    fun `RETENTION_EXECUTE を明示しても --dry-run が付けば削除しない`() {
        val s = Counting()
        assertEquals("RETENTION_DRY_RUN", handler(s, execute = true).execute(cmd(dryRun = true)).reason)
        assertEquals(0, s.deletes.get())
    }

    @Test
    fun `明示したときだけ削除する`() {
        val s = Counting()
        assertEquals(JobResult(JobExit.OK, "RETENTION_DONE"), handler(s, execute = true).execute(cmd()))
        assertTrue(s.deletes.get() > 0)
    }

    @Test
    fun `DB に接続できなければ 75`() {
        val r = handler(Counting(failure = SQLTransientConnectionException("down")), execute = true).execute(cmd())
        assertEquals(JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE"), r)
    }

    @Test
    fun `設定は任意で既定は設計の案とドライラン 範囲外と不正は拒否する`() {
        val d = RetentionSettings.fromEnv(emptyMap())
        assertFalse(d.execute)
        assertEquals(14L, d.policy.days.getValue(RetentionTarget.COMBO_ODDS))
        val s = RetentionSettings.fromEnv(mapOf("RETENTION_EXECUTE" to "true", "RETENTION_COMBO_ODDS_DAYS" to "30"))
        assertTrue(s.execute)
        assertEquals(30L, s.policy.days.getValue(RetentionTarget.COMBO_ODDS))
        for (bad in listOf(mapOf("RETENTION_EXECUTE" to "yes"), mapOf("RETENTION_BOT_RUN_DAYS" to "3"), mapOf("RETENTION_INGEST_RAW_DAYS" to "abc"))) {
            val e = assertFailsWith<IllegalStateException> { RetentionSettings.fromEnv(bad) }
            assertTrue(e.message!!.contains("RETENTION_") && listOf("yes", "abc").none { v -> e.message!!.contains(v) }, "変数名だけを示し、値を出さない: ${e.message}")
        }
    }
}
