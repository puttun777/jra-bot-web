package jp.jrabot.mltrain.job

/**
 * Job の終了コードと失敗の分類（CONTRACTS §9.1。bot と同じ意味。旧 ml/src/ml_train/errors.py の移植）。
 * 0 = 正常（REJECTED の登録を含む。理由は JOB_RESULT の reason）／1 = 失敗／2 = 引数・設定の誤り／75 = 一時的な失敗（保存先・DB 不達、モード UNKNOWN、SIGTERM）。
 * 理由コードは固定語彙で、値・資格情報・モデルの内容を含めない。
 */
object ExitCode {
    const val OK = 0
    const val FAILURE = 1
    const val USAGE = 2
    const val TEMPFAIL = 75
}

open class MlTrainError(
    val reason: String,
    message: String = reason,
    val exitCode: Int = ExitCode.FAILURE,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class UsageError(
    reason: String,
    message: String = reason,
) : MlTrainError(reason, message, ExitCode.USAGE)

open class TransientError(
    reason: String,
    message: String = reason,
    cause: Throwable? = null,
) : MlTrainError(reason, message, ExitCode.TEMPFAIL, cause)

/** データセットの契約違反（学習を始めない）。 */
class DatasetInvalid(
    message: String,
) : MlTrainError("DATASET_INVALID", message)

/** 学習・評価に構造的に足りない（片方のクラスしかない、valid の日数不足など）。品質ゲートの閾値とは別。 */
class DatasetInsufficient(
    message: String,
) : MlTrainError("DATASET_INSUFFICIENT", message)

/** SIGTERM を受けて、確定前（レジストリへの登録前）に中断した。 */
class Terminated : TransientError("TERMINATED", "SIGTERM を受けたため確定前に中断")
