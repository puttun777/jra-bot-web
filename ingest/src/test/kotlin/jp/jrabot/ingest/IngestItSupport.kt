package jp.jrabot.ingest

import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.calendar.EffectiveCalendar
import jp.jrabot.domain.calendar.EffectiveDay
import jp.jrabot.domain.calendar.RaceDayRepository
import jp.jrabot.domain.calendar.ScheduleFetchFactReader
import jp.jrabot.domain.calendar.WatchSettings
import jp.jrabot.domain.ingest.AgentCredentialIssuer
import jp.jrabot.domain.ingest.AgentSigning
import jp.jrabot.domain.ingest.FetchEnsure
import jp.jrabot.domain.ingest.FetchRecord
import jp.jrabot.domain.ingest.FetchStatus
import jp.jrabot.domain.ingest.FetchStore
import jp.jrabot.domain.ingest.Hashing
import jp.jrabot.domain.ingest.IngestEnvelope
import jp.jrabot.domain.ingest.KeyEncryptionKeys
import jp.jrabot.domain.ingest.RawRecordRow
import jp.jrabot.domain.ingest.RawRecordStore
import jp.jrabot.domain.ingest.SigningKeyCipher
import jp.jrabot.domain.race.RaceScheduleRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.persistence.integration.MySqlSupport
import jp.jrabot.persistence.integration.TestDb
import jp.jrabot.persistence.repository.AgentRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** 時計。既定は実時刻。テストが固定・進行できる（claim の引き継ぎ・期限切れの確認用）。 */
object ItClock : Clock {
    @Volatile var fixed: Instant? = null

    override fun now(): Instant = fixed ?: Instant.now().truncatedTo(ChronoUnit.MICROS)
}

/** 失敗の注入。対象は「書込みの途中」と「完了の印の直前」（途中失敗・ACK 直前の切断を再現する）。 */
class Faults {
    @Volatile var rawInsertFailOn = 0 // n 回目の生レコード保存で失敗（0=なし）
    val rawCalls = AtomicInteger()

    @Volatile var fetchSaveFails = false

    /** true の間、保留の確定（settlePending）が失敗する（保留の再適用が行ごとに失敗する状況）。 */
    @Volatile var settleFails = false

    /** 生レコードの最初の保存の直前に 1 回だけ実行する（この時点で、このバッチの適用は終わり、コミットはまだ）。 */
    @Volatile var beforeFirstRawInsert: (() -> Unit)? = null

    fun reset() {
        rawInsertFailOn = 0
        rawCalls.set(0)
        fetchSaveFails = false
        settleFails = false
        beforeFirstRawInsert = null
    }
}

class InjectedFailure : RuntimeException("injected")

@TestConfiguration(proxyBeanMethods = false)
class IngestItConfig {
    @Bean fun faults() = Faults()

    @Bean @Primary
    fun itClock(): Clock = ItClock

    @Bean @Primary
    fun itMode() = ControllableModeProvider()

    @Bean @Primary
    fun faultyRaw(
        @Qualifier("rawRecordStore") real: RawRecordStore,
        f: Faults,
    ): RawRecordStore =
        object : RawRecordStore {
            override fun insertIfAbsent(row: RawRecordRow): Boolean {
                val n = f.rawCalls.incrementAndGet()
                f.beforeFirstRawInsert?.let {
                    f.beforeFirstRawInsert = null
                    it()
                }
                if (f.rawInsertFailOn != 0 && n == f.rawInsertFailOn) throw InjectedFailure()
                return real.insertIfAbsent(row)
            }

            override fun settlePending(
                tenantId: TenantId,
                recordHash: String,
                status: jp.jrabot.domain.ingest.ParseStatus,
                error: String?,
            ): Boolean {
                if (f.settleFails) throw InjectedFailure()
                return real.settlePending(tenantId, recordHash, status, error)
            }

            override fun listPending(
                tenantId: TenantId,
                reasons: List<String>,
                limit: Int,
                after: jp.jrabot.domain.ingest.PendingCursor?,
            ): List<jp.jrabot.domain.ingest.PendingRawRecord> = real.listPending(tenantId, reasons, limit, after)
        }

    @Bean @Primary
    fun faultyFetch(
        @Qualifier("fetchStore") real: FetchStore,
        f: Faults,
    ): FetchStore =
        object : FetchStore {
            override fun ensure(
                tenantId: TenantId,
                envelope: IngestEnvelope,
                now: Instant,
            ): FetchEnsure = real.ensure(tenantId, envelope, now)

            override fun peek(
                tenantId: TenantId,
                fetchId: String,
            ) = real.peek(tenantId, fetchId)

            override fun lockForUpdate(
                tenantId: TenantId,
                fetchId: String,
            ) = real.lockForUpdate(tenantId, fetchId)

            override fun save(fetch: FetchRecord) {
                if (f.fetchSaveFails) throw InjectedFailure()
                real.save(fetch)
            }
        }
}

class ItAgent(
    val id: String,
    val token: String,
    val tenant: Long,
)

/** 実際の HTTP サーバ + 本物の MySQL の統合テスト環境（JVM で 1 つを共有）。合成データだけ。 */
object IngestIt {
    val db: TestDb by lazy { MySqlSupport.migratedDatabase() }
    val keks = KeyEncryptionKeys(mapOf("k1" to ByteArray(32) { (it * 7 + 3).toByte() }), "k1")
    val json: JsonMapper = JsonMapper.builder().build()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val random = SecureRandom()

    val ctx: ConfigurableApplicationContext by lazy {
        val ops = writeOpsState(Files.createTempDirectory("ingest-it"))
        val kek = "k1:" + Base64.getEncoder().encodeToString(keks.key("k1"))
        val props =
            baseProperties(ops).filterNot { it.startsWith("DB_URL=") || it.startsWith("DB_PASSWORD=") } +
                listOf(
                    "DB_URL=${MySqlSupport.jdbcUrl(db.name)}",
                    "DB_PASSWORD=${MySqlSupport.PASSWORDS.getValue("ingest")}",
                    "DB_POOL_MAX=8",
                    "server.port=0",
                    "AGENT_KEK_KEYS=$kek",
                    "AGENT_KEK_ACTIVE_KID=k1",
                    "INGEST_ACCEPT_NORMALIZED_RECORDS=true",
                    "INGEST_STORE_COMBO_ODDS=true",
                )
        SpringApplicationBuilder(IngestApplication::class.java, TestAuthConfig::class.java, IngestItConfig::class.java)
            .run(*props.map { "--$it" }.toTypedArray())
            .also { c -> Runtime.getRuntime().addShutdownHook(Thread { c.close() }) }
    }

    val port: Int get() = ctx.environment.serverPort()
    val mode: ControllableModeProvider get() = ctx.getBean(ControllableModeProvider::class.java)
    val faults: Faults get() = ctx.getBean(Faults::class.java)
    val facts: ScheduleFetchFactReader get() = ctx.getBean(ScheduleFetchFactReader::class.java)

    private val cipher = SigningKeyCipher(keks)
    private val issuer = AgentCredentialIssuer(cipher, ByteArray(16) { 5 })

    val one: ItAgent by lazy { register("ag_one", 1) }
    val two: ItAgent by lazy { register("ag_two", 2) }

    fun register(
        id: String,
        tenant: Long,
        expiresAt: Instant = Instant.now().plus(Duration.ofDays(90)),
    ): ItAgent {
        val issued = issuer.issue(TenantId.of(tenant), AgentId.of(id))
        ctx.getBean(AgentRegistrar::class.java).register(TenantId.of(tenant), "test $id", issued, expiresAt, "test", Instant.now())
        return ItAgent(id, issued.token, tenant)
    }

    private val tables =
        listOf(
            "race_schedule_event",
            "ingest_raw",
            "ingest_batch",
            "ingest_fetch",
            "ingest_backfill",
            "ingest_gap",
            "odds_snapshots",
            "combo_odds_snapshots",
            "race_day",
            "race_schedule",
            "races",
            "ingest_cursor",
            "agent_nonce",
            "agent_heartbeat",
        )

    /** テスト間でデータを空にする（エージェントは残す）。状態（モード・時計・注入）も戻す。 */
    fun reset() {
        ctx
        for (t in tables) db.exec("DELETE FROM $t")
        mode.mode = jp.jrabot.domain.ops.OpsMode.NORMAL
        ItClock.fixed = null
        faults.reset()
    }

    // ---------- リクエスト ----------
    fun bytes(map: Any?): ByteArray = json.writeValueAsBytes(map)

    fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    fun nonce(): String =
        "n" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(28)

    @Suppress("LongParameterList")
    fun post(
        agent: ItAgent,
        path: String,
        body: ByteArray,
        gzip: Boolean = false,
        nonce: String = nonce(),
        tsOffsetSec: Long = 0,
        contentType: String? = "application/json",
        tamperSig: Boolean = false,
        signedBody: ByteArray = body,
        agentHeader: String = agent.id,
        token: String = agent.token,
    ): Resp {
        val ts = ItClock.now().epochSecond + tsOffsetSec
        val sig =
            AgentSigning.sign(
                AgentSigning.deriveSigningKey(token),
                AgentSigning.canonical("POST", path, ts, nonce, Hashing.sha256Hex(signedBody)),
            )
        val b =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(60))
                .header("X-Agent-Id", agentHeader)
                .header("X-Timestamp", ts.toString())
                .header("X-Nonce", nonce)
                .header("X-Signature", if (tamperSig) sig.reversed() else sig)
        if (contentType != null) b.header("Content-Type", contentType)
        if (gzip) b.header("Content-Encoding", "gzip")
        val r = client.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body(), r.headers().map())
    }

    fun postRaw(
        path: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30))
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = client.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body(), r.headers().map())
    }

    /** 署名つきの GET（`signed=false` なら署名なし）。 */
    fun get(
        agent: ItAgent,
        path: String,
        signed: Boolean = true,
    ): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(30))
        if (signed) {
            val ts = ItClock.now().epochSecond
            val nonce = nonce()
            val sig =
                AgentSigning.sign(
                    AgentSigning.deriveSigningKey(agent.token),
                    AgentSigning.canonical("GET", path, ts, nonce, Hashing.sha256Hex(ByteArray(0))),
                )
            b
                .header("X-Agent-Id", agent.id)
                .header("X-Timestamp", ts.toString())
                .header("X-Nonce", nonce)
                .header("X-Signature", sig)
        }
        val r = client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body(), r.headers().map())
    }

    /** 取込 API に封筒を送る（署名つき）。 */
    fun ingest(
        agent: ItAgent,
        env: Map<String, Any?>,
        gzip: Boolean = false,
    ): Resp {
        val raw = bytes(env)
        return post(agent, "/v1/agent/ingest", if (gzip) gzip(raw) else raw, gzip = gzip)
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(r: Resp): Map<String, Any?> = json.readValue(r.body, Map::class.java) as Map<String, Any?>

    // ---------- 封筒・レコードの組み立て ----------
    private var seq = 0L

    /** 小文字の UUIDv7 形式（時系列に増える）。 */
    @Synchronized
    fun batchId(): String {
        seq++
        return "01890a5d-ac96-7%03x-8%03x-%012x".format(seq % 4096, (seq / 4096) % 4096, System.nanoTime() and 0xffffffffffffL)
    }

    fun rec(
        id: String,
        payload: ByteArray,
    ): Map<String, Any?> = mapOf("id" to id, "bytesB64" to Base64.getEncoder().encodeToString(payload), "sha256" to Hashing.sha256Hex(payload))

    fun norm(
        id: String,
        body: Map<String, Any?>,
    ) = rec(id, bytes(body))

    fun nys(
        date: String,
        venue: String = "05",
        kai: Int = 3,
        nichime: Int = 1,
        status: String = "PLANNED",
        replacedBy: String? = null,
    ) = norm("NYS", mapOf("date" to date, "venue" to venue, "kai" to kai, "nichime" to nichime, "status" to status, "replacedBy" to replacedBy))

    fun nra(
        raceId: String,
        kubun: String,
        createdOn: String,
        postTime: String? = "1525",
        name: String? = "テストレース",
        kai: Int = 3,
        nichime: Int = 1,
    ) = norm(
        "NRA",
        mapOf("raceId" to raceId, "kubun" to kubun, "createdOn" to createdOn, "postTime" to postTime, "raceName" to name, "kai" to kai, "nichime" to nichime),
    )

    fun now(
        raceId: String,
        vararg e: Pair<Int, Double>,
    ) = norm("NOW", mapOf("raceId" to raceId, "entries" to e.map { mapOf("umaban" to it.first, "odds" to it.second, "pop" to null) }))

    fun envelope(
        batchId: String = batchId(),
        agent: ItAgent = one,
        dataspec: String = "RACE",
        cls: String = "S",
        eof: Boolean = true,
        records: List<Map<String, Any?>>,
        fetch: Map<String, Any?>? = null,
        observedAt: Instant = ItClock.now(),
        option: Int? = 2,
        fromtime: String? = "20261001000000",
        lastFileTimestamp: String? = "20261031200000",
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        m["protocol"] = 1
        m["agentId"] = agent.id
        m["batchId"] = batchId
        m["class"] = cls
        m["observedAt"] = observedAt.toString()
        m["dataspec"] = dataspec
        m["option"] = option
        m["fromtime"] = fromtime
        m["lastFileTimestamp"] = lastFileTimestamp
        m["eof"] = eof
        if (fetch != null) m["fetch"] = fetch
        m["records"] = records
        return m
    }

    fun fetch(
        id: String,
        seq: Int,
        from: String? = "20261101",
        to: String? = "20271231",
        backfillId: Long? = null,
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "seq" to seq)
        if (from != null) m["targetFrom"] = from
        if (to != null) m["targetTo"] = to
        if (backfillId != null) {
            m["origin"] = "BACKFILL"
            m["backfillId"] = backfillId
        }
        return m
    }

    fun fetchId(): String = UUID.randomUUID().toString()

    // ---------- 照会 ----------
    fun count(sql: String): Long = db.count(sql)

    fun effectiveDay(
        tenant: Long,
        date: LocalDate,
    ): EffectiveDay =
        EffectiveCalendar.derive(
            TenantId.of(tenant),
            date,
            ItClock.now(),
            CalendarSettings(WatchSettings(Duration.ofMinutes(120), Duration.ofMinutes(10), Duration.ofMinutes(90)), Duration.ofHours(120)),
            ctx.getBean(RaceDayRepository::class.java).listByDate(TenantId.of(tenant), date),
            ctx.getBean(RaceScheduleRepository::class.java).listByDate(TenantId.of(tenant), date),
            emptyList(),
            facts.latestSuccess(TenantId.of(tenant), date),
        )

    fun fetchStatus(fetchId: String): FetchStatus = FetchStatus.valueOf(checkNotNull(db.scalar("SELECT status FROM ingest_fetch WHERE fetch_id = '$fetchId'")))
}
