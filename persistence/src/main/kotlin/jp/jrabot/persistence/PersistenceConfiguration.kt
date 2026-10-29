package jp.jrabot.persistence

import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.botrun.RaceEntryReader
import jp.jrabot.domain.botrun.RaceEvaluationWriter
import jp.jrabot.domain.botrun.TimingNoticeStore
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.ingest.AgentCredentialStore
import jp.jrabot.domain.ingest.AgentHeartbeatStore
import jp.jrabot.domain.ingest.BackfillStore
import jp.jrabot.domain.ingest.FetchStore
import jp.jrabot.domain.ingest.IngestBatchStore
import jp.jrabot.domain.ingest.IngestCursorStore
import jp.jrabot.domain.ingest.IngestGapStore
import jp.jrabot.domain.ingest.NonceStore
import jp.jrabot.domain.ingest.RawRecordStore
import jp.jrabot.domain.ingest.UnitOfWork
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.HorseMasterHistoryStore
import jp.jrabot.domain.ml.registry.ModelRegistryReader
import jp.jrabot.domain.odds.ComboOddsSnapshotRepository
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.AppUserRepository
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.AgentDaoImpl
import jp.jrabot.persistence.dao.AgentHeartbeatDaoImpl
import jp.jrabot.persistence.dao.AgentNonceDaoImpl
import jp.jrabot.persistence.dao.AppUserDaoImpl
import jp.jrabot.persistence.dao.BotRunDaoImpl
import jp.jrabot.persistence.dao.CalendarOverrideDaoImpl
import jp.jrabot.persistence.dao.ComboOddsSnapshotDaoImpl
import jp.jrabot.persistence.dao.HorseMasterHistoryDaoImpl
import jp.jrabot.persistence.dao.IngestBackfillDaoImpl
import jp.jrabot.persistence.dao.IngestBatchDaoImpl
import jp.jrabot.persistence.dao.IngestCursorDaoImpl
import jp.jrabot.persistence.dao.IngestFetchDaoImpl
import jp.jrabot.persistence.dao.IngestGapDaoImpl
import jp.jrabot.persistence.dao.IngestRawDaoImpl
import jp.jrabot.persistence.dao.MlModelDaoImpl
import jp.jrabot.persistence.dao.MlSourceDaoImpl
import jp.jrabot.persistence.dao.OddsSnapshotDaoImpl
import jp.jrabot.persistence.dao.RaceDaoImpl
import jp.jrabot.persistence.dao.RaceDayDaoImpl
import jp.jrabot.persistence.dao.RaceEvaluationDaoImpl
import jp.jrabot.persistence.dao.RaceHorseNameDaoImpl
import jp.jrabot.persistence.dao.RaceScheduleDaoImpl
import jp.jrabot.persistence.dao.RaceTimingNoticeDaoImpl
import jp.jrabot.persistence.dao.ScheduleEventDaoImpl
import jp.jrabot.persistence.dao.SignalDaoImpl
import jp.jrabot.persistence.doma.jrabotMysqlDialect
import jp.jrabot.persistence.repository.AgentHeartbeatStoreImpl
import jp.jrabot.persistence.repository.AgentRegistrar
import jp.jrabot.persistence.repository.AgentRegistrarImpl
import jp.jrabot.persistence.repository.AgentRepositoryImpl
import jp.jrabot.persistence.repository.AppUserRepositoryImpl
import jp.jrabot.persistence.repository.BackfillStoreImpl
import jp.jrabot.persistence.repository.BotRunRepositoryImpl
import jp.jrabot.persistence.repository.CalendarOverrideRepositoryImpl
import jp.jrabot.persistence.repository.ComboOddsSnapshotRepositoryImpl
import jp.jrabot.persistence.repository.DbDatasetSource
import jp.jrabot.persistence.repository.DbFeatureSource
import jp.jrabot.persistence.repository.DbScheduleFetchFactReader
import jp.jrabot.persistence.repository.FetchStoreImpl
import jp.jrabot.persistence.repository.HorseMasterHistoryStoreImpl
import jp.jrabot.persistence.repository.IngestBatchStoreImpl
import jp.jrabot.persistence.repository.IngestCursorStoreImpl
import jp.jrabot.persistence.repository.IngestGapStoreImpl
import jp.jrabot.persistence.repository.MlModelRegistryRepository
import jp.jrabot.persistence.repository.NonceStoreImpl
import jp.jrabot.persistence.repository.OddsSnapshotRepositoryImpl
import jp.jrabot.persistence.repository.RaceDayRepositoryImpl
import jp.jrabot.persistence.repository.RaceEntryReaderImpl
import jp.jrabot.persistence.repository.RaceEvaluationRepositoryImpl
import jp.jrabot.persistence.repository.RaceRepositoryImpl
import jp.jrabot.persistence.repository.RaceScheduleRepositoryImpl
import jp.jrabot.persistence.repository.RawRecordStoreImpl
import jp.jrabot.persistence.repository.ScheduleEventRepositoryImpl
import jp.jrabot.persistence.repository.SignalRepositoryImpl
import jp.jrabot.persistence.repository.SpringUnitOfWork
import jp.jrabot.persistence.repository.TimingNoticeStoreImpl
import org.seasar.doma.jdbc.Config
import org.seasar.doma.jdbc.dialect.Dialect
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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

    /** モデルレジストリの読取り（bot が run の開始時に ACTIVE を固定する: T3.8）。書込み（昇格）は Stage 4 の管理操作で、ここには無い。 */
    @Bean
    fun modelRegistryReader(config: Config): ModelRegistryReader = MlModelRegistryRepository(MlModelDaoImpl(config))

    /** 特徴量の入力（学習用データセット・推論）。読取りだけ。すべての SQL が tenant_id と時刻の上限（asOf・レース日）を持つ。 */
    @Bean
    fun featureSource(config: Config): FeatureSource = DbFeatureSource(MlSourceDaoImpl(config))

    /** 学習用データセットの元データ（出走馬・確定着順・asOf 以前のオッズ）。読取りだけ。 */
    @Bean
    fun datasetSource(config: Config): DatasetSource = DbDatasetSource(MlSourceDaoImpl(config))

    /** 馬マスタの累積値の版（追記専用。書込みは取込側。bot は SELECT のみで読む）。 */
    @Bean
    fun horseMasterHistoryStore(
        config: Config,
        tx: TransactionOperations,
        clock: Clock,
    ): HorseMasterHistoryStore = HorseMasterHistoryStoreImpl(HorseMasterHistoryDaoImpl(config), tx, clock)

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
    ): RaceScheduleRepository = RaceScheduleRepositoryImpl(RaceScheduleDaoImpl(config), ScheduleEventDaoImpl(config), tx)

    @Bean
    fun raceDayRepository(
        config: Config,
        tx: TransactionOperations,
    ): RaceDayRepository = RaceDayRepositoryImpl(RaceDayDaoImpl(config), ScheduleEventDaoImpl(config), tx)

    @Bean
    fun scheduleEventRepository(
        config: Config,
        tx: TransactionOperations,
    ): ScheduleEventRepository = ScheduleEventRepositoryImpl(ScheduleEventDaoImpl(config), tx)

    @Bean
    fun calendarOverrideRepository(
        config: Config,
        tx: TransactionOperations,
    ): CalendarOverrideRepository = CalendarOverrideRepositoryImpl(CalendarOverrideDaoImpl(config), ScheduleEventDaoImpl(config), tx)

    @Bean
    fun signalRepository(
        config: Config,
        clock: Clock,
        tx: TransactionOperations,
    ): SignalRepository = SignalRepositoryImpl(SignalDaoImpl(config), clock, tx)

    // ---- 取込（T3.4）。ingest が書き、bot が取得の事実を読む。ここは Config・トランザクション・時計だけで作れるものに限る（KEK などの秘密は :ingest 側）

    @Bean
    fun unitOfWork(transactionManager: PlatformTransactionManager): UnitOfWork = SpringUnitOfWork(transactionManager)

    @Bean
    fun agentCredentialStore(config: Config): AgentCredentialStore = AgentRepositoryImpl(AgentDaoImpl(config))

    @Bean
    fun agentRegistrar(
        config: Config,
        tx: TransactionOperations,
    ): AgentRegistrar = AgentRegistrarImpl(AgentDaoImpl(config), tx)

    @Bean
    fun nonceStore(
        config: Config,
        tx: TransactionOperations,
    ): NonceStore = NonceStoreImpl(AgentNonceDaoImpl(config), tx)

    @Bean
    fun agentHeartbeatStore(
        config: Config,
        tx: TransactionOperations,
    ): AgentHeartbeatStore = AgentHeartbeatStoreImpl(AgentHeartbeatDaoImpl(config), tx)

    @Bean
    fun ingestBatchStore(
        config: Config,
        tx: TransactionOperations,
    ): IngestBatchStore = IngestBatchStoreImpl(IngestBatchDaoImpl(config), tx)

    @Bean
    fun rawRecordStore(
        config: Config,
        tx: TransactionOperations,
    ): RawRecordStore = RawRecordStoreImpl(IngestRawDaoImpl(config), tx)

    @Bean
    fun fetchStore(
        config: Config,
        tx: TransactionOperations,
    ): FetchStore = FetchStoreImpl(IngestFetchDaoImpl(config), tx)

    /** 取得の事実（YSCH の完全な取得）。DB の `ingest_fetch` から読む。bot の calendar-check がこれを使う。 */
    @Bean
    @ConditionalOnMissingBean(ScheduleFetchFactReader::class) // テストは合成の事実に差し替える（本番に差し替えの口は無い。配線は bot のテストが検査）
    fun scheduleFetchFactReader(config: Config): ScheduleFetchFactReader = DbScheduleFetchFactReader(IngestFetchDaoImpl(config))

    @Bean
    fun ingestCursorStore(
        config: Config,
        tx: TransactionOperations,
    ): IngestCursorStore = IngestCursorStoreImpl(IngestCursorDaoImpl(config), tx)

    @Bean
    fun ingestGapStore(
        config: Config,
        tx: TransactionOperations,
    ): IngestGapStore = IngestGapStoreImpl(IngestGapDaoImpl(config), tx)

    @Bean
    fun backfillStore(
        config: Config,
        tx: TransactionOperations,
    ): BackfillStore = BackfillStoreImpl(IngestBackfillDaoImpl(config), tx)

    @Bean
    fun comboOddsSnapshotRepository(
        config: Config,
        tx: TransactionOperations,
        clock: Clock,
    ): ComboOddsSnapshotRepository = ComboOddsSnapshotRepositoryImpl(ComboOddsSnapshotDaoImpl(config), tx, clock)

    /** 判定の記録（bot-run が書く。冪等な追記）。 */
    @Bean
    fun raceEvaluationWriter(
        config: Config,
        clock: Clock,
        tx: TransactionOperations,
    ): RaceEvaluationWriter = RaceEvaluationRepositoryImpl(RaceEvaluationDaoImpl(config), clock, tx)

    /** 通知タイミングの確定・通知内容のスナップショット（V5。bot-run が書く。追記専用）。 */
    @Bean
    fun timingNoticeStore(
        config: Config,
        tx: TransactionOperations,
    ): TimingNoticeStore = TimingNoticeStoreImpl(RaceTimingNoticeDaoImpl(config), tx)

    /** 出走表の馬名（通知の理由文用。読取りだけ）。 */
    @Bean
    fun raceEntryReader(config: Config): RaceEntryReader = RaceEntryReaderImpl(RaceHorseNameDaoImpl(config))
}
