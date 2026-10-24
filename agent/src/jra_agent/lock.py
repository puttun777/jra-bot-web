"""多重起動の防止（データフォルダの排他ロック）。OS のファイルロック（Windows: msvcrt、POSIX: fcntl）なので、プロセスが落ちれば OS が解放する。"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from types import TracebackType
from typing import IO

from .errors import AgentError


class AlreadyRunningError(AgentError):
    pass


class InstanceLock:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._fh: IO[bytes] | None = None

    def acquire(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fh = open(self._path, "a+b")  # noqa: SIM115
        try:
            if sys.platform == "win32":
                import msvcrt

                fh.seek(0)
                msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)  # type: ignore[attr-defined,unused-ignore]
            else:
                import fcntl

                fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as e:
            fh.close()
            raise AlreadyRunningError("別のエージェントが同じデータフォルダで動作中") from e
        fh.seek(0)
        fh.truncate()
        fh.write(str(os.getpid()).encode())
        fh.flush()
        self._fh = fh

    def release(self) -> None:
        fh, self._fh = self._fh, None
        if fh is None:
            return
        try:
            if sys.platform == "win32":
                import msvcrt

                fh.seek(0)
                msvcrt.locking(fh.fileno(), msvcrt.LK_UNLCK, 1)  # type: ignore[attr-defined,unused-ignore]
        except OSError:
            pass
        fh.close()

    def __enter__(self) -> InstanceLock:
        self.acquire()
        return self

    def __exit__(self, et: type[BaseException] | None, ev: BaseException | None, tb: TracebackType | None) -> None:
        self.release()
