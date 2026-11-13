package jp.jrabot.cloudaws

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * S3 の REST（パス形式の GET/PUT/DELETE と条件付きの PUT）を真似るローカルのフェイク（実 AWS には接続しない。署名は検証しない）。
 * [override] を設定すると、その応答を返す（障害の注入）。
 */
class FakeS3 : AutoCloseable {
    data class Obj(
        val body: ByteArray,
        val etag: String,
    )

    data class Seen(
        val method: String,
        val path: String,
        val ifMatch: String?,
        val ifNoneMatch: String?,
    )

    val objects = LinkedHashMap<String, Obj>()
    val requests = CopyOnWriteArrayList<Seen>()

    @Volatile
    var override: ((HttpExchange) -> Boolean)? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex -> ex.use { handle(it) } }
        server.start()
    }

    @Synchronized
    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path.removePrefix("/")
        requests += Seen(ex.requestMethod, path, ex.requestHeaders.getFirst("If-Match"), ex.requestHeaders.getFirst("If-None-Match"))
        val body = ex.requestBody.readAllBytes()
        if (override?.invoke(ex) == true) return
        val existing = objects[path]
        when (ex.requestMethod) {
            "GET" -> {
                if (existing == null) return error(ex, 404, "NoSuchKey")
                ex.responseHeaders.add("ETag", existing.etag)
                ex.responseHeaders.add("Content-Type", "application/octet-stream")
                ex.sendResponseHeaders(200, existing.body.size.toLong())
                ex.responseBody.write(existing.body)
            }

            "PUT" -> {
                val ifNone = ex.requestHeaders.getFirst("If-None-Match")
                val ifMatch = ex.requestHeaders.getFirst("If-Match")
                if (ifNone == "*" && existing != null) return error(ex, 412, "PreconditionFailed")
                if (ifMatch != null && existing?.etag != ifMatch) return error(ex, 412, "PreconditionFailed")
                val etag = "\"" + MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it) } + "\""
                objects[path] = Obj(body, etag)
                ex.responseHeaders.add("ETag", etag)
                ex.sendResponseHeaders(200, -1)
            }

            "DELETE" -> {
                objects.remove(path)
                ex.sendResponseHeaders(204, -1)
            }

            else -> {
                error(ex, 405, "MethodNotAllowed")
            }
        }
    }

    fun error(
        ex: HttpExchange,
        status: Int,
        code: String,
    ) {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$code</Message></Error>".toByteArray()
        ex.responseHeaders.add("Content-Type", "application/xml")
        ex.sendResponseHeaders(status, xml.size.toLong())
        ex.responseBody.write(xml)
        ex.close()
    }

    override fun close() = server.stop(0)
}
