package jp.jrabot.dbmigration.integration

import jp.jrabot.dbmigration.ProcessSupport
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 実際の MySQL に対する CLI の終了コードの契約（CONTRACTS §4.2）。 */
@Tag("integration")
class CliIntegrationTest {
    private fun singleJsonLine(text: String) {
        assertEquals(1, text.trimEnd('\n').lines().size, "標準出力は JSON 1 行のはず: <$text>")
        assertTrue(text.startsWith("{") && text.trimEnd().endsWith("}"), text)
    }

    @Test
    fun `空のDBでは info 0 validate 10 、migrate 0 の後は validate 0 info は最新`() {
        val db = MySqlSupport.newDatabase()

        val info0 = CliHarness.run(db, "info")
        assertEquals(0, info0.code, info0.stderr)
        assertEquals("""{"current":null,"pending":["1","2","R__grants"],"failed":[],"latest":"2"}""" + "\n", info0.stdout)

        val validate0 = CliHarness.run(db, "validate")
        assertEquals(10, validate0.code, validate0.stderr + validate0.stdout)
        assertTrue(""""valid":true""" in validate0.stdout && """"pending":["1","2","R__grants"]""" in validate0.stdout, validate0.stdout)

        assertEquals(0, CliHarness.run(db, "migrate").code)

        val validate1 = CliHarness.run(db, "validate")
        assertEquals(0, validate1.code, validate1.stderr + validate1.stdout)
        assertTrue(""""valid":true""" in validate1.stdout && """"pending":[]""" in validate1.stdout && """"errors":[]""" in validate1.stdout, validate1.stdout)

        val info1 = CliHarness.run(db, "info")
        assertEquals(0, info1.code)
        assertEquals("""{"current":"2","pending":[],"failed":[],"latest":"2"}""" + "\n", info1.stdout)
    }

    @Test
    fun `適用済みのチェックサムが変わっていれば validate も migrate も 1 で、勝手に直さない`() {
        val db = CliHarness.migratedDatabase()
        db.exec("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'")
        val tampered = db.scalar("SELECT checksum FROM flyway_schema_history WHERE version = '1'")

        val validate = CliHarness.run(db, "validate")
        assertEquals(1, validate.code, validate.stderr + validate.stdout)
        assertTrue(""""valid":false""" in validate.stdout, validate.stdout)
        assertTrue("checksum" in validate.stdout.lowercase() || "mismatch" in validate.stdout.lowercase(), validate.stdout)

        val migrate = CliHarness.run(db, "migrate")
        assertEquals(1, migrate.code, "validateOnMigrate=true なので不整合のまま migrate しない")
        assertEquals("""{"mode":"migrate","error":"failed"}""" + "\n", migrate.stdout)
        assertEquals(tampered, db.scalar("SELECT checksum FROM flyway_schema_history WHERE version = '1'"), "自動の repair をしていない")
    }

    @Test
    fun `R__grants の内容が変わったときは未適用扱い（validate 10）で、migrate で再適用される`() {
        val db = CliHarness.migratedDatabase()
        db.exec("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE script LIKE 'R__%'")

        val validate = CliHarness.run(db, "validate")
        assertEquals(10, validate.code, validate.stderr + validate.stdout)
        assertTrue(""""pending":["R__grants"]""" in validate.stdout, validate.stdout)

        val migrate = CliHarness.run(db, "migrate")
        assertEquals(0, migrate.code, migrate.stderr)
        assertTrue(""""applied":["R__grants"]""" in migrate.stdout, migrate.stdout)
        assertEquals(0, CliHarness.run(db, "validate").code)
    }

    @Test
    fun `履歴の無い空でないスキーマには migrate しない（baselineOnMigrate=false）`() {
        val db = MySqlSupport.newDatabase()
        db.exec("CREATE TABLE stray (id INT)")
        val migrate = CliHarness.run(db, "migrate")
        assertEquals(1, migrate.code, migrate.stderr + migrate.stdout)
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_NAME = 'races'"))
        assertEquals(1, CliHarness.run(db, "validate").code)
    }

    @Test
    fun `パスワードが違えば 1（認証失敗は一時的ではない）で、パスワードは出力に出ない`() {
        val db = MySqlSupport.newDatabase()
        val wrong = "definitely-wrong-pw-123"
        for (mode in listOf("info", "validate", "migrate")) {
            val r = CliHarness.run(db, mode, env = db.env(password = wrong))
            assertEquals(1, r.code, "mode=$mode ${r.stderr}")
            assertEquals("""{"mode":"$mode","error":"failed"}""" + "\n", r.stdout)
            assertFalse(wrong in r.stdout + r.stderr)
            assertFalse(MySqlSupport.PASSWORDS.getValue("migrate") in r.stdout + r.stderr)
        }
    }

    @Test
    fun `存在しないデータベースは 1`() {
        val db = MySqlSupport.newDatabase()
        val env = db.env() + ("DB_URL" to MySqlSupport.jdbcUrl("nosuchdatabase"))
        val r = CliHarness.run(db, "info", env = env)
        assertEquals(1, r.code, r.stderr)
    }

    @Test
    fun `実プロセスでも終了コードと標準出力が契約どおり（Flyway のログが標準出力に混ざらない）`() {
        val db = MySqlSupport.newDatabase()
        val env = db.env()

        val validate0 = ProcessSupport.launch(listOf("--mode=validate"), env)
        assertEquals(10, validate0.code, validate0.stderr)
        singleJsonLine(validate0.stdout)

        val migrate = ProcessSupport.launch(listOf("--mode=migrate"), env)
        assertEquals(0, migrate.code, migrate.stderr)
        singleJsonLine(migrate.stdout)
        assertTrue(""""applied":["1","2","R__grants"]""" in migrate.stdout, migrate.stdout)

        val again = ProcessSupport.launch(listOf("--mode=migrate"), env)
        assertEquals(0, again.code, again.stderr)
        singleJsonLine(again.stdout)

        val validate1 = ProcessSupport.launch(listOf("--mode=validate"), env)
        assertEquals(0, validate1.code, validate1.stderr)
        singleJsonLine(validate1.stdout)

        val info = ProcessSupport.launch(listOf("--mode=info"), env)
        assertEquals(0, info.code, info.stderr)
        assertEquals("""{"current":"2","pending":[],"failed":[],"latest":"2"}""" + "\n", info.stdout)

        for (r in listOf(validate0, migrate, again, validate1, info)) {
            assertFalse(MySqlSupport.PASSWORDS.getValue("migrate") in r.stdout + r.stderr, "パスワードが出力に出ている")
        }
        assertEquals(2, ProcessSupport.launch(emptyList(), env).code)
        assertEquals(2, ProcessSupport.launch(listOf("--mode=repair"), env).code)
    }
}
