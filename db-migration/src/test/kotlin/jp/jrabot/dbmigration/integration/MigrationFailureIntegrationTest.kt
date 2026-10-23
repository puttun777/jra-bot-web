package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** マイグレーション失敗時の挙動: 失敗版を履歴に残し、勝手に repair せず、再実行しても進まない（人が判断する）。 */
@Tag("integration")
class MigrationFailureIntegrationTest {
    private val failing = listOf("classpath:db-failing")
    private val noUser = listOf("classpath:db-nouser")

    @Test
    fun `SQL が壊れた版で migrate は 1 になり、失敗が記録され、repair しない`() {
        val db = MySqlSupport.newDatabase()
        val first = CliHarness.run(db, "migrate", locations = failing)
        assertEquals(1, first.code, first.stderr)
        assertEquals("""{"mode":"migrate","error":"failed"}""" + "\n", first.stdout)

        // 成功した V1 は残り、失敗した V2 は success=0 の行として記録される（MySQL は DDL を巻き戻せないので half_table は残る）
        assertEquals(
            listOf("1|1", "2|0"),
            db.query("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank") {
                "${it.getString(1)}|${it.getInt(2)}"
            },
        )
        assertEquals("1", db.scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'half_table'"))
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'broken_table'"))

        val info = CliHarness.run(db, "info", locations = failing)
        assertEquals(0, info.code)
        assertEquals("""{"current":"1","pending":[],"failed":["2"],"latest":"2"}""" + "\n", info.stdout)

        val validate = CliHarness.run(db, "validate", locations = failing)
        assertEquals(1, validate.code, validate.stdout)
        assertTrue(""""failed":["2"]""" in validate.stdout, validate.stdout)

        // 再実行しても進まない（自動の repair / 再試行をしない）。履歴は変わらない
        val second = CliHarness.run(db, "migrate", locations = failing)
        assertEquals(1, second.code)
        assertEquals(
            listOf("1|1", "2|0"),
            db.query("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank") {
                "${it.getString(1)}|${it.getInt(2)}"
            },
        )
    }

    @Test
    fun `GRANT 先のユーザーが無ければ migrate は 1 で、ユーザーを勝手に作らず、強い権限で回避しない`() {
        val db = MySqlSupport.newDatabase()
        val result = CliHarness.run(db, "migrate", locations = noUser)
        assertEquals(1, result.code, result.stderr)
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM mysql.user WHERE user = 'no_such_user'"), "GRANT でユーザーが作られていない")
        val info = CliHarness.run(db, "info", locations = noUser)
        assertTrue(""""failed":["R__grants missing user"]""" in info.stdout, info.stdout)
        assertEquals(1, CliHarness.run(db, "validate", locations = noUser).code)
    }

    @Test
    fun `権限の足りない migrate ユーザーは失敗として報告し、権限を勝手に補わない`() {
        val db = MySqlSupport.newDatabase()
        // migrate から GRANT OPTION を外した状態（Cloud SQL で GRANT できない場合の模擬: G-DB-6）
        db.exec("REVOKE GRANT OPTION ON `${db.name}`.* FROM 'migrate'@'%'")
        try {
            val result = CliHarness.run(db, "migrate")
            assertEquals(1, result.code, result.stderr)
            assertTrue("R__grants" in CliHarness.run(db, "info").stdout, "R__grants が失敗として残る")
            assertEquals(
                "0",
                db.scalar("SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = '${db.name}' AND GRANTEE = \"'app'@'%'\""),
                "権限が足りないときに app へ何も付けない",
            )
        } finally {
            db.exec("GRANT ALL PRIVILEGES ON `${db.name}`.* TO 'migrate'@'%' WITH GRANT OPTION")
        }
    }
}
