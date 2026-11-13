package jp.jrabot.cloudaws.agent

import jp.jrabot.cloudaws.S3Clients
import jp.jrabot.domain.agent.AgentHeartbeat
import jp.jrabot.domain.agent.HeartbeatMirror
import jp.jrabot.domain.agent.MirrorOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import jp.jrabot.platform.store.ObjectStoreLocation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * エージェントの最終接続の S3 への写し（CONTRACTS §5.4。D-280）。DB の `agent_heartbeat` が正本で、これは DB・EC2 の停止中に status が読むための写し。
 *
 * - 書く場所: `AGENT_HEARTBEAT_PREFIX`（`s3://bucket/prefix/`）+ `heartbeat-<tenant_id>.json`。パスは [TenantId] だけから作る（リクエストの値を使わない）。
 * - 書く条件: 同じエージェントについて前回の書込みから [minInterval]（5 分）以上、または `agent_version` が変わったとき。それ以外は SKIPPED。
 *   前回の書込みはこのプロセスの記憶（再起動の後の最初の heartbeat は書く）。
 * - 書く内容: 時刻・ID・版だけ（データ本文・トークン・IP・メールアドレスは型として持てない）。同じテナントの他のエージェントの行は残す。
 * - CAS: 読んだ ETag で `If-Match`（無ければ `If-None-Match: *`）。412 なら読み直して 1 回だけ再試行する。
 * - エラー: 例外を投げない（FAILED。警告ログだけで heartbeat の応答には影響させない）。ログに URL・資格情報を出さない。
 */
class S3HeartbeatMirror(
    private val location: ObjectStoreLocation,
    private val client: () -> S3Client,
    private val clock: Clock,
    private val minInterval: Duration = Duration.ofMinutes(5),
    private val log: Logger = LoggerFactory.getLogger(S3HeartbeatMirror::class.java),
) : HeartbeatMirror {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val written = HashMap<Pair<Long, String>, Pair<Instant, String>>()

    @Volatile
    private var s3: S3Client? = null

    @Synchronized
    override fun record(
        tenantId: TenantId,
        heartbeat: AgentHeartbeat,
    ): MirrorOutcome {
        val now = clock.now()
        val memo = tenantId.value to heartbeat.agentId.value
        val last = written[memo]
        if (last != null && last.second == heartbeat.agentVersion && Duration.between(last.first, now) < minInterval) return MirrorOutcome.SKIPPED
        return try {
            val key = "${location.prefix}heartbeat-${tenantId.value}.json"
            for (attempt in 1..2) {
                val current = read(key)
                val body = merge(tenantId, current?.first, heartbeat, now)
                if (write(key, body, current?.second)) {
                    written[memo] = now to heartbeat.agentVersion
                    return MirrorOutcome.WRITTEN
                }
                if (attempt == 2) log.warn("HEARTBEAT_MIRROR conflict tenant={} (再試行しても競合した)", tenantId.value)
            }
            MirrorOutcome.FAILED
        } catch (e: Exception) {
            val status = (e as? S3Exception)?.statusCode()
            log.warn("HEARTBEAT_MIRROR failed tenant={} error={} status={}", tenantId.value, e.javaClass.simpleName, status)
            MirrorOutcome.FAILED
        }
    }

    /** (内容, ETag)。無ければ null。 */
    private fun read(key: String): Pair<ByteArray, String>? =
        try {
            val r =
                s3().getObjectAsBytes(
                    GetObjectRequest
                        .builder()
                        .bucket(location.bucket)
                        .key(key)
                        .build(),
                )
            r.asByteArray() to r.response().eTag()
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) null else throw e
        }

    /** 条件付きの書込み。412（他が先に書いた / 既にある）は false。 */
    private fun write(
        key: String,
        body: ByteArray,
        etag: String?,
    ): Boolean {
        val req =
            PutObjectRequest
                .builder()
                .bucket(location.bucket)
                .key(key)
                .contentType("application/json")
                .cacheControl("no-store")
                .let { if (etag == null) it.ifNoneMatch("*") else it.ifMatch(etag) }
                .build()
        return try {
            s3().putObject(req, RequestBody.fromBytes(body))
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 412) false else throw e
        }
    }

    /** 既存の文書（壊れていれば空とみなす）に、このエージェントの行を入れ替えて足す。行は agent_id の順、最大 [MAX_AGENTS] 行。 */
    internal fun merge(
        tenantId: TenantId,
        existing: ByteArray?,
        heartbeat: AgentHeartbeat,
        now: Instant,
    ): ByteArray {
        val others = mutableMapOf<String, JsonNode>()
        val parsed = existing?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        if (parsed != null && parsed.path("schema_version").asInt(0) == 1 && parsed.path("tenant_id").asLong(-1) == tenantId.value) {
            for (row in parsed.path("agents")) {
                val id = row.path("agent_id").asString("")
                if (AGENT_ID.matches(id) && id != heartbeat.agentId.value) others[id] = row
            }
        }
        val root: ObjectNode = mapper.createObjectNode()
        root.put("schema_version", 1)
        root.put("tenant_id", tenantId.value)
        val agents = root.putArray("agents")
        val mine =
            mapper
                .createObjectNode()
                .put("agent_id", heartbeat.agentId.value)
                .put("last_seen_at", heartbeat.lastSeenAt.truncatedTo(ChronoUnit.SECONDS).toString())
                .put("agent_version", heartbeat.agentVersion)
        (others + (heartbeat.agentId.value to mine))
            .toSortedMap()
            .values
            .take(MAX_AGENTS)
            .forEach { agents.add(it) }
        root.put("updated_at", now.truncatedTo(ChronoUnit.SECONDS).toString())
        return mapper.writeValueAsBytes(root)
    }

    private fun s3(): S3Client = s3 ?: client().also { s3 = it }

    companion object {
        const val MAX_AGENTS = 20
        private val AGENT_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** `AGENT_HEARTBEAT_PREFIX=s3://bucket/prefix/`。不正な URI は [IllegalArgumentException]。クライアントは最初の書込みで作る。 */
        fun fromUri(
            uri: String,
            clock: Clock,
        ): S3HeartbeatMirror =
            S3HeartbeatMirror(
                ObjectStoreLocation.parse(uri, "s3", ObjectStoreLocation.S3_BUCKET),
                { S3Clients.create(Duration.ofSeconds(5)) },
                clock,
            )
    }
}
