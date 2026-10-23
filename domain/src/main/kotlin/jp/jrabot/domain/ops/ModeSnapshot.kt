package jp.jrabot.domain.ops

/**
 * `GET /internal/ops/mode` に出す診断用の写し（CONTRACTS §3.2）。そのインスタンスの [ModeProvider] が**現在採用している**値だけを持つ。
 *
 * - [mode] が [OpsMode.UNKNOWN] のとき、[desiredMode]・[seq]・[cacheAgeMs] は null（直前の成功値を診断として残さない）、[source] は `"unknown"`。
 * - [source] は正本の読取り元の種別（GCS = `"gcs"`、ローカルファイル = `"file"`、確認できない = `"unknown"`）。正本の JSON 全文・パス・認証情報は持たない。
 * - [cacheAgeMs] は採用中の値を取得してからの経過ミリ秒。
 */
data class ModeSnapshot(
    val mode: OpsMode,
    val desiredMode: OpsMode?,
    val seq: Long?,
    val cacheAgeMs: Long?,
    val source: String,
) {
    init {
        if (mode == OpsMode.UNKNOWN) {
            require(desiredMode == null && seq == null && cacheAgeMs == null && source == SOURCE_UNKNOWN) { "UNKNOWN の写しは詳細を持たない" }
        } else {
            require(desiredMode != null && seq != null && cacheAgeMs != null && source != SOURCE_UNKNOWN) { "確定したモードには詳細が必要" }
        }
    }

    companion object {
        const val SOURCE_UNKNOWN = "unknown"

        fun unknown(): ModeSnapshot = ModeSnapshot(OpsMode.UNKNOWN, null, null, null, SOURCE_UNKNOWN)
    }
}

/**
 * [ModeProvider] に診断の口を足したもの（`/internal/ops/mode` 用）。[snapshot] は [current] と同じキャッシュ・検証を通り、
 * 呼ぶたびに正本を取り直すことはしない。例外を投げない（確認できなければ [ModeSnapshot.unknown]）。
 */
interface InspectableModeProvider : ModeProvider {
    fun snapshot(): ModeSnapshot
}
