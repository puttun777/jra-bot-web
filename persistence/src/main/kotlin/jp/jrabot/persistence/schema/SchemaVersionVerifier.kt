package jp.jrabot.persistence.schema

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/** スキーマ版の確認結果（CONTRACTS §4.3）。メッセージ・接続情報・資格情報を持たない。 */
sealed interface SchemaCheck {
    /** 適用済みの最大の成功版が必要な版以上（新しい版も可: expand/contract）。 */
    data class Satisfied(
        val current: SchemaVersion,
    ) : SchemaCheck

    /** DB には接続できたが、必要な版に届かない（履歴の表が無い・成功した版が無い場合は [current] が null）。ready=503、Job は exit 1。 */
    data class Insufficient(
        val current: SchemaVersion?,
        val required: SchemaVersion,
    ) : SchemaCheck

    /** DB に接続できない（一時的な障害）。ready=503、Job は exit 75。 */
    data object Unreachable : SchemaCheck

    /** 接続はできる/試みたが、再試行しても直らない設定・権限の問題（認証失敗、DB が無い、履歴を読む権限が無い、想定外の例外）。ready=503、Job は exit 1。 */
    data class Failed(
        val reason: SchemaFailure,
    ) : SchemaCheck
}

enum class SchemaFailure {
    /** DB ユーザーの認証に失敗した（パスワード・ユーザーの誤り）。 */
    AUTHENTICATION,

    /** DB が無い、または接続ユーザーに DB/履歴表へのアクセス権が無い。 */
    DATABASE_OR_PERMISSION,

    /** 上記以外の想定外のエラー（JDBC URL の誤りなど）。再試行しても直るとは限らない。 */
    UNEXPECTED,
}

/**
 * アプリの起動条件のスキーマ版を確認する（CONTRACTS §4.3）。Flyway の履歴表（`flyway_schema_history`）を**読むだけ**で、
 * マイグレーションを実行しない（アプリのイメージに Flyway は入れない）。
 *
 * - 判定: 成功した（`success=1`）版の最大が [required] 以上なら [SchemaCheck.Satisfied]。新しい版は許容する。
 * - 接続不能（一時障害）と、認証・設定の誤りを区別する。**例外を無条件に「接続不能」にしない**: MySQL のエラー番号が確定しているもの
 *   （1045 認証、1044/1049/1142/1143 DB・権限）を先に判定し、その次にネットワーク系（`SQLTransientConnectionException`・
 *   `SQLRecoverableException`・SQLState `08S01`・ソケット例外・1040 接続数超過）だけを [SchemaCheck.Unreachable] とする。
 * - ログにはエラー番号・SQLState・例外クラス名だけを出し、メッセージ（ホスト名やユーザー名を含み得る）・スタックは出さない。
 * - 接続は [DataSource] から 1 本借りて戻す。1 回の確認で DB に出す問い合わせは 1 つ。
 */
class SchemaVersionVerifier(
    private val dataSource: DataSource,
    val required: SchemaVersion,
    private val log: Logger = LoggerFactory.getLogger(SchemaVersionVerifier::class.java),
) {
    fun check(): SchemaCheck =
        try {
            val versions = readSuccessfulVersions()
            val current = versions.maxOrNull()
            if (current != null && current >= required) SchemaCheck.Satisfied(current) else SchemaCheck.Insufficient(current, required)
        } catch (e: Exception) {
            classify(e)
        }

    private fun readSuccessfulVersions(): List<SchemaVersion> =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.queryTimeout = QUERY_TIMEOUT_SECONDS
                st.executeQuery("SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL").use { rs ->
                    buildList { while (rs.next()) SchemaVersion.parseOrNull(rs.getString(1))?.let(::add) }
                }
            }
        }

    internal fun classify(e: Exception): SchemaCheck {
        val chain = generateSequence<Throwable>(e) { it.cause?.takeIf { c -> c !== it } }.take(MAX_CHAIN).toList()
        val sql = chain.filterIsInstance<SQLException>()
        val codes = sql.map { it.errorCode }
        val result =
            when {
                // 履歴の表が無い = まだ何も適用されていない DB（版 0 と同じ扱い）
                MYSQL_NO_SUCH_TABLE in codes -> SchemaCheck.Insufficient(null, required)

                MYSQL_ACCESS_DENIED in codes -> SchemaCheck.Failed(SchemaFailure.AUTHENTICATION)

                codes.any { it in DB_OR_PERMISSION_CODES } -> SchemaCheck.Failed(SchemaFailure.DATABASE_OR_PERMISSION)

                isNetworkFailure(chain, sql) -> SchemaCheck.Unreachable

                else -> SchemaCheck.Failed(SchemaFailure.UNEXPECTED)
            }
        log.warn(
            "schema check: result={} cause={} sqlState={} errorCode={}",
            result::class.simpleName,
            e.javaClass.simpleName,
            sql.firstOrNull()?.sqlState,
            sql.firstOrNull()?.errorCode,
        )
        return result
    }

    private fun isNetworkFailure(
        chain: List<Throwable>,
        sql: List<SQLException>,
    ): Boolean =
        chain.any {
            it is SQLTransientConnectionException || it is SQLRecoverableException || it is SocketException || it is SocketTimeoutException ||
                it is UnknownHostException
        } ||
            sql.any { it.sqlState == SQLSTATE_COMMUNICATION_FAILURE || it.errorCode == MYSQL_TOO_MANY_CONNECTIONS }

    companion object {
        const val QUERY_TIMEOUT_SECONDS = 5
        private const val MAX_CHAIN = 10
        private const val SQLSTATE_COMMUNICATION_FAILURE = "08S01"
        private const val MYSQL_TOO_MANY_CONNECTIONS = 1040
        private const val MYSQL_ACCESS_DENIED = 1045
        private const val MYSQL_NO_SUCH_TABLE = 1146
        private val DB_OR_PERMISSION_CODES = setOf(1044, 1049, 1142, 1143)
    }
}
