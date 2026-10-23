package jp.jrabot.persistence.doma

import org.seasar.doma.jdbc.JdbcMappingFunction
import org.seasar.doma.jdbc.JdbcMappingHint
import org.seasar.doma.jdbc.dialect.MysqlDialect
import org.seasar.doma.jdbc.dialect.StandardDialect
import org.seasar.doma.jdbc.type.AbstractJdbcType
import org.seasar.doma.wrapper.LocalDateTimeWrapper
import org.seasar.doma.wrapper.LocalDateWrapper
import org.seasar.doma.wrapper.LocalTimeWrapper
import java.sql.CallableStatement
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * `LocalDateTime` / `LocalDate` / `LocalTime` を `setObject` / `getObject(…, 型)` でそのまま渡す。
 * Doma 標準は `java.sql.Timestamp` 経由（`Timestamp.valueOf`）で、JVM の既定タイムゾーンや夏時間の欠落時刻で値がずれ得る。
 * DB の時刻は UTC の壁時計（`DATETIME(6)`）なので、変換を挟まないこの方式が JVM のタイムゾーンに依存しない（G-VER-3）。
 */
internal class UtcJdbcMappingVisitor : StandardDialect.StandardJdbcMappingVisitor() {
    override fun visitLocalDateTimeWrapper(
        wrapper: LocalDateTimeWrapper,
        p: JdbcMappingFunction,
        q: JdbcMappingHint,
    ): Void? = p.apply(wrapper, LocalDateTimeAsObject)

    override fun visitLocalDateWrapper(
        wrapper: LocalDateWrapper,
        p: JdbcMappingFunction,
        q: JdbcMappingHint,
    ): Void? = p.apply(wrapper, LocalDateAsObject)

    override fun visitLocalTimeWrapper(
        wrapper: LocalTimeWrapper,
        p: JdbcMappingFunction,
        q: JdbcMappingHint,
    ): Void? = p.apply(wrapper, LocalTimeAsObject)
}

/** [UtcJdbcMappingVisitor] を使う MySQL 方言。Spring のコンテキストには `dialect` として登録する。 */
internal fun jrabotMysqlDialect(): MysqlDialect = MysqlDialect(UtcJdbcMappingVisitor())

private object LocalDateTimeAsObject : AbstractJdbcType<LocalDateTime>(Types.TIMESTAMP) {
    override fun doGetValue(
        resultSet: ResultSet,
        index: Int,
    ): LocalDateTime? = resultSet.getObject(index, LocalDateTime::class.java)

    override fun doSetValue(
        preparedStatement: PreparedStatement,
        index: Int,
        value: LocalDateTime,
    ) = preparedStatement.setObject(index, value)

    override fun doGetValue(
        callableStatement: CallableStatement,
        index: Int,
    ): LocalDateTime? = callableStatement.getObject(index, LocalDateTime::class.java)

    override fun doConvertToLogFormat(value: LocalDateTime): String = "'$value'"
}

private object LocalDateAsObject : AbstractJdbcType<LocalDate>(Types.DATE) {
    override fun doGetValue(
        resultSet: ResultSet,
        index: Int,
    ): LocalDate? = resultSet.getObject(index, LocalDate::class.java)

    override fun doSetValue(
        preparedStatement: PreparedStatement,
        index: Int,
        value: LocalDate,
    ) = preparedStatement.setObject(index, value)

    override fun doGetValue(
        callableStatement: CallableStatement,
        index: Int,
    ): LocalDate? = callableStatement.getObject(index, LocalDate::class.java)

    override fun doConvertToLogFormat(value: LocalDate): String = "'$value'"
}

private object LocalTimeAsObject : AbstractJdbcType<LocalTime>(Types.TIME) {
    override fun doGetValue(
        resultSet: ResultSet,
        index: Int,
    ): LocalTime? = resultSet.getObject(index, LocalTime::class.java)

    override fun doSetValue(
        preparedStatement: PreparedStatement,
        index: Int,
        value: LocalTime,
    ) = preparedStatement.setObject(index, value)

    override fun doGetValue(
        callableStatement: CallableStatement,
        index: Int,
    ): LocalTime? = callableStatement.getObject(index, LocalTime::class.java)

    override fun doConvertToLogFormat(value: LocalTime): String = "'$value'"
}
