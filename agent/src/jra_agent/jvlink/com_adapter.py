"""実 JV-Link（COM）のアダプタ。**Windows でだけ import できる実体**。ワーカープロセスの中でだけ使う（`worker_main`）。

公式仕様で未確認の部分（`spec.py`）は、ここに**推測で実装しない**。現状この実装が持つのは:
  - COM オブジェクトの生成（ProgID の候補は参照元実装の使用実態。`create_com_object`）と COM の初期化/終了
  - 戻り値を中立な状態へ写す表（`codes.py`。参照元実装の使用実態 = 二次情報。公式未確認の印つき）
本番の取得経路は、`SpecRegistry.require(...)` が通るまでワーカーを起動しない（supervisor/fetcher 側のゲート）。
PoC（poc/）は、利用者が明示的に許可したときだけ、このアダプタを使って参照元実装と同じ呼出しを試す。
"""

from __future__ import annotations

import sys
from typing import Any

from . import codes
from .interfaces import OpenResult, ReadResult, ReadStatus

# 参照元実装（jvlink_provider.py）が試している ProgID（公式未確認。PoC の「COM 登録確認」で実際に存在するものを集計する）
PROGID_CANDIDATES = ("JVDTLab.JVLink",)
# 参照元実装の CLSID（レジストリの存在確認にだけ使う。公式未確認）
CLSID_CANDIDATE = "{2AB1774D-0C41-11D7-916F-0003479BEB3F}"


def create_com_object() -> Any:
    """COM を生成する（ワーカー内でだけ呼ぶ）。pywin32 が無い・Windows でない場合は ImportError/OSError。"""
    if sys.platform != "win32":
        raise OSError("COM は Windows でだけ使える")
    import pythoncom  # type: ignore[import-not-found,unused-ignore]
    import win32com.client  # type: ignore[import-not-found,unused-ignore]

    pythoncom.CoInitialize()
    last: Exception | None = None
    for progid in PROGID_CANDIDATES:
        try:
            return win32com.client.Dispatch(progid)
        except Exception as e:
            last = e
    raise OSError("JV-Link の COM を生成できない") from last


def shutdown_com() -> None:
    if sys.platform == "win32":
        try:
            import pythoncom  # type: ignore[import-not-found,unused-ignore]

            pythoncom.CoUninitialize()
        except Exception:  # noqa: S110 - 終了処理の失敗は握りつぶす（プロセスが終わる）
            pass


class ComJvLink:
    """`JvLinkApi` の COM 実装。メソッド名・引数は参照元実装で使われているもの。公式未確認の戻り値の解釈は `codes.py` に分離。"""

    # 参照元実装の JVRead の呼出し形: JVRead("", 110000, "") -> (ret, 文字列, ファイル名)。文字列は NUL 終端で、cp932 に再エンコードされる
    READ_BUFFER = 110_000

    def __init__(self, com: Any) -> None:
        self._com = com
        self._open = False

    def init(self, sid: str) -> int:
        return int(self._com.JVInit(sid))

    def open_stored(self, dataspec: str, fromtime: str, option: int) -> OpenResult:
        if self._open:
            raise RuntimeError("OPEN_WHILE_OPEN")
        r = self._com.JVOpen(dataspec, fromtime, option, 0, 0, "")  # 参照元実装の呼出し形（引数の意味は公式未確認）
        code, read_count, dl_count, last_ts = _unpack_open(r)
        self._open = code >= 0
        return OpenResult(code, read_count, dl_count, last_ts or None)

    def open_realtime(self, dataspec: str, key: str) -> OpenResult:
        if self._open:
            raise RuntimeError("OPEN_WHILE_OPEN")
        code = int(self._com.JVRTOpen(dataspec, key))
        self._open = code >= 0
        return OpenResult(code)

    def read(self) -> ReadResult:
        # JVGets（バイト取得）の呼出し形は参照元実装に無く、公式仕様も未確認（JVGETS_BYTES）なので、ここには実装しない。PoC の明示的な探索（poc/）で確認する
        return codes.interpret_read(*_unpack_read(self._com.JVRead("", self.READ_BUFFER, "")))

    def close(self) -> int:
        self._open = False
        return int(self._com.JVClose())


def _unpack_open(r: Any) -> tuple[int, int, int, str]:
    if isinstance(r, tuple) and len(r) >= 4:
        return int(r[0]), int(r[1]), int(r[2]), str(r[3] or "")
    return int(r), 0, 0, ""


def _unpack_read(r: Any) -> tuple[int, bytes | None, str | None]:
    # JVRead は文字列で返る（参照元実装は cp932 に再エンコード）。公式のバイト扱いは未確認（JVGETS_BYTES / RECORD_LAYOUT）
    if isinstance(r, tuple) and len(r) >= 3:
        code, text, fname = int(r[0]), r[1], str(r[2] or "").split("\0", 1)[0]
        if isinstance(text, str):
            # 往復一致（cp932 → 文字列 → cp932）は PoC の G-AGENT-2 で検査する。ここでは変換できなければ strict で例外にして欠損を隠さない
            return code, text.split("\0", 1)[0].encode("cp932", errors="strict"), fname
        return code, None, fname
    return int(r), None, None


__all__ = ["ComJvLink", "ReadStatus", "create_com_object", "shutdown_com"]
