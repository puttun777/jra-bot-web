"""旧実装（jra-bot）の固定コミット検証とロード。参照元は読み取り専用で、書き込み・commit・push はしない。"""
import contextlib
import importlib
import inspect
import logging
import os
import random
import socket
import subprocess
import sys
import tempfile
import time
import types

from . import constants


class LegacyError(RuntimeError):
    pass


def _git(path, *args):
    try:
        r = subprocess.run(["git", "-C", str(path), *args], capture_output=True, text=True, check=False)
    except FileNotFoundError as e:
        raise LegacyError("git が見つかりません") from e
    if r.returncode != 0:
        raise LegacyError(f"git {' '.join(args)} が失敗: {r.stderr.strip()[:200]}")
    return r.stdout.strip()


def verify_checkout(path, expected=constants.PINNED_COMMIT):
    """想定外のコミット・未コミットの変更がある参照元は拒否する。代用や上書き許可の手段は用意しない。"""
    head = _git(path, "rev-parse", "HEAD")
    if head != expected:
        raise LegacyError(f"参照元のコミットが固定値と異なります: HEAD={head} 期待={expected}")
    if _git(path, "status", "--porcelain", "--untracked-files=no"):
        raise LegacyError("参照元の作業ツリーに未コミットの変更があります")
    return head


def _block_network(*_a, **_k):
    raise RuntimeError("ゴールデン生成中のネットワーク接続は禁止されています")


@contextlib.contextmanager
def sandbox():
    """空の作業ディレクトリ・固定 TZ・固定 seed・ネットワーク遮断・通知系環境変数の除去。"""
    import numpy as np  # 生成時のみ必要
    saved_env = dict(os.environ)
    saved_cwd = os.getcwd()
    saved_connect = socket.socket.connect
    saved_create = socket.create_connection
    with tempfile.TemporaryDirectory(prefix="golden-run-") as tmp:
        try:
            for k in list(os.environ):
                if any(s in k.upper() for s in ("DISCORD", "WEBHOOK", "TOKEN", "SECRET", "JRAVAN", "SERVICE_KEY")):
                    del os.environ[k]
            os.environ["TZ"] = constants.TIMEZONE
            time.tzset()
            os.chdir(tmp)  # ./models 等の相対パスが実ファイルに当たらないようにする
            random.seed(constants.SEED)
            np.random.seed(constants.SEED)
            socket.socket.connect = _block_network
            socket.create_connection = _block_network
            logging.disable(logging.CRITICAL)
            yield
        finally:
            logging.disable(logging.NOTSET)
            socket.socket.connect = saved_connect
            socket.create_connection = saved_create
            os.chdir(saved_cwd)
            os.environ.clear()
            os.environ.update(saved_env)
            time.tzset()


def _import_discord():
    """旧 `notify/discord.py` は import 時に `requests` を読む。ここでは**ネットワーク送信を一切行わない**（送信関数は呼ばず、本文の組み立てだけを検証する）ので、
    `requests` の代わりに最小のスタブを差し込んで import する（golden の依存を numpy だけに保つ）。スタブの `post` は呼ばれたら例外にする。"""
    class _RequestException(Exception):
        pass

    class _Timeout(_RequestException):
        pass

    def _post(*_a, **_k):
        raise RuntimeError("ゴールデン生成中のネットワーク送信は禁止されています")

    stub = types.ModuleType("requests")
    stub.exceptions = types.SimpleNamespace(Timeout=_Timeout, RequestException=_RequestException)
    stub.Response = object
    stub.post = _post
    saved = sys.modules.get("requests")
    sys.modules["requests"] = stub
    try:
        return importlib.import_module("jraev.notify.discord")
    finally:
        if saved is None:
            del sys.modules["requests"]
        else:
            sys.modules["requests"] = saved


def load(path):
    """検証済みチェックアウトから、計算に必要なモジュールだけを import する（DB・UI・通知・JV-Link は import しない）。"""
    verify_checkout(path)
    import numpy
    if numpy.__version__ != constants.PINNED_NUMPY:
        raise LegacyError(f"numpy {numpy.__version__} は固定版 {constants.PINNED_NUMPY} と異なります")
    sys.dont_write_bytecode = True  # 参照元ツリーに .pyc を残さない
    src = os.path.join(str(path), "src")
    if src not in sys.path:
        sys.path.insert(0, src)
    ns = types.SimpleNamespace()
    with sandbox():
        ns.ev_calc = importlib.import_module("jraev.ev.ev_calc")
        ns.probability = importlib.import_module("jraev.ev.probability")
        ns.decision = importlib.import_module("jraev.ev.decision")
        ns.bet_strategy = importlib.import_module("jraev.ev.bet_strategy")
        ns.features = importlib.import_module("jraev.ml.features")
        ns.model = importlib.import_module("jraev.ml.model")
        ns.models = importlib.import_module("jraev.data.models")
        ns.discord = _import_discord()  # Discord 通知の本文・分割・Retry-After の解釈（T3.7）。送信は呼ばない
        ns.db = importlib.import_module("jraev.storage.db")  # 合成 SQLite（特徴量 G6・ML 経路 G3〜G5）。ネットワーク・実 DB は使わない
    ns.inspect = inspect
    return ns
