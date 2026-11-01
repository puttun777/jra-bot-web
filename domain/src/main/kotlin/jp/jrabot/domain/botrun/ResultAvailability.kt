package jp.jrabot.domain.botrun

import jp.jrabot.domain.ingest.PayoutStore
import jp.jrabot.domain.ingest.ResultStore
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId

/** そのレースの結果の取り込み状況（DB の `results` / `payouts` から**毎回導出**する。状態を別に保存しない）。 */
enum class ResultState {
    /** 着順がまだ無い（結果待ち）。 */
    NONE,

    /** 着順はあるが、単勝の**確定した**払戻がまだ無い（払戻待ち。速報の払戻だけの場合も）。 */
    RESULT_ONLY,

    /** 着順（1 着がある）と単勝の確定払戻がそろった。成績の計算に使える。 */
    COMPLETE,
}

fun interface ResultAvailability {
    fun state(
        tenantId: TenantId,
        raceId: RaceId,
    ): ResultState

    companion object {
        /** 結果を使わない構成（テスト・結果待ちを有効にしていない構成）。 */
        val NONE = ResultAvailability { _, _ -> ResultState.NONE }
    }
}

/**
 * [ResultAvailability] の実装（取込が書いた `results`/`payouts` を読む）。**完了の条件**: 着順 1 位の馬がいて、単勝（win）の払戻に**確定**（provisional = false）の行がある。
 * ワイド・三連複の払戻は完了の条件にしない（レースによって公表されない、または遅れて届くことがあるため。成績の計算は無いものを『不明』として扱う）。
 */
class StoreResultAvailability(
    private val results: ResultStore,
    private val payouts: PayoutStore,
) : ResultAvailability {
    override fun state(
        tenantId: TenantId,
        raceId: RaceId,
    ): ResultState {
        if (results.list(tenantId, raceId).none { it.finish == 1 }) return ResultState.NONE
        val confirmedWin = payouts.list(tenantId, raceId, Market.WIN).any { !it.provisional }
        return if (confirmedWin) ResultState.COMPLETE else ResultState.RESULT_ONLY
    }
}
