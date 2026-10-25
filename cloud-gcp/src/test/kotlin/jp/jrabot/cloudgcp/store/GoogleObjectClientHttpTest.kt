package jp.jrabot.cloudgcp.store

import com.google.cloud.storage.StorageException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 実際の Cloud Storage クライアント（SDK）を、ローカルのフェイク HTTP サーバ（127.0.0.1、認証なし）に向けて検証する。実 GCP・認証情報は使わない。
 * 確認するのは『作成が前提条件 ifGenerationMatch=0 つき（上書きしない）であること』と『404 / 412 / 403 の変換』。
 * **実バケット・ADC・IAM でのアクセスの成功を確認したものではない**（Stage 5）。
 */
class GoogleObjectClientHttpTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    @Volatile private var handler: (HttpExchange) -> Unit = { it.reply(200, "{}") }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.rawPath}?${ex.requestURI.rawQuery.orEmpty()}"
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

    private fun client() = GoogleObjectClient(GoogleObjectClient.forFakeEndpoint("http://127.0.0.1:${server.address.port}"))

    @Test
    fun `読取りは GET で、404 は null`() {
        handler = { it.reply(404, """{"error":{"code":404,"message":"Not Found"}}""") }
        assertNull(client().read("fake-bucket", "a/b.json"))
        handler = { it.reply(200, "hello") }
        assertContentEquals("hello".toByteArray(), client().read("fake-bucket", "a/b.json"))
        assertTrue(requests.all { it.startsWith("GET ") && it.contains("fake-bucket") && it.contains("a%2Fb.json") }, "$requests")
    }

    @Test
    fun `作成は ifGenerationMatch=0 の前提条件つきで、412（既にある）は false`() {
        handler = { it.reply(200, """{"bucket":"fake-bucket","name":"a/b.json","generation":"1"}""") }
        assertTrue(client().createIfAbsent("fake-bucket", "a/b.json", byteArrayOf(1, 2), "application/json"))
        val post = requests.single { it.startsWith("POST ") }
        assertTrue("ifGenerationMatch=0" in post, "上書きしない前提条件が送られていない: $post")
        assertTrue("/upload/" in post, post)

        requests.clear()
        handler = { it.reply(412, """{"error":{"code":412,"message":"Precondition Failed"}}""") }
        assertFalse(client().createIfAbsent("fake-bucket", "a/b.json", byteArrayOf(1, 2), "application/json"))
    }

    @Test
    fun `権限不足（403）は例外（成功扱いにしない）`() {
        handler = { it.reply(403, """{"error":{"code":403,"message":"Forbidden"}}""") }
        assertEquals(403, assertFailsWith<StorageException> { client().read("fake-bucket", "x") }.code)
        assertEquals(403, assertFailsWith<StorageException> { client().createIfAbsent("fake-bucket", "x", byteArrayOf(1), "text/plain") }.code)
    }
}
