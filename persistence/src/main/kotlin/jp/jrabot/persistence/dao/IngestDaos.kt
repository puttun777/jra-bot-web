package jp.jrabot.persistence.dao

import org.seasar.doma.BatchInsert
import org.seasar.doma.Dao
import org.seasar.doma.Entity
import org.seasar.doma.GeneratedValue
import org.seasar.doma.GenerationType
import org.seasar.doma.Id
import org.seasar.doma.Insert
import org.seasar.doma.Select
import org.seasar.doma.Table
import org.seasar.doma.Update
import org.seasar.doma.jdbc.BatchResult
import org.seasar.doma.jdbc.Result
import org.seasar.doma.jdbc.entity.NamingType
import java.time.LocalDate
import java.time.LocalDateTime

/** `agent`。署名鍵は暗号化したバイト列（`sign_key_enc`）と KEK の識別子だけ。トークン自体は持たない。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "agent")
internal data class AgentEntity(
    @Id val tenantId: Long,
    @Id val agentId: String,
    val name: String,
    val tokenHash: String,
    val signKeyEnc: ByteArray?,
    val signKeyKid: String?,
    val tokenExpiresAt: LocalDateTime,
    val status: String,
    val createdAt: LocalDateTime,
    val createdBy: String?,
    val revokedAt: LocalDateTime?,
    val revokedBy: String?,
)

@Dao
internal interface AgentDao {
    /** **テナント条件なし**（認証でエージェントの行からテナントを決めるため。`agent_id` は全体で一意: V3 の UNIQUE）。TenantSqlGuard の許可リストにある。 */
    @Select
    fun selectByAgentId(agentId: String): AgentEntity?

    @Insert
    fun insert(entity: AgentEntity): Result<AgentEntity>
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "agent_nonce")
internal data class AgentNonceEntity(
    @Id val tenantId: Long,
    @Id val agentId: String,
    @Id val nonce: String,
    val expiresAt: LocalDateTime,
)

@Dao
internal interface AgentNonceDao {
    /** 主キーの重複（1062）= リプレイ。 */
    @Insert
    fun insert(entity: AgentNonceEntity): Result<AgentNonceEntity>

    /** 期限切れの掃除（全テナント共通の保守。テナント条件なし = 許可リスト）。 */
    @Update(sqlFile = true)
    fun deleteExpired(
        before: LocalDateTime,
        limit: Int,
    ): Int
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "agent_heartbeat")
internal data class AgentHeartbeatEntity(
    @Id val tenantId: Long,
    @Id val agentId: String,
    val lastSeenAt: LocalDateTime,
    val agentVersion: String?,
    val clockSkewMs: Int?,
    val queueDepth: Long?,
    val oldestQueuedAt: LocalDateTime?,
    val ackedTs: String?,
    val lastJvResult: String?,
    val diskFreeMb: Long?,
    val lastError: String?,
    val localMode: String?,
)

@Dao
internal interface AgentHeartbeatDao {
    @Insert(sqlFile = true)
    fun upsert(entity: AgentHeartbeatEntity): Result<AgentHeartbeatEntity>

    @Select
    fun selectById(
        tenantId: Long,
        agentId: String,
    ): AgentHeartbeatEntity?
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_batch")
internal data class IngestBatchEntity(
    @Id val tenantId: Long,
    @Id val batchId: String,
    val agentId: String,
    val batchClass: String,
    val dataspec: String,
    val optionNo: Int?,
    val fromtime: String?,
    val lastFileTimestamp: String?,
    val eof: Boolean,
    val observedAt: LocalDateTime,
    val receivedAt: LocalDateTime,
    val payloadSha256: String,
    val recordCount: Int,
    val acceptedCount: Int,
    val duplicateCount: Int,
    val status: String,
    val completedAt: LocalDateTime?,
    val fetchId: String?,
    val fetchSeq: Int?,
    val failReason: String?,
)

@Dao
internal interface IngestBatchDao {
    /**
     * `ON DUPLICATE KEY UPDATE tenant_id = tenant_id`（件数 1 = 挿入できた、0 = どれかの UNIQUE が既にある）。重複で失敗させて S ロックを取るやり方は、
     * 同時の再送が S ロックを持ったまま X ロックを待ち合ってデッドロックするので使わない（既存の行には最初から X ロックを取る）。
     * 重複したのが主キー（同じ batchId）か `uq_ingest_batch_fetch_seq`（同じ連番を別の batchId が持つ）かは、呼び出し側が主キーで読んで区別する。
     */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: IngestBatchEntity): Result<IngestBatchEntity>

    @Select
    fun selectById(
        tenantId: Long,
        batchId: String,
    ): IngestBatchEntity?

    @Select
    fun selectByIdForUpdate(
        tenantId: Long,
        batchId: String,
    ): IngestBatchEntity?

    /** 引き継ぎ: 読んだ行（状態・received_at が同じ）のときだけ、新しい received_at（= 処理権のトークン）で PROCESSING に戻す。 */
    @Update(sqlFile = true)
    fun takeover(
        tenantId: Long,
        batchId: String,
        expectedStatus: String,
        expectedReceivedAt: LocalDateTime,
        newReceivedAt: LocalDateTime,
    ): Int

    @Update(sqlFile = true)
    fun complete(
        tenantId: Long,
        batchId: String,
        token: LocalDateTime,
        accepted: Int,
        duplicates: Int,
        now: LocalDateTime,
    ): Int

    @Update(sqlFile = true)
    fun fail(
        tenantId: Long,
        batchId: String,
        token: LocalDateTime,
        reason: String,
    ): Int
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_raw")
internal data class IngestRawEntity(
    @Id val tenantId: Long,
    @Id val recordHash: String,
    val batchId: String,
    val seq: Long,
    val dataspec: String,
    val recordId: String,
    val payload: ByteArray,
    val receivedAt: LocalDateTime,
    val parseStatus: String,
    val parseError: String?,
)

@Dao
internal interface IngestRawDao {
    /** `ON DUPLICATE KEY UPDATE tenant_id = tenant_id`（件数 1 = 挿入、0 = 既存）。`INSERT IGNORE` は FK・CHECK の違反も握りつぶすので使わない。 */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: IngestRawEntity): Result<IngestRawEntity>

    @Select
    fun selectByHash(
        tenantId: Long,
        recordHash: String,
    ): IngestRawEntity?

    @Select
    fun selectByBatch(
        tenantId: Long,
        batchId: String,
    ): List<IngestRawEntity>

    /** 保留（PENDING）の行の結果を更新する。**PENDING の行だけ**（件数 1 = 更新、0 = 既に確定している・不在）。 */
    @Update(sqlFile = true)
    fun settlePending(entity: IngestRawEntity): Result<IngestRawEntity>

    /** [reason] を理由にした保留の行を、バッチの種別・観測時刻付きで古い順に。 */
    @Select
    fun selectPending(
        tenantId: Long,
        reason: String,
        limit: Int,
    ): List<PendingRawRow>
}

/** 保留中の生レコード + 取り込んだバッチの種別・観測時刻（再適用用）。 */
@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
internal data class PendingRawRow(
    val recordHash: String,
    val batchId: String,
    val batchClass: String,
    val observedAt: LocalDateTime,
    val dataspec: String,
    val recordId: String,
    val payload: ByteArray,
)

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_fetch")
internal data class IngestFetchEntity(
    @Id val tenantId: Long,
    @Id val fetchId: String,
    val agentId: String,
    val dataspec: String,
    val optionNo: Int?,
    val fromtime: String?,
    val origin: String,
    val backfillId: Long?,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val lastFileTimestamp: String?,
    val status: String,
    val failReason: String?,
    val nextSeq: Int,
    val recordCount: Long,
    val unparsedCount: Int,
    val failedParseCount: Int,
    val maxRaceDate: LocalDate?,
    val coveredFrom: LocalDate?,
    val coveredTo: LocalDate?,
    val startedAt: LocalDateTime,
    val lastBatchAt: LocalDateTime,
    val completedAt: LocalDateTime?,
)

@Dao
internal interface IngestFetchDao {
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: IngestFetchEntity): Result<IngestFetchEntity>

    @Select
    fun selectById(
        tenantId: Long,
        fetchId: String,
    ): IngestFetchEntity?

    @Select
    fun selectByIdForUpdate(
        tenantId: Long,
        fetchId: String,
    ): IngestFetchEntity?

    @Update(sqlFile = true)
    fun update(entity: IngestFetchEntity): Result<IngestFetchEntity>

    /** 日付 [date] を覆う、取得に成功した YSCH の最新の完了時刻。無ければ null。 */
    @Select
    fun selectLatestFactAt(
        tenantId: Long,
        dataspec: String,
        date: LocalDate,
    ): LocalDateTime?
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_cursor")
internal data class IngestCursorEntity(
    @Id val tenantId: Long,
    @Id val dataType: String,
    val lastFileTimestamp: String,
    val updatedAt: LocalDateTime,
)

@Dao
internal interface IngestCursorDao {
    /** 前進だけ（保存値以下なら件数 0）。 */
    @Insert(sqlFile = true)
    fun advance(entity: IngestCursorEntity): Result<IngestCursorEntity>

    @Select
    fun selectById(
        tenantId: Long,
        dataType: String,
    ): IngestCursorEntity?
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_gap")
internal data class IngestGapEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long?,
    val tenantId: Long,
    val agentId: String?,
    val dataspec: String?,
    val reason: String,
    val gapFrom: LocalDateTime,
    val gapTo: LocalDateTime?,
    val detectedAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
    val detail: String?,
)

@Dao
internal interface IngestGapDao {
    /** 自然キー `(tenant, reason, gap_from, agent_key)` の重複は何もしない（件数 0）。 */
    @Insert(sqlFile = true)
    fun insertIfAbsent(entity: IngestGapEntity): Result<IngestGapEntity>

    @Update(sqlFile = true)
    fun resolve(
        tenantId: Long,
        id: Long,
        now: LocalDateTime,
    ): Int

    @Select
    fun selectOpen(tenantId: Long): List<IngestGapEntity>

    @Select
    fun selectById(
        tenantId: Long,
        id: Long,
    ): IngestGapEntity?
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "ingest_backfill")
internal data class IngestBackfillEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long?,
    val tenantId: Long,
    val dataspec: String,
    val optionNo: Int?,
    val fromtime: String,
    val targetFrom: LocalDate?,
    val targetTo: LocalDate?,
    val gapId: Long?,
    val status: String,
    val fetchId: String?,
    val requestedAt: LocalDateTime,
    val requestedBy: String,
    val updatedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val failReason: String?,
)

@Dao
internal interface IngestBackfillDao {
    /** 一意違反（1062 `uq_ingest_backfill_active`）= 同じ要求が実行中。生成列（option_key・active_slot）は挿入しない（Doma の @Insert は列を明示するので生成列を除く）。 */
    @Insert(sqlFile = true)
    fun insert(entity: IngestBackfillEntity): Result<IngestBackfillEntity>

    @Select
    fun selectById(
        tenantId: Long,
        id: Long,
    ): IngestBackfillEntity?

    @Select
    fun selectActive(tenantId: Long): List<IngestBackfillEntity>

    @Select
    fun selectActiveByKey(
        tenantId: Long,
        dataspec: String,
        optionKey: Int,
        fromtime: String,
    ): IngestBackfillEntity?

    @Update(sqlFile = true)
    fun attachFetch(
        tenantId: Long,
        id: Long,
        fetchId: String,
        now: LocalDateTime,
        takeoverBefore: LocalDateTime,
    ): Int

    @Update(sqlFile = true)
    fun complete(
        tenantId: Long,
        id: Long,
        fetchId: String,
        now: LocalDateTime,
    ): Int

    @Update(sqlFile = true)
    fun releaseFetch(
        tenantId: Long,
        id: Long,
        fetchId: String,
        reason: String,
        now: LocalDateTime,
    ): Int

    @Update(sqlFile = true)
    fun cancel(
        tenantId: Long,
        id: Long,
        now: LocalDateTime,
    ): Int
}

@Entity(immutable = true, naming = NamingType.SNAKE_LOWER_CASE)
@Table(name = "combo_odds_snapshots")
internal data class ComboOddsRowEntity(
    @Id val tenantId: Long,
    @Id val raceId: String,
    @Id val market: String,
    @Id val collectedAt: LocalDateTime,
    @Id val comboKey: String,
    val odds: Double,
    val oddsHigh: Double?,
    val popularity: Int?,
    val source: String?,
    val rtKey: String?,
    val createdAt: LocalDateTime,
)

@Dao
internal interface ComboOddsSnapshotDao {
    @Select
    fun selectHistory(
        tenantId: Long,
        raceId: String,
        market: String,
    ): List<ComboOddsRowEntity>

    /** 行ごとの更新件数: 1 = 挿入、2 = 更新、0 = 同じ内容。 */
    @BatchInsert(sqlFile = true)
    fun upsertAll(rows: List<ComboOddsRowEntity>): BatchResult<ComboOddsRowEntity>
}
