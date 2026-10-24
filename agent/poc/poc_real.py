"""実機 PoC（Windows・64bit Python・64bit JV-Link）。**利用者が明示的に実行したときだけ**動く。集計だけを出力し、レコードの内容は保存・表示しない。

  python -m poc.poc_real g1 --allow-real-jvlink --out g1.json
  python -m poc.poc_real g2 --allow-real-jvlink --dataspec RACE --option 1 --fromtime 20261001000000 --max-records 500 --out g2.json
  python -m poc.poc_real g3-realtime --allow-real-jvlink --dataspec 0B12 --key 20261101 --out g3rt.json

安全策:
 - `--allow-real-jvlink` が無ければ何も実行しない。実行前に『これから呼ぶ JV-Link の API 一覧』を表示し、`--yes` が無ければ確認を求める。
 - 呼ぶのは参照元実装（jvlink_provider.py）が使っている呼出し（JVInit・JVOpen・JVRTOpen・JVRead・JVClose）だけ。仕様書未確認の API
   （JVGets など）は `--probe-jvgets` を付けたときだけ。**付ける前に公式の JV-Link インターフェース仕様書で確認すること。**
 - ingest・GCP・どこにも送信しない（ネットワークを使わない）。保存するのは集計 JSON だけ。
 - 利用キーは JRA-VAN 公式の設定ソフトで設定する。このスクリプトは扱わない。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from jra_agent.jvlink import codes
from jra_agent.jvlink.com_adapter import ComJvLink, create_com_object, shutdown_com
from jra_agent.jvlink.interfaces import ReadStatus

from .poc_common import Aggregate, GateResult, Timer, write_report

CALLS = {
    "g1": ["COM 生成 (Dispatch)", "JVInit(sid)"],
    "g2": ["COM 生成", "JVInit(sid)", "JVOpen(dataspec, fromtime, option, 0, 0, '')", "JVRead('', 110000, '') を最大 N 回", "JVClose()"],
    "g3-realtime": ["COM 生成", "JVInit(sid)", "JVRTOpen(dataspec, key)", "JVRead('', 110000, '') を EOF まで", "JVClose()"],
}


def _confirm(gate: str, ns: argparse.Namespace) -> bool:
    if not ns.allow_real_jvlink:
        sys.stderr.write("実 JV-Link を呼ぶには --allow-real-jvlink が必要です。何も実行しません。\n")
        return False
    sys.stderr.write("これから呼ぶ JV-Link の API:\n" + "".join(f"  - {c}\n" for c in CALLS[gate]))
    if ns.probe_jvgets:
        sys.stderr.write("  - [仕様書未確認] JVGets の探索（--probe-jvgets）\n")
    if ns.yes:
        return True
    return input("実行しますか? [y/N] ").strip().lower() == "y"


def _cp932_roundtrip(data: bytes) -> bool:
    try:
        return data.decode("cp932").encode("cp932") == data
    except UnicodeError:
        return False


def g1(ns: argparse.Namespace) -> GateResult:
    try:
        com = create_com_object()
    except Exception as e:
        return GateResult(
            "G-AGENT-1",
            "FAIL",
            f"COM を生成できない: {type(e).__name__}",
            {"hint": "32bit 版 JV-Link + 32bit Python を試す（D-018 第二案）"},
        )
    try:
        code = ComJvLink(com).init(ns.sid)
        return GateResult("G-AGENT-1", "PASS" if code == 0 else "FAIL", "" if code == 0 else "JVInit が 0 以外", {"jvinit_code": code})
    finally:
        shutdown_com()


def g2(ns: argparse.Namespace) -> GateResult:
    com = ComJvLink(create_com_object())
    agg = Aggregate()
    rt = {"roundtrip_ok": 0, "roundtrip_ng": 0, "nul_in_record": 0}
    try:
        init = com.init(ns.sid)
        with Timer() as t_open:
            o = com.open_stored(ns.dataspec, ns.fromtime, ns.option)
        agg.add_code(f"open:{o.code}")
        if not o.ok:
            return GateResult("G-AGENT-2", "FAIL", "JVOpen が失敗", {"jvinit": init, "open_code": o.code, **agg.summary()})
        reads = 0
        t0 = time.perf_counter()
        while reads < ns.max_records:
            with Timer() as t:
                r = com.read()
            agg.add_duration(t.seconds)
            agg.add_code(f"read:{r.status.value}:{r.code if r.status is not ReadStatus.RECORD else 'n'}")
            if r.status is ReadStatus.RECORD and r.data is not None:
                reads += 1
                agg.add_record(r.data)
                rt["roundtrip_ok" if _cp932_roundtrip(r.data) else "roundtrip_ng"] += 1
            elif r.status is ReadStatus.DOWNLOADING:
                time.sleep(0.5)
            elif r.status in (ReadStatus.EOF, ReadStatus.ERROR):
                break
        com.close()
        ok = rt["roundtrip_ng"] == 0 and agg.count > 0
        return GateResult(
            "G-AGENT-2", "PASS" if ok else "FAIL", "" if ok else "レコードが 0 件、または cp932 の往復が一致しない",
            {"jvinit": init, "open_code": o.code, "read_count_reported": o.read_count, "open_seconds": round(t_open.seconds, 2), "elapsed": round(time.perf_counter() - t0, 2), **rt, **agg.summary()},
        )  # fmt: skip
    finally:
        shutdown_com()


def g3_realtime(ns: argparse.Namespace) -> GateResult:
    com = ComJvLink(create_com_object())
    agg = Aggregate()
    try:
        com.init(ns.sid)
        o = com.open_realtime(ns.dataspec, ns.key)
        agg.add_code(f"rtopen:{o.code}")
        if not o.ok:
            return GateResult(
                "G-AGENT-3",
                "FAIL",
                f"JVRTOpen が {o.code}",
                {**agg.summary(), "hint": "-413 は Python 3.14 の報告がある。公式の検証ツールで同条件を確認し、コードと条件を報告"},
            )
        for _ in range(ns.max_records):
            r = com.read()
            agg.add_code(f"read:{r.status.value}")
            if r.status is ReadStatus.RECORD and r.data is not None:
                agg.add_record(r.data)
            elif r.status in (ReadStatus.EOF, ReadStatus.ERROR):
                break
            elif r.status is ReadStatus.DOWNLOADING:
                time.sleep(0.5)
        com.close()
        return GateResult("G-AGENT-3", "PASS" if agg.count > 0 else "FAIL", "" if agg.count else "速報が 0 件", agg.summary())
    finally:
        shutdown_com()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gate", choices=["g1", "g2", "g3-realtime"])
    ap.add_argument("--allow-real-jvlink", action="store_true")
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--probe-jvgets", action="store_true", help="[仕様書未確認] JVGets の探索。公式仕様で確認してから")
    ap.add_argument("--sid", default="UNKNOWN", help="JVInit に渡すソフトウェア ID（利用キーではない）")
    ap.add_argument("--dataspec", default="RACE")
    ap.add_argument("--option", type=int, default=1)
    ap.add_argument("--fromtime", default="00000000000000")
    ap.add_argument("--key", default="")
    ap.add_argument("--max-records", type=int, default=200)
    ap.add_argument("--out", type=Path)
    ns = ap.parse_args(argv)
    if sys.platform != "win32":
        sys.stderr.write("実機 PoC は Windows でだけ動きます（Mock は poc_mock.py）。\n")
        return 2
    if not _confirm(ns.gate, ns):
        return 2
    res = {"g1": g1, "g2": g2, "g3-realtime": g3_realtime}[ns.gate](ns)
    write_report(ns.out, "real", [res], {"codes_note": codes.UNCONFIRMED_NOTICE})
    return 0 if res.status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
