package jp.jrabot.domain.decision

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 判定の不変条件の性質試験（監査 優先度3。乱数は固定シード・合成データ）。旧 Python と数値を比べるものではなく（それは golden）、
 * どんな入力でも例外・非有限値・負の金額・上限超過・並び順の揺れが出ないことを確かめる。実装を変えずに境界を探す。
 */
class DecisionInvariantsFuzzTest {
    private val noCombo = ComboOdds(emptyMap(), emptyMap())

    private fun horses(r: Random): List<DecisionHorse> {
        val n = 1 + r.nextInt(18)
        val pool = doubleArrayOf(1.0, 1.01, 1.1, 1.5, 2.0, 5.5, 12.3, 49.9, 99.9, 150.0, 999.9)
        return (1..n).map {
            DecisionHorse(
                it,
                if (r.nextInt(3) ==
                    0
                ) {
                    pool[r.nextInt(pool.size)]
                } else {
                    1.0 + r.nextDouble() * 80.0
                },
                if (r.nextBoolean()) it else 0,
            )
        }
    }

    private fun check(
        cfg: WinDecisionConfig,
        h: List<DecisionHorse>,
    ) {
        val r1 = WinDecisionEvaluator(cfg).evaluate(h, noCombo)
        // 同率の並びは入力順の安定ソート（旧 Python 互換）。呼び出し側（RaceEvaluator）が馬番順に固定するので、入力順への依存はここでは検査しない
        for (s in r1.signals) {
            assertTrue(s.ev.value.isFinite(), "EV は有限: $s")
            when (s) {
                is BetSignal -> {
                    assertTrue(s.betAmount.isFinite() && s.betAmount >= 0.0, "金額は有限で 0 以上: ${s.betAmount}")
                    assertTrue(s.betAmount <= cfg.maxBet + 1e-9, "1 点の上限を超えない: ${s.betAmount} > ${cfg.maxBet}")
                    assertTrue(s.prob.value in 0.0..1.0, "確率は 0〜1: ${s.prob.value}")
                    assertTrue(s.odds >= 1.0 || s.odds > 0.0)
                    s.betStrategy?.let { b ->
                        assertTrue(b.totalSuggestedBet.isFinite() && b.totalSuggestedBet >= 0.0, "合計推奨額は有限で 0 以上")
                        assertTrue(b.totalExpectedProfit.isFinite())
                        b.strategies.forEach { e ->
                            assertTrue(e.suggestedBet.isFinite() && e.suggestedBet >= 0.0, "買い目の推奨額は 0 以上: ${e.suggestedBet}")
                            e.tickets.forEach { t ->
                                assertTrue((t.stake ?: 0.0).let { it.isFinite() && it >= 0.0 } && t.ev.value.isFinite() && t.probUsed.value in 0.0..1.0)
                            }
                        }
                    }
                }

                is SkipSignal -> {
                    assertTrue(s.prob.value in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun `ランダムな単勝オッズ 1 頭から 18 頭 で例外も非有限値も負の金額も上限超過もない`() {
        val r = Random(20261029)
        val base = WinDecisionConfig.legacyFunctionDefaults()
        repeat(3000) {
            val h = horses(r)
            check(base, h)
            check(base.copy(notifySkips = true), h)
            check(base.copy(priorityMode = PriorityMode.HIT_RATE, hitRateMinEv = 0.0), h)
        }
    }

    @Test
    fun `極端な入力 全馬が同じオッズ 全馬が 1_0 倍 1 頭だけ 巨大オッズ でも安全`() {
        val base = WinDecisionConfig.legacyFunctionDefaults().copy(notifySkips = true)
        check(base, (1..16).map { DecisionHorse(it, 1.0, it) })
        check(base, (1..16).map { DecisionHorse(it, 16.0, it) })
        check(base, listOf(DecisionHorse(1, 1.0, 1)))
        check(base, listOf(DecisionHorse(1, 1.0, 1), DecisionHorse(2, 9999.0, 2)))
        check(base, (1..18).map { DecisionHorse(it, 999.9, 0) })
    }

    @Test
    fun `レース合計の上限を設定すると 買い目の金額の合計は上限を超えない 比例縮小で min_bet を下回ることは旧互換 E6`() {
        val r = Random(7)
        val cfg = WinDecisionConfig.legacyFunctionDefaults().copy(maxTotalBetPerRace = 12.0)
        repeat(2000) {
            val res = WinDecisionEvaluator(cfg).evaluate(horses(r), noCombo)
            val total = res.signals.filterIsInstance<BetSignal>().sumOf { it.betAmount }
            assertTrue(total <= 12.0 + 1e-6, "合計 $total が上限 12.0 を超える")
        }
    }
}
