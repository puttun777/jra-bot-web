"""Fake の JV-Link（Linux の CI と開発用）。決めたレコード列を返し、ハング・クラッシュ・エラーを注入できる。実データは使わない（合成のバイト列）。"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any

from .interfaces import OpenResult, ReadResult, ReadStatus


@dataclass
class FakeScript:
    """`files`: ファイルごとのレコード（cp932 のバイト列）。`hang_after`: n レコード読んだ後の read で無限に止まる。`crash_after`: 同じく強制終了。"""

    files: list[list[bytes]] = field(default_factory=list)
    last_file_timestamp: str = "20261031200000"
    open_code: int = 0
    init_code: int = 0
    close_code: int = 0
    read_error_after: int | None = None
    read_error_code: int = -203
    downloading_polls: int = 0
    hang_after: int | None = None
    hang_in_open: bool = False
    crash_after: int | None = None
    realtime_records: list[bytes] = field(default_factory=list)

    @classmethod
    def from_json(cls, text: str) -> FakeScript:
        d: dict[str, Any] = json.loads(text)
        files = [[bytes.fromhex(r) for r in f] for f in d.pop("files", [])]
        rt = [bytes.fromhex(r) for r in d.pop("realtime_records", [])]
        return cls(files=files, realtime_records=rt, **d)

    def to_json(self) -> str:
        d = {k: v for k, v in self.__dict__.items() if k not in ("files", "realtime_records")}
        d["files"] = [[r.hex() for r in f] for f in self.files]
        d["realtime_records"] = [r.hex() for r in self.realtime_records]
        return json.dumps(d)


class FakeJvLink:
    def __init__(self, script: FakeScript) -> None:
        self.s = script
        self.is_open = False
        self.inited = False
        self.opens = 0
        self.max_concurrent_open = 0
        self._queue: list[tuple[str, bytes | None]] = []
        self._read = 0
        self._dl_left = 0

    def init(self, sid: str) -> int:
        self.inited = self.s.init_code >= 0
        return self.s.init_code

    def _begin(self) -> None:
        if self.is_open:
            raise RuntimeError("OPEN_WHILE_OPEN")  # 同時に複数の open を実行しない
        self.is_open = True
        self.opens += 1
        self.max_concurrent_open = max(self.max_concurrent_open, 1)
        self._read = 0
        self._dl_left = self.s.downloading_polls

    def open_stored(self, dataspec: str, fromtime: str, option: int) -> OpenResult:
        self._begin()
        if self.s.hang_in_open:
            time.sleep(10**6)
        if self.s.open_code < 0:
            self.is_open = False
            return OpenResult(self.s.open_code)
        self._queue = []
        for i, f in enumerate(self.s.files):
            self._queue.extend(("R", r) for r in f)
            if i < len(self.s.files) - 1:
                self._queue.append(("SWITCH", None))
        return OpenResult(self.s.open_code, sum(len(f) for f in self.s.files), len(self.s.files), self.s.last_file_timestamp)

    def open_realtime(self, dataspec: str, key: str) -> OpenResult:
        self._begin()
        if self.s.open_code < 0:
            self.is_open = False
            return OpenResult(self.s.open_code)
        self._queue = [("R", r) for r in self.s.realtime_records]
        return OpenResult(self.s.open_code, len(self._queue), 0, None)

    def read(self) -> ReadResult:
        if not self.is_open:
            return ReadResult(ReadStatus.ERROR, code=-203)
        if self._dl_left > 0:
            self._dl_left -= 1
            return ReadResult(ReadStatus.DOWNLOADING, code=-3)
        if self.s.crash_after is not None and self._read >= self.s.crash_after:
            os._exit(139)  # COM が落ちた場合の模擬（プロセスごと消える）
        if self.s.hang_after is not None and self._read >= self.s.hang_after:
            time.sleep(10**6)  # COM が戻らない場合の模擬
        if self.s.read_error_after is not None and self._read >= self.s.read_error_after:
            return ReadResult(ReadStatus.ERROR, code=self.s.read_error_code)
        if not self._queue:
            return ReadResult(ReadStatus.EOF, code=0)
        kind, data = self._queue.pop(0)
        if kind == "SWITCH":
            return ReadResult(ReadStatus.FILE_SWITCH, code=-1)
        self._read += 1
        return ReadResult(ReadStatus.RECORD, data, len(data or b""), "FAKE.jvd")

    def close(self) -> int:
        self.is_open = False
        self._queue = []
        return self.s.close_code
