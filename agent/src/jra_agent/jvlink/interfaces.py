"""JV-Link の抽象インターフェース。COM の実装（`com_adapter.ComJvLink`）と Fake（`fake.FakeJvLink`）が同じ型を実装する。

ここで定義する「状態」は、エージェント内部の中立な語彙。数値の戻り値との対応（`codes.py`）は**公式仕様で未確認**なので、実装（ComJvLink）側に閉じる。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol


class ReadStatus(Enum):
    RECORD = "RECORD"  # 1 レコード読めた
    FILE_SWITCH = "FILE_SWITCH"  # ファイルの切替（安全な区切り）
    DOWNLOADING = "DOWNLOADING"  # サーバからダウンロード中（まだ読めない）
    EOF = "EOF"  # 全部読み終えた
    ERROR = "ERROR"  # エラー（`code` に数値）


@dataclass(frozen=True)
class OpenResult:
    code: int  # 0 以上が成功（仕様は未確認: ComJvLink が解釈する）
    read_count: int = 0
    download_count: int = 0
    last_file_timestamp: str | None = None  # yyyyMMddHHmmss（次回の fromtime の元）

    @property
    def ok(self) -> bool:
        return self.code >= 0


@dataclass(frozen=True)
class ReadResult:
    status: ReadStatus
    data: bytes | None = None  # cp932 のバイト列（RECORD のときだけ）
    code: int = 0
    filename: str | None = None


class JvLinkApi(Protocol):
    """1 つの COM インスタンスの操作。**同時に開けるのは 1 つだけ**（open 中の open は拒否する）。"""

    def init(self, sid: str) -> int: ...

    def open_stored(self, dataspec: str, fromtime: str, option: int) -> OpenResult: ...

    def open_realtime(self, dataspec: str, key: str) -> OpenResult: ...

    def read(self) -> ReadResult: ...

    def close(self) -> int: ...
