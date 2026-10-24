"""署名付き HTTP クライアント。要求ごとに新しい nonce とタイムスタンプで署名し、応答を分類する。

- 本文は**送信するバイト列**（gzip なら圧縮後）をハッシュする（サーバは認証の前に展開しない）。gzip は決定的（mtime=0）なので、
  同じバッチの再送は毎回同じ本文バイト列になる（署名は nonce が違うので毎回別）。
- 応答の `Date` から時計ずれを測る（[`SkewMonitor`]）。ずれが大きいと 401 の原因になるため、401 の解釈に使う。
- トークンの期限切れ・失効（401/403）では自動で再試行しない。呼び出し側（sender）が送信を止める。
"""

from __future__ import annotations

import gzip
import json
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.parse import urlsplit

from . import __version__
from .clock import Clock, to_unix
from .errors import TransportError
from .responses import Classified, Outcome, classify
from .signing import RequestSigner
from .transport import Transport, TransportResponse

SKEW_WARN_MS = 30_000  # NTP と 30 秒超ずれたら警告（JVLINK_DESIGN §8）
SKEW_AUTH_SUSPECT_MS = 240_000  # サーバの許容は ±300 秒。これを超えそうなら 401 の原因として疑う


def gzip_deterministic(data: bytes) -> bytes:
    return gzip.compress(data, compresslevel=6, mtime=0)


class SkewMonitor:
    """サーバの `Date` ヘッダとの差（ミリ秒、正=ローカルが進んでいる）。応答の遅延（数百 ms）を含む概算で、警告と原因の絞り込みにだけ使う。"""

    def __init__(self, clock: Clock) -> None:
        self._clock = clock
        self.last_skew_ms: int | None = None

    def observe(self, headers: dict[str, str]) -> None:
        raw = headers.get("date")
        if not raw:
            return
        try:
            server = parsedate_to_datetime(raw)
        except (TypeError, ValueError):
            return
        self.last_skew_ms = int((self._clock.now() - server).total_seconds() * 1000)

    @property
    def suspicious(self) -> bool:
        return self.last_skew_ms is not None and abs(self.last_skew_ms) >= SKEW_AUTH_SUSPECT_MS

    @property
    def warn(self) -> bool:
        return self.last_skew_ms is not None and abs(self.last_skew_ms) >= SKEW_WARN_MS


@dataclass
class ApiResult:
    classified: Classified
    response: TransportResponse | None  # 通信失敗のときは None

    @property
    def ok(self) -> bool:
        return self.classified.outcome is Outcome.OK

    def json(self) -> Any:
        if self.response is None:
            return None
        try:
            return json.loads(self.response.body)
        except ValueError:
            return None


def _unreachable(kind: str) -> ApiResult:
    return ApiResult(Classified(Outcome.UNREACHABLE, None, kind), None)


class SignedClient:
    def __init__(
        self, base_url: str, signer: RequestSigner, transport: Transport, clock: Clock, timeout: float = 30.0, gzip_min_bytes: int = 512
    ) -> None:
        self._base = base_url.rstrip("/")
        self._signer = signer
        self._transport = transport
        self._clock = clock
        self._timeout = timeout
        self._gzip_min = gzip_min_bytes
        self.skew = SkewMonitor(clock)

    def _send(self, method: str, request_uri: str, body: bytes, content_headers: dict[str, str]) -> ApiResult:
        # 署名は「送信するバイト列」に対して行う。request_uri はパス + クエリ（サーバと同じ文字列）
        signed = self._signer.sign_request(method, request_uri, body, to_unix(self._clock.now()))
        headers = {"User-Agent": f"jra-agent/{__version__}", "Accept": "application/json", **content_headers, **signed.as_dict()}
        url = self._base + request_uri
        try:
            resp = self._transport.request(method, url, headers, body if method != "GET" or body else None, self._timeout)
        except TransportError as e:
            return _unreachable(e.kind)
        self.skew.observe(resp.headers)
        return ApiResult(classify(resp), resp)

    def get(self, request_uri: str) -> ApiResult:
        return self._send("GET", request_uri, b"", {})

    def post_json(self, request_uri: str, payload: bytes) -> ApiResult:
        """`payload` は JSON のバイト列。大きければ gzip する（`Content-Encoding: gzip`）。本文のバイト列は呼び出しごとに同一。"""
        if len(payload) >= self._gzip_min:
            body, headers = gzip_deterministic(payload), {"Content-Type": "application/json", "Content-Encoding": "gzip"}
        else:
            body, headers = payload, {"Content-Type": "application/json"}
        return self._send("POST", request_uri, body, headers)


def host_of(url: str) -> str:
    return urlsplit(url).hostname or ""
