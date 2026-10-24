"""エージェント API（plan / mode / heartbeat / update）の呼出し。応答はモードの状態機械へ渡す（200 は NORMAL の確認、503+X-Ops-Mode は NORMAL 以外の確認）。"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from .client import ApiResult, SignedClient
from .modes import ModeMachine
from .responses import Outcome


@dataclass(frozen=True)
class ModeProbe:
    reachable: bool
    mode: str | None  # サーバが名乗るモード（補助情報。判定の主は署名付きビーコン）
    result: ApiResult


class AgentApi:
    def __init__(self, client: SignedClient, modes: ModeMachine) -> None:
        self._client, self._modes = client, modes

    def probe_mode(self) -> ModeProbe:
        """`GET /v1/agent/mode`（DB に触れない・認証なしの軽い確認。NORMAL 以外でも応答する）。"""
        r = self._client.get("/v1/agent/mode")
        doc = r.json() if r.ok else None
        mode = doc.get("mode") if isinstance(doc, dict) and isinstance(doc.get("mode"), str) else None
        if r.ok:
            # 200 は「サーバが応答した」ことを示すが、モードは本文の mode が NORMAL のときだけ NORMAL の確認になる
            if mode == "NORMAL":
                self._modes.on_server(r.classified)
            elif mode is not None:
                from .responses import Classified

                self._modes.on_server(Classified(Outcome.OPS_NOT_NORMAL, 200, None, None, mode))
        else:
            self._modes.on_server(r.classified)
        return ModeProbe(r.classified.outcome is not Outcome.UNREACHABLE, mode, r)

    def get_plan(self) -> ApiResult:
        r = self._client.get("/v1/agent/plan")
        self._modes.on_server(r.classified)
        return r

    def post_heartbeat(self, report: dict[str, Any]) -> ApiResult:
        r = self._client.post_json("/v1/agent/heartbeat", json.dumps(report, separators=(",", ":")).encode("utf-8"))
        self._modes.on_server(r.classified)
        return r

    def get_update(self) -> ApiResult:
        r = self._client.get("/v1/agent/update")
        self._modes.on_server(r.classified)
        return r
