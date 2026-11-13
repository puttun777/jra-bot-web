package jp.jrabot.cloudaws

import jp.jrabot.cloudaws.agent.S3HeartbeatMirror
import jp.jrabot.cloudaws.ops.S3OpsStateSource
import jp.jrabot.cloudaws.ops.S3OpsStateSources
import jp.jrabot.cloudaws.store.S3ObjectClient
import jp.jrabot.cloudaws.store.S3Stores
import jp.jrabot.domain.agent.AgentHeartbeat
import jp.jrabot.domain.agent.AgentId
import jp.jrabot.domain.agent.MirrorOutcome
import jp.jrabot.domain.ml.ArtifactWriteOutcome
import jp.jrabot.domain.ml.StoragePermissionException
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.platform.ops.MutableClock
import jp.jrabot.platform.ops.OpsFailureReason
import jp.jrabot.platform.ops.OpsModeConfigurationException
import jp.jrabot.platform.ops.OpsModeProviders
import jp.jrabot.platform.ops.OpsStateReadException
import jp.jrabot.platform.ops.OpsStateSource
import jp.jrabot.platform.ops.opsStateJson
import jp.jrabot.platform.store.ObjectAccessDeniedException
import jp.jrabot.platform.store.ObjectBlobStore
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3AdaptersTest {
    private val s3 = FakeS3()
    private val timeout = Duration.ofSeconds(2)

    private fun client() = S3Clients.forFakeEndpoint(s3.endpoint, timeout)

    @AfterTest
    fun close() = s3.close()

    // ---------------------------------------------------------------- URI
    @Test
    fun `s3 のオブジェクト URI の検査（バケット名・キーの形式。値はメッセージに出さない）`() {
        assertEquals(S3ObjectLocation("my-ops-state", "state/ops-state.json"), S3ObjectLocation.parse("s3://my-ops-state/state/ops-state.json"))
        for (bad in listOf(
            "gs://b/k",
            "s3://",
            "s3://bucket",
            "s3://bucket/",
            "s3://B_ad/k",
            "s3://ab/k",
            "s3://bucket//k",
            "s3://bucket/a/../k",
            "s3://bucket/k?x=1",
            "s3://bucket/k/",
        )) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { S3ObjectLocation.parse(bad) }
            assertFalse(e.message.orEmpty().contains("bucket/"), bad)
        }
        assertEquals("s3://bkt/a/b", S3Stores.location("s3://bkt/a/b/").uriPrefix)
        assertFailsWith<IllegalArgumentException> { S3Stores.location("s3://bad_bucket") }
    }

    @Test
    fun `OPS_MODE_SOURCE の s3 は S3 実装で、生成時には通信しない・形式違いは起動失敗`() {
        val remotes = listOf(S3OpsStateSources)
        for (appEnv in listOf("prod", "local", null)) {
            val env =
                buildMap {
                    put("OPS_MODE_SOURCE", "s3://jrabot-ops-state/state/ops-state.json")
                    appEnv?.let { put("APP_ENV", it) }
                }
            OpsModeProviders.fromEnvironment(env, MutableClock(Instant.parse("2026-11-13T00:00:00Z")), remotes)
        }
        assertFailsWith<OpsModeConfigurationException> {
            OpsModeProviders.fromEnvironment(mapOf("OPS_MODE_SOURCE" to "s3://bucket", "APP_ENV" to "prod"), MutableClock(Instant.EPOCH), remotes)
        }
        assertFailsWith<OpsModeConfigurationException> {
            OpsModeProviders.fromEnvironment(mapOf("OPS_MODE_SOURCE" to "gs://bucket/x", "APP_ENV" to "prod"), MutableClock(Instant.EPOCH), remotes)
        }
    }

    // ---------------------------------------------------------------- ops-state
    private fun source(t: Duration = timeout) = S3OpsStateSource(S3ObjectLocation("ops", "state/ops-state.json"), { client() }, t)

    private fun reason(src: OpsStateSource = source()): OpsFailureReason = assertFailsWith<OpsStateReadException> { src.read() }.reason

    @Test
    fun `ops-state を読み、モードを判定できる`() {
        s3.objects["ops/state/ops-state.json"] = FakeS3.Obj(opsStateJson(mode = "NORMAL").toByteArray(), "\"e1\"")
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val provider =
            jp.jrabot.platform.ops
                .CachingModeProvider(source(), clock, sourceLabel = "s3")
        assertEquals(OpsMode.NORMAL, provider.current())
        assertEquals("s3", provider.snapshot().source)
    }

    @Test
    fun `404・403・500・大きすぎる・遅すぎるは理由つきの失敗（UNKNOWN の材料）`() {
        assertEquals(OpsFailureReason.SOURCE_NOT_FOUND, reason())
        s3.override = {
            s3.error(it, 403, "AccessDenied")
            true
        }
        assertEquals(OpsFailureReason.SOURCE_PERMISSION_DENIED, reason())
        s3.override = {
            s3.error(it, 500, "InternalError")
            true
        }
        assertEquals(OpsFailureReason.SOURCE_UNAVAILABLE, reason())
        s3.override = null
        s3.objects["ops/state/ops-state.json"] = FakeS3.Obj(ByteArray(OpsStateSource.MAX_BYTES + 10) { 'a'.code.toByte() }, "\"big\"")
        assertEquals(OpsFailureReason.SOURCE_TOO_LARGE, reason())
        s3.override = {
            Thread.sleep(1_500)
            false
        }
        val started = System.nanoTime()
        assertEquals(OpsFailureReason.SOURCE_TIMEOUT, reason(source(Duration.ofMillis(300))))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(1_200), "呼び出し側の上限で戻る")
    }

    @Test
    fun `クライアントの生成に失敗しても例外は UNKNOWN の材料になり、次の読取りで作り直す`() {
        var calls = 0
        val src =
            S3OpsStateSource(S3ObjectLocation("ops", "state/ops-state.json"), {
                if (++calls == 1) error("no credentials") else client()
            }, timeout)
        assertEquals(OpsFailureReason.SOURCE_UNAVAILABLE, reason(src))
        s3.objects["ops/state/ops-state.json"] = FakeS3.Obj("{}".toByteArray(), "\"e\"")
        assertContentEquals("{}".toByteArray(), src.read())
        assertEquals(2, calls)
    }

    // ---------------------------------------------------------------- objects
    @Test
    fun `作成は If-None-Match の条件付きで、既存は上書きしない（412 は既にある）`() {
        val c = S3ObjectClient(client())
        assertTrue(c.createIfAbsent("models", "1/models/m.json", byteArrayOf(1, 2), "application/json"))
        assertFalse(c.createIfAbsent("models", "1/models/m.json", byteArrayOf(9), "application/json"))
        assertContentEquals(byteArrayOf(1, 2), c.read("models", "1/models/m.json"))
        assertEquals("*", s3.requests.first { it.method == "PUT" }.ifNoneMatch)
        assertNull(c.read("models", "nope"))
        c.delete("models", "1/models/m.json")
        assertNull(c.read("models", "1/models/m.json"))
    }

    @Test
    fun `権限の拒否（403）は provider 中立の権限エラーで、blob の保管では再試行しない失敗になる`() {
        s3.override = {
            s3.error(it, 403, "AccessDenied")
            true
        }
        val c = S3ObjectClient(client())
        assertEquals(403, assertFailsWith<ObjectAccessDeniedException> { c.read("models", "x") }.status)
        assertEquals(403, assertFailsWith<ObjectAccessDeniedException> { c.createIfAbsent("models", "x", byteArrayOf(1), "text/plain") }.status)
        val blob = ObjectBlobStore(S3Stores.location("s3://models/env1"), c)
        assertFailsWith<StoragePermissionException> { blob.get("1/models/m.json") }
    }

    @Test
    fun `blob の保管（作成・同一・競合）は GCS と同じ判定`() {
        val blob = ObjectBlobStore(S3Stores.location("s3://models/env1"), S3ObjectClient(client()))
        assertEquals("s3://models/env1", blob.uriPrefix)
        assertEquals(ArtifactWriteOutcome.CREATED, blob.putIfAbsent("1/models/m.sig", byteArrayOf(1), "application/octet-stream"))
        assertEquals(ArtifactWriteOutcome.UNCHANGED, blob.putIfAbsent("1/models/m.sig", byteArrayOf(1), "application/octet-stream"))
        assertEquals(ArtifactWriteOutcome.CONFLICT, blob.putIfAbsent("1/models/m.sig", byteArrayOf(2), "application/octet-stream"))
        assertTrue("models/env1/1/models/m.sig" in s3.objects)
    }

    // ---------------------------------------------------------------- heartbeat mirror
    private val mapper = JsonMapper.builder().build()

    private fun agentIds(doc: tools.jackson.databind.JsonNode): List<String> {
        val arr = doc.path("agents")
        return (0 until arr.size()).map { i -> arr.get(i).path("agent_id").asString("") }
    }

    private fun hb(
        id: String,
        at: String,
        v: String = "0.3.0",
    ) = AgentHeartbeat(AgentId.of(id), Instant.parse(at), v)

    @Test
    fun `最終接続の写し（5 分未満の同じ版は書かず、版の変更・5 分経過で書く。他のエージェントの行は残す）`() {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val m = S3HeartbeatMirror(S3Stores.location("s3://ops-state/agent/"), { client() }, clock)
        val t1 = TenantId.of(1)
        assertEquals(MirrorOutcome.WRITTEN, m.record(t1, hb("ag_a", "2026-11-13T00:00:00Z")))
        assertEquals("*", s3.requests.last { it.method == "PUT" }.ifNoneMatch)
        clock.advance(Duration.ofMinutes(1))
        assertEquals(MirrorOutcome.SKIPPED, m.record(t1, hb("ag_a", "2026-11-13T00:01:00Z")))
        assertEquals(MirrorOutcome.WRITTEN, m.record(t1, hb("ag_a", "2026-11-13T00:01:00Z", "0.4.0")))
        assertTrue(s3.requests.last { it.method == "PUT" }.ifMatch != null, "既存は If-Match")
        assertEquals(MirrorOutcome.WRITTEN, m.record(t1, hb("ag_b", "2026-11-13T00:01:00Z")))
        clock.advance(Duration.ofMinutes(5))
        assertEquals(MirrorOutcome.WRITTEN, m.record(t1, hb("ag_a", "2026-11-13T00:06:00Z", "0.4.0")))
        val doc = mapper.readTree(s3.objects.getValue("ops-state/agent/heartbeat-1.json").body)
        assertEquals(1, doc.path("schema_version").asInt())
        assertEquals(1L, doc.path("tenant_id").asLong())
        assertEquals<List<String>>(listOf("ag_a", "ag_b"), agentIds(doc))
        assertEquals("2026-11-13T00:06:00Z", doc.path("agents")[0].path("last_seen_at").asString())
        assertEquals(setOf("agent_id", "last_seen_at", "agent_version"), doc.path("agents")[0].propertyNames().toSet())
        // テナントごとに別の文書（他テナントに触れない）
        assertEquals(MirrorOutcome.WRITTEN, m.record(TenantId.of(2), hb("ag_c", "2026-11-13T00:06:00Z")))
        assertEquals(1, mapper.readTree(s3.objects.getValue("ops-state/agent/heartbeat-2.json").body).path("agents").size())
        assertEquals(2, mapper.readTree(s3.objects.getValue("ops-state/agent/heartbeat-1.json").body).path("agents").size())
    }

    @Test
    fun `最終接続の写し（412 は読み直して 1 回だけ再試行し、失敗は例外にせず FAILED）`() {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val m = S3HeartbeatMirror(S3Stores.location("s3://ops-state/agent/"), { client() }, clock)
        var conflicts = 1
        s3.override = { ex ->
            if (ex.requestMethod == "PUT" && conflicts-- > 0) {
                s3.error(ex, 412, "PreconditionFailed")
                true
            } else {
                false
            }
        }
        assertEquals(MirrorOutcome.WRITTEN, m.record(TenantId.of(1), hb("ag_a", "2026-11-13T00:00:00Z")))
        assertEquals(2, s3.requests.count { it.method == "PUT" })
        s3.override = {
            s3.error(it, 500, "InternalError")
            true
        }
        assertEquals(MirrorOutcome.FAILED, m.record(TenantId.of(1), hb("ag_a", "2026-11-13T00:00:00Z", "0.9.0")))
        s3.override = { ex ->
            if (ex.requestMethod == "PUT") {
                s3.error(ex, 412, "PreconditionFailed")
                true
            } else {
                false
            }
        }
        assertEquals(MirrorOutcome.FAILED, m.record(TenantId.of(1), hb("ag_a", "2026-11-13T00:00:00Z", "1.0.0")))
    }

    @Test
    fun `最終接続の写し（壊れた既存の文書・別テナントの文書は空とみなして上書きする）`() {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        val m = S3HeartbeatMirror(S3Stores.location("s3://ops-state/agent/"), { client() }, clock)
        s3.objects["ops-state/agent/heartbeat-1.json"] =
            FakeS3.Obj("""{"schema_version":1,"tenant_id":2,"agents":[{"agent_id":"ag_x"}]}""".toByteArray(), "\"old\"")
        assertEquals(MirrorOutcome.WRITTEN, m.record(TenantId.of(1), hb("ag_a", "2026-11-13T00:00:00Z")))
        val doc = mapper.readTree(s3.objects.getValue("ops-state/agent/heartbeat-1.json").body)
        assertEquals<List<String>>(listOf("ag_a"), agentIds(doc))
    }
}
