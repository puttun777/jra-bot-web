"""Job の終了コードと失敗の分類（docs/CONTRACTS.md §9.1。bot と同じ意味）。

0 = 正常（REJECTED の登録を含む。理由は JOB_RESULT の reason）／1 = 失敗／2 = 引数・設定の誤り／75 = 一時的な失敗（保存先・DB 不達、運用モード UNKNOWN、SIGTERM による中断）。
理由コードは固定語彙で、値・資格情報・モデルの内容を含めない。
"""


class ExitCode:
    OK = 0
    FAILURE = 1
    USAGE = 2
    TEMPFAIL = 75


class MlTrainError(Exception):
    """Job を失敗で終える例外。[reason] は固定語彙、[exit_code] は ExitCode。"""

    exit_code: int = ExitCode.FAILURE

    def __init__(self, reason: str, message: str = "") -> None:
        super().__init__(message or reason)
        self.reason = reason


class UsageError(MlTrainError):
    exit_code = ExitCode.USAGE


class TransientError(MlTrainError):
    exit_code = ExitCode.TEMPFAIL


class DatasetInvalidError(MlTrainError):
    """データセットの契約違反（学習を始めない）。"""

    def __init__(self, message: str) -> None:
        super().__init__("DATASET_INVALID", message)


class DatasetInsufficientError(MlTrainError):
    """学習・評価に構造的に足りない（片方のクラスしかない、valid の日数不足など）。ゲートの閾値とは別（閾値は gate.py）。"""

    def __init__(self, message: str) -> None:
        super().__init__("DATASET_INSUFFICIENT", message)


class Terminated(TransientError):
    """SIGTERM を受けて、確定前（レジストリへの登録前）に中断した。"""

    def __init__(self) -> None:
        super().__init__("TERMINATED", "SIGTERM を受けたため確定前に中断")
