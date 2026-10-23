package jp.jrabot.bot

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotArgsParserTest {
    private fun valid(vararg a: String): BotCommand = assertIs<ParsedArgs.Valid>(BotArgsParser.parse(arrayOf(*a)), a.joinToString(" ")).command

    private fun invalid(vararg a: String): String = assertIs<ParsedArgs.Invalid>(BotArgsParser.parse(arrayOf(*a)), a.joinToString(" ")).message

    @Test
    fun `5 つのタスクを識別する`() {
        for (t in BotTask.entries) {
            val c = valid("--task=${t.cliName}", "--tenant-id=7")
            assertEquals(t, c.task)
            assertEquals(7L, c.tenantId.value)
        }
        assertEquals(listOf("bot-run", "calendar-check", "reconcile", "retention", "export-dataset"), BotTask.entries.map { it.cliName })
    }

    @Test
    fun `task が無い・空・不明は不正`() {
        invalid("--tenant-id=1")
        invalid("--task=", "--tenant-id=1")
        invalid("--task", "--tenant-id=1")
        invalid("--task=unknown", "--tenant-id=1")
        invalid("--task=BOT-RUN", "--tenant-id=1")
        invalid("--task=ml-train", "--tenant-id=1") // ml-train は別イメージ
        invalid("--task=bot-run ", "--tenant-id=1")
        invalid()
    }

    @Test
    fun `tenant-id は全タスクで必須で、1 以上の整数だけ`() {
        for (t in BotTask.entries) invalid("--task=${t.cliName}")
        for (bad in listOf("", "0", "-1", "abc", "1.5", "01", "+1", " 1", "99999999999999999999")) {
            invalid("--task=reconcile", "--tenant-id=$bad")
        }
    }

    @Test
    fun `未知の引数・位置引数・重複・形式違いは不正`() {
        invalid("--task=reconcile", "--tenant-id=1", "--unknown=1")
        invalid("--task=reconcile", "--tenant-id=1", "extra")
        invalid("--task=reconcile", "--tenant-id=1", "-x")
        invalid("--task=reconcile", "--tenant-id=1", "--tenant-id=2")
        invalid("--task=reconcile", "--task=bot-run", "--tenant-id=1")
        invalid("--task=reconcile", "--tenant-id", "1")
        invalid("--task=reconcile", "--tenant-id=1", "--=x")
        invalid("--task=reconcile", "--tenant-id=1", "--Date=2026-11-01")
    }

    @Test
    fun `タスク別の引数 — bot-run は date と trigger`() {
        val c = valid("--task=bot-run", "--tenant-id=1", "--date=2026-11-01", "--trigger=BACKFILL")
        assertEquals(LocalDate.of(2026, 11, 1), c.date)
        assertEquals(TriggerType.BACKFILL, c.trigger)
        assertEquals(null, valid("--task=bot-run", "--tenant-id=1").trigger)
        for (t in TriggerType.entries) assertEquals(t, valid("--task=bot-run", "--tenant-id=1", "--trigger=${t.name}").trigger)
    }

    @Test
    fun `タスク別の引数 — date は calendar-check と reconcile、dry-run は retention だけ`() {
        valid("--task=calendar-check", "--tenant-id=1", "--date=2026-11-01")
        valid("--task=reconcile", "--tenant-id=1", "--date=2026-11-01")
        assertTrue(valid("--task=retention", "--tenant-id=1", "--dry-run").dryRun)
        assertFalse(valid("--task=retention", "--tenant-id=1").dryRun)
        invalid("--task=retention", "--tenant-id=1", "--date=2026-11-01")
        invalid("--task=calendar-check", "--tenant-id=1", "--dry-run")
        invalid("--task=export-dataset", "--tenant-id=1", "--date=2026-11-01")
        invalid("--task=reconcile", "--tenant-id=1", "--trigger=MANUAL")
        invalid("--task=retention", "--tenant-id=1", "--dry-run=true")
    }

    @Test
    fun `date と trigger の値の検証`() {
        for (bad in listOf("2026-13-01", "2026-02-30", "20261101", "2026/11/01", "x")) invalid("--task=reconcile", "--tenant-id=1", "--date=$bad")
        for (bad in listOf("scheduler", "UNKNOWN", "SCHEDULER,MANUAL")) invalid("--task=bot-run", "--tenant-id=1", "--trigger=$bad")
    }

    @Test
    fun `エラーメッセージに引数の値を含めない`() {
        val m = invalid("--task=reconcile", "--tenant-id=secret-value-123")
        assertFalse(m.contains("secret-value-123"))
        val m2 = invalid("--task=reconcile", "--tenant-id=1", "--date=secret-date")
        assertFalse(m2.contains("secret-date"))
    }

    @Test
    fun `プールの既定値は CONTRACTS 8_3 のとおり`() {
        assertEquals(4, BotTask.BOT_RUN.defaultPoolMax)
        assertEquals(2, BotTask.EXPORT_DATASET.defaultPoolMax)
        for (t in listOf(BotTask.CALENDAR_CHECK, BotTask.RECONCILE, BotTask.RETENTION)) assertEquals(1, t.defaultPoolMax, t.cliName)
        // 全体の最大 24 接続（dashboard 6 + ingest 6 + bot-run 4 + 定期 Job 3 + export 2 + db-migration 2 + ml-train 1）
        val total = 3 * 2 + 3 * 2 + 4 + 1 * 3 + 2 + 2 + 1
        assertEquals(24, total)
    }
}
