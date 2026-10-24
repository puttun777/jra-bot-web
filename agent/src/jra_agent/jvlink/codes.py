"""JV-Link の数値の戻り値 → 中立な状態の写像。

**公式仕様で未確認**（`spec.py` の JVREAD_RETURN）。ここにある対応は、参照元実装（jvlink_provider.py）が使っている解釈で、二次情報。
0 超 = 読めたバイト数、-1 = ファイル切替、-3 = ダウンロード中、0 = EOF、その他の負数 = エラー。確認が取れるまで、
本番の取得はこの写像を使う経路（`ComJvLink.read`）を実行しない（ゲート）。
"""

from __future__ import annotations

from .interfaces import ReadResult, ReadStatus

UNCONFIRMED_NOTICE = "戻り値の解釈は参照元実装に基づく二次情報（公式仕様未確認）"


def interpret_read(code: int, data: bytes | None, filename: str | None) -> ReadResult:
    if code > 0:
        if data is None:
            return ReadResult(ReadStatus.ERROR, code=-9001, filename=filename)  # バイト列を取り出せなかった
        return ReadResult(ReadStatus.RECORD, data, code, filename)
    if code == 0:
        return ReadResult(ReadStatus.EOF, code=0, filename=filename)
    if code == -1:
        return ReadResult(ReadStatus.FILE_SWITCH, code=-1, filename=filename)
    if code == -3:
        return ReadResult(ReadStatus.DOWNLOADING, code=-3, filename=filename)
    return ReadResult(ReadStatus.ERROR, code=code, filename=filename)
