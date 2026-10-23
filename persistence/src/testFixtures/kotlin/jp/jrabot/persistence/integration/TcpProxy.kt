package jp.jrabot.persistence.integration

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * ローカルの TCP 中継。DB コンテナを止めずに「DB が落ちた/戻った」を再現する（[stop] で待受けと既存の接続をすべて切り、[start] で同じポートで再開する）。
 * アプリの接続先をこの中継にすると、DB の停止中は接続拒否になり、復旧後は再接続できる。
 */
class TcpProxy(
    private val targetHost: String,
    private val targetPort: Int,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    private val open = CopyOnWriteArrayList<Socket>()

    /** 中継の待受けポート。最初の [start] で決まり、再開しても同じ。 */
    var port: Int = 0
        private set

    @Synchronized
    fun start(): TcpProxy {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        port = s.localPort
        server = s
        thread(isDaemon = true, name = "tcp-proxy-accept") {
            while (!s.isClosed) {
                val client =
                    try {
                        s.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                val upstream =
                    try {
                        Socket(targetHost, targetPort)
                    } catch (_: java.io.IOException) {
                        client.close()
                        continue
                    }
                open += client
                open += upstream
                pipe(client, upstream)
                pipe(upstream, client)
            }
        }
        return this
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun pipe(
        from: Socket,
        to: Socket,
    ) {
        thread(isDaemon = true, name = "tcp-proxy-pipe") {
            try {
                from.getInputStream().copyTo(to.getOutputStream())
            } catch (_: java.io.IOException) {
                // 切断
            } finally {
                runCatching { from.close() }
                runCatching { to.close() }
            }
        }
    }

    val jdbcHost: String get() = "127.0.0.1"

    override fun close() = stop()
}
