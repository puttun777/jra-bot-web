package jp.jrabot.dbmigration

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 実際の JVM プロセスとして起動し、終了コードと標準出力を確かめる（Docker 不要の範囲: 引数・設定の誤りと DB 不達）。 */
class ProcessExitCodeTest {
    @Test
    fun `モード未指定は終了コード 2`() {
        val o = ProcessSupport.launch(emptyList(), emptyMap())
        assertEquals(2, o.code)
        assertEquals("", o.stdout)
    }

    @Test
    fun `不正な引数は終了コード 2`() {
        assertEquals(2, ProcessSupport.launch(listOf("--mode=bogus"), emptyMap()).code)
        assertEquals(2, ProcessSupport.launch(listOf("--mode=info", "--extra"), emptyMap()).code)
    }

    @Test
    fun `設定が無ければ終了コード 2`() {
        assertEquals(2, ProcessSupport.launch(listOf("--mode=validate"), emptyMap()).code)
    }

    @Test
    fun `接続先が閉じていれば全モードで終了コード 75 で、標準出力は JSON 1 行`() {
        val closedPort = ServerSocket(0).use { it.localPort } // 閉じた直後のポートには誰も待ち受けていない
        val env =
            mapOf(
                "DB_URL" to "jdbc:mysql://127.0.0.1:$closedPort/jrabot",
                "DB_USER" to "migrate",
                "DB_PASSWORD" to "pw-for-process-test",
            )
        for (mode in listOf("info", "validate", "migrate")) {
            val o = ProcessSupport.launch(listOf("--mode=$mode"), env)
            assertEquals(75, o.code, "mode=$mode stderr=${o.stderr}")
            assertEquals("""{"mode":"$mode","error":"unavailable"}""" + "\n", o.stdout)
            assertFalse("pw-for-process-test" in o.stderr + o.stdout)
        }
    }
}
