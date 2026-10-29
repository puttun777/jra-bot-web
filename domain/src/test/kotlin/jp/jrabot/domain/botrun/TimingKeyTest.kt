package jp.jrabot.domain.botrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 評価タイミング → `timing_centi` の換算規則（V5 の PK の一部）。 */
class TimingKeyTest {
    @Test
    fun `0_01 分刻みの値は 100 倍の整数になり 逆変換で同じ Double に戻る`() {
        assertEquals(3000, TimingKey.centi(30.0))
        assertEquals(500, TimingKey.centi(5.0))
        assertEquals(550, TimingKey.centi(5.5))
        assertEquals(1234, TimingKey.centi(12.34))
        assertEquals(1, TimingKey.centi(0.01))
        assertEquals(144_000, TimingKey.centi(1440.0))
        for (m in listOf(30.0, 5.0, 5.5, 12.34, 0.07, 0.29, 1.15, 1440.0, 0.01)) {
            assertEquals(m, TimingKey.minutes(TimingKey.centi(m)), "往復: $m")
        }
        // 浮動小数の誤差（0.1 + 0.2 など）があっても、10 進で 0.01 分刻みの値は同じキーになる
        assertEquals(30, TimingKey.centi(0.1 + 0.2))
    }

    @Test
    fun `0_01 分の倍数でない値 範囲外 非有限は 丸めずに拒否する 異なる設定値が衝突しない`() {
        for (m in listOf(5.005, 5.001, 0.004, 0.0, -1.0, 1440.01, 1e9, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(TimingKey.centiOrNull(m), "拒否: $m")
            assertFailsWith<IllegalArgumentException> { TimingKey.centi(m) }
        }
        assertFailsWith<IllegalArgumentException> { TimingKey.minutes(0) }
        assertFailsWith<IllegalArgumentException> { TimingKey.minutes(144_001) }
    }

    @Test
    fun `評価の設定は換算できない値や衝突する値を受け付けない`() {
        SnapshotPolicy(listOf(30.0, 5.0), TightenMode.OFF)
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(5.005), TightenMode.OFF) }
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(5.0, 5.0), TightenMode.OFF) }
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(1441.0), TightenMode.OFF) }
        // 0.01 分未満だけ違う 2 つの値（5.0 と 5.001）は、丸めると同じキーになるので設定の段階で拒否される
        assertFailsWith<IllegalArgumentException> { SnapshotPolicy(listOf(5.0, 5.001), TightenMode.OFF) }
    }
}
