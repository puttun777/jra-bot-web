package jp.jrabot.bot

import jp.jrabot.domain.ops.AppEnv
import jp.jrabot.persistence.schema.SchemaCheck
import jp.jrabot.persistence.schema.SchemaFailure
import jp.jrabot.persistence.schema.SchemaVersion
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NORMAL の後のスキーマ版の確認 → タスクの実行 → 終了コード（CONTRACTS §4.3・§9）。DB 不達とスキーマ不一致を区別する。 */
class BotJobExecutorTest {
    private val v2 = SchemaVersion.parse("2")
    private val command = (BotArgsParser.parse(args("calendar-check")) as ParsedArgs.Valid).command

    private fun run(
        check: SchemaCheck,
        handler: BotTaskHandler,
    ) = BotJobExecutor(command, { check }, handler).execute()

    private fun counting(result: JobResult = JobResult(JobExit.OK, "DONE")): Pair<BotTaskHandler, AtomicInteger> {
        val calls = AtomicInteger()
        return BotTaskHandler {
            calls.incrementAndGet()
            result
        } to calls
    }

    @Test
    fun `スキーマ版が必要な版以上ならタスクを実行する（新しい版も許容）`() {
        val (h, calls) = counting()
        assertEquals(JobResult(0, "DONE"), run(SchemaCheck.Satisfied(v2), h))
        assertEquals(JobResult(0, "DONE"), run(SchemaCheck.Satisfied(SchemaVersion.parse("10")), h))
        assertEquals(2, calls.get())
    }

    @Test
    fun `スキーマ版が足りなければ 1 で、タスクは実行しない`() {
        val (h, calls) = counting()
        assertEquals(JobResult(1, "SCHEMA_INSUFFICIENT"), run(SchemaCheck.Insufficient(SchemaVersion.parse("1"), v2), h))
        assertEquals(JobResult(1, "SCHEMA_INSUFFICIENT"), run(SchemaCheck.Insufficient(null, v2), h))
        assertEquals(0, calls.get())
    }

    @Test
    fun `DB に接続できなければ 75(一時障害)で、タスクは実行しない`() {
        val (h, calls) = counting()
        assertEquals(JobResult(75, "DB_UNAVAILABLE"), run(SchemaCheck.Unreachable, h))
        assertEquals(0, calls.get())
    }

    @Test
    fun `認証・設定・権限の失敗は 75 にせず 1`() {
        val (h, calls) = counting()
        assertEquals(JobResult(1, "DB_AUTH_FAILED"), run(SchemaCheck.Failed(SchemaFailure.AUTHENTICATION), h))
        assertEquals(JobResult(1, "DB_ERROR"), run(SchemaCheck.Failed(SchemaFailure.DATABASE_OR_PERMISSION), h))
        assertEquals(JobResult(1, "DB_ERROR"), run(SchemaCheck.Failed(SchemaFailure.UNEXPECTED), h))
        assertEquals(0, calls.get())
    }

    @Test
    fun `DB 不達とスキーマ不一致は終了コードで区別される`() {
        val (h, _) = counting()
        val unreachable = run(SchemaCheck.Unreachable, h).exitCode
        val insufficient = run(SchemaCheck.Insufficient(null, v2), h).exitCode
        assertEquals(75, unreachable)
        assertEquals(1, insufficient)
        assertTrue(unreachable != insufficient)
    }

    @Test
    fun `タスクが例外を投げたら 1（75 にしない）`() {
        val r = run(SchemaCheck.Satisfied(v2)) { throw IllegalStateException("boom") }
        assertEquals(JobResult(1, "TASK_FAILED"), r)
    }

    @Test
    fun `スタブは本番では 1 TASK_NOT_IMPLEMENTED で、成功に見せない`() {
        val r = run(SchemaCheck.Satisfied(v2), StubTaskHandler(AppEnv.PROD))
        assertEquals(JobResult(1, "TASK_NOT_IMPLEMENTED"), r)
    }

    @Test
    fun `スタブはローカルでだけ 0 で終わる`() {
        val r = run(SchemaCheck.Satisfied(v2), StubTaskHandler(AppEnv.LOCAL))
        assertEquals(JobResult(0, "STUB_LOCAL_OK"), r)
    }

    @Test
    fun `5 つのタスクのどれもスタブで、本番では完了扱いにならない`() {
        for (t in BotTask.entries) {
            val cmd = (BotArgsParser.parse(args(t.cliName)) as ParsedArgs.Valid).command
            assertEquals(1, StubTaskHandler(AppEnv.PROD).execute(cmd).exitCode, t.cliName)
        }
    }
}
