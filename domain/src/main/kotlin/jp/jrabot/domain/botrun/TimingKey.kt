package jp.jrabot.domain.botrun

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 評価のタイミング（発走の何分前か）を、DB の一意性のキー `timing_centi`（分 × 100 の整数）に換算する規則（V5。`race_timing_notice` の PK の一部）。
 *
 * - **0.01 分刻み**だけを受け付ける（`30`・`5`・`5.5`・`12.34`）。`5.005` のような 0.01 分の倍数でない値は**丸めずに拒否**する
 *   （黙って丸めると、異なる設定値が同じキーに衝突して、片方のタイミングが通知されなくなる）。
 * - 範囲は 0.01 分以上 1440 分以下（`timing_centi` は 1〜144000。DB の CHECK と同じ）。
 * - 換算は決定的: `round(minutes × 100)`。逆変換は `centi / 100.0` で、10 進の小数として書かれた設定値（`12.34`）と同じ `Double` に戻る
 *   （どちらも 10 進の真の値に最も近い `Double`）。
 * - 設定の中で異なる値が同じキーになることはない（[SnapshotPolicy] が検証する）。
 */
object TimingKey {
    const val MIN_CENTI = 1
    const val MAX_CENTI = 144_000
    private const val TOLERANCE = 1e-6

    /** 換算できなければ null（非有限・範囲外・0.01 分の倍数でない）。 */
    fun centiOrNull(minutes: Double): Int? {
        if (!minutes.isFinite()) return null
        val scaled = minutes * 100.0
        val rounded = scaled.roundToLong()
        if (abs(scaled - rounded) > TOLERANCE) return null
        if (rounded < MIN_CENTI || rounded > MAX_CENTI) return null
        return rounded.toInt()
    }

    fun centi(minutes: Double): Int = requireNotNull(centiOrNull(minutes)) { "評価のタイミングは 0.01〜1440 分の 0.01 分刻み: $minutes" }

    fun minutes(centi: Int): Double {
        require(centi in MIN_CENTI..MAX_CENTI) { "timing_centi は $MIN_CENTI〜$MAX_CENTI: $centi" }
        return centi / 100.0
    }
}
