package jp.jrabot.persistence.integration

import org.testcontainers.mysql.MySQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testcontainers で起動する使い捨ての MySQL（環境変数 `MYSQL_IMAGE`、既定 `mysql:8.4`）。合成データだけを扱う。
 * Docker が使えなければ**失敗する**（スキップしない）。コンテナは JVM 内で 1 つを共有し、テストクラスごとに別のデータベースを作る。
 * スキーマは本物の db-migration CLI（`installDist`）で適用する（V1・V2・R__grants.sql）。
 */
object MySqlSupport {
    val image: String = System.getenv("MYSQL_IMAGE")?.takeIf { it.isNotBlank() } ?: "mysql:8.4"

    // CI の一時コンテナ専用のテスト値（本番の秘密ではない）
    const val ROOT_PASSWORD = "root-pw-for-ci-container"
    val PASSWORDS =
        mapOf(
            "migrate" to "migrate-pw-ci",
            "app" to "app-pw-ci",
            "ingest" to "ingest-pw-ci",
            "bot" to "bot-pw-ci",
            "mltrain" to "mltrain-pw-ci",
        )

    /**
     * **ローカル検証専用**: `JRABOT_TEST_MYSQL_EXTERNAL=host:port`（root のパスワードは [ROOT_PASSWORD]）を設定すると、Docker を使わず
     * 既に動いている MySQL を使う（Docker の使えないクラウドセッションでの事前確認用。版は 8.4/9.7 と限らない）。
     * CI では設定しない。8.4/9.7 の合否は Testcontainers（`MYSQL_IMAGE`）の結果だけを正とする。
     */
    private val external: Pair<String, Int>? =
        System.getenv("JRABOT_TEST_MYSQL_EXTERNAL")?.takeIf { it.isNotBlank() }?.let { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }

    private val container: MySQLContainer by lazy {
        check(external == null) { "外部の MySQL を使うときはコンテナを起動しない" }
        MySQLContainer(image)
            .withDatabaseName("bootstrap")
            .withUsername("bootstrap")
            .withPassword(ROOT_PASSWORD)
            .also { it.start() }
    }

    private val usersCreated: Boolean by lazy {
        rootConnection(null).use { c ->
            for ((user, password) in PASSWORDS) {
                c.createStatement().use { it.execute("CREATE USER IF NOT EXISTS '$user'@'%' IDENTIFIED BY '$password'") }
            }
        }
        true
    }

    fun jdbcUrl(database: String?): String {
        val (host, port) = hostAndPort()
        return "jdbc:mysql://$host:$port/${database.orEmpty()}"
    }

    /** コンテナの接続先（ホスト, ポート）。中継（[TcpProxy]）の転送先に使う。 */
    fun hostAndPort(): Pair<String, Int> = external ?: (container.host to container.getMappedPort(3306))

    /**
     * サーバが受け付けた接続の累計（`Connections` ステータス。接続の試行を含み、失敗した認証も数える）。
     * 「この操作で DB 接続が一切発生しない」ことの確認に使う（この確認自体は 1 本の root 接続を使うので、前後の差は +1 になる）。
     */
    fun serverConnections(): Long =
        rootConnection(null).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SHOW GLOBAL STATUS LIKE 'Connections'").use {
                    it.next()
                    it.getLong(2)
                }
            }
        }

    fun rootConnection(database: String?): Connection = DriverManager.getConnection(jdbcUrl(database), "root", ROOT_PASSWORD)

    fun serverVersion(): String =
        rootConnection(null).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT VERSION()").use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    /** db-migration CLI で V1・V2・R__grants を適用した新しいデータベース（テナント 1 と 2 を投入済み）。失敗したらテストを失敗させる。 */
    fun migratedDatabase(): TestDb {
        check(usersCreated)
        val name =
            "jrt" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12) // `_` を含めない（GRANT のワイルドカード）
        rootConnection(null).use { c ->
            c.createStatement().use {
                it.execute("CREATE DATABASE `$name` DEFAULT CHARACTER SET utf8mb4")
                it.execute("GRANT ALL PRIVILEGES ON `$name`.* TO 'migrate'@'%' WITH GRANT OPTION")
            }
        }
        val db = TestDb(name)
        runMigrateCli(db)
        db.exec("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1, 'tenant-one', NOW(6)), (2, 'tenant-two', NOW(6))")
        return db
    }

    private fun runMigrateCli(db: TestDb) {
        val installDir = File(requireNotNull(System.getProperty("dbmigration.install.dir")) { "dbmigration.install.dir が未設定（integrationTest タスクで実行する）" })
        val lib = File(installDir, "lib")
        assertTrue(lib.isDirectory, "db-migration の installDist が無い: $lib")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", "${lib.path}/*", "jp.jrabot.dbmigration.DbMigrationMainKt", "--mode=migrate")
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH") ?: "")
            put("LC_ALL", "C.UTF-8")
            put("DB_URL", jdbcUrl(db.name))
            put("DB_USER", "migrate")
            put("DB_PASSWORD", PASSWORDS.getValue("migrate"))
        }
        val out = File.createTempFile("migrate-out", ".txt")
        val err = File.createTempFile("migrate-err", ".txt")
        try {
            val process = builder.redirectOutput(out).redirectError(err).start()
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "migrate が 180 秒で終わらない")
            assertEquals(0, process.exitValue(), "migrate が失敗: stdout=${out.readText()} stderr=${err.readText()}")
        } finally {
            out.delete()
            err.delete()
        }
    }
}

class TestDb(
    val name: String,
) {
    fun root(): Connection = MySqlSupport.rootConnection(name)

    /** 管理者（root）で SQL を実行する。 */
    fun exec(sql: String): Int = root().use { c -> c.createStatement().use { s -> s.executeUpdate(sql) } }

    fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        root().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    fun scalar(sql: String): String? = query(sql) { it.getString(1) }.singleOrNull()

    fun count(sql: String): Long = checkNotNull(scalar(sql)).toLong()
}

/** MySQL のエラー番号。 */
object MySqlError {
    const val DUPLICATE_ENTRY = 1062
    const val FK_CHILD = 1452
    const val CHECK_VIOLATED = 3819
    const val TABLE_DENIED = 1142
    const val COLUMN_DENIED = 1143
    const val DB_DENIED = 1044
    const val OUT_OF_RANGE = 1264
}

/** 例外の連鎖にある SQLException のエラー番号。無ければ null。 */
fun Throwable.mysqlErrorCode(): Int? = generateSequence(this) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.errorCode

/** [block] が、指定したエラー番号のいずれかの SQL エラーで失敗することを確かめる（他のエラー・成功はテスト失敗）。 */
fun assertSqlError(
    vararg codes: Int,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        val actual = e.mysqlErrorCode()
        assertTrue(actual != null && actual in codes, "期待した SQL エラー ${codes.toList()} ではない: $actual ($e)")
        return
    }
    kotlin.test.fail("SQL エラー ${codes.toList()} になるはずだが成功した")
}

/** 権限不足（表・列・DB のいずれかの拒否）で失敗すること。 */
fun assertDenied(block: () -> Unit) = assertSqlError(MySqlError.TABLE_DENIED, MySqlError.COLUMN_DENIED, MySqlError.DB_DENIED, block = block)

/** テスト間でデータを空にする（FK の子から順に）。テナント 1・2 は残す。 */
fun TestDb.resetData() {
    for (table in listOf(
        "signals",
        "race_evaluations",
        "race_horses",
        "combo_odds_snapshots",
        "odds_snapshots",
        "bot_run",
        "calendar_override",
        "race_schedule_event",
        "race_day",
        "race_schedule",
        "races",
    )) {
        exec("DELETE FROM $table")
    }
}

/** [n] 個のタスクを同時に開始する（全員が揃うまで待ってから実行する）。結果（または例外）を順序どおりに返す。 */
fun <T> runConcurrently(
    n: Int,
    task: (Int) -> T,
): List<Result<T>> {
    val pool =
        java.util.concurrent.Executors
            .newFixedThreadPool(n)
    try {
        val ready = java.util.concurrent.CountDownLatch(n)
        val go = java.util.concurrent.CountDownLatch(1)
        val futures =
            (0 until n).map { i ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    go.await()
                    runCatching { task(i) }
                }
            }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "スレッドが揃わない")
        go.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
