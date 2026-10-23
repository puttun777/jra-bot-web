package jp.jrabot.persistence.schema

import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaVersionTest {
    @Test
    fun `数値の組として比較する`() {
        assertTrue(SchemaVersion.parse("10") > SchemaVersion.parse("9"))
        assertTrue(SchemaVersion.parse("2.1") > SchemaVersion.parse("2"))
        assertTrue(SchemaVersion.parse("2") < SchemaVersion.parse("2.0.1"))
        assertEquals(SchemaVersion.parse("2"), SchemaVersion.parse("2.0"))
        assertEquals(SchemaVersion.parse("2").hashCode(), SchemaVersion.parse("2.0.0").hashCode())
        assertEquals("2.1", SchemaVersion.parse("2.1").toString())
    }

    @Test
    fun `形式が不正な版は null`() {
        for (bad in listOf(null, "", " ", "v2", "2.", ".2", "2..1", "2-1", "1e3", "-1", "2.x", "9999999999", "1".repeat(40))) {
            assertNull(SchemaVersion.parseOrNull(bad), "'$bad'")
        }
    }
}

/** 例外の分類（接続不能と、再試行しても直らないものの区別）。DB は使わない。 */
class SchemaCheckClassificationTest {
    private val required = SchemaVersion.parse("2")

    private fun checkFailingWith(error: Exception): SchemaCheck {
        val ds =
            object : DataSource by unsupported() {
                override fun getConnection(): Connection = throw error
            }
        return SchemaVersionVerifier(ds, required).check()
    }

    private fun sql(
        code: Int,
        state: String,
    ) = SQLException("msg-with-secret-host", state, code)

    @Test
    fun `接続系の例外は Unreachable`() {
        assertEquals(
            SchemaCheck.Unreachable,
            checkFailingWith(SQLTransientConnectionException("pool timeout", SQLException("x", "08S01", 0, ConnectException("refused")))),
        )
        assertEquals(SchemaCheck.Unreachable, checkFailingWith(SQLException("Communications link failure", "08S01", 0)))
        assertEquals(SchemaCheck.Unreachable, checkFailingWith(SQLRecoverableException("lost")))
        assertEquals(SchemaCheck.Unreachable, checkFailingWith(SQLException("outer", "HY000", 0, SocketTimeoutException("read timed out"))))
        assertEquals(SchemaCheck.Unreachable, checkFailingWith(SQLException("outer", "HY000", 0, UnknownHostException("db"))))
        assertEquals(SchemaCheck.Unreachable, checkFailingWith(sql(1040, "08004"))) // 接続数超過
    }

    @Test
    fun `認証の失敗は Unreachable にしない（再試行しても直らない）`() {
        assertEquals(SchemaCheck.Failed(SchemaFailure.AUTHENTICATION), checkFailingWith(sql(1045, "28000")))
        // Hikari が接続の待ちでタイムアウトした例外の原因が認証失敗なら、認証失敗を優先する
        val wrapped = SQLTransientConnectionException("pool timeout", sql(1045, "28000"))
        assertEquals(SchemaCheck.Failed(SchemaFailure.AUTHENTICATION), checkFailingWith(wrapped))
    }

    @Test
    fun `DB が無い・権限が無いは Failed`() {
        for (code in listOf(1044, 1049, 1142, 1143)) {
            assertEquals(SchemaCheck.Failed(SchemaFailure.DATABASE_OR_PERMISSION), checkFailingWith(sql(code, "42000")), "code=$code")
        }
    }

    @Test
    fun `履歴の表が無いのは Insufficient(現在版なし)`() {
        assertEquals(SchemaCheck.Insufficient(null, required), checkFailingWith(sql(1146, "42S02")))
    }

    @Test
    fun `想定外の例外・設定の誤りは Unreachable にしない`() {
        assertEquals(SchemaCheck.Failed(SchemaFailure.UNEXPECTED), checkFailingWith(IllegalStateException("bad config")))
        assertEquals(SchemaCheck.Failed(SchemaFailure.UNEXPECTED), checkFailingWith(RuntimeException("Failed to get driver instance for jdbcUrl")))
        assertEquals(SchemaCheck.Failed(SchemaFailure.UNEXPECTED), checkFailingWith(SQLNonTransientConnectionException("invalid url", "08001", 0)))
        assertEquals(SchemaCheck.Failed(SchemaFailure.UNEXPECTED), checkFailingWith(sql(1064, "42000")))
    }

    @Test
    fun `結果にメッセージ・接続情報を含めない`() {
        val r = checkFailingWith(sql(1045, "28000"))
        assertFalse(r.toString().contains("secret"))
    }
}

/** 使わないメソッドは呼ばれたら失敗させる DataSource の土台。 */
internal fun unsupported(): DataSource =
    java.lang.reflect.Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as DataSource
