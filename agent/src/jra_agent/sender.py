"""キューのバッチを 1 つずつ送る。応答ごとの動作は CONTRACTS §3.6 / §7（responses.py の表）。

- **再試行で新しい batchId を作らない**: キューに保存した本文を、そのまま（同じ batchId・同じ内容）送る。
- 無限に再試行しない: 同じバッチの試行回数に上限があり、超えたら DEAD（取得セッションも中止）。指数バックオフ + ジッタ。
- 401/403 は自動で再試行しない（送信停止 + ローカルアラート。トークンが差し替えられたら再開）。
- 503 + X-Ops-Mode が NORMAL 以外 / モードが不明: 試行に数えず待機（状態機械が許可するまで claim しない）。
- 同時に送るのは 1 バッチだけ。送信間隔は設定の `sends_per_second`（復帰時は最大 2 バッチ/秒）。
"""

from __future__ import annotations

import json
import logging
import random
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum

from .backoff import Backoff
from .client import SignedClient
from .clock import Clock
from .config import SenderConfig
from .modes import ModeMachine
from .queue import AckInfo, BatchRow, OutboxQueue
from .responses import Outcome

log = logging.getLogger(__name__)


class Step(Enum):
    ACKED = "ACKED"
    DEAD = "DEAD"
    RETRY = "RETRY"  # 同じバッチを後で再送する
    DEFERRED = "DEFERRED"  # 待機（モード・認証）。試行に数えない
    IDLE = "IDLE"  # 送るものが無い
    HALTED = "HALTED"  # モード/認証/時計で送れない


@dataclass(frozen=True)
class StepResult:
    step: Step
    batch_id: str | None = None
    detail: str | None = None


def parse_ack(doc: object) -> AckInfo | None:
    """200 の本文から ACK を取り出す。形が違えば None（ACK として扱わない = 再送して重複 ACK を待つ）。"""
    if not isinstance(doc, dict):
        return None

    def num(key: str) -> int | None:
        v = doc.get(key)
        return v if isinstance(v, int) and not isinstance(v, bool) else None

    if num("accepted") is None or num("duplicates") is None or not isinstance(doc.get("duplicate"), bool):
        return None
    fetch = doc.get("fetch")
    cursor = doc.get("cursor")
    fid = fstatus = None
    fnext = None
    if isinstance(fetch, dict):
        fid, fstatus, fnext = fetch.get("id"), fetch.get("status"), fetch.get("nextSeq")
    ctype = cts = None
    if isinstance(cursor, dict):
        ctype, cts = cursor.get("dataType"), cursor.get("lastFileTimestamp")
    return AckInfo(
        accepted=num("accepted") or 0,
        duplicates=num("duplicates") or 0,
        quarantined=num("quarantined") or 0,
        unsupported=num("unsupported") or 0,
        duplicate=bool(doc.get("duplicate")),
        fetch_id=fid if isinstance(fid, str) else None,
        fetch_status=fstatus if isinstance(fstatus, str) else None,
        fetch_next_seq=fnext if isinstance(fnext, int) and not isinstance(fnext, bool) else None,
        cursor_type=ctype if isinstance(ctype, str) else None,
        cursor_ts=cts if isinstance(cts, str) else None,
    )


class Sender:
    AUTH_HALT_KEY = "auth_halt"

    def __init__(
        self,
        queue: OutboxQueue,
        client: SignedClient,
        modes: ModeMachine,
        cfg: SenderConfig,
        clock: Clock,
        credential_fingerprint: str,
        backoff: Backoff | None = None,
        rand: Callable[[], float] = random.random,
    ) -> None:
        self._q, self._client, self._modes, self._cfg, self._clock = queue, client, modes, cfg, clock
        self._fp = credential_fingerprint
        self._backoff = backoff or Backoff()
        self._rand = rand
        self._last_send: float | None = None
        self._slow_until = 0.0

    # --------------------------------------------------------------- 認証・時計の停止
    def auth_halted(self) -> str | None:
        raw = self._q.get_state(self.AUTH_HALT_KEY)
        if not raw:
            return None
        doc = json.loads(raw)
        if doc.get("fp") != self._fp:
            self._q.set_state(self.AUTH_HALT_KEY, "")  # 資格情報が差し替えられた = 利用者が再発行したトークン。再開する
            self._q.clear_alert("AUTH_FAILED")
            return None
        if doc.get("reason") == "CLOCK_SKEW" and not self._client.skew.suspicious:
            self._q.set_state(self.AUTH_HALT_KEY, "")  # 時計のずれが解消した
            self._q.clear_alert("CLOCK_SKEW")
            return None
        return str(doc.get("reason", "AUTH"))

    def _halt_auth(self, status: int | None) -> None:
        reason = "CLOCK_SKEW" if self._client.skew.suspicious else f"AUTH_{status}"
        self._q.set_state(self.AUTH_HALT_KEY, json.dumps({"fp": self._fp, "reason": reason}))
        self._q.raise_alert(
            "CLOCK_SKEW" if reason == "CLOCK_SKEW" else "AUTH_FAILED",
            f"HTTP {status}: 送信を停止（トークンの再発行または時計の修正が必要）",
        )
        log.error("認証に失敗したため送信を停止: %s", reason)

    # --------------------------------------------------------------- 1 バッチ
    def send_next(self) -> StepResult:
        halted = self.auth_halted()
        if halted:
            return StepResult(Step.HALTED, detail=halted)
        view = self._modes.evaluate()
        if not self._modes.may_send():
            return StepResult(Step.HALTED, detail=f"{view.mode.value}:{view.reason}")
        self._q.expire_realtime()
        wait = self._pacing_wait()
        if wait > 0:
            self._clock.sleep(wait)
        batch = self._q.claim_next()
        if batch is None:
            return StepResult(Step.IDLE)
        self._last_send = self._clock.monotonic()
        return self._send(batch)

    def _pacing_wait(self) -> float:
        now = self._clock.monotonic()
        gap = 1.0 / self._cfg.sends_per_second
        wait = 0.0
        if self._last_send is not None:
            wait = max(0.0, self._last_send + gap - now)
        return max(wait, self._slow_until - now)

    def _send(self, batch: BatchRow) -> StepResult:
        res = self._client.post_json("/v1/agent/ingest", batch.payload)
        c = res.classified
        self._modes.on_server(c)
        o = c.outcome
        bid = batch.batch_id
        if o is Outcome.OK:
            ack = parse_ack(res.json())
            if ack is None:
                self._q.retry_later(
                    bid, self._delay(batch, None), "BAD_ACK", self._cfg.max_attempts
                )  # ACK の形が違う = 受理を確認できない。同じ batchId で再送
                return StepResult(Step.RETRY, bid, "BAD_ACK")
            self._q.mark_acked(bid, ack)
            return StepResult(Step.ACKED, bid, "DUPLICATE" if ack.duplicate else None)
        if o is Outcome.DEAD:
            self._q.mark_dead(bid, c.code or f"HTTP_{c.status}")
            self._q.raise_alert("DEAD_BATCH", f"不正なバッチを DEAD にした（{c.code or c.status}）。自動再送はしない")
            log.error("バッチが拒否された（DEAD）: %s code=%s", bid, c.code)
            return StepResult(Step.DEAD, bid, c.code)
        if o is Outcome.AUTH:
            self._q.defer(bid, f"AUTH_{c.status}")
            self._halt_auth(c.status)
            return StepResult(Step.HALTED, bid, f"AUTH_{c.status}")
        if o in (Outcome.OPS_NOT_NORMAL,):
            self._q.defer(bid, "OPS_NOT_NORMAL")
            return StepResult(Step.DEFERRED, bid, c.ops_mode)
        if o is Outcome.RETRY_CONFLICT:
            delay = min(c.retry_after if c.retry_after is not None else self._delay(batch, None), self._cfg.max_conflict_wait_seconds)
            return self._retry(bid, delay, f"409:{c.code}")
        if o is Outcome.THROTTLED:
            delay = c.retry_after if c.retry_after is not None else self._delay(batch, None)
            self._slow_until = self._clock.monotonic() + delay  # 全体も減速する
            return self._retry(bid, delay, "429")
        # TRANSIENT_DB / UNREACHABLE / SERVER_ERROR / OPS_UNKNOWN / UNEXPECTED: 同じバッチを保持してバックオフ
        if o is Outcome.UNEXPECTED:
            self._q.raise_alert("UNEXPECTED_RESPONSE", f"想定外の応答 HTTP {c.status}")
        return self._retry(bid, self._delay(batch, c.retry_after), f"{o.value}:{c.status or c.code}")

    def _delay(self, batch: BatchRow, retry_after: float | None) -> float:
        d = self._backoff.delay(max(batch.attempts, 1), self._rand)
        return max(d, retry_after) if retry_after is not None else d

    def _retry(self, batch_id: str, delay: float, outcome: str) -> StepResult:
        state = self._q.retry_later(batch_id, delay, outcome, self._cfg.max_attempts)
        if state == "DEAD":
            self._q.raise_alert("RETRY_EXHAUSTED", "再試行の上限に達したバッチを DEAD にした")
            return StepResult(Step.DEAD, batch_id, "RETRY_EXHAUSTED")
        return StepResult(Step.RETRY, batch_id, outcome)
