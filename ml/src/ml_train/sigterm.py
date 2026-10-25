"""SIGTERM の受信フラグ（Cloud Run が Job を止めるときの SIGTERM。猶予の後に SIGKILL）。

**プロセスの最初（重い import の前）に [install] する**: 起動中に SIGTERM が来ても、既定の動作（即終了。PID 1 では無視）にせず、フラグを立てる。
フラグは job が『確定（レジストリ登録）の前』の各段階で見て、確定前なら、この実行が作った成果物だけを片付けて終了コード 75 で終わる。確定後は完了させる。
"""

import signal
import threading
from types import FrameType

_flag = threading.Event()


def _handler(signum: int, _frame: FrameType | None) -> None:
    _flag.set()


def install() -> None:
    signal.signal(signal.SIGTERM, _handler)


def is_set() -> bool:
    return _flag.is_set()


def reset() -> None:
    """テスト用。"""
    _flag.clear()
