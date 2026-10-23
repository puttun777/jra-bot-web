package jp.jrabot.persistence

import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.AppUserDaoImpl
import jp.jrabot.persistence.dao.BotRunDaoImpl
import jp.jrabot.persistence.dao.OddsSnapshotDaoImpl
import jp.jrabot.persistence.dao.RaceDaoImpl
import jp.jrabot.persistence.dao.RaceScheduleDaoImpl
import jp.jrabot.persistence.dao.SignalDaoImpl
import jp.jrabot.persistence.doma.jrabotMysqlDialect
import jp.jrabot.persistence.repository.AppUserRepositoryImpl
import jp.jrabot.persistence.repository.BotRunRepositoryImpl
import jp.jrabot.persistence.repository.OddsSnapshotRepositoryImpl
import jp.jrabot.persistence.repository.RaceRepositoryImpl
import jp.jrabot.persistence.repository.RaceScheduleRepositoryImpl
import jp.jrabot.persistence.repository.SignalRepositoryImpl
import org.seasar.doma.jdbc.Config
import org.seasar.doma.jdbc.dialect.Dialect
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * :persistence の Spring 配線。利用側（T2.8 のアプリ）は `DataSource`（[jp.jrabot.persistence.jdbc.DataSourceFactory]）と
 * ドメインの [Clock] を用意して、この構成を取り込む。Doma の `Config` は doma-spring-boot-starter の自動構成が作る
 * （`DataSource` を Spring のトランザクションに参加させる）。公開するのは Repository だけで、DAO・Entity は内部。
 */
@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {
    /** Doma 標準の `Timestamp` 経由をやめ、JVM のタイムゾーンに依存しない方言を使う（starter の既定の `dialect` を置き換える）。 */
    @Bean
    fun dialect(): Dialect = jrabotMysqlDialect()

    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager = DataSourceTransactionManager(dataSource)

    @Bean
    fun transactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations = TransactionTemplate(transactionManager)

    @Bean
    fun raceRepository(
        config: Config,
        clock: Clock,
    ): RaceRepository = RaceRepositoryImpl(RaceDaoImpl(config), clock)

    @Bean
    fun oddsSnapshotRepository(
        config: Config,
        tx: TransactionOperations,
    ): OddsSnapshotRepository = OddsSnapshotRepositoryImpl(OddsSnapshotDaoImpl(config), tx)

    @Bean
    fun botRunRepository(
        config: Config,
        tx: TransactionOperations,
    ): BotRunRepository = BotRunRepositoryImpl(BotRunDaoImpl(config), tx)

    @Bean
    fun appUserRepository(config: Config): AppUserRepository = AppUserRepositoryImpl(AppUserDaoImpl(config))

    @Bean
    fun raceScheduleRepository(
        config: Config,
        tx: TransactionOperations,
    ): RaceScheduleRepository = RaceScheduleRepositoryImpl(RaceScheduleDaoImpl(config), tx)

    @Bean
    fun signalRepository(
        config: Config,
        clock: Clock,
        tx: TransactionOperations,
    ): SignalRepository = SignalRepositoryImpl(SignalDaoImpl(config), clock, tx)
}
