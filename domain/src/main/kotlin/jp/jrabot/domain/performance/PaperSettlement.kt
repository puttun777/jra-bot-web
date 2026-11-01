package jp.jrabot.domain.performance

import jp.jrabot.domain.ingest.ResultEntry
import jp.jrabot.domain.ingest.StoredPayout
import jp.jrabot.domain.race.RaceId
import java.time.Instant

/*
 * 通知した Signal（推奨）の**紙上成績**（購入していない仮想の損益）の計算。DB には書かず、`signals` + `results` + `payouts` から毎回計算する純粋関数
 * （保存先の追加 = V6 は不要。冪等で二重計上しない。D-234）。金額の概念を混ぜない（T39_DESIGN §5.5）:
 *  ① 実払戻（`payouts`）② 通知時の予測オッズ（Signal.odds。払戻の根拠ではない）③ 推奨額（stake。購入していない）④ 紙上損益（③×②/③×①から計算した仮想の値）。
 * ここで出すのは ④ で、**旧互換（stake × 通知時のオッズ。元本込み）** [paperPayout] と、**実払戻ベース**（stake × 100 円あたりの実払戻 / 100）[actualPayout] を別の値で持つ。
 * 結果が無い・着順が不明な馬は**未確定**にし、外れ（損失）として数えない（取消などを損失にしない）。
 */

/** 単勝 Signal の入力（`signals` の 1 行から）。 */
data class WinSignal(
    val raceId: RaceId,
    val umaban: Int,
    val observedAt: Instant,
    val odds: Double,
    val stake: Double,
)

/** NOT_A_BET: 推奨額が 0 以下（見送りの Signal）またはオッズが 0 以下。損益の対象ではないので数えない（レビュー P1）。 */
enum class UnsettledReason { NO_RESULT, HORSE_NOT_IN_RESULT, NO_FINISH, NOT_A_BET }

sealed interface PaperOutcome {
    data class Unsettled(
        val reason: UnsettledReason,
    ) : PaperOutcome

    /**
     * 確定。[paperPayout] は stake × 通知時オッズ（的中のみ。元本込み）、[actualPayout] は実払戻（`payouts` の単勝の行があるときだけ。無ければ null）。
     * 損益は payout − stake。
     */
    data class Settled(
        val hit: Boolean,
        val stake: Double,
        val paperPayout: Double,
        val actualPayout: Double?,
    ) : PaperOutcome {
        val paperProfit: Double get() = paperPayout - stake
        val actualProfit: Double? get() = actualPayout?.let { it - stake }
    }
}

object PaperSettlement {
    /**
     * 単勝の紙上決済（旧 `settle_open_win_bets_for_race` と同じ規則 + 未確定の区別）。1 着（同着は全員）が的中。
     * [payouts] は単勝の払戻（組み合わせキーは 0 埋めの馬番 `07`）。**確定（provisional = false）の行だけ**を実払戻に使う。
     * 推奨額 0 以下（見送り）・オッズ 0 以下は [UnsettledReason.NOT_A_BET]（損益の対象外）。この関数は**単勝専用**（ワイド・三連複は賭式・組み合わせキーを持つ別の入力で扱う）。
     */
    fun settleWin(
        signal: WinSignal,
        results: List<ResultEntry>,
        payouts: List<StoredPayout>,
    ): PaperOutcome {
        if (!(signal.stake > 0.0) || !(signal.odds > 0.0)) return PaperOutcome.Unsettled(UnsettledReason.NOT_A_BET)
        // 着順 0 以下は取消・中止などで順位が付かない（外れにしない）。null と同じく『着順なし』
        if (results.isEmpty() || results.none { (it.finish ?: 0) > 0 }) return PaperOutcome.Unsettled(UnsettledReason.NO_RESULT)
        val mine = results.firstOrNull { it.umaban == signal.umaban } ?: return PaperOutcome.Unsettled(UnsettledReason.HORSE_NOT_IN_RESULT)
        val finish = mine.finish?.takeIf { it > 0 } ?: return PaperOutcome.Unsettled(UnsettledReason.NO_FINISH)
        val hit = finish == 1
        val paper = if (hit) signal.stake * signal.odds else 0.0
        // 実払戻は**確定した払戻だけ**を使う（速報は後で変わる。速報だけなら実払戻は null = 不明）
        val confirmed = payouts.filter { !it.provisional }
        val per100 = confirmed.firstOrNull { it.comboKey == "%02d".format(signal.umaban) }?.payout
        val actual =
            if (hit) {
                per100?.let { signal.stake * it / 100.0 }
            } else if (confirmed.isNotEmpty()) {
                0.0
            } else {
                null
            }
        return PaperOutcome.Settled(hit, signal.stake, paper, actual)
    }
}
