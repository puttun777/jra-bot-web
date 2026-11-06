package jp.jrabot.persistence.integration

import com.zaxxer.hikari.HikariDataSource
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.PersistenceConfiguration
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.jdbc.DbSettings
import org.seasar.doma.boot.autoconfigure.DomaAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Instant
import javax.sql.DataSource

/** テストで動かせる時計。 */
class MutableClock(
    var instant: Instant = Instant.parse("2026-10-23T03:00:00.123456Z"),
) : Clock {
    override fun now(): Instant = instant
}

val T1: TenantId = TenantId.of(1)
val T2: TenantId = TenantId.of(2)

/**
 * 本番と同じ配線（[PersistenceConfiguration] + doma-spring-boot-starter + [DataSourceFactory]）の Spring コンテキストを、
 * 指定した DB ユーザーで開く。Repository は公開された型（ドメインのポート）でだけ取り出す。
 */
class RepositoryHarness(
    val db: TestDb,
    val user: String = "migrate",
    val clock: MutableClock = MutableClock(),
    poolMax: Int = 4,
    /** 接続先（既定は [db] を直接）。DB 停止の再現では [TcpProxy] 経由の URL を渡す。 */
    url: String = MySqlSupport.jdbcUrl(db.name),
) : AutoCloseable {
    private val pool: HikariDataSource =
        DataSourceFactory.create(DbSettings(url, user, MySqlSupport.PASSWORDS.getValue(user), poolMax), "test-$user")

    private val context =
        AnnotationConfigApplicationContext().apply {
            beanFactory.registerSingleton("dataSource", pool)
            beanFactory.registerSingleton("clock", clock)
            register(PersistenceConfiguration::class.java, DomaAutoConfiguration::class.java)
            refresh()
        }

    val races: RaceRepository get() = context.getBean(RaceRepository::class.java)
    val odds: OddsSnapshotRepository get() = context.getBean(OddsSnapshotRepository::class.java)
    val botRuns: BotRunRepository get() = context.getBean(BotRunRepository::class.java)
    val appUsers: AppUserRepository get() = context.getBean(AppUserRepository::class.java)
    val schedules: RaceScheduleRepository get() = context.getBean(RaceScheduleRepository::class.java)
    val signals: SignalRepository get() = context.getBean(SignalRepository::class.java)
    val raceDays: RaceDayRepository get() = context.getBean(RaceDayRepository::class.java)
    val events: ScheduleEventRepository get() = context.getBean(ScheduleEventRepository::class.java)
    val overrides: CalendarOverrideRepository get() = context.getBean(CalendarOverrideRepository::class.java)
    val models: ModelRegistryReader get() = context.getBean(ModelRegistryReader::class.java)
    val catalog: jp.jrabot.domain.ml.registry.ModelCatalogReader get() = context.getBean(jp.jrabot.domain.ml.registry.ModelCatalogReader::class.java)
    val results: jp.jrabot.domain.ingest.ResultStore get() = context.getBean(jp.jrabot.domain.ingest.ResultStore::class.java)
    val payouts: jp.jrabot.domain.ingest.PayoutStore get() = context.getBean(jp.jrabot.domain.ingest.PayoutStore::class.java)
    val dataSource: DataSource get() = context.getBean(DataSource::class.java)

    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    override fun close() {
        context.close()
        pool.close()
    }
}
