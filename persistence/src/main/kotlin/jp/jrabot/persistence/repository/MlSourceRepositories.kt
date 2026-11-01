package jp.jrabot.persistence.repository

import jp.jrabot.domain.ml.dataset.DatasetEntry
import jp.jrabot.domain.ml.dataset.DatasetSource
import jp.jrabot.domain.ml.features.EntryHorse
import jp.jrabot.domain.ml.features.FeatureSource
import jp.jrabot.domain.ml.features.HistoryAppendOutcome
import jp.jrabot.domain.ml.features.HorseCumulative
import jp.jrabot.domain.ml.features.HorseMaster
import jp.jrabot.domain.ml.features.HorseMasterAtTime
import jp.jrabot.domain.ml.features.HorseMasterHistoryStore
import jp.jrabot.domain.ml.features.HorseMasterJson
import jp.jrabot.domain.ml.features.HorseMasterVersion
import jp.jrabot.domain.ml.features.OddsPoint
import jp.jrabot.domain.ml.features.PastRun
import jp.jrabot.domain.ml.features.RaceConditions
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.dao.HorseHistoryRow
import jp.jrabot.persistence.dao.HorseMasterHistoryDao
import jp.jrabot.persistence.dao.HorseRow
import jp.jrabot.persistence.dao.MlSourceDao
import jp.jrabot.persistence.mapping.toDbTime
import jp.jrabot.persistence.mapping.toInstantUtc
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

/** コード列（ASCII）の前後の空白を除く。無ければ空文字（旧実装 `str(x or "").strip()`）。 */
private fun String?.code(): String = this?.trim() ?: ""

/**
 * 特徴量の入力（[FeatureSource]）の MySQL 実装（読取りだけ）。すべての SQL が `tenant_id` を必須にし（SqlTemplateTenantGuardTest）、
 * **時刻の上限を SQL に持つ**: 過去走は `run_date < レース日`、馬マスタの版は `known_at <= asOf`。asOf より後の情報を DB から取り出さない。
 */
internal class DbFeatureSource(
    private val dao: MlSourceDao,
) : FeatureSource {
    override fun raceConditions(
        tenantId: TenantId,
        raceId: String,
    ): RaceConditions? {
        val r = dao.selectRace(tenantId.value, raceId) ?: return null
        val post =
            if (r.postTimeUnknown) {
                null
            } else {
                r.postTime?.let {
                    r.raceDate
                        .atTime(it)
                        .atZone(JST)
                        .toInstant()
                }
            }
        return RaceConditions(
            raceDate = r.raceDate,
            postTime = post,
            distanceM = r.distanceM ?: 0,
            trackTypeCd = r.trackTypeCd.code(),
            courseKubunCd = r.courseKubunCd.code(),
            tenkoCd = r.tenkoCd.code(),
            shibaBabaCd = r.shibaBabaCd.code(),
            dirtBabaCd = r.dirtBabaCd.code(),
            updatedAt = r.updatedAt.toInstantUtc(),
        )
    }

    override fun entry(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
    ): EntryHorse? = dao.selectEntry(tenantId.value, raceId, umaban)?.let { EntryHorse(it.kettoNum ?: "") }

    /** 最新の 1 行（累積値は『今』の値）。**学習・point-in-time では使わない**（[horseAsOf] を使う）。 */
    override fun horse(
        tenantId: TenantId,
        kettoNum: String,
    ): HorseMaster? = dao.selectHorse(tenantId.value, kettoNum)?.toMaster()

    override fun horseAsOf(
        tenantId: TenantId,
        kettoNum: String,
        asOf: Instant,
    ): HorseMaster? {
        val current = dao.selectHorse(tenantId.value, kettoNum)?.toMaster() ?: return null
        val history = dao.selectHistoryAsOf(tenantId.value, kettoNum, asOf.toDbTime())?.toCumulative()
        return HorseMasterAtTime.resolve(current, history, asOf)
    }

    override fun pastRuns(
        tenantId: TenantId,
        kettoNum: String,
        before: LocalDate?,
        limit: Int,
    ): List<PastRun> {
        require(limit in 1..1000) { "limit は 1〜1000" }
        return dao.selectPastRuns(tenantId.value, kettoNum, before, limit).map { PastRun(it.raceId, it.runDate, it.finish ?: 0) }
    }
}

private fun HorseRow.toMaster(): HorseMaster =
    HorseMaster(
        kettoNum = kettoNum,
        birthDate = birthDate,
        styleHint = HorseMasterJson.styleHint(styleTendencyJson),
        distanceTable = HorseMasterJson.chakuTable(chakuByDistanceJson),
        surfaceTable = HorseMasterJson.chakuTable(chakuBySurfaceJson),
        conditionTable = HorseMasterJson.chakuTable(chakuByConditionJson),
        pedigreeNames = HorseMasterJson.pedigreeNames(pedigreeJson),
        updatedAt = updatedAt.toInstantUtc(),
    )

private fun HorseHistoryRow.toCumulative(): HorseCumulative =
    HorseCumulative(
        knownAt = knownAt.toInstantUtc(),
        styleHint = HorseMasterJson.styleHint(styleTendencyJson),
        distanceTable = HorseMasterJson.chakuTable(chakuByDistanceJson),
        surfaceTable = HorseMasterJson.chakuTable(chakuBySurfaceJson),
        conditionTable = HorseMasterJson.chakuTable(chakuByConditionJson),
    )

/**
 * 学習用データセットの元データ（[DatasetSource]）の MySQL 実装（読取りだけ）。出走馬は期間内のレースの `race_horses`、確定着順は `results`
 * （無ければ `finish = null` = ラベル無し。勝敗が確定したものとして扱わない）、オッズは `observed_at <= asOf` の単勝だけ（SQL の条件）。
 */
internal class DbDatasetSource(
    private val dao: MlSourceDao,
) : DatasetSource {
    override fun entries(
        tenantId: TenantId,
        from: LocalDate,
        to: LocalDate,
    ): List<DatasetEntry> {
        val keys = dao.selectRaceEntries(tenantId.value, from, to)
        val finishByRace = HashMap<String, Map<Int, Int?>>()
        return keys.map { k ->
            val finishes = finishByRace.getOrPut(k.raceId) { dao.selectResults(tenantId.value, k.raceId).associate { it.umaban to it.finish } }
            DatasetEntry(k.raceId, k.umaban, finishes[k.umaban])
        }
    }

    override fun oddsHistory(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
        asOf: Instant,
    ): List<OddsPoint> =
        dao.selectWinOddsAsOf(tenantId.value, raceId, umaban, asOf.toDbTime()).map { OddsPoint(it.observedAt.toInstantUtc(), it.odds, it.popularity ?: 0) }
}

/** 馬マスタ履歴の追記（[HorseMasterHistoryStore]）。追記専用: 同じキーの再投入は内容が同じなら UNCHANGED、違えば CONFLICT（上書きしない）。 */
internal class HorseMasterHistoryStoreImpl(
    private val dao: HorseMasterHistoryDao,
    private val tx: TransactionOperations,
    private val clock: Clock,
) : HorseMasterHistoryStore {
    override fun append(
        tenantId: TenantId,
        kettoNum: String,
        version: HorseMasterVersion,
    ): HistoryAppendOutcome {
        val row =
            HorseHistoryRow(
                tenantId = tenantId.value,
                kettoNum = kettoNum,
                knownAt = version.knownAt.toDbTime(),
                source = version.source,
                styleTendencyJson = version.styleTendencyJson,
                chakuBySurfaceJson = version.chakuBySurfaceJson,
                chakuByConditionJson = version.chakuByConditionJson,
                chakuByDistanceJson = version.chakuByDistanceJson,
                raceCount = version.raceCount,
                createdAt = clock.now().toDbTime(),
            )
        return try {
            tx.inTransaction { dao.insert(row) }
            HistoryAppendOutcome.CREATED
        } catch (e: RuntimeException) {
            if (!e.isDuplicateOn("horse_master_history", "PRIMARY")) throw e
            val existing = dao.selectAll(tenantId.value, kettoNum).firstOrNull { it.knownAt == row.knownAt }
            if (existing != null && sameVersion(existing.toVersion(), version)) HistoryAppendOutcome.UNCHANGED else HistoryAppendOutcome.CONFLICT
        }
    }

    override fun versions(
        tenantId: TenantId,
        kettoNum: String,
    ): List<HorseMasterVersion> = dao.selectAll(tenantId.value, kettoNum).map { it.toVersion() }

    /** DB はマイクロ秒に切り捨てて保存し、JSON 列は正規化（キー順・空白）して保存するので、時刻は精度を、JSON は意味（木）を比べる。 */
    private fun sameVersion(
        stored: HorseMasterVersion,
        wanted: HorseMasterVersion,
    ): Boolean =
        stored.knownAt == wanted.knownAt.toDbTime().toInstantUtc() && stored.source == wanted.source && stored.raceCount == wanted.raceCount &&
            sameJson(stored.styleTendencyJson, wanted.styleTendencyJson) && sameJson(stored.chakuBySurfaceJson, wanted.chakuBySurfaceJson) &&
            sameJson(stored.chakuByConditionJson, wanted.chakuByConditionJson) && sameJson(stored.chakuByDistanceJson, wanted.chakuByDistanceJson)

    private fun sameJson(
        a: String?,
        b: String?,
    ): Boolean {
        if (a == null || b == null) return a == b
        return try {
            StrictJson.parse(a) == StrictJson.parse(b)
        } catch (e: IllegalArgumentException) {
            a == b
        }
    }

    private fun HorseHistoryRow.toVersion() =
        HorseMasterVersion(knownAt.toInstantUtc(), source, styleTendencyJson, chakuBySurfaceJson, chakuByConditionJson, chakuByDistanceJson, raceCount)
}
