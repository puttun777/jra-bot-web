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
            val c = valid("--task=${t.cliName}", "--tenant-id=7", *exportDefaults(t.cliName))
            assertEquals(t, c.task)
            assertEquals(7L, c.tenantId.value)
        }
        assertEquals(listOf("bot-run", "calendar-check", "reconcile", "retention", "export-dataset", "backtest"), BotTask.entries.map { it.cliName })
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

    private val backtestBase = arrayOf("--task=backtest", "--tenant-id=1", "--from=2026-01-01", "--to=2026-03-31")

    @Test
    fun `backtest の引数 — 必須と任意、既定値（評価の時点は降順・重複なし）`() {
        val b = valid(*backtestBase).backtest!!
        assertEquals(LocalDate.of(2026, 1, 1), b.from)
        assertEquals(LocalDate.of(2026, 3, 31), b.to)
        assertEquals(listOf(30.0, 5.0), b.minutesBeforePost)
        assertEquals(null, b.maxOddsAge, "既定は BOT_RUN_MAX_ODDS_AGE_SECONDS")
        val full = valid(*backtestBase, "--minutes-before-post=5,60,2.5", "--max-odds-age-seconds=120").backtest!!
        assertEquals(listOf(60.0, 5.0, 2.5), full.minutesBeforePost)
        assertEquals(120L, full.maxOddsAge!!.seconds)
        assertEquals(
            366L,
            java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.of(2025, 1, 1),
                valid("--task=backtest", "--tenant-id=1", "--from=2025-01-01", "--to=2026-01-02").backtest!!.to,
            ),
        )
        assertEquals(null, valid("--task=reconcile", "--tenant-id=1").backtest)
        assertEquals(1, BotTask.BACKTEST.defaultPoolMax)
    }

    @Test
    fun `backtest の不正な引数は拒否し、メッセージに値を含めない`() {
        for (missing in listOf("--from", "--to")) invalid(*backtestBase.filterNot { it.startsWith(missing + "=") }.toTypedArray())
        invalid("--task=backtest", "--tenant-id=1", "--from=2025-01-01", "--to=2026-01-03") // 367 日
        invalid(*backtestBase, "--date=2026-01-01") // backtest の引数ではない
        invalid(*backtestBase, "--dry-run")
        val bads =
            listOf(
                "--from=2026-13-01",
                "--to=2025-12-31",
                "--minutes-before-post=0",
                "--minutes-before-post=-5",
                "--minutes-before-post=1441",
                "--minutes-before-post=NaN",
                "--minutes-before-post=Infinity",
                "--minutes-before-post=30,",
                "--minutes-before-post=30,30",
                "--minutes-before-post=" + (1..13).joinToString(","),
                "--max-odds-age-seconds=0",
                "--max-odds-age-seconds=86401",
                "--max-odds-age-seconds=1.5",
            )
        for (bad in bads) {
            val name = bad.substringBefore('=')
            val m = invalid(*backtestBase.filterNot { it.startsWith(name + "=") }.toTypedArray(), bad)
            assertFalse(m.contains(bad.substringAfter('=')) && bad.substringAfter('=').length > 3, "$bad: $m")
        }
    }

    private val exportBase =
        arrayOf("--task=export-dataset", "--tenant-id=1", "--dataset-id=ds_1-A", "--from=2026-01-01", "--to=2026-03-31", "--feature-schema=alpha")

    @Test
    fun `export-dataset の引数 — 必須と任意、既定値`() {
        val c = valid(*exportBase)
        val e = c.export!!
        assertEquals("ds_1-A", e.datasetId.value)
        assertEquals(LocalDate.of(2026, 1, 1), e.from)
        assertEquals(LocalDate.of(2026, 3, 31), e.to)
        assertEquals(ExportSchema.ALPHA, e.schema)
        assertEquals(0.2, e.validFraction)
        assertEquals(10L, e.asOfLead.toMinutes())
        val full = valid(*exportBase, "--valid-fraction=0.3", "--as-of-lead-minutes=15").export!!
        assertEquals(0.3, full.validFraction)
        assertEquals(15L, full.asOfLead.toMinutes())
        assertEquals(ExportSchema.CALIBRATION, valid(*exportBase.dropLast(1).toTypedArray(), "--feature-schema=calibration").export!!.schema)
        assertEquals(null, valid("--task=reconcile", "--tenant-id=1").export)
    }

    @Test
    fun `export-dataset の不正な引数は拒否し、メッセージに値を含めない`() {
        for (missing in listOf("--dataset-id", "--from", "--to", "--feature-schema")) {
            invalid(*exportBase.filterNot { it.startsWith(missing + "=") }.toTypedArray())
        }
        val bads =
            listOf(
                "--dataset-id=../x",
                "--dataset-id=a b",
                "--dataset-id=" + "a".repeat(65),
                "--from=2026-13-01",
                "--from=2026/01/01",
                "--to=2025-12-31",
                "--to=2029-01-01",
                "--feature-schema=market",
                "--feature-schema=CALIBRATION",
                "--valid-fraction=0",
                "--valid-fraction=1",
                "--valid-fraction=x",
                "--valid-fraction=NaN",
                "--as-of-lead-minutes=0",
                "--as-of-lead-minutes=1441",
                "--as-of-lead-minutes=1.5",
            )
        for (bad in bads) {
            val name = bad.substringBefore('=')
            val m = invalid(*exportBase.filterNot { it.startsWith(name + "=") }.toTypedArray(), bad)
            assertFalse(m.contains(bad.substringAfter('=')) && bad.substringAfter('=').length > 3, "$bad: $m")
        }
        invalid(*exportBase, "--dry-run")
        invalid(*exportBase, "--date=2026-01-01")
        invalid("--task=bot-run", "--tenant-id=1", "--dataset-id=a") // 他のタスクに export の引数は渡せない
    }
}
