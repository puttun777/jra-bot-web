"""COM ワーカー（子プロセス）。JV-Link の COM は**このプロセスの中だけ**で初期化・使用・終了する。COM オブジェクトをプロセス間で共有しない。

親（supervisor）との通信は stdin/stdout の JSON 行（1 行 1 メッセージ）。バイト列は Base64。pickle は使わない。
親が応答を待つのは設定の時間まで（ウォッチドッグ）。ここが COM で止まっても、親が強制終了して作り直す。
トークンなどの秘密は受け取らない（受け取るのは dataspec・fromtime・option・key・sid だけ）。

使い方: `python -m jra_agent.jvlink.worker_main --backend fake --script <path>` / `--backend com`（Windows・利用者が許可したときだけ）
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import time
from typing import Any

from .fake import FakeJvLink, FakeScript
from .interfaces import JvLinkApi, ReadStatus

MAX_LINE = 4 * 1024 * 1024


def _respond(out: Any, msg: dict[str, Any]) -> None:
    out.write((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
    out.flush()


def read_batch(api: JvLinkApi, max_records: int, max_bytes: int, max_polls: int, poll_sleep: float) -> dict[str, Any]:
    """レコードを最大 `max_records` 件 / `max_bytes` バイトまで読む。ファイル切替・EOF・エラーで返す（切替は安全な区切り）。"""
    records: list[str] = []
    total = 0
    polls = 0
    names: set[str] = set()
    while True:
        r = api.read()
        if r.filename:
            names.add(r.filename)
        if r.status is ReadStatus.RECORD:
            data = r.data or b""
            records.append(base64.b64encode(data).decode("ascii"))
            total += len(data)
            if len(records) >= max_records or total >= max_bytes:
                return {"status": "MORE", "code": r.code, "records": records, "files": len(names)}
            continue
        if r.status is ReadStatus.DOWNLOADING:
            polls += 1
            if polls >= max_polls:
                return {"status": "DOWNLOADING", "code": r.code, "records": records, "files": len(names)}
            time.sleep(poll_sleep)
            continue
        return {"status": r.status.value, "code": r.code, "records": records, "files": len(names)}


def handle(api: JvLinkApi | None, req: dict[str, Any], opened: dict[str, bool]) -> dict[str, Any]:
    op = req["op"]
    a = req.get("args", {})
    if op == "ping":
        return {"pong": True}
    if api is None:
        raise RuntimeError("NO_BACKEND")
    if op == "init":
        return {"code": api.init(str(a["sid"]))}
    if op in ("open_stored", "open_realtime"):
        if opened["v"]:
            raise RuntimeError("OPEN_WHILE_OPEN")  # 同時に複数の JVOpen を実行しない
        o = (
            api.open_stored(str(a["dataspec"]), str(a["fromtime"]), int(a["option"]))
            if op == "open_stored"
            else api.open_realtime(str(a["dataspec"]), str(a["key"]))
        )
        opened["v"] = o.ok
        return {
            "code": o.code,
            "read_count": o.read_count,
            "download_count": o.download_count,
            "last_file_timestamp": o.last_file_timestamp,
        }
    if op == "read_batch":
        if not opened["v"]:
            raise RuntimeError("NOT_OPEN")
        return read_batch(api, int(a["max_records"]), int(a["max_bytes"]), int(a.get("max_polls", 20)), float(a.get("poll_sleep", 0.5)))
    if op == "close":
        opened["v"] = False
        return {"code": api.close()}
    raise RuntimeError("UNKNOWN_OP")


def serve(api: JvLinkApi | None, inp: Any, out: Any) -> None:
    opened = {"v": False}
    while True:
        line = inp.readline(MAX_LINE)
        if not line:
            return  # 親が閉じた
        req: dict[str, Any] = {}
        try:
            req = json.loads(line)
            if req.get("op") == "shutdown":
                _respond(out, {"id": req.get("id"), "ok": True, "result": {}})
                return
            _respond(out, {"id": req.get("id"), "ok": True, "result": handle(api, req, opened)})
        except Exception as e:
            _respond(out, {"id": req.get("id"), "ok": False, "error": type(e).__name__})


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--backend", choices=["fake", "com"], required=True)
    p.add_argument("--script", help="fake の台本（JSON ファイル）")
    ns = p.parse_args(argv)
    out = sys.stdout.buffer
    sys.stdout = sys.stderr  # うっかりの print がプロトコルを壊さない
    api: JvLinkApi | None
    shutdown = None
    if ns.backend == "fake":
        script = FakeScript.from_json(open(ns.script, encoding="utf-8").read()) if ns.script else FakeScript()  # noqa: SIM115
        api = FakeJvLink(script)
    else:
        from .com_adapter import ComJvLink, create_com_object, shutdown_com

        api = ComJvLink(create_com_object())
        shutdown = shutdown_com
    try:
        serve(api, sys.stdin.buffer, out)
    finally:
        if api is not None:
            try:
                api.close()
            except Exception:  # noqa: S110
                pass
        if shutdown:
            shutdown()  # COM の終了もワーカーの中で行う
    return 0


if __name__ == "__main__":
    sys.exit(main())
