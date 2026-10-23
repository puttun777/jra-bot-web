package jp.jrabot.bot

import jp.jrabot.domain.bot.AcquireResult
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.bot.BotRunTrigger
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import jp.jrabot.domain.bot.LeaseRequest
import jp.jrabot.domain.bot.RunId
import jp.jrabot.domain.calendar.ScheduleFetchFact
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.Zones
import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TcpProxy
import jp.jrabot.persistence.integration.TestDb
import jp.jrabot.persistence.integration.resetData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * calendar-check の全体（本物の MySQL・本番と同じ配線の Spring・`bot` ユーザー）。起動基盤・時計・取得の事実だけを合成の Fake にする
 * （実 GCP には接続しない。取得の事実は実際に取得したものではない）。C01〜C24 のうち T3.3 で確認できる範囲（RACE_CALENDAR_DESIGN §9）。
 */
@Tag("integration")
class CalendarCheckIntegrationTest {
    class MutableClock(
        @Volatile var instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    class FakeLauncher : JobLauncher {
        val requests = CopyOnWriteArrayList<JobLaunchRequest>()

        @Volatile var result: LaunchResult = LaunchResult.Launched("exec-synthetic")

        override fun launch(request: JobLaunchRequest): LaunchResult {
            requests += request
            return result
        }
    }

    class MutableFacts : ScheduleFetchFactReader {
        val facts = HashMap<Pair<TenantId, LocalDate>, ScheduleFetchFact>()

        override fun latestSuccess(
            tenantId: TenantId,
            date: LocalDate,
        ) = facts[tenantId to date]
    }

    class MutableMode(
        @Volatile var mode: OpsMode = OpsMode.NORMAL,
    ) : ModeProvider {
        override fun current(): OpsMode = mode
    }

    companion object {
        private val db: TestDb = MySqlSupport.migratedDatabase()
        private val proxy = MySqlSupport.hostAndPort().let { (h, p) -> TcpProxy(h, p).start() }
        private val dir = Files.createTempDirectory("bot-cal-it")
        private val d = LocalDate.of(2026, 10, 24)
        private val clock = MutableClock(Instant.parse("2026-10-24T00:00:00Z"))
        private val launcher = FakeLauncher()
        private val facts = MutableFacts()
        private val mode = MutableMode()
        private val contexts = mutableListOf<ConfigurableApplicationContext>()

        @JvmStatic
        @AfterAll
        fun stop() {
            contexts.forEach { it.close() }
            proxy.close()
        }

        private fun jst(
            date: LocalDate,
            h: Int,
            m: Int,
            s: Int = 0,
        ): Instant = date.atTime(h, m, s).atZone(Zones.JST).toInstant()

        private fun env(url: String = "jdbc:mysql://127.0.0.1:${proxy.port}/${db.name}") =
            baseEnv(
                writeOpsState(Files.createTempDirectory(dir, "ops")).toString(),
                "DB_URL" to url,
                "DB_PASSWORD" to MySqlSupport.PASSWORDS.getValue("bot"),
            )

        /** 本番と同じ Spring 構成。[overrides] が true なら時計・起動基盤・取得の事実を合成の Fake に差し替える。 */
        private fun start(
            overrides: Boolean = true,
            url: String = "jdbc:mysql://127.0.0.1:${proxy.port}/${db.name}",
        ): ConfigurableApplicationContext {
            val command = BotCommand(BotTask.CALENDAR_CHECK, TenantId.of(1))
            val runtime = BotRuntime(command, BotSettings.from(env(url), BotTask.CALENDAR_CHECK), mode)
            return SpringApplicationBuilder(BotApplication::class.java)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> { ctx ->
                        ctx.beanFactory.registerSingleton("botRuntime", runtime)
                        if (overrides) {
                            ctx.beanFactory.registerSingleton("testClock", clock)
                            ctx.beanFactory.registerSingleton("testLauncher", launcher)
                            ctx.beanFactory.registerSingleton("testFacts", facts)
                        }
                    },
                ).run()
                .also { contexts += it }
        }

        private val ctx: ConfigurableApplicationContext by lazy { start() }

        // --- 合成データの投入（管理者権限の SQL。業務の表だけ） ---

        private fun raceId(
            date: LocalDate,
            venue: String,
            no: Int,
        ) = "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth) + venue + "%02d".format(no)

        private fun utc(i: Instant) = "'" + i.toString().removeSuffix("Z").replace('T', ' ') + "'"

        fun seedDay(
            date: LocalDate = d,
            venue: String = "05",
            status: String = "CONFIRMED",
            confirmedAt: Instant? = jst(date, 6, 0),
            replacedBy: LocalDate? = null,
            tenant: Int = 1,
        ) = db.exec(
            "INSERT INTO race_day (tenant_id, race_date, venue_code, kai, nichime, status, replaced_by_date, first_seen_at, last_confirmed_at) VALUES " +
                "($tenant, '$date', '$venue', 4, 8, '$status', ${replacedBy?.let { "'$it'" } ?: "NULL"}, ${utc(
                    jst(date.minusDays(3), 12, 0),
                )}, ${confirmedAt?.let { utc(it) } ?: "NULL"})",
        )

        fun seedRaces(
            date: LocalDate = d,
            venue: String = "05",
            count: Int = 12,
            status: String = "CARD",
            firstPost: Pair<Int, Int> = 9 to 55,
            tenant: Int = 1,
            withTime: Boolean = true,
        ) {
            for (no in 1..count) {
                val post = jst(date, firstPost.first, firstPost.second).plus(Duration.ofMinutes(25L * (no - 1)))
                db.exec(
                    "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, scheduled_post_at, post_time_unknown, status, " +
                        "data_kubun, source_version, " +
                        "race_name, last_changed_at) VALUES ($tenant, '${raceId(
                            date,
                            venue,
                            no,
                        )}', '$date', '$venue', $no, ${if (withTime) utc(post) else "NULL"}, " +
                        "${if (withTime) 0 else 1}, '$status', '2', '202610232', 'R$no', ${utc(jst(date.minusDays(1), 12, 0))})",
                )
            }
        }

        fun seedOverride(
            action: String,
            scope: String = "DAY",
            raceNo: Int? = null,
            value: String? = null,
            createdAt: Instant = jst(d, 5, 0),
            validUntil: Instant? = null,
            revokedAt: Instant? = null,
            date: LocalDate = d,
        ) = db.exec(
            "INSERT INTO calendar_override (tenant_id, scope, race_date, race_id, action, value, reason, created_by, created_at, valid_until, " +
                "revoked_at, revoked_by) VALUES " +
                "(1, '$scope', '$date', ${raceNo?.let {
                    "'${raceId(
                        date,
                        "05",
                        it,
                    )}'"
                } ?: "NULL"}, '$action', ${value?.let { "'$it'" } ?: "NULL"}, 'synthetic', 'admin@example.com', " +
                "${utc(
                    createdAt,
                )}, ${validUntil?.let {
                    utc(
                        it,
                    )
                } ?: "NULL"}, ${revokedAt?.let { utc(it) } ?: "NULL"}, ${if (revokedAt != null) "'admin@example.com'" else "NULL"})",
        )
    }

    private fun run(): JobResult = ctx.getBean(BotJobExecutor::class.java).execute()

    /** 時計を進める。[fresh] なら、取込が開催日の確認の時刻を 10 分前に更新し続けているものとする（古い確認を試すテストだけ false）。 */
    private fun at(
        h: Int,
        m: Int,
        s: Int = 0,
        date: LocalDate = d,
        fresh: Boolean = true,
    ) {
        clock.instant = jst(date, h, m, s)
        if (fresh) db.exec("UPDATE race_day SET last_confirmed_at = ${utc(clock.instant.minusSeconds(600))}")
    }

    @BeforeEach
    fun reset() {
        db.resetData()
        launcher.requests.clear()
        launcher.result = LaunchResult.Launched("exec-synthetic")
        facts.facts.clear()
        mode.mode = OpsMode.NORMAL
    }

    @AfterEach
    fun restoreZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    private fun threeVenues() {
        for (v in listOf("05", "08", "01")) {
            seedDay(venue = v)
            seedRaces(venue = v)
        }
    }

    // --- C01〜C03 ---

    @Test
    fun `C01 通常の開催（3 場 12 レース）は窓の開始ちょうどに起動を要求し、開始 1 秒前は要求しない`() {
        threeVenues()
        at(7, 44, 59)
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), run())
        assertTrue(launcher.requests.isEmpty())
        at(7, 45)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        val req = launcher.requests.single()
        assertEquals(LaunchableJob.BOT_RUN, req.job)
        assertEquals(1L, req.tenantId.value)
        assertEquals(mapOf("date" to "2026-10-24", "trigger" to "SCHEDULER"), req.params)
    }

    @Test
    fun `C01 窓の終了（最後の発走 +90 分）ちょうどまでは起動を要求し、1 秒後は要求しない`() {
        threeVenues()
        // 最後の発走 = 9:55 + 25 分 × 11 = 14:30。+90 分 = 16:00
        at(16, 0)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        launcher.requests.clear()
        at(16, 0, 1)
        assertEquals(JobResult(JobExit.OK, "AFTER_SETTLE_DEADLINE"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `C02 C03 平日・祝日・土曜でも同じ（曜日・祝日を見ない）`() {
        for (date in listOf(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 1, 1))) {
            db.resetData()
            launcher.requests.clear()
            seedDay(date)
            seedRaces(date)
            at(8, 0, date = date)
            assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run(), "$date")
            assertEquals(date.toString(), launcher.requests.single().params["date"])
        }
    }

    // --- C04・C05・C14・C15 ---

    @Test
    fun `C04 取得の事実が新鮮で予定が無ければ開催なし（0・起動しない）、事実が無ければ不明（75・起動しない）`() {
        at(8, 0)
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNKNOWN"), run())
        facts.facts[TenantId.of(1) to d] = ScheduleFetchFact(d, jst(d, 7, 0))
        assertEquals(JobResult(JobExit.OK, "NOT_A_RACE_DAY"), run())
        facts.facts[TenantId.of(1) to d] = ScheduleFetchFact(d, jst(d, 5, 59))
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_STALE"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `C05 STOPPED から NORMAL に戻っても非開催日では起動しない`() {
        at(8, 0)
        facts.facts[TenantId.of(1) to d] = ScheduleFetchFact(d, jst(d, 7, 30))
        mode.mode = OpsMode.STOPPED
        assertEquals(JobResult(JobExit.OK, "OPS_MODE_NOT_NORMAL"), run())
        mode.mode = OpsMode.NORMAL
        assertEquals(JobResult(JobExit.OK, "NOT_A_RACE_DAY"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `C14 朝に RA が無く PLANNED のままは 75（UNCONFIRMED）で起動せず、RA 到着後に起動する`() {
        seedDay(status = "PLANNED")
        at(8, 0)
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNCONFIRMED"), run())
        assertTrue(launcher.requests.isEmpty())
        seedRaces()
        db.exec("UPDATE race_day SET status = 'CONFIRMED', version = version + 1")
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    @Test
    fun `C15 確認が古い（3 時間前）と起動せず 75、行の時刻が新しければ起動する`() {
        seedDay(confirmedAt = jst(d, 6, 0))
        seedRaces()
        at(9, 0, fresh = false)
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_STALE"), run())
        assertTrue(launcher.requests.isEmpty())
        db.exec("UPDATE race_day SET last_confirmed_at = ${utc(jst(d, 8, 30))}")
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    // --- C06〜C13 ---

    @Test
    fun `C06 延期の当日は起動せず、振替先の日は起動する`() {
        val next = d.plusDays(1)
        seedDay(date = d, status = "POSTPONED", replacedBy = next)
        seedDay(date = next)
        seedRaces(date = next)
        at(8, 0)
        assertEquals(JobResult(JobExit.OK, "DAY_POSTPONED"), run())
        at(8, 0, date = next)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        assertEquals(next.toString(), launcher.requests.single().params["date"])
    }

    @Test
    fun `C07 全レース中止は起動しない、C08 一部の中止は窓から除いて起動する`() {
        seedDay()
        seedRaces(status = "CANCELLED")
        at(8, 0)
        assertEquals(JobResult(JobExit.OK, "DAY_CANCELLED"), run())
        db.exec("UPDATE race_schedule SET status = 'CARD' WHERE race_no > 1")
        // 1R（9:55）だけ中止 → 最初の発走は 2R の 10:20、起動してよくなるのは 08:10
        at(8, 9, 59)
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), run())
        at(8, 10)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    @Test
    fun `C09 延期先が不明は 75、手動の POSTPONE_TO で解消する`() {
        seedDay(status = "POSTPONED")
        at(8, 0)
        assertEquals(JobResult(JobExit.TEMPFAIL, "POSTPONED_TARGET_UNKNOWN"), run())
        seedOverride("POSTPONE_TO", value = """{"to":"2026-10-25"}""")
        assertEquals(JobResult(JobExit.OK, "DAY_POSTPONED"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `C10 発走時刻が後ろへ変更されると窓も動く`() {
        seedDay()
        seedRaces()
        at(7, 45)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        launcher.requests.clear()
        db.exec("UPDATE race_schedule SET scheduled_post_at = DATE_ADD(scheduled_post_at, INTERVAL 2 HOUR), version = version + 1")
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), run())
        assertTrue(launcher.requests.isEmpty())
        at(9, 45)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    @Test
    fun `C12 臨時のレースの追加で窓が広がる`() {
        seedDay()
        seedRaces(count = 1)
        at(7, 0)
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), run())
        // 午前の臨時レースが 1 つ増える（最初の発走が 06:00 になる）
        db.exec(
            "INSERT INTO race_schedule (tenant_id, race_id, race_date, venue_code, race_no, scheduled_post_at, post_time_unknown, status, " +
                "data_kubun, source_version, last_changed_at) VALUES " +
                "(1, '202610240512', '2026-10-24', '05', 12, ${utc(jst(d, 9, 0))}, 0, 'CARD', '2', '202610232', ${utc(jst(d, 6, 30))})",
        )
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    @Test
    fun `C13 削除（DELETED）のレースは窓に含めない`() {
        seedDay()
        seedRaces(count = 2)
        db.exec("UPDATE race_schedule SET status = 'DELETED' WHERE race_no = 1")
        at(8, 10)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        launcher.requests.clear()
        at(8, 9, 59)
        assertEquals(JobResult(JobExit.OK, "BEFORE_WATCH_WINDOW"), run())
    }

    // --- C17〜C19 ---

    @Test
    fun `C17 手動の開催日と発走時刻（予定は出走馬名表で時刻なし）で起動できる`() {
        seedDay(status = "PLANNED", confirmedAt = jst(d, 6, 0))
        seedRaces(count = 1, status = "ANNOUNCED", withTime = false)
        at(8, 30)
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNCONFIRMED"), run())
        seedOverride("MARK_RACE_DAY")
        assertEquals(JobResult(JobExit.TEMPFAIL, "NO_POST_TIME"), run())
        seedOverride("SET_POST_TIME", scope = "RACE", raceNo = 1, value = """{"post_at":"2026-10-24T01:30:00Z"}""") // JST 10:30
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    @Test
    fun `C18 手動の開催なしは自動データがあっても起動しない`() {
        seedDay()
        seedRaces()
        seedOverride("MARK_NO_RACE")
        at(8, 0)
        assertEquals(JobResult(JobExit.OK, "NOT_A_RACE_DAY"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `C19 DENY_RUN は ALLOW_RUN があっても拒否し、取消・期限切れで解除される`() {
        seedDay()
        seedRaces()
        seedOverride("ALLOW_RUN")
        seedOverride("DENY_RUN", validUntil = jst(d, 8, 30))
        at(8, 0)
        assertEquals(JobResult(JobExit.OK, "DENIED_BY_OVERRIDE"), run())
        at(8, 29, 59)
        assertEquals(JobResult(JobExit.OK, "DENIED_BY_OVERRIDE"), run())
        at(8, 30) // 期限ちょうどは期限切れ
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        launcher.requests.clear()
        seedOverride("DENY_RUN", createdAt = jst(d, 8, 31))
        at(8, 40)
        assertEquals(JobResult(JobExit.OK, "DENIED_BY_OVERRIDE"), run())
        db.exec("UPDATE calendar_override SET revoked_at = ${utc(jst(d, 8, 45))}, revoked_by = 'admin@example.com' WHERE created_at = ${utc(jst(d, 8, 31))}")
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
    }

    // --- C20・C21 ---

    @Test
    fun `C20 実行中の bot-run があれば起動を要求せず、連続実行しても同じ（起動要求の重複は起き得るがリースが止める）`() {
        threeVenues()
        at(8, 0)
        // 起動要求は冪等ではない: bot-run がまだリースを取っていない間の連続実行は、要求が重複する
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        assertEquals(2, launcher.requests.size)
        // 起動された側（bot-run）がリースを取る。取れるのは 1 本だけ
        val runs = ctx.getBean(BotRunRepository::class.java)
        val first = assertIs<AcquireResult.Acquired>(runs.acquire(lease("owner-a")))
        assertIs<AcquireResult.AlreadyActive>(runs.acquire(lease("owner-b")))
        // 以後の calendar-check は起動を要求しない
        launcher.requests.clear()
        repeat(3) { assertEquals(JobResult(JobExit.OK, "ALREADY_ACTIVE"), run()) }
        assertTrue(launcher.requests.isEmpty())
        assertEquals(first.run.runId, runs.findActive(TenantId.of(1), d)!!.runId)
    }

    @Test
    fun `C21 リースが期限切れの実行が残っていれば起動を要求し、新しい実行が奪取して checkpoint を引き継げる`() {
        threeVenues()
        at(8, 0)
        val runs = ctx.getBean(BotRunRepository::class.java)
        val old = assertIs<AcquireResult.Acquired>(runs.acquire(lease("crashed", clock.instant))).run
        runs.saveCheckpoint(TenantId.of(1), old.runId, "crashed", old.version, """{"stage":"MONITORING"}""", clock.instant)
        // リース期限（5 分）の前は ALREADY_ACTIVE、期限ちょうどからは起動を要求する
        clock.instant = clock.instant.plus(Duration.ofMinutes(5)).minusNanos(1000)
        assertEquals(JobResult(JobExit.OK, "ALREADY_ACTIVE"), run())
        clock.instant = clock.instant.plusNanos(1000)
        assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run())
        val taken = assertIs<AcquireResult.Acquired>(runs.acquire(lease("new", clock.instant)))
        assertEquals(old.runId, taken.takenOver!!.runId)
        assertTrue(taken.takenOver!!.checkpoint!!.contains("MONITORING"))
    }

    // --- C23・C24・配線 ---

    @Test
    fun `C23 DB に接続できなければ 75（再試行ループなし）で、起動を要求しない`() {
        threeVenues()
        at(8, 0)
        // この試験専用の中継と Spring のコンテキスト（共有の中継を止めて同じポートで再開すると、他のプロセスにポートを取られて失敗し得るので、再開しない）
        val (host, port) = MySqlSupport.hostAndPort()
        TcpProxy(host, port).start().use { own ->
            val viaOwn = start(url = "jdbc:mysql://127.0.0.1:${own.port}/${db.name}")
            val executor = viaOwn.getBean(BotJobExecutor::class.java)
            assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), executor.execute())
            launcher.requests.clear()
            own.stop()
            val started = System.nanoTime()
            assertEquals(JobResult(JobExit.TEMPFAIL, "DB_UNAVAILABLE"), executor.execute())
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(60), "再試行で長く待っている")
            assertTrue(launcher.requests.isEmpty())
        }
    }

    @Test
    fun `C24 コンテナの TZ が UTC でも Asia-Tokyo でも、JST 0 時の前後で評価する日が切り替わる`() {
        // 25 日の開催（最初の発走 00:30 JST。起動してよいのは前日 22:20 JST）。24 日は予定なし
        val next = d.plusDays(1)
        seedDay(date = next, confirmedAt = jst(d, 23, 0))
        seedRaces(date = next, count = 1, firstPost = 0 to 30)
        for (zone in listOf("UTC", "Asia/Tokyo", "America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            launcher.requests.clear()
            clock.instant = Instant.parse("2026-10-24T14:59:59Z") // JST 23:59:59（24 日）
            assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNKNOWN"), run(), zone)
            assertTrue(launcher.requests.isEmpty(), zone)
            clock.instant = Instant.parse("2026-10-24T15:00:00Z") // JST 00:00:00（25 日）
            assertEquals(JobResult(JobExit.OK, "LAUNCH_REQUESTED"), run(), zone)
            assertEquals("2026-10-25", launcher.requests.single().params["date"], zone)
        }
    }

    @Test
    fun `別のテナントの予定では起動しない（テナント越境なし）`() {
        seedDay(tenant = 2)
        seedRaces(tenant = 2)
        at(8, 0)
        assertEquals(JobResult(JobExit.TEMPFAIL, "SCHEDULE_UNKNOWN"), run())
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun `本番の配線（Fake なし）では起動基盤が未設定で fail closed、取得の事実も無い`() {
        threeVenues()
        // 時計は本番のまま（システム時計）だと窓の外になるので、起動基盤と取得の事実だけを本番の配線にして、時計だけ差し替える
        val prod =
            SpringApplicationBuilder(BotApplication::class.java)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .initializers(
                    ApplicationContextInitializer<ConfigurableApplicationContext> { c ->
                        c.beanFactory.registerSingleton(
                            "botRuntime",
                            BotRuntime(BotCommand(BotTask.CALENDAR_CHECK, TenantId.of(1)), BotSettings.from(env(), BotTask.CALENDAR_CHECK), mode),
                        )
                        c.beanFactory.registerSingleton("testClock", clock)
                    },
                ).run()
        contexts += prod
        assertIs<UnconfiguredJobLauncher>(prod.getBean(JobLauncher::class.java))
        assertEquals("DbScheduleFetchFactReader", prod.getBean(ScheduleFetchFactReader::class.java)::class.simpleName, "本番の配線は DB の取得の事実")
        at(8, 0)
        assertEquals(JobResult(JobExit.FAILURE, "LAUNCH_FAILED"), prod.getBean(BotJobExecutor::class.java).execute())
    }

    private fun lease(
        owner: String,
        now: Instant = clock.instant,
    ) = LeaseRequest(TenantId.of(1), RunId.of(UUID.randomUUID()), d, BotRunTrigger.SCHEDULER, owner, now, Duration.ofMinutes(5))
}
