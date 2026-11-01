package jp.jrabot.domain.botrun

import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode

/** bot-run の各サイクルの開始時の判断（CONTRACTS §5.3）。 */
sealed interface ModeDecision {
    /** NORMAL: サイクルを始めてよい。 */
    data object Proceed : ModeDecision

    /** UNKNOWN（まだ連続 [ModeGate.maxConsecutiveUnknown] 回未満）: 新しいサイクルは始めないが、まだ DRAINING にはしない。 */
    data class Skip(
        val consecutiveUnknown: Int,
    ) : ModeDecision

    /** DRAINING へ移る。[exitCode] は確定した非 NORMAL なら 0（`STOPPED_BY_MODE`）、UNKNOWN の連続なら 75（`MODE_UNKNOWN`）。 */
    data class Drain(
        val reason: String,
        val exitCode: Int,
    ) : ModeDecision
}

/**
 * 『サイクルごとにモードを再確認する』判断（CONTRACTS §5.3）。[ModeProvider] は状態を持たないので UNKNOWN の連続回数はここで数える
 * （約 15 秒キャッシュ × 3 回 ≒ 45 秒）。
 *
 * - NORMAL → [ModeDecision.Proceed]（連続回数をリセット）。
 * - STARTING / STOPPING / STOPPED → [ModeDecision.Drain]（exit 0、理由 `STOPPED_BY_MODE`）。
 * - UNKNOWN → 連続 1〜2 回目は [ModeDecision.Skip]、[maxConsecutiveUnknown] 回目で [ModeDecision.Drain]（exit 75、理由 `MODE_UNKNOWN`）。NORMAL とみなさない。
 * - 一度 Drain を返した後は、NORMAL に戻っても Drain を返し続ける。スレッドセーフではない（ループ 1 本から使う）。
 */
class ModeGate(
    private val modes: ModeProvider,
    val maxConsecutiveUnknown: Int = 3,
) {
    private var consecutiveUnknown = 0
    private var draining: ModeDecision.Drain? = null

    init {
        require(maxConsecutiveUnknown >= 1) { "maxConsecutiveUnknown は 1 以上" }
    }

    fun onCycleStart(): ModeDecision {
        draining?.let { return it }
        return when (modes.current()) {
            OpsMode.NORMAL -> {
                consecutiveUnknown = 0
                ModeDecision.Proceed
            }

            OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED -> {
                drain(ModeDecision.Drain(REASON_STOPPED_BY_MODE, RunExit.OK))
            }

            OpsMode.UNKNOWN -> {
                consecutiveUnknown++
                if (consecutiveUnknown >= maxConsecutiveUnknown) {
                    drain(ModeDecision.Drain(REASON_MODE_UNKNOWN, RunExit.TEMPFAIL))
                } else {
                    ModeDecision.Skip(consecutiveUnknown)
                }
            }
        }
    }

    private fun drain(d: ModeDecision.Drain): ModeDecision.Drain {
        draining = d
        return d
    }

    companion object {
        const val REASON_STOPPED_BY_MODE = "STOPPED_BY_MODE"
        const val REASON_MODE_UNKNOWN = "MODE_UNKNOWN"
    }
}

/** 終了コード（CONTRACTS §9.1）。`:bot` の `JobExit` と同じ値。 */
object RunExit {
    const val OK = 0
    const val FAILURE = 1
    const val TEMPFAIL = 75
}
