package jp.jrabot.dbmigration

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorClassifierTest {
    @Test
    fun `接続できない・通信断・サーバ停止は一時的`() {
        val temporary =
            listOf<Throwable>(
                SQLException("Communications link failure", "08S01", 0),
                SQLException("Connection refused", "08001", 2003),
                SQLException("Server shutdown in progress", "08S01", 1053),
                SQLException("Too many connections", "08004", 1040),
                SQLException("odd state, known code", "HY000", 2013),
                SQLTransientConnectionException("pool timeout"),
                ConnectException("Connection refused"),
                SocketTimeoutException("connect timed out"),
                UnknownHostException("no-such-host"),
            )
        for (e in temporary) assertTrue(ErrorClassifier.isTemporary(e), "一時的のはず: $e")
    }

    @Test
    fun `原因の奥にある接続失敗も一時的（Flyway が包む）`() {
        val wrapped = RuntimeException("Unable to obtain connection", IllegalStateException("x", SQLException("link", "08S01")))
        assertTrue(ErrorClassifier.isTemporary(wrapped))
    }

    @Test
    fun `認証失敗・SQL の誤り・制約違反は一時的ではない`() {
        val permanent =
            listOf<Throwable>(
                SQLInvalidAuthorizationSpecException("Access denied for user", "28000", 1045),
                SQLSyntaxErrorException("You have an error in your SQL syntax", "42000", 1064),
                SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062),
                SQLException("unknown database", "42000", 1049),
                IllegalStateException("bug"),
            )
        for (e in permanent) assertFalse(ErrorClassifier.isTemporary(e), "一時的ではないはず: $e")
    }

    @Test
    fun `循環した原因でも止まる`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(2, ErrorClassifier.causeChain(a).size)
        assertFalse(ErrorClassifier.isTemporary(a))
    }
}
