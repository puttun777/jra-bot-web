package jp.jrabot.domain.calendar

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * 「開催予定の取得に成功した」という**取得の事実**（YSCH など。誰がいつ取得したか）。
 * 開催日の行が無い日を「開催なしと確認した」と言えるのは、この事実が新鮮なときだけ（RACE_CALENDAR_DESIGN §1 原則 2）。
 * 行が無いだけでは「不明」であって「開催なし」ではない。
 */
data class ScheduleFetchFact(
    val date: LocalDate,
    /** 取得に成功した時刻（UTC）。 */
    val fetchedAt: Instant,
)

/**
 * 取得の事実の読取り口。実際の取得は T3.4（取込 API）・T3.5（Windows エージェント）で、それまで本番に事実は存在しない。
 * 本番の配線は [NoScheduleFetchFacts]（常に「事実なし」= 行の無い日は不明）。[FixedScheduleFetchFacts] は合成データのテスト用で、
 * 実際には取得していない事実を作れてしまうので本番に配線しない（配線の検査あり）。
 */
fun interface ScheduleFetchFactReader {
    fun latestSuccess(
        tenantId: TenantId,
        date: LocalDate,
    ): ScheduleFetchFact?
}

/** 取得の事実がまだ存在しない（T3.4 まで）。常に null を返す = 行の無い日は UNKNOWN のまま（開催なしを確認したことにしない）。 */
object NoScheduleFetchFacts : ScheduleFetchFactReader {
    override fun latestSuccess(
        tenantId: TenantId,
        date: LocalDate,
    ): ScheduleFetchFact? = null
}

/** **合成・テスト専用**: 与えた事実をそのまま返す（実際の取得ではない）。 */
class FixedScheduleFetchFacts(
    private val facts: Map<Pair<TenantId, LocalDate>, ScheduleFetchFact> = emptyMap(),
) : ScheduleFetchFactReader {
    override fun latestSuccess(
        tenantId: TenantId,
        date: LocalDate,
    ): ScheduleFetchFact? = facts[tenantId to date]
}
