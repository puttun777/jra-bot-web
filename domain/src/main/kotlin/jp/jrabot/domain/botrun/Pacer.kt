package jp.jrabot.domain.botrun

import java.time.Duration

/**
 * 待機。実装は実時間の `sleep`（:bot）、テストは偽の時計を進めるだけ（実時間の長い sleep をテストで必要としない）。
 * [stopRequested] が true になったら**早く戻る**（SIGTERM で新しいサイクルを始めないため）。短い間隔で確認する実装にする。
 */
fun interface Pacer {
    fun pause(
        duration: Duration,
        stopRequested: () -> Boolean,
    )
}
