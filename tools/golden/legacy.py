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
    ns.inspect = inspect
    return ns
