package jp.jrabot.persistence.dao

import org.seasar.doma.Dao
import org.seasar.doma.Delete
import org.seasar.doma.Select
import java.time.LocalDateTime

/** 保持期間を過ぎた行の件数と削除（retention。`bot` ユーザーの DELETE 権限は R__grants.sql）。どの SQL も `tenant_id` で絞り、削除は上限付き。 */
@Dao
internal interface RetentionDao {
    @Select
    fun countComboOdds(
        tenantId: Long,
        before: LocalDateTime,
    ): Long

    @Select
    fun countIngestRaw(
        tenantId: Long,
        before: LocalDateTime,
    ): Long

    @Select
    fun countScheduleEvents(
        tenantId: Long,
        before: LocalDateTime,
    ): Long

    @Select
    fun countBotRuns(
        tenantId: Long,
        before: LocalDateTime,
    ): Long

    @Delete(sqlFile = true)
    fun deleteComboOdds(
        tenantId: Long,
        before: LocalDateTime,
        limit: Int,
    ): Int

    @Delete(sqlFile = true)
    fun deleteIngestRaw(
        tenantId: Long,
        before: LocalDateTime,
        limit: Int,
    ): Int

    @Delete(sqlFile = true)
    fun deleteScheduleEvents(
        tenantId: Long,
        before: LocalDateTime,
        limit: Int,
    ): Int

    @Delete(sqlFile = true)
    fun deleteBotRuns(
        tenantId: Long,
        before: LocalDateTime,
        limit: Int,
    ): Int
}
