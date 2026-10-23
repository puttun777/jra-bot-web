package jp.jrabot.dbmigration.integration

import jp.jrabot.dbmigration.Cli
import jp.jrabot.dbmigration.FlywayEngine
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CliResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
)

/** 実際の Flyway エンジンで [Cli] を同じプロセス内で実行する。 */
object CliHarness {
    fun run(
        db: TestDb,
        mode: String,
        env: Map<String, String> = db.env(),
        locations: List<String> = FlywayEngine.DEFAULT_LOCATIONS,
    ): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val cli = Cli { config -> FlywayEngine(config, locations) }
        val code = cli.run(listOf("--mode=$mode"), env, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return CliResult(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    /** 新しい空のデータベースに V1・V2・R__grants を適用して返す。成功しなければテストを失敗させる。 */
    fun migratedDatabase(): TestDb {
        val db = MySqlSupport.newDatabase()
        val result = run(db, "migrate")
        check(result.code == 0) { "migrate が失敗: code=${result.code} stdout=${result.stdout} stderr=${result.stderr}" }
        return db
    }
}
