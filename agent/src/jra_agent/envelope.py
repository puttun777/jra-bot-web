"""ingest 封筒の組み立て（CONTRACTS §3.4）。サーバの `EnvelopeValidator` が受け付ける形だけを作る（未知のキーなし・`tenantId` なし）。

- `batchId` は UUIDv7（小文字）。**再送では同じ batchId・同じ内容**（キューに保存した JSON のバイト列をそのまま使う）。
- レコードは cp932 のバイト列をそのまま Base64（標準・パディングあり）+ SHA-256。エージェントは解釈しない（サーバが解析する）。
- 蓄積系（S）は取得セッション `fetch`（提案 P-2）を付ける。速報（R）には付けない（サーバが拒否する）。
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import time
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime

from . import PROTOCOL_VERSION
from .clock import iso_z

UUID_V7_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
RECORD_ID_RE = re.compile(r"[A-Z0-9]{2,4}")
TS14_RE = re.compile(r"[0-9]{14}")

STORED_DATASPECS = frozenset({"RACE", "YSCH", "RCVN", "RCOV"})
REALTIME_DATASPECS = frozenset({"0B11", "0B12", "0B14", "0B15", "0B30", "0B31", "0B33", "0B35"})


def uuid7(now_ms: int | None = None, rand: Callable[[int], bytes] = os.urandom) -> str:
    """RFC 9562 の UUIDv7（48bit のミリ秒 + 乱数）。時系列でソートできる。"""
    ms = int(time.time() * 1000) if now_ms is None else now_ms
    r = rand(10)
    b = bytearray(ms.to_bytes(6, "big") + r)
    b[6] = 0x70 | (b[6] & 0x0F)
    b[8] = 0x80 | (b[8] & 0x3F)
    return str(uuid.UUID(bytes=bytes(b)))


@dataclass(frozen=True)
class FetchInfo:
    fetch_id: str
    seq: int
    target_from: str | None = None  # yyyyMMdd
    target_to: str | None = None
    origin: str = "LIVE"
    backfill_id: int | None = None

    def to_json(self) -> dict[str, object]:
        d: dict[str, object] = {"id": self.fetch_id, "seq": self.seq, "origin": self.origin}
        if self.target_from is not None and self.target_to is not None:
            d["targetFrom"], d["targetTo"] = self.target_from, self.target_to
        if self.backfill_id is not None:
            d["backfillId"] = self.backfill_id
        return d


@dataclass(frozen=True)
class RawRecord:
    record_id: str  # 先頭 2〜4 文字の種別 ID（RA・SE・O1 など）
    data: bytes

    def to_json(self) -> dict[str, str]:
        return {
            "id": self.record_id,
            "bytesB64": base64.b64encode(self.data).decode("ascii"),
            "sha256": hashlib.sha256(self.data).hexdigest(),
        }


def batch_class_of(dataspec: str) -> str:
    if dataspec in STORED_DATASPECS:
        return "S"
    if dataspec in REALTIME_DATASPECS:
        return "R"
    raise ValueError("サーバが受け付けない dataspec")  # 値は含めない（呼び出し側がログに出す）


def build_envelope(
    *,
    agent_id: str,
    batch_id: str,
    dataspec: str,
    observed_at: datetime,
    records: Sequence[RawRecord],
    eof: bool,
    option: int | None = None,
    fromtime: str | None = None,
    last_file_timestamp: str | None = None,
    fetch: FetchInfo | None = None,
) -> bytes:
    """封筒の JSON（UTF-8、キー順固定）。検証はここでも行う（サーバで 422 になる前に、バグとして気づく）。"""
    if not UUID_V7_RE.fullmatch(batch_id):
        raise ValueError("batch_id は小文字の UUIDv7")
    cls = batch_class_of(dataspec)
    if fetch is not None and cls != "S":
        raise ValueError("fetch は蓄積系（S）だけ")
    if not records and not eof:
        raise ValueError("EOF 以外の空バッチは作れない")
    if option is not None and not 0 <= option <= 9:
        raise ValueError("option は 0〜9")
    for ts in (fromtime, last_file_timestamp):
        if ts is not None and not TS14_RE.fullmatch(ts):
            raise ValueError("タイムスタンプは yyyyMMddHHmmss")
    for r in records:
        if not RECORD_ID_RE.fullmatch(r.record_id) or not r.data:
            raise ValueError("レコード ID または本体が不正")
    doc: dict[str, object] = {
        "protocol": PROTOCOL_VERSION,
        "agentId": agent_id,
        "batchId": batch_id,
        "class": cls,
        "observedAt": iso_z(observed_at),
        "dataspec": dataspec,
    }
    if option is not None:
        doc["option"] = option
    if fromtime is not None:
        doc["fromtime"] = fromtime
    if last_file_timestamp is not None:
        doc["lastFileTimestamp"] = last_file_timestamp
    doc["eof"] = eof
    if fetch is not None:
        doc["fetch"] = fetch.to_json()
    doc["records"] = [r.to_json() for r in records]
    return json.dumps(doc, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
