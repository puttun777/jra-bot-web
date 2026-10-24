"""HTTP 通信の下層。標準ライブラリだけ（urllib）。リダイレクトは**追わない**（署名した要求を別の宛先へ送らない）。
TLS は既定のコンテキスト（証明書・ホスト名の検証あり）。応答本文は上限つきで読む。
"""

from __future__ import annotations

import socket
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Protocol

from .errors import TransportError

MAX_RESPONSE_BYTES = 1024 * 1024


@dataclass
class TransportResponse:
    status: int
    headers: dict[str, str]  # キーは小文字
    body: bytes = b""


class Transport(Protocol):
    def request(self, method: str, url: str, headers: dict[str, str], body: bytes | None, timeout: float) -> TransportResponse: ...


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None  # 3xx はそのまま応答として返す（呼び出し側が異常として扱う）


@dataclass
class UrllibTransport:
    context: ssl.SSLContext | None = None
    _opener: urllib.request.OpenerDirector = field(init=False)

    def __post_init__(self) -> None:
        handlers: list[urllib.request.BaseHandler] = [
            _NoRedirect(),
            urllib.request.HTTPSHandler(context=self.context or ssl.create_default_context()),
        ]
        self._opener = urllib.request.build_opener(*handlers)

    def request(self, method: str, url: str, headers: dict[str, str], body: bytes | None, timeout: float) -> TransportResponse:
        if not url.startswith(("https://", "http://127.0.0.1", "http://localhost", "http://[::1]")):
            raise TransportError("INSECURE_URL")  # 平文 HTTP は開発用のループバックだけ（設定の検証と二重に確認）
        req = urllib.request.Request(url, data=body, method=method, headers=headers)  # noqa: S310 - スキームは上で検査済み
        try:
            with self._opener.open(req, timeout=timeout) as resp:
                return TransportResponse(
                    resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read(MAX_RESPONSE_BYTES + 1)[:MAX_RESPONSE_BYTES]
                )
        except urllib.error.HTTPError as e:
            try:
                data = e.read(MAX_RESPONSE_BYTES + 1)[:MAX_RESPONSE_BYTES]
            except OSError:
                data = b""
            return TransportResponse(e.code, {k.lower(): v for k, v in e.headers.items()}, data)
        except TimeoutError as e:
            raise TransportError("TIMEOUT") from e
        except ssl.SSLError as e:
            raise TransportError("TLS") from e
        except urllib.error.URLError as e:
            if isinstance(e.reason, (TimeoutError, socket.timeout)):
                raise TransportError("TIMEOUT") from e
            if isinstance(e.reason, ssl.SSLError):
                raise TransportError("TLS") from e
            raise TransportError("CONNECTION") from e
        except (ConnectionError, OSError) as e:
            raise TransportError("CONNECTION") from e
