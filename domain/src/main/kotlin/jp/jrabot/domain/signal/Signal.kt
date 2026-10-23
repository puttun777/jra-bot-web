package jp.jrabot.domain.signal

import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.odds.Odds
import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import jp.jrabot.domain.value.Probability
import java.time.Instant

/** 永続化された Signal の ID（`signals.id`）。 */
@JvmInline
value class SignalId private constructor(
    val value: Long,
) {
    override fun toString(): String = "SignalId($value)"

    companion object {
        fun of(value: Long): SignalId {
            require(value >= 1) { "signal id は 1 以上: $value" }
            return SignalId(value)
        }
    }
}

/**
 * 通知の状態。通知は「claim → 送信 → sent を記録」の順で行う（CONTRACTS §9.2、RACE_CALENDAR_DESIGN §6.3）。
 * 再実行時に [CLAIMED] のまま残っているものは再送せず [SEND_UNKNOWN] とする（二重通知より取りこぼしを選ぶ）。
 */
enum class NotificationState { UNSENT, CLAIMED, SENT, SEND_UNKNOWN }

/**
 * 通知候補（`signals`）。自然キーは `(tenantId, raceId, market, umaban, observedAt)`（DDL 案。`cycle_id` との関係は T2.3/T3.3 で確定）。
 *
 * [stake] は「点」の Double（参照元の単位）で金額（[jp.jrabot.domain.value.Money]）ではない。EV・Kelly の詳細な内訳は Stage 3 で追加する。
 * [id] は永続化前は null。
 */
data class Signal(
    override val tenantId: TenantId,
    val id: SignalId?,
    val raceId: RaceId,
    val market: Market,
    val umaban: HorseNumber,
    val observedAt: Instant,
    val odds: Odds,
    val prob: Probability,
    /** 期待値 `prob * odds - 1`。負も有限値としてあり得る。計算は Stage 3。 */
    val ev: Double,
    val isPaper: Boolean,
    val stake: Double,
    /** 確率の出どころ（旧 `prob_source`。"market" / "ml:alpha" など自由な文字列。分類はしない）。 */
    val probSource: String?,
    val state: NotificationState = NotificationState.UNSENT,
) : TenantScoped {
    init {
        require(ev.isFinite()) { "ev は有限値: $ev" }
        require(stake.isFinite() && stake >= 0.0) { "stake は 0 以上の有限値: $stake" }
        require(probSource == null || probSource.length <= 100) { "probSource は 100 文字以下" }
    }
}
