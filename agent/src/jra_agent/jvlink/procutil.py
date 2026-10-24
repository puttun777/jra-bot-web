"""子プロセス（COM ワーカー）の起動と、確実な終了。POSIX はプロセスグループ、Windows は新しいプロセスグループ + taskkill /T + ジョブオブジェクト。"""

from __future__ import annotations

import contextlib
import os
import signal
import subprocess
import sys
from collections.abc import Sequence
from typing import IO

from ..winapi import IS_WINDOWS, KillOnCloseJob

_job: KillOnCloseJob | None = None


def spawn(argv: Sequence[str], env: dict[str, str] | None = None) -> subprocess.Popen[bytes]:
    """stdin/stdout をパイプにして起動する（stdout は JSON 行のプロトコル専用）。stderr は捨てる（ログに生データを残さない）。"""
    global _job  # noqa: PLW0603
    kwargs: dict[str, object] = {}
    if IS_WINDOWS:
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW  # type: ignore[attr-defined,unused-ignore]
    else:
        kwargs["start_new_session"] = True
    proc: subprocess.Popen[bytes] = subprocess.Popen(  # noqa: S603 - 引数は固定の配列（シェルを使わない）
        list(argv),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=env,
        close_fds=True,
        **kwargs,  # type: ignore[call-overload]
    )
    if IS_WINDOWS:
        if _job is None:
            _job = KillOnCloseJob()
        _job.assign(proc.pid)  # 親が落ちても OS がワーカーを終了する
    return proc


def terminate_tree(proc: subprocess.Popen[bytes], grace: float = 3.0) -> None:
    """ワーカーとその子を終了する。強制終了後、パイプを閉じて資源を解放し、必ず wait する（ゾンビ・ハンドルを残さない）。"""
    if proc.poll() is None:
        if IS_WINDOWS:
            with contextlib.suppress(OSError, subprocess.SubprocessError):
                subprocess.run(  # noqa: S603
                    ["taskkill", "/F", "/T", "/PID", str(proc.pid)],  # noqa: S607
                    capture_output=True, timeout=grace + 5, check=False,
                )  # fmt: skip
            if proc.poll() is None:
                proc.kill()
        else:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=grace)
            except subprocess.TimeoutExpired:
                with contextlib.suppress(ProcessLookupError, PermissionError):
                    os.killpg(proc.pid, signal.SIGKILL)
    for stream in (proc.stdin, proc.stdout):
        if stream is not None:
            with contextlib.suppress(OSError, ValueError):
                _close(stream)
    with contextlib.suppress(subprocess.TimeoutExpired):
        proc.wait(timeout=10)


def _close(stream: IO[bytes]) -> None:
    stream.close()


def python_executable() -> str:
    return sys.executable
