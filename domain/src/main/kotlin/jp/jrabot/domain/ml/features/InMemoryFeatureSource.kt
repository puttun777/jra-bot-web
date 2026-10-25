package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * 合成データ用の [FeatureSource]（ゴールデン・単体テスト・`export-dataset` の合成入力）。実データ・DB を使わない。
 * テナントごとに別の表を持つ（他テナントの行は見えない）。
 */
class InMemoryFeatureSource : FeatureSource {
    private data class Key(
        val tenant: Long,
        val a: String,
        val b: Int = 0,
    )

    private val races = HashMap<Key, RaceConditions>()
    private val entries = HashMap<Key, EntryHorse>()
    private val horses = HashMap<Key, HorseMaster>()
    private val runs = HashMap<Key, MutableList<PastRun>>()
    private val history = HashMap<Key, MutableList<HorseCumulative>>()

    fun putRace(
        tenantId: TenantId,
        raceId: String,
        conditions: RaceConditions,
    ) {
        races[Key(tenantId.value, raceId)] = conditions
    }

    fun putEntry(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
        kettoNum: String,
    ) {
        entries[Key(tenantId.value, raceId, umaban)] = EntryHorse(kettoNum)
    }

    fun putHorse(
        tenantId: TenantId,
        horse: HorseMaster,
    ) {
        horses[Key(tenantId.value, horse.kettoNum)] = horse
    }

    /** 馬マスタの累積値の版を追加する。版を 1 つでも追加した馬は、[horseAsOf] が [HorseMasterAtTime] の規則で時点を解決する（DB と同じ規則）。 */
    fun putHorseHistory(
        tenantId: TenantId,
        kettoNum: String,
        version: HorseCumulative,
    ) {
        history.getOrPut(Key(tenantId.value, kettoNum)) { ArrayList() }.add(version)
    }

    fun putPastRun(
        tenantId: TenantId,
        kettoNum: String,
        run: PastRun,
    ) {
        runs.getOrPut(Key(tenantId.value, kettoNum)) { ArrayList() }.add(run)
    }

    override fun raceConditions(
        tenantId: TenantId,
        raceId: String,
    ): RaceConditions? = races[Key(tenantId.value, raceId)]

    override fun entry(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
    ): EntryHorse? = entries[Key(tenantId.value, raceId, umaban)]

    override fun horse(
        tenantId: TenantId,
        kettoNum: String,
    ): HorseMaster? = horses[Key(tenantId.value, kettoNum)]

    override fun horseAsOf(
        tenantId: TenantId,
        kettoNum: String,
        asOf: Instant,
    ): HorseMaster? {
        val versions = history[Key(tenantId.value, kettoNum)] ?: return horse(tenantId, kettoNum)
        val latest = versions.filter { !it.knownAt.isAfter(asOf) }.maxByOrNull { it.knownAt }
        return HorseMasterAtTime.resolve(horse(tenantId, kettoNum), latest, asOf)
    }

    override fun pastRuns(
        tenantId: TenantId,
        kettoNum: String,
        before: LocalDate?,
        limit: Int,
    ): List<PastRun> {
        val all = runs[Key(tenantId.value, kettoNum)] ?: return emptyList()
        // 旧 SQLite: ORDER BY date DESC, race_id DESC（日付なしは最後）。finish > 0 のみ。
        return all
            .filter { it.finish > 0 }
            .filter { before == null || (it.runDate != null && it.runDate.isBefore(before)) }
            .sortedWith(compareByDescending<PastRun, LocalDate?>(nullsFirst()) { it.runDate }.thenByDescending { it.raceId })
            .take(limit)
    }
}
