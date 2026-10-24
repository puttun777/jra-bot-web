"""Kotlin ingest との結合テスト用ドライバ（:ingest の AgentInteropIntegrationTest が子プロセスとして起動する）。

エージェントの本物の部品（署名クライアント・SQLite キュー・Sender・状態機械・UrllibTransport）を、実際の HTTP で Kotlin の ingest に接続する。
トークンは標準入力から受け取る（引数・環境変数・ログに出さない）。レコードは合成の正規化 JSON（サーバのローカル専用パーサ）。結果は集計 JSON を標準出力へ。
"""

from __future__ import annotations

import json
import sys
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from jra_agent.api import AgentApi
from jra_agent.beacon import Beacon, BeaconResult, BeaconStatus
from jra_agent.client import SignedClient
from jra_agent.clock import SystemClock
from jra_agent.config import QueueConfig, SenderConfig
from jra_agent.envelope import FetchInfo, RawRecord, build_envelope, uuid7
from jra_agent.errors import TransportError
from jra_agent.modes import Mode, ModeMachine
from jra_agent.plan import parse_plan
from jra_agent.queue import OutboxQueue
from jra_agent.sender import Sender, Step
from jra_agent.signing import RequestSigner
from jra_agent.transport import UrllibTransport

TS = "20261031200000"


class LoseAckOnce:
    """リクエストはサーバへ届くが、応答を受け取る前に切断された状態を 1 回だけ作る（`lose_on` は本文に含まれる batchId の一部）。"""

    def __init__(self, inner: UrllibTransport) -> None:
        self.inner, self.armed = inner, False

    def request(self, method, url, headers, body, timeout):  # type: ignore[no-untyped-def]
        resp = self.inner.request(method, url, headers, body, timeout)
        if self.armed and url.endswith("/v1/agent/ingest"):
            self.armed = False
            raise TransportError("CONNECTION")
        return resp


def nys(date: str) -> RawRecord:
    doc = {"date": date, "venue": "05", "kai": 3, "nichime": 1, "status": "PLANNED", "replacedBy": None}
    return RawRecord("NYS", json.dumps(doc).encode())


def main() -> int:
    base_url, agent_id, phase = sys.argv[1], sys.argv[2], sys.argv[3]
    token = sys.stdin.readline().strip()
    clock = SystemClock()
    out: dict[str, object] = {"phase": phase}
    with tempfile.TemporaryDirectory() as d:
        q = OutboxQueue(Path(d) / "q.sqlite3", clock, QueueConfig(min_free_bytes=0))
        modes = ModeMachine(clock)
        transport = LoseAckOnce(UrllibTransport())
        client = SignedClient(base_url, RequestSigner(agent_id, token), transport, clock)  # type: ignore[arg-type]
        api = AgentApi(client, modes)
        modes.on_beacon(
            BeaconResult(BeaconStatus.VALID, Beacon("NORMAL", 1, "k", datetime.now(UTC), None))
        )  # ビーコンは Mock（実 GCP には接続しない）
        probe = api.probe_mode()
        out["probe_mode"] = probe.mode
        sender = Sender(q, client, modes, SenderConfig(sends_per_second=10.0), clock, "fp")
        if phase == "stopped":
            bid = uuid7()
            payload = build_envelope(
                agent_id=agent_id, batch_id=bid, dataspec="0B12", observed_at=clock.now(), records=[nys("20261101")], eof=True
            )
            q.enqueue(batch_id=bid, cls="R", dataspec="0B12", payload=payload, record_count=1, eof=True)
            step = sender.send_next()
            out.update(step=step.step.value, mode=modes.evaluate().mode.value, queued=q.stats().by_status)
            print(json.dumps(out))
            return 0
        # ---- normal: plan / heartbeat / 取得セッション 3 バッチ（最後の ACK を失う）
        plan_res = api.get_plan()
        plan = parse_plan(plan_res.json())
        out["plan_status"] = plan_res.classified.status
        out["plan_items"] = len(plan.items)
        out["plan_idle"] = not plan.usable(clock.now())
        out["heartbeat"] = api.post_heartbeat(
            {"agentVersion": "0.1.0", "queueDepth": 0, "mode": "NORMAL", "ackedTs": {"RACE.2": TS}}
        ).classified.status
        fid = uuid7()
        q.begin_session(fid, "YSCH:2", "YSCH", 2, "20261030000000", TS, "20261101", "20261130")
        for i in range(3):
            s = q.session(fid)
            assert s is not None
            bid = uuid7()
            eof = i == 2
            payload = build_envelope(
                agent_id=agent_id, batch_id=bid, dataspec="YSCH", observed_at=clock.now(), records=[nys(f"202611{i + 1:02d}")], eof=eof,
                option=2, fromtime="20261030000000", last_file_timestamp=TS, fetch=FetchInfo(fid, s.next_seq, "20261101", "20261130"),
            )  # fmt: skip
            q.enqueue(
                batch_id=bid, cls="S", dataspec="YSCH", payload=payload, record_count=1, eof=eof, fetch_id=fid, last_file_timestamp=TS
            )
        steps = []
        steps.append(sender.send_next().step.value)
        steps.append(sender.send_next().step.value)
        transport.armed = True  # 最後（EOF）のバッチ: サーバは保存するが ACK が届かない
        steps.append(sender.send_next().step.value)
        out["acked_ts_after_lost_ack"] = q.checkpoint("YSCH:2").acked_ts
        modes.on_server(
            __import__("jra_agent.responses", fromlist=["x"]).Classified(__import__("jra_agent.responses", fromlist=["x"]).Outcome.OK, 200)
        )
        clock_wait = q.next_eligible_at()
        out["retry_scheduled"] = clock_wait is not None
        # 再送時刻まで待つ代わりに、キューの待機を解除して同じバッチを再送する
        q._db.execute("UPDATE batch SET next_try_at = 0")
        r = sender.send_next()
        steps.append(r.step.value)
        out["steps"] = steps
        out["final_detail"] = r.detail
        out["acked_ts"] = q.checkpoint("YSCH:2").acked_ts
        out["session_state"] = getattr(q.session(fid), "state", None)
        out["problems"] = q.verify()
        out["all_acked"] = all(b.status == "ACKED" for b in q.batches()) and r.step is Step.ACKED
        out["mode"] = modes.evaluate().mode is Mode.NORMAL_OK
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
