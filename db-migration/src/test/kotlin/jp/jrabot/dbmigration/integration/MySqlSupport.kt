package jp.jrabot.dbmigration.integration

import jp.jrabot.dbmigration.DbConfig
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Testcontainers で起動する使い捨ての MySQL（環境変数 `MYSQL_IMAGE`、既定 `mysql:8.4`）。合成データだけを扱う。
 * Docker が使えなければ**失敗する**（スキップしない）。コンテナは JVM 内で 1 つを共有し、テストごとに別のデータベースを作る
 * （ユーザーは用途別に 1 回だけ作る。権限はデータベースごとに R__grants.sql が付ける）。
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
    val RESTRICTED_USERS = listOf("app", "ingest", "bot", "mltrain")

    /** **ローカル検証専用**: `JRABOT_TEST_MYSQL_EXTERNAL=host:port` で Docker を使わず動作中の MySQL を使う（persistence 側と同じ。CI では設定しない）。 */
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

    val host: String get() = external?.first ?: container.host
    val port: Int get() = external?.second ?: container.getMappedPort(3306)

    fun jdbcUrl(database: String?): String = "jdbc:mysql://$host:$port/${database.orEmpty()}"

    fun rootConnection(database: String?): Connection = DriverManager.getConnection(jdbcUrl(database), "root", ROOT_PASSWORD)

    /** `SELECT VERSION()` の値。 */
    fun serverVersion(): String =
        rootConnection(null).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT VERSION()").use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    /** テスト専用の空のデータベース。名前に `_` を含めない（GRANT のスキーマ名でワイルドカードになるため）。 */
    fun newDatabase(): TestDb {
        check(usersCreated)
        val name =
            "jrt" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        rootConnection(null).use { c ->
            c.createStatement().use {
                it.execute("CREATE DATABASE `$name` DEFAULT CHARACTER SET utf8mb4")
                // infra が付ける権限（R__grants.sql の前提）: migrate はスキーマの全権限と GRANT OPTION を持つ
                it.execute("GRANT ALL PRIVILEGES ON `$name`.* TO 'migrate'@'%' WITH GRANT OPTION")
            }
        }
        return TestDb(name)
    }
}

class TestDb(
    val name: String,
) {
    fun config(
        user: String = "migrate",
        password: String = MySqlSupport.PASSWORDS.getValue(user),
    ) = DbConfig(MySqlSupport.jdbcUrl(name), user, password)

    fun env(
        user: String = "migrate",
        password: String = MySqlSupport.PASSWORDS.getValue(user),
    ): Map<String, String> = mapOf("DB_URL" to MySqlSupport.jdbcUrl(name), "DB_USER" to user, "DB_PASSWORD" to password)

    fun root(): Connection = MySqlSupport.rootConnection(name)

    fun connect(user: String): Connection = DriverManager.getConnection(MySqlSupport.jdbcUrl(name), user, MySqlSupport.PASSWORDS.getValue(user))

    /** 管理者（root）で SQL を実行する。 */
    fun exec(sql: String) = root().use { c -> c.createStatement().use { it.execute(sql) } }

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
}

/** MySQL のエラー番号（`SQLException.errorCode`）。 */
object MySqlError {
    const val DUPLICATE_ENTRY = 1062
    const val FK_CHILD = 1452
    const val FK_PARENT = 1451
    const val CHECK_VIOLATED = 3819
    const val DATA_TRUNCATED = 1265
    const val TABLE_DENIED = 1142
    const val COLUMN_DENIED = 1143
    const val DB_DENIED = 1044
    const val ACCESS_DENIED = 1045
}

/** [block] が、指定したエラー番号のいずれかの [SQLException] で失敗することを確かめる。 */
fun assertSqlError(
    vararg codes: Int,
    message: String = "",
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: SQLException) {
        assertTrue(e.errorCode in codes, "期待したエラー番号 ${codes.toList()} ではない: ${e.errorCode} ${e.message} ($message)")
        return
    }
    fail("SQL エラー ${codes.toList()} になるはずだが成功した ($message)")
}

fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

fun Connection.update(sql: String): Int = createStatement().use { it.executeUpdate(sql) }

fun Connection.long(sql: String): Long =
    createStatement().use { s ->
        s.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

fun assertTableCount(
    db: TestDb,
    expected: Int,
) = assertEquals(
    expected.toString(),
    db.scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '${db.name}' AND TABLE_TYPE = 'BASE TABLE'"),
)
