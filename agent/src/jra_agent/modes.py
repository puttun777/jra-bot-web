"""エージェントの状態機械（CONTRACTS §7.3）。UNKNOWN / NORMAL_OK / STOPPED_CONFIRMED。

**安全側の原則**: NORMAL が確認できない限り、取得も送信もしない。起動直後は必ず UNKNOWN（前回の状態を信用しない = STOPPED 中に古い NORMAL で動かない）。
判定は観測の組合せから**毎回導出**する（状態を直接書き換えない）:

| 観測 | 判定 |
|---|---|
| 有効なビーコン NORMAL（観測が `max_age` 以内、`validUntil` 内）かつ、その観測以降に ingest/mode/plan が 200 | NORMAL_OK |
| 有効なビーコン STOPPED/STOPPING/STARTING | STOPPED_CONFIRMED（STARTING は待機） |
| ingest が 503 + `X-Ops-Mode`（NORMAL 以外）で、それより新しい有効な NORMAL ビーコンが無い | STOPPED_CONFIRMED |
| ビーコンが署名不正・seq 巻き戻り・形式不正（INVALID） | UNKNOWN（無視して稼働しない）+ ローカルアラート |
| ビーコン不達 かつ ingest が 503（ヘッダなし）/不達/タイムアウトを連続 N 回 | UNKNOWN |
| ビーコン NORMAL なのに ingest が障害（一時障害） | NORMAL_OK だが**送信は止める**（`send_paused`）。30 分続けば UNKNOWN |
| STOPPED_CONFIRMED で有効なビーコンの観測が 6 時間以上無い | UNKNOWN |
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from .beacon import BeaconResult, BeaconStatus
from .clock import Clock
from .responses import Classified, Outcome

UNKNOWN_AFTER_INGEST_FAILURES = 3
TRANSIENT_TO_UNKNOWN_SECONDS = 30 * 60
STOPPED_STALE_SECONDS = 6 * 3600


class Mode(Enum):
    UNKNOWN = "UNKNOWN"
    NORMAL_OK = "NORMAL_OK"
    STOPPED_CONFIRMED = "STOPPED_CONFIRMED"


@dataclass(frozen=True)
class ModeView:
    mode: Mode
    reason: str
    send_paused: bool = False  # NORMAL_OK だが ingest が一時障害（バックオフ中）


class ModeMachine:
    def __init__(self, clock: Clock, beacon_max_age_seconds: float = 6 * 3600) -> None:
        self._clock = clock
        self._max_age = beacon_max_age_seconds
        # ビーコン
        self._beacon_status: BeaconStatus | None = None  # 最後の取得の結果
        self._beacon_mode: str | None = None  # 最後の有効なビーコンの mode
        self._beacon_valid_until: float | None = None
        self._beacon_ok_at: float | None = None  # 最後に有効だった取得の時刻（単調時計）
        self._beacon_invalid_reason: str | None = None
        # サーバ（ingest/mode/plan）
        self._server_ok_at: float | None = None
        self._ingest_failures = 0
        self._ingest_failing_since: float | None = None
        self._hint_nonnormal_at: float | None = None  # 503 + X-Ops-Mode（NORMAL 以外）を見た時刻
        self._hint_mode: str | None = None

    # ------------------------------------------------------------ 入力
    def on_beacon(self, result: BeaconResult) -> None:
        now = self._clock.monotonic()
        self._beacon_status = result.status
        if result.status is BeaconStatus.VALID and result.beacon is not None:
            self._beacon_mode = result.beacon.mode
            self._beacon_ok_at = now
            self._beacon_invalid_reason = None
            vu = result.beacon.valid_until
            self._beacon_valid_until = None if vu is None else self._clock.monotonic() + (vu - self._clock.now()).total_seconds()
            if not result.beacon.normal:
                self._server_ok_at = None  # 止まった後の復帰には、NORMAL ビーコンの後の新しい 200 が要る（古い 200 を引きずらない）
            elif self._hint_nonnormal_at is not None and self._hint_nonnormal_at <= now:
                self._hint_nonnormal_at = None  # より新しい有効な NORMAL ビーコンが来たので、古い 503 の印は外す（サーバの 200 は別途必要）
                self._hint_mode = None
                self._server_ok_at = None
        elif result.status is BeaconStatus.INVALID:
            self._beacon_invalid_reason = result.reason
            self._beacon_mode = None  # 古い NORMAL を引きずらない
            self._server_ok_at = None

    def on_server(self, c: Classified) -> None:
        """ingest / mode / plan / heartbeat の応答。"""
        now = self._clock.monotonic()
        o = c.outcome
        if o is Outcome.OK:
            self._server_ok_at = now
            self._ingest_failures, self._ingest_failing_since = 0, None
            self._hint_nonnormal_at, self._hint_mode = None, None
        elif o is Outcome.OPS_NOT_NORMAL:
            self._hint_nonnormal_at, self._hint_mode = now, c.ops_mode
            self._server_ok_at = None
            self._ingest_failures, self._ingest_failing_since = 0, None
        elif o in (Outcome.OPS_UNKNOWN, Outcome.UNREACHABLE, Outcome.TRANSIENT_DB, Outcome.SERVER_ERROR):
            self._ingest_failures += 1
            if self._ingest_failing_since is None:
                self._ingest_failing_since = now
        # 400/409/429/401/403 などは「サーバは生きて応答した」= モードの判定材料にしない（認証・個別バッチの問題は別系統）

    # ------------------------------------------------------------ 判定
    def evaluate(self) -> ModeView:
        now = self._clock.monotonic()
        if self._beacon_status is BeaconStatus.INVALID:
            return ModeView(Mode.UNKNOWN, f"BEACON_INVALID:{self._beacon_invalid_reason}")
        if self._hint_nonnormal_at is not None:
            return ModeView(Mode.STOPPED_CONFIRMED, f"INGEST_503:{self._hint_mode}")
        if self._beacon_mode is None or self._beacon_ok_at is None:
            return ModeView(Mode.UNKNOWN, "NO_BEACON")
        if self._beacon_mode != "NORMAL":
            if now - self._beacon_ok_at > STOPPED_STALE_SECONDS:
                return ModeView(Mode.UNKNOWN, "STOPPED_BEACON_STALE")
            return ModeView(Mode.STOPPED_CONFIRMED, f"BEACON:{self._beacon_mode}")
        # ビーコン NORMAL
        if now - self._beacon_ok_at > self._max_age:
            return ModeView(Mode.UNKNOWN, "BEACON_STALE")
        if self._beacon_valid_until is not None and now > self._beacon_valid_until:
            return ModeView(Mode.UNKNOWN, "BEACON_EXPIRED")
        beacon_reachable = self._beacon_status is BeaconStatus.VALID
        if self._ingest_failures >= UNKNOWN_AFTER_INGEST_FAILURES and not beacon_reachable:
            return ModeView(Mode.UNKNOWN, "BEACON_UNREACHABLE_AND_INGEST_FAILING")
        if self._ingest_failing_since is not None and now - self._ingest_failing_since >= TRANSIENT_TO_UNKNOWN_SECONDS:
            return ModeView(Mode.UNKNOWN, "INGEST_FAILING_30M")
        if self._server_ok_at is None or now - self._server_ok_at > self._max_age:
            return ModeView(Mode.UNKNOWN, "AWAITING_SERVER_CONFIRMATION")
        if self._ingest_failures > 0:
            return ModeView(Mode.NORMAL_OK, "INGEST_TRANSIENT", send_paused=True)
        return ModeView(Mode.NORMAL_OK, "BEACON_NORMAL_AND_SERVER_OK")

    def may_fetch(self) -> bool:
        return self.evaluate().mode is Mode.NORMAL_OK

    def may_send(self) -> bool:
        v = self.evaluate()
        return v.mode is Mode.NORMAL_OK and not v.send_paused
