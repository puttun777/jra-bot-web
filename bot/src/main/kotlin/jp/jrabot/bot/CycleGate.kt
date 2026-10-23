package jp.jrabot.bot

import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode

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
 * bot-run の「サイクルごとにモードを再確認する」判断の骨組み（T2.8 では**どこからも呼ばれない**。Stage 3 の bot-run ループが使う）。
 * ModeProvider は状態を持たないので、UNKNOWN の連続回数はここで数える（約 15 秒キャッシュ × 3 回 ≒ 45 秒）。
 *
 * - NORMAL → [CycleDecision.StartCycle]（連続回数をリセット）。
 * - STARTING / STOPPING / STOPPED → [CycleDecision.Drain]（exit 0、理由 `STOPPED_BY_MODE`）。
 * - UNKNOWN → 連続 1〜2 回目は [CycleDecision.SkipCycle]、[maxConsecutiveUnknown] 回目で [CycleDecision.Drain]（exit 75、理由 `MODE_UNKNOWN`）。NORMAL とみなさない。
 * - 一度 Drain を返した後は、以降の呼び出しも Drain を返し続ける（NORMAL に戻っても新しいサイクルを始めない）。スレッドセーフではない（ループ 1 本から使う）。
 */
class CycleGate(
    private val modeProvider: ModeProvider,
    val maxConsecutiveUnknown: Int = 3,
) {
    private var consecutiveUnknown = 0
    private var draining: CycleDecision.Drain? = null

    init {
        require(maxConsecutiveUnknown >= 1) { "maxConsecutiveUnknown は 1 以上" }
    }

    fun onCycleStart(): CycleDecision {
        draining?.let { return it }
        return when (modeProvider.current()) {
            OpsMode.NORMAL -> {
                consecutiveUnknown = 0
                CycleDecision.StartCycle
            }

            OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED -> {
                drain(CycleDecision.Drain("STOPPED_BY_MODE", JobExit.OK))
            }

            OpsMode.UNKNOWN -> {
                consecutiveUnknown++
                if (consecutiveUnknown >= maxConsecutiveUnknown) {
                    drain(CycleDecision.Drain("MODE_UNKNOWN", JobExit.TEMPFAIL))
                } else {
                    CycleDecision.SkipCycle(consecutiveUnknown)
                }
            }
        }
    }

    private fun drain(d: CycleDecision.Drain): CycleDecision.Drain {
        draining = d
        return d
    }
}
