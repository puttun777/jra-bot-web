package jp.jrabot.cloudgcp.ops

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jp.jrabot.domain.ops.OpsMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 実際の Google Cloud Storage クライアント（SDK）を、ローカルのフェイク HTTP サーバ（127.0.0.1、認証なし）に向けて検証する。
 * 実 GCP・エミュレータ・認証情報は使わない。これは「SDK の例外・ステータスの変換と読取り専用であること」の確認で、
 * 実 GCP へのアクセスが成功することの確認ではない。
 */
class GcsOpsStateSourceTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var handler: (HttpExchange) -> Unit = { it.reply(200, "{}") }

    private val location = GcsLocation("fake-ops-state", "state/ops-state.json")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}"
            try {
                handler(ex)
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun HttpExchange.reply(
        code: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    }

    private fun source(timeout: Duration = Duration.ofSeconds(2)): GcsOpsStateSource {
        val host = "http://127.0.0.1:${server.address.port}"
        return GcsOpsStateSource(location, { GcsStorageFactory.createForFakeEndpoint(host, timeout) }, timeout)
    }

    private fun readReason(s: GcsOpsStateSource = source()): OpsFailureReason = assertFailsWith<OpsStateReadException> { s.read() }.reason

    @Test
    fun `200 の内容をそのまま返し、読取り（GET）だけを送る`() {
        val json = opsStateJson(seq = 9)
        handler = { it.reply(200, json) }
        assertContentEquals(json.toByteArray(), source().read())
        assertTrue(requests.isNotEmpty() && requests.all { it.startsWith("GET ") }, "書込み系のメソッドを送らない: $requests")
        assertTrue(requests.all { it.contains("fake-ops-state") && it.contains("state%2Fops-state.json") }, "$requests")
    }

    @Test
    fun `異常系18 404 はオブジェクト不在`() {
        handler = { it.reply(404, """{"error":{"code":404,"message":"Not Found"}}""") }
        assertEquals(OpsFailureReason.SOURCE_NOT_FOUND, readReason())
    }

    @Test
    fun `異常系17 403 と 401 は権限エラー`() {
        handler = { it.reply(403, """{"error":{"code":403,"message":"Forbidden"}}""") }
        assertEquals(OpsFailureReason.SOURCE_PERMISSION_DENIED, readReason())
        handler = { it.reply(401, """{"error":{"code":401,"message":"Unauthorized"}}""") }
        assertEquals(OpsFailureReason.SOURCE_PERMISSION_DENIED, readReason())
    }

    @Test
    fun `異常系19 サーバエラー（リトライ後）と接続拒否は通信障害`() {
        handler = { it.reply(500, """{"error":{"code":500,"message":"Internal"}}""") }
        assertEquals(OpsFailureReason.SOURCE_UNAVAILABLE, readReason(source(Duration.ofMillis(1500))))
        server.stop(0)
        assertEquals(OpsFailureReason.SOURCE_UNAVAILABLE, readReason())
    }

    @Test
    fun `異常系16 応答が遅いときは上限で戻り SOURCE_TIMEOUT`() {
        handler = {
            Thread.sleep(3_000)
            it.reply(200, opsStateJson())
        }
        val started = System.nanoTime()
        assertEquals(OpsFailureReason.SOURCE_TIMEOUT, readReason(source(Duration.ofMillis(300))))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_500, "上限（300ms）を大きく超えて待たない: ${elapsedMs}ms")
    }

    @Test
    fun `クライアントの生成に失敗しても例外にせず UNAVAILABLE、次の読取りで作り直す`() {
        var calls = 0
        val host = "http://127.0.0.1:${server.address.port}"
        handler = { it.reply(200, opsStateJson()) }
        val s =
            GcsOpsStateSource(location, {
                if (++calls == 1) error("no credentials") else GcsStorageFactory.createForFakeEndpoint(host, Duration.ofSeconds(2))
            })
        assertEquals(OpsFailureReason.SOURCE_UNAVAILABLE, readReason(s))
        assertTrue(s.read().isNotEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `大きすぎるオブジェクトは SOURCE_TOO_LARGE`() {
        handler = { it.reply(200, "x".repeat(OpsStateSource.MAX_BYTES + 1)) }
        assertEquals(OpsFailureReason.SOURCE_TOO_LARGE, readReason())
    }

    @Test
    fun `例外のメッセージに内容を含めない`() {
        handler = { it.reply(403, """{"error":{"code":403,"message":"secret-detail-xyz"}}""") }
        val e = assertFailsWith<OpsStateReadException> { source().read() }
        assertTrue(e.message?.contains("secret-detail-xyz") != true)
    }

    @Test
    fun `GCS のプロバイダとして 200 の NORMAL と 404 の UNKNOWN を返す（結合）`() {
        val clock = MutableClock()
        val p = CachingModeProvider(source(), clock, log = RecordingLogger())
        handler = { it.reply(200, opsStateJson(seq = 3)) }
        assertEquals(OpsMode.NORMAL, p.current())
        handler = { it.reply(404, "{}") }
        clock.advanceSeconds(15)
        assertEquals(OpsMode.UNKNOWN, p.current())
    }

    @Test
    fun `GcsLocation の解析`() {
        assertEquals(GcsLocation("my-prj-ops-state", "state/ops-state.json"), GcsLocation.parse("gs://my-prj-ops-state/state/ops-state.json"))
        for (bad in listOf(
            "",
            "gs://",
            "gs://bucket",
            "gs://bucket/",
            "gs:///obj",
            "gs://B/obj",
            "gs://ab/obj",
            "gs://bucket/dir/",
            "gs://bucket/a?b",
            "http://bucket/obj",
            "gs://bucket/a\nb",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { GcsLocation.parse(bad) }
        }
    }
}
