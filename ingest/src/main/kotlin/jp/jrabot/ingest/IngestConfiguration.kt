package jp.jrabot.ingest

import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.cloudgcp.ops.OpsModeProviders
import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.RaceDayUpdater
import jp.jrabot.domain.calendar.RaceScheduleUpdater
import jp.jrabot.domain.calendar.ScheduleEventRepository
import jp.jrabot.domain.ingest.AgentHeartbeatStore
import jp.jrabot.domain.ingest.ApplyPolicy
import jp.jrabot.domain.ingest.BackfillStore
import jp.jrabot.domain.ingest.BatchIngestor
import jp.jrabot.domain.ingest.EnvelopeValidator
import jp.jrabot.domain.ingest.FetchStore
import jp.jrabot.domain.ingest.HeartbeatService
import jp.jrabot.domain.ingest.IngestBatchStore
import jp.jrabot.domain.ingest.IngestCursorStore
import jp.jrabot.domain.ingest.IngestGapStore
import jp.jrabot.domain.ingest.ObservationApplier
import jp.jrabot.domain.ingest.RawRecordStore
import jp.jrabot.domain.ingest.RecordParsers
import jp.jrabot.domain.ingest.UnitOfWork
import jp.jrabot.domain.odds.ComboOddsSnapshotRepository
import jp.jrabot.domain.odds.OddsSnapshotRepository
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.race.RaceRepository
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.time.Clock
import jp.jrabot.domain.time.SystemClock
import jp.jrabot.ingest.agent.AgentApiController
import jp.jrabot.ingest.agent.AgentMetaController
import jp.jrabot.ingest.agent.JsonTrees
import jp.jrabot.ingest.agent.NormalizedJsonRecordParser
import jp.jrabot.ingest.agent.PlanProvider
import jp.jrabot.ingest.agent.UnavailableHeartbeatMirror
import jp.jrabot.persistence.PersistenceConfiguration
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.schema.AppBuildInfo
import jp.jrabot.persistence.schema.SchemaVersionVerifier
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * :ingest の Bean 構成（T2.8）。DB は [DataSourceFactory.createLazy]（起動時に接続しない）、運用モードは
 * `OpsModeProviders.fromEnvironment`（`gs://` は GCS、ローカルファイルは `APP_ENV=local` のときだけ。本番で設定が足りなければ起動失敗で、
 * ローカル実装へ黙ってフォールバックしない）。Flyway は使わない（マイグレーションは :db-migration だけ）。
 */
@Configuration(proxyBeanMethods = false)
@Import(PersistenceConfiguration::class)
class IngestConfiguration {
    @Bean
    fun clock(): Clock = SystemClock

    @Bean
    fun ingestSettings(env: Environment): IngestSettings = IngestSettings.from { env.getProperty(it) }

    @Bean
    fun appBuildInfo(settings: IngestSettings): AppBuildInfo = AppBuildInfo.load(settings.releaseIdOverride)

    @Bean
    fun dataSource(settings: IngestSettings): DataSource = DataSourceFactory.createLazy(settings.db, "ingest")

    @Bean
    fun modeProvider(
        settings: IngestSettings,
        clock: Clock,
    ): InspectableModeProvider = OpsModeProviders.fromEnvironment(settings.opsEnv, clock)

    @Bean
    fun schemaVersionVerifier(
        dataSource: DataSource,
        info: AppBuildInfo,
    ): SchemaVersionVerifier = SchemaVersionVerifier(dataSource, info.requiredSchemaVersion)

    /** `/internal` の呼び出し元の検証（CONTRACTS §11.2: Google ID トークン、aud=INTERNAL_ID_TOKEN_AUDIENCE、email=OPS_SA_EMAIL）。 */
    @Bean
    fun internalCallerVerifier(
        settings: IngestSettings,
        clock: Clock,
    ): InternalCallerVerifier = GoogleSignedTokenVerifier.googleIdToken(settings.internalAudience, settings.opsServiceAccountEmail, clock)

    // ---- エージェント API（T3.4）

    @Bean
    fun jsonTrees(settings: IngestSettings): JsonTrees = JsonTrees(settings.ingest.maxInflatedBytes)

    @Bean
    fun recordParsers(
        settings: IngestSettings,
        json: JsonTrees,
    ): RecordParsers =
        // 実データ（JV-Data）のパーサは一次仕様の確認後（T1.17）。それまでは合成の正規化 JSON（ローカル・テストだけ）しか解析できない
        RecordParsers(if (settings.ingest.acceptNormalizedRecords) listOf(NormalizedJsonRecordParser(json)) else emptyList())

    @Bean
    fun heartbeatMirror(): HeartbeatMirror = UnavailableHeartbeatMirror()

    @Bean
    fun envelopeValidator(): EnvelopeValidator = EnvelopeValidator()

    @Bean
    fun observationApplier(
        settings: IngestSettings,
        days: RaceDayRepository,
        schedules: RaceScheduleRepository,
        events: ScheduleEventRepository,
        races: RaceRepository,
        odds: OddsSnapshotRepository,
        combos: ComboOddsSnapshotRepository,
        clock: Clock,
    ): ObservationApplier =
        ObservationApplier(
            RaceDayUpdater(days, clock),
            days,
            RaceScheduleUpdater(schedules, events, clock),
            races,
            odds,
            combos,
            ApplyPolicy(settings.ingest.oddsBelowPar, settings.ingest.storeComboOdds),
        )

    @Bean
    fun batchIngestor(
        batches: IngestBatchStore,
        raw: RawRecordStore,
        fetches: FetchStore,
        cursors: IngestCursorStore,
        backfills: BackfillStore,
        gaps: IngestGapStore,
        events: ScheduleEventRepository,
        parsers: RecordParsers,
        applier: ObservationApplier,
        races: RaceRepository,
        uow: UnitOfWork,
        clock: Clock,
    ): BatchIngestor =
        BatchIngestor(
            batches, raw, fetches, cursors, backfills, gaps, events, parsers, applier, races, uow, clock,
            onReplay = { tenantId, report ->
                // 残件・失敗がある再適用だけ記録する（件数のみ。レコードの中身や秘密は出さない）。残件は次にそのレースの観測が来たときに再試行される
                if (report.leftover) {
                    LoggerFactory.getLogger(BatchIngestor::class.java).warn(
                        "pending replay left rows: tenant={} scanned={} settled={} stillPending={} rowErrors={} aborted={}",
                        tenantId.value, report.scanned, report.settled, report.stillPending, report.rowErrors, report.aborted,
                    )
                }
            },
        )

    @Bean
    fun heartbeatService(
        store: AgentHeartbeatStore,
        mirror: HeartbeatMirror,
        clock: Clock,
    ): HeartbeatService = HeartbeatService(store, mirror, clock)

    @Bean
    fun planProvider(
        settings: IngestSettings,
        json: JsonTrees,
        clock: Clock,
    ): PlanProvider = PlanProvider(settings.ingest.planItemsJson, settings.ingest.verifiedFetchSpecs, json, clock)

    @Bean
    fun agentMetaController(
        mode: InspectableModeProvider,
        plans: PlanProvider,
        json: JsonTrees,
        settings: IngestSettings,
    ): AgentMetaController = AgentMetaController(mode, plans, json, settings.ingest.updateManifestPath)

    @Bean
    fun agentApiController(
        ingestor: BatchIngestor,
        heartbeats: HeartbeatService,
        validator: EnvelopeValidator,
        json: JsonTrees,
        clock: Clock,
        settings: IngestSettings,
    ): AgentApiController = AgentApiController(ingestor, heartbeats, validator, json, clock, settings.ingest.maxInflatedBytes)
}
