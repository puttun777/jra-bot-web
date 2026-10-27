package jp.jrabot.domain.signal

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

interface SignalReader {
    fun find(
        tenantId: TenantId,
        id: SignalId,
    ): Signal?

    /** レースの Signal を `observedAt` の昇順で返す。無ければ空。 */
    fun listByRace(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<Signal>

    /** 通知が [NotificationState.UNSENT] の Signal を古い順に最大 [limit] 件（1〜1000）。 */
    fun listUnsent(
        tenantId: TenantId,
        limit: Int,
    ): List<Signal>

    /**
     * [NotificationState.CLAIMED] のまま `notify_updated_at < [cutoff]`（claim した時刻が古い）の Signal を古い順に最大 [limit] 件（1〜1000）。
     * claim 後に送信の結果を記録できなかった（プロセスの停止など）ものの回収用。[cutoff] は送信に掛かり得る最長の時間より古くすること。
     */
    fun listClaimedBefore(
        tenantId: TenantId,
        cutoff: Instant,
        limit: Int,
    ): List<Signal>
}

/**
 * Signal の書込みと通知の claim。再実行しても二重通知にならないための操作を分けて持つ。
 * 状態の遷移は `UNSENT → CLAIMED → SENT | SEND_UNKNOWN` だけで、逆戻りしない。
 */
interface SignalWriter {
    /**
     * 自然キー `(tenantId, raceId, market, umaban, observedAt)` が無いときだけ挿入する（既存の行、特に通知の状態は変更しない）。
     * 戻り値の [RecordedSignal.signal] は [SignalId] 付きの保存済みの行（既にあれば既存の行）。
     */
    fun insertIfAbsent(signal: Signal): RecordedSignal

    /** [NotificationState.UNSENT] → [NotificationState.CLAIMED] を原子的に行う。勝ったときだけ true（他が先に claim 済み・存在しないときは false）。 */
    fun claim(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean

    /** [NotificationState.CLAIMED] → [NotificationState.SENT]。呼出し後に SENT なら true（既に SENT でも true＝冪等）。不在、または CLAIMED/SENT 以外なら false。 */
    fun markSent(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean

    /** [NotificationState.CLAIMED] → [NotificationState.SEND_UNKNOWN]（送信結果が不明。再送しない）。呼出し後に SEND_UNKNOWN なら true（冪等）。不在、または CLAIMED/SEND_UNKNOWN 以外なら false。 */
    fun markSendUnknown(
        tenantId: TenantId,
        id: SignalId,
    ): Boolean
}

/** [SignalWriter.insertIfAbsent] の結果。 */
data class RecordedSignal(
    val outcome: InsertOutcome,
    val signal: Signal,
)

interface SignalRepository :
    SignalReader,
    SignalWriter
