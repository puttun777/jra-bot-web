"""COM ワーカーの監視（ウォッチドッグ）。

- 呼出しごとにタイムアウトを設ける。**応答が無ければワーカーを強制終了**し（プロセスツリーごと）、呼出し元には [`WorkerTimeout`] を返す。
- ワーカーが落ちた（EOF・終了コード）場合は [`WorkerCrashed`]。どちらでも取得は**完了扱いにしない**（呼出し元が取得セッションを中止する。`fetched_ts` は動かない）。
- 再起動は呼出しの前に遅延して行う。`restart_window` の間に `max_restarts` 回を超えたら、`restart_cooldown` の間は起動しない（[`WorkerQuarantined`]。無限再起動ループの防止）。
- 同時に呼び出せるのは 1 つ（ロック）。open 中の open はワーカー側でも拒否される。
"""

from __future__ import annotations

import base64
import contextlib
import json
import queue
import subprocess
import threading
from collections import deque
from collections.abc import Callable, Sequence
from typing import Any

from ..clock import Clock
from ..config import WorkerConfig
from . import procutil


class WorkerError(Exception):
    def __init__(self, kind: str) -> None:
        super().__init__(kind)
        self.kind = kind


class WorkerTimeout(WorkerError):
    pass


class WorkerCrashed(WorkerError):
    pass


class WorkerQuarantined(WorkerError):
    pass


class WorkerRemoteError(WorkerError):
    """ワーカーが処理に失敗した（例外の型名だけを持つ）。"""


class _Handle:
    def __init__(self, proc: subprocess.Popen[bytes]) -> None:
        self.proc = proc
        self.lines: queue.Queue[bytes | None] = queue.Queue()
        self._t = threading.Thread(target=self._pump, daemon=True)
        self._t.start()

    def _pump(self) -> None:
        stdout = self.proc.stdout
        assert stdout is not None
        try:
            for line in iter(stdout.readline, b""):
                self.lines.put(line)
        except (OSError, ValueError):
            pass
        self.lines.put(None)  # EOF = ワーカーが終了した

    def send(self, msg: dict[str, Any]) -> None:
        stdin = self.proc.stdin
        assert stdin is not None
        stdin.write((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
        stdin.flush()


class WorkerSupervisor:
    def __init__(
        self, argv: Sequence[str], cfg: WorkerConfig, clock: Clock, spawn: Callable[[Sequence[str]], subprocess.Popen[bytes]] | None = None
    ) -> None:
        self._argv = list(argv)
        self._cfg = cfg
        self._clock = clock
        self._spawn = spawn or procutil.spawn
        self._lock = threading.Lock()
        self._h: _Handle | None = None
        self._next_id = 0
        self._starts: deque[float] = deque()
        self._quarantine_until = 0.0
        self.restarts = 0
        self.timeouts = 0
        self.crashes = 0

    # ------------------------------------------------------------ 起動・終了
    def _ensure(self) -> _Handle:
        if self._h is not None and self._h.proc.poll() is None:
            return self._h
        if self._h is not None:
            self._discard()
        now = self._clock.monotonic()
        if now < self._quarantine_until:
            raise WorkerQuarantined("RESTART_COOLDOWN")
        while self._starts and now - self._starts[0] > self._cfg.restart_window_seconds:
            self._starts.popleft()
        if len(self._starts) >= self._cfg.max_restarts:
            self._quarantine_until = now + self._cfg.restart_cooldown_seconds
            raise WorkerQuarantined("RESTART_LIMIT")  # 無限再起動ループを防ぐ
        self._starts.append(now)
        if len(self._starts) > 1:
            self.restarts += 1
        self._h = _Handle(self._spawn(self._argv))
        try:
            self._roundtrip(self._h, "ping", {}, self._cfg.startup_timeout_seconds)
        except WorkerError:
            self._discard()
            raise
        return self._h

    def _discard(self) -> None:
        h, self._h = self._h, None
        if h is not None:
            procutil.terminate_tree(h.proc)

    def stop(self) -> None:
        with self._lock:
            h = self._h
            if h is not None and h.proc.poll() is None:
                with contextlib.suppress(WorkerError, OSError):
                    self._roundtrip(h, "shutdown", {}, 3.0)
            self._discard()

    # ------------------------------------------------------------ 呼出し
    def call(self, op: str, args: dict[str, Any] | None = None, timeout: float | None = None) -> dict[str, Any]:
        with self._lock:
            h = self._ensure()
            try:
                return self._roundtrip(h, op, args or {}, timeout or self._cfg.call_timeout_seconds)
            except WorkerTimeout:
                self.timeouts += 1
                self._discard()  # 無応答 = 強制終了（COM が戻らない）
                raise
            except WorkerCrashed:
                self.crashes += 1
                self._discard()
                raise

    def _roundtrip(self, h: _Handle, op: str, args: dict[str, Any], timeout: float) -> dict[str, Any]:
        self._next_id += 1
        rid = self._next_id
        try:
            h.send({"id": rid, "op": op, "args": args})
        except (OSError, ValueError) as e:
            raise WorkerCrashed("PIPE_BROKEN") from e
        try:
            # ウォッチドッグの待ち時間は実時間（FakeClock では進まない）。テストでは短いタイムアウトを設定する
            line = h.lines.get(timeout=timeout)
        except queue.Empty as e:
            raise WorkerTimeout(op) from e
        if line is None:
            raise WorkerCrashed(f"EXIT_{h.proc.poll()}")
        try:
            msg = json.loads(line)
        except ValueError as e:
            raise WorkerCrashed("BAD_PROTOCOL") from e
        if not isinstance(msg, dict) or msg.get("id") != rid:  # JSON でも辞書でない行（配列・数値）は不正なプロトコル
            raise WorkerCrashed("BAD_PROTOCOL")
        if not msg.get("ok"):
            raise WorkerRemoteError(str(msg.get("error", "ERROR")))
        result = msg.get("result")
        return result if isinstance(result, dict) else {}


def decode_records(result: dict[str, Any]) -> list[bytes]:
    return [base64.b64decode(r, validate=True) for r in result.get("records", [])]
