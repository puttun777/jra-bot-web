package jp.jrabot.bot

import jp.jrabot.bot.discord.StopFlag
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.botrun.BotRunRunner
import jp.jrabot.domain.botrun.RunOutcome
import jp.jrabot.domain.botrun.RunRequest
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.tenant.TenantId
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotRunTaskHandlerTest {
    @Test
    fun `通知の出どころ SCHEDULER と MANUAL は LIVE BACKFILL と RECONCILE は通知しない 指定なしは判定不能`() {
        assertEquals(NotificationOrigin.LIVE, BotRunTaskHandler.originOf(TriggerType.SCHEDULER))
        assertEquals(NotificationOrigin.LIVE, BotRunTaskHandler.originOf(TriggerType.MANUAL))
        assertEquals(NotificationOrigin.BACKFILL, BotRunTaskHandler.originOf(TriggerType.BACKFILL))
        assertEquals(NotificationOrigin.BACKFILL, BotRunTaskHandler.originOf(TriggerType.RECONCILE))
        assertEquals(NotificationOrigin.UNDETERMINED, BotRunTaskHandler.originOf(null))
    }

    @Test
    fun `一時的な障害の分類 接続失敗 デッドロック ロック待ち 直列化失敗だけが一時的`() {
        assertTrue(BotRunTaskHandler.isTransient(SQLTransientConnectionException("x")))
        assertTrue(BotRunTaskHandler.isTransient(RuntimeException("wrap", SQLException("deadlock", "40001", 1213))))
        assertTrue(BotRunTaskHandler.isTransient(SQLException("lock wait", "HY000", 1205)))
        assertTrue(BotRunTaskHandler.isTransient(SQLException("serialization", "40001", 0)))
        assertFalse(BotRunTaskHandler.isTransient(SQLException("syntax", "42000", 1064)))
        assertFalse(BotRunTaskHandler.isTransient(IllegalStateException("bug")))
    }

    @Test
    fun `既定の所有者は一意で 100 文字以内`() {
        val a = BotRunTaskHandler.defaultOwner()
        val b = BotRunTaskHandler.defaultOwner()
        assertTrue(a != b && a.length in 1..100)
    }

    private class Harness {
        val stop = StopFlag()
        val installed = AtomicInteger()
        val restored = AtomicInteger()
        var onTerm: Runnable? = null
        val requests = mutableListOf<RunRequest>()
        val clock = ManualClock(Instant.parse("2026-10-24T00:00:00Z")) // JST 09:00

        fun handler(exit: Int = 0): BotRunTaskHandler {
            val runner =
                BotRunRunner { req ->
                    requests += req
                    RunOutcome(exit, "TEST", null, null)
                }
            return BotRunTaskHandler(
                runnerFactory = { runner },
                clock = clock,
                stop = stop,
                signals =
                    SignalInstaller { t ->
                        installed.incrementAndGet()
                        onTerm = t
                        Runnable { restored.incrementAndGet() }
                    },
                ownerId = { "test-owner" },
            )
        }
    }

    private fun command(vararg extra: String) = (BotArgsParser.parse(args("bot-run", "1", *extra)) as ParsedArgs.Valid).command

    @Test
    fun `引数が RunRequest になり SIGTERM の処理は設置され 終わったら元に戻る`() {
        val h = Harness()
        val result = h.handler(75).execute(command("--date=2026-10-24", "--trigger=SCHEDULER"))
        assertEquals(75, result.exitCode)
        assertEquals(1, h.installed.get())
        assertEquals(1, h.restored.get())
        val r = h.requests.single()
        assertEquals(TenantId.of(1), r.tenantId)
        assertEquals(LocalDate.of(2026, 10, 24), r.date)
        assertEquals(BotRunTrigger.SCHEDULER, r.trigger)
        assertEquals(NotificationOrigin.LIVE, r.origin)
        assertEquals("test-owner", r.owner)
    }

    @Test
    fun `日付と trigger の指定が無ければ 今日 JST と MANUAL で 出どころは判定不能`() {
        val h = Harness()
        h.handler().execute(command())
        val r = h.requests.single()
        assertEquals(LocalDate.of(2026, 10, 24), r.date)
        assertEquals(BotRunTrigger.MANUAL, r.trigger)
        assertEquals(NotificationOrigin.UNDETERMINED, r.origin)
        h.requests.clear()
        h.handler().execute(command("--trigger=BACKFILL"))
        assertEquals(NotificationOrigin.BACKFILL, h.requests.single().origin)
    }

    @Test
    fun `SIGTERM を受けると停止の旗が立つ 通知側と同じ旗`() {
        val h = Harness()
        h.handler().execute(command())
        assertFalse(h.stop.isRequested())
        assertNotNull(h.onTerm).run()
        assertTrue(h.stop.isRequested())
    }

    @Test
    fun `例外で終わっても SIGTERM の処理は元に戻す`() {
        val h = Harness()
        val bad =
            BotRunTaskHandler(
                runnerFactory = { throw IllegalStateException("boom") },
                clock = h.clock,
                stop = h.stop,
                signals = SignalInstaller { Runnable { h.restored.incrementAndGet() } },
            )
        runCatching { bad.execute(command()) }
        assertEquals(1, h.restored.get())
    }

    @Test
    fun `SleepPacer は停止要求で早く戻り 指定の時間だけ待つ`() {
        var slept = 0L
        var now = 0L
        val pacer =
            SleepPacer(sleepMillis = {
                slept += it
                now += it * 1_000_000
            }, nanoTime = { now })
        pacer.pause(Duration.ofSeconds(2)) { false }
        assertTrue(slept in 2000..2500, "slept=$slept")
        slept = 0
        now = 0
        var polls = 0
        pacer.pause(Duration.ofHours(1)) { ++polls > 3 } // 停止要求が立てば 1 時間待たない
        assertTrue(slept <= 2000, "slept=$slept")
        slept = 0
        pacer.pause(Duration.ofSeconds(5)) { true }
        assertEquals(0, slept)
    }

    @Test
    fun `実際のシグナルを受けると停止の旗が立つ（無害な USR2 で SIGTERM と同じ経路を確かめる）`() {
        val flag = StopFlag()
        val restore = SignalInstaller.handling("USR2", strict = true).install { flag.request() }
        try {
            sun.misc.Signal.raise(sun.misc.Signal("USR2"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!flag.isRequested() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(flag.isRequested(), "シグナルのハンドラが呼ばれた")
        } finally {
            restore.run()
        }
    }

    @Test
    fun `本番の SIGTERM の設置は例外を投げず 元に戻せる`() {
        SignalInstaller.SIGTERM.install { }.run()
        SignalInstaller.NONE.install { }.run()
    }
}
