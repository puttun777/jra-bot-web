package jp.jrabot.bot

import jp.jrabot.domain.botrun.ModeDecision
import jp.jrabot.domain.botrun.ModeGate
import jp.jrabot.domain.ops.ModeProvider

/** bot-run の各サイクルの開始時の判断（CONTRACTS §5.3）。 */
sealed interface CycleDecision {
    /** NORMAL: サイクルを始めてよい。 */
    data object StartCycle : CycleDecision

    /** UNKNOWN（まだ連続 [CycleGate.maxConsecutiveUnknown] 回未満）: 新しいサイクルは始めないが、まだ DRAINING にはしない。 */
    data class SkipCycle(
        val consecutiveUnknown: Int,
    ) : CycleDecision

    /**
     * DRAINING へ移る: 新しいサイクルを始めない → checkpoint 保存 → リース解放 → 終了（**これらの実行は Stage 3 の bot-run**。
     * このクラスは判断だけで、checkpoint もリース解放も行わない）。[exitCode] は確定した非 NORMAL なら 0（`STOPPED_BY_MODE`）、UNKNOWN の連続なら 75。
     */
    data class Drain(
        val reason: String,
        val exitCode: Int,
    ) : CycleDecision
}

/**
 * bot-run の「サイクルごとにモードを再確認する」判断（T2.8 の骨組み）。**T3.8 で判断の本体を `:domain` の [ModeGate] に移した**
 * （bot-run のループは `:domain` にあるため）。ここは `:bot` 側の型（[CycleDecision]・[JobExit]）への薄い変換で、規則は [ModeGate] の KDoc のとおり
 * （NORMAL → 開始、確定した非 NORMAL → Drain exit 0 `STOPPED_BY_MODE`、UNKNOWN は連続 [maxConsecutiveUnknown] 回で Drain exit 75 `MODE_UNKNOWN`）。
 */
class CycleGate(
    modeProvider: ModeProvider,
    val maxConsecutiveUnknown: Int = 3,
) {
    private val gate = ModeGate(modeProvider, maxConsecutiveUnknown)

    fun onCycleStart(): CycleDecision =
        when (val d = gate.onCycleStart()) {
            ModeDecision.Proceed -> CycleDecision.StartCycle
            is ModeDecision.Skip -> CycleDecision.SkipCycle(d.consecutiveUnknown)
            is ModeDecision.Drain -> CycleDecision.Drain(d.reason, d.exitCode)
        }
}
