"""Windows 固有の薄い層（DPAPI・プロセスの強制終了・ジョブオブジェクト）。**Windows 以外では import しても何も呼ばない**。

DPAPI は `crypt32.dll` を ctypes で呼ぶ（pywin32 不要）。ユーザーのログオン資格情報に結びつくので、**別ユーザーや別 PC では復号できない**。
実機でしか確認できないため、PoC（poc/）と Windows ランナーのテストで確認する（tests/test_winapi_windows.py）。
"""

from __future__ import annotations

import ctypes
import sys

from .errors import SecretError

IS_WINDOWS = sys.platform == "win32"
_CRYPTPROTECT_UI_FORBIDDEN = 0x01


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def _blob(data: bytes) -> tuple[_DataBlob, object]:
    buf = (ctypes.c_ubyte * len(data)).from_buffer_copy(data) if data else (ctypes.c_ubyte * 1)()
    return _DataBlob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_ubyte))), buf


class DpapiProtector:
    """CryptProtectData / CryptUnprotectData（現在のユーザーのスコープ。LOCAL_MACHINE は使わない）。"""

    def __init__(self) -> None:
        if not IS_WINDOWS:
            raise SecretError("DPAPI は Windows でだけ使える")

    def protect(self, plain: bytes, entropy: bytes) -> bytes:
        return self._call(plain, entropy, protect=True)

    def unprotect(self, blob: bytes, entropy: bytes) -> bytes:
        return self._call(blob, entropy, protect=False)

    @staticmethod
    def _call(data: bytes, entropy: bytes, protect: bool) -> bytes:
        windll = ctypes.windll  # type: ignore[attr-defined,unused-ignore]
        crypt32, kernel32 = windll.crypt32, windll.kernel32
        din, keep1 = _blob(data)
        ent, keep2 = _blob(entropy)
        out = _DataBlob()
        fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
        # 引数の並びは 2 つの関数で同じ: (入力, 説明, 追加エントロピー, 予約, UI, フラグ, 出力)
        ok = fn(ctypes.byref(din), None, ctypes.byref(ent), None, None, _CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(out))
        del keep1, keep2
        if not ok:
            raise SecretError("DPAPI の呼出しに失敗" if protect else "DPAPI で復号できない")
        try:
            return ctypes.string_at(out.pbData, out.cbData)
        finally:
            kernel32.LocalFree(out.pbData)


class _IoCounters(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("ReadOps", "WriteOps", "OtherOps", "ReadBytes", "WriteBytes", "OtherBytes")]


class _BasicLimit(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", ctypes.c_uint32),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", ctypes.c_uint32),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", ctypes.c_uint32),
        ("SchedulingClass", ctypes.c_uint32),
    ]


class _ExtendedLimit(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _BasicLimit),
        ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t),
    ]


_JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
_JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
_PROCESS_SET_QUOTA_TERMINATE = 0x0100 | 0x0001


class KillOnCloseJob:
    """Windows のジョブオブジェクト。**エージェントが落ちたとき、ジョブに入れたワーカー（と、その子プロセス）が OS によって確実に終了する**
    （COM ワーカーが孤児として残り、JV-Link を掴んだままにならない）。`close()` でも終了する。Windows 以外では何もしない（POSIX は呼び出し側が killpg）。"""

    def __init__(self) -> None:
        self._handle: int | None = None
        if not IS_WINDOWS:
            return
        k32 = ctypes.windll.kernel32  # type: ignore[attr-defined,unused-ignore]
        k32.CreateJobObjectW.restype = ctypes.c_void_p
        k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p]
        h = k32.CreateJobObjectW(None, None)
        if not h:
            raise OSError("ジョブオブジェクトを作れない")
        info = _ExtendedLimit()
        info.BasicLimitInformation.LimitFlags = _JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        k32.SetInformationJobObject.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        if not k32.SetInformationJobObject(h, _JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, ctypes.byref(info), ctypes.sizeof(info)):
            k32.CloseHandle(ctypes.c_void_p(h))
            raise OSError("ジョブオブジェクトを設定できない")
        self._handle = int(h)

    def assign(self, pid: int) -> bool:
        if not IS_WINDOWS or self._handle is None:
            return False
        k32 = ctypes.windll.kernel32  # type: ignore[attr-defined,unused-ignore]
        k32.OpenProcess.restype = ctypes.c_void_p
        k32.OpenProcess.argtypes = [ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32]
        proc = k32.OpenProcess(_PROCESS_SET_QUOTA_TERMINATE, 0, pid)
        if not proc:
            return False
        try:
            k32.AssignProcessToJobObject.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            return bool(k32.AssignProcessToJobObject(ctypes.c_void_p(self._handle), ctypes.c_void_p(proc)))
        finally:
            k32.CloseHandle.argtypes = [ctypes.c_void_p]
            k32.CloseHandle(ctypes.c_void_p(proc))

    def close(self) -> None:
        if IS_WINDOWS and self._handle is not None:
            k32 = ctypes.windll.kernel32  # type: ignore[attr-defined,unused-ignore]
            k32.CloseHandle.argtypes = [ctypes.c_void_p]
            k32.CloseHandle(ctypes.c_void_p(self._handle))
            self._handle = None
