package jp.jrabot.domain.decision

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WinStakeTest {
    private fun stake(
        shrink: Double = 0.7,
        kelly: Double = 0.25,
        bankroll: Double = 100.0,
        minBet: Double = 1.0,
        maxBet: Double = 10.0,
        source: String = WinDecisionConfig.MARKET_FALLBACK_ML_DISABLED,
    ) = WinStake(shrink, kelly, bankroll, minBet, maxBet, source)

    @ParameterizedTest
    @CsvSource("0.7, 0.4, 0.27999999999999997", "1.0, 0.4, 0.4", "1.5, 0.4, 0.4", "-0.2, 0.4, 0.0", "0.0, 0.4, 0.0", "1.0, 1.0, 0.99", "1.0, 0.0, 0.0")
    fun `縮小後の確率は 係数を 0 から 1 に収めて掛け、0 から 0_99 に収める`(
        factor: Double,
        raw: Double,
        expected: Double,
    ) {
        assertEquals(expected, stake(shrink = factor).probUsed(ProbRaw(raw)).value)
    }

    @Test
    fun `Kelly 係数は確率源ラベルの完全一致で、判定が渡す実際のラベルでは常に 0_8（旧欠陥 H10）`() {
        assertEquals(0.8, stake(source = "market:fallback(ml_disabled)").kellyMultiplier())
        assertEquals(0.8, stake(source = "ml:alpha").kellyMultiplier())
        assertEquals(0.8, stake(source = "ml:ensemble(calib+alpha,beta=0.35)").kellyMultiplier())
        assertEquals(0.8, stake(source = "market:fallback(no_model)").kellyMultiplier())
        assertEquals(0.8, stake(source = "").kellyMultiplier()) // 空は "unknown"
        // 旧実装の表にある 3 つの文字列だけが別の値（小文字化してから比較する）
        assertEquals(1.0, stake(source = "ml_features").kellyMultiplier())
        assertEquals(0.9, stake(source = "ML_Model").kellyMultiplier())
        assertEquals(0.8, stake(source = "market").kellyMultiplier())
        assertEquals(0.2, stake(kelly = 0.25).kellyFractionAdjusted(0.25)) // 実効 Kelly 分数 0.25 × 0.8
    }

    @Test
    fun `調整後の Kelly 分数は 0 から 1 に収める`() {
        assertEquals(1.0, stake().kellyFractionAdjusted(5.0))
        assertEquals(0.0, stake().kellyFractionAdjusted(-1.0))
    }

    @Test
    fun `賭け額 - 通常は 資金 掛ける Kelly 掛ける 分数 で、min と max の間はそのまま`() {
        val info = stake(shrink = 1.0).calcBetAmount(ProbUsed(0.39388565616548354), 3.0)
        assertEquals(0.09082848424822532, info.kellyRaw)
        assertEquals(0.018165696849645065, info.kellyAdjusted)
        assertEquals(1.8165696849645065, info.kellyBetBeforeClip)
        assertEquals(1.8165696849645065, info.suggestedBet)
        assertEquals(false, info.clipped)
        assertNull(info.skipReason)
    }

    @Test
    fun `賭け額 - 推奨額が min_bet 未満は 0 点の見送りで、理由の文言は旧実装のまま（丸めは表示だけ）`() {
        val info = stake(shrink = 1.0).calcBetAmount(ProbUsed(0.2), 4.0) // kelly_raw = 0.0667 → 1.33 → 実際は下の確認
        // 0.2 × 4.0: EV = -0.2 → Kelly 0 → 推奨額 0.00 < 1.0
        assertEquals(0.0, info.suggestedBet)
        assertEquals("Kelly推奨額(0.00点) < min_bet(1.0点)", info.skipReason)
        assertEquals(false, info.clipped)
    }

    @Test
    fun `賭け額 - 推奨額がちょうど min_bet のときは見送らない（未満だけ見送り）`() {
        val s = stake(kelly = 0.25, bankroll = 100.0, minBet = 1.8165696849645065, shrink = 1.0)
        val info = s.calcBetAmount(ProbUsed(0.39388565616548354), 3.0)
        assertEquals(info.kellyBetBeforeClip, 1.8165696849645065)
        assertEquals(1.8165696849645065, info.suggestedBet)
        assertNull(info.skipReason)
        val below = stake(minBet = Math.nextUp(1.8165696849645065), shrink = 1.0).calcBetAmount(ProbUsed(0.39388565616548354), 3.0)
        assertEquals(0.0, below.suggestedBet)
    }

    @Test
    fun `賭け額 - 推奨額が max_bet ちょうどは打ち切りではなく、超えると打ち切り（clipped）`() {
        val exactly = stake(maxBet = 1.8165696849645065, shrink = 1.0).calcBetAmount(ProbUsed(0.39388565616548354), 3.0)
        assertEquals(false, exactly.clipped)
        val over = stake(maxBet = 1.0, shrink = 1.0).calcBetAmount(ProbUsed(0.39388565616548354), 3.0)
        assertEquals(1.0, over.suggestedBet)
        assertEquals(true, over.clipped)
        assertEquals(1.8165696849645065, over.kellyBetBeforeClip) // 打ち切り前の値を保持する
    }

    @Test
    fun `賭け額 - min_bet が 0 以下なら 推奨額 0 でも見送り理由は付かない（0 未満ではない）`() {
        val info = stake(minBet = 0.0).calcBetAmount(ProbUsed(0.1), 3.0)
        assertEquals(0.0, info.suggestedBet)
        assertNull(info.skipReason)
    }

    @Test
    fun `賭け額 - maxFraction は 1_0 固定で、Kelly が 0_25 を超えても打ち切られない（E12）`() {
        val info = stake(kelly = 1.0).calcBetAmount(ProbUsed(0.9), 3.0)
        assertEquals(0.8500000000000001, info.kellyRaw) // 0.25 で打ち切られていない
    }

    @Test
    fun `賭け額 - odds が 1_0 ちょうどで確率が正なら 0 除算（E5）で、確率 0 なら 0`() {
        assertFailsWith<LegacyZeroDivisionException> { stake().calcBetAmount(ProbUsed(0.5), 1.0) }
        assertEquals(0.0, stake().calcBetAmount(ProbUsed(0.0), 1.0).kellyRaw)
    }

    @Test
    fun `メタ情報の ev_shrink_factor は 0 のとき 1_0 になる旧実装の癖をそのまま再現する`() {
        assertEquals(1.0, stake(shrink = 0.0).evShrinkFactorForMeta()) // 実際の縮小は 0.0 だが表示は 1.0
        assertEquals(0.0, stake(shrink = 0.0).probUsed(ProbRaw(0.4)).value)
        assertEquals(0.7, stake(shrink = 0.7).evShrinkFactorForMeta())
        assertEquals(1.0, stake(shrink = 1.5).evShrinkFactorForMeta())
        assertEquals(0.0, stake(shrink = -0.2).evShrinkFactorForMeta())
    }
}
