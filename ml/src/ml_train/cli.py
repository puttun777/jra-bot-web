"""ml-train の CLI。

  python -m ml_train train --tenant-id N --dataset-id ID --config train.json [--gate gate.json]
  python -m ml_train convert-pkl --pkl OLD.pkl --out-dir DIR --tenant-id N ...   # 利用者のローカルだけ（docs/ML_DESIGN.md §11.9）

環境変数: APP_ENV(local|prod)、OPS_MODE_SOURCE、MODEL_STORE_URI、MODEL_SIGNING_KEY、MODEL_VERIFY_PUBKEY（任意）、DB_URL / DB_USER / DB_PASSWORD、GIT_SHA（任意）。
終了コード（CONTRACTS §9.1）: 0 正常（REJECTED の登録を含む）／1 失敗／2 引数・設定の誤り／75 一時的な失敗（保存先・DB 不達、モード UNKNOWN、SIGTERM）。
標準出力に構造化ログ（JSON 1 行）と最後に `JOB_RESULT`。秘密・成果物の内容は出さない。
"""

import argparse
import logging
import os
import re
import sys
from collections.abc import Mapping, Sequence
from typing import NoReturn

from . import modeljson, sigterm
from .config import GateConfig, TrainConfig, load_json_file
from .errors import ExitCode, MlTrainError, UsageError
from .job import JobDeps, run_train
from .logsetup import log, setup
from .ops import read_mode
from .registry import MySqlRegistry
from .storage import ArtifactStore, open_store


class _Args(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        raise UsageError("USAGE", message)


def _parser() -> argparse.ArgumentParser:
    p = _Args(prog="ml-train", description="JRA Bot ML 学習 Job")
    sub = p.add_subparsers(dest="command", required=True)
    t = sub.add_parser("train", help="データセットから学習し、CANDIDATE / REJECTED を登録する（ACTIVE にはしない）")
    t.add_argument("--tenant-id", required=True, type=int)
    t.add_argument("--dataset-id", required=True)
    t.add_argument("--config", required=True, help="学習設定 JSON")
    t.add_argument("--gate", help="品質ゲート設定 JSON（無ければ fail closed: REJECTED）")
    c = sub.add_parser("convert-pkl", help="旧 pkl → モデル JSON（利用者のローカル専用。クラウドでは実行しない）")
    c.add_argument("--pkl", required=True)
    c.add_argument("--out-dir", required=True)
    c.add_argument("--tenant-id", required=True, type=int)
    c.add_argument("--model-id", required=True)
    c.add_argument("--created-at", help="ISO-8601 UTC（省略時は現在時刻）")
    from .legacy_pkl.cli import add_arguments

    add_arguments(c)
    return p


class Terminator:
    """SIGTERM の受信状態（実体は [sigterm]。`python -m ml_train` ではプロセスの最初に install 済み）。"""

    @property
    def requested(self) -> bool:
        return sigterm.is_set()

    def install(self) -> None:
        sigterm.install()


def _result(code: int, reason: str, **fields: object) -> int:
    log("JOB_RESULT", level=logging.INFO if code == 0 else logging.ERROR, exit_code=code, reason=reason, **fields)
    return code


def main(argv: Sequence[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    setup()
    env = os.environ if env is None else env
    try:
        args = _parser().parse_args(list(argv) if argv is not None else None)
        if args.command == "train":
            return _train(args, env)
        if args.command == "convert-pkl":
            from .legacy_pkl.cli import run as convert_run

            return convert_run(args, env)
        raise UsageError("USAGE", "不明なコマンド")
    except MlTrainError as e:
        return _result(e.exit_code, e.reason)
    except SystemExit:
        raise
    except Exception as e:
        return _result(ExitCode.FAILURE, "UNEXPECTED_ERROR", error=type(e).__name__)


def _train(args: argparse.Namespace, env: Mapping[str, str]) -> int:
    if args.tenant_id < 1:
        raise UsageError("USAGE", "--tenant-id は 1 以上")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.dataset_id):
        raise UsageError("USAGE", "--dataset-id の形式が不正")
    cfg = TrainConfig.from_json(load_json_file(args.config, "学習設定"))
    gate = GateConfig.from_json(load_json_file(args.gate, "品質ゲート設定")) if args.gate else GateConfig.unconfigured()
    app_env = env.get("APP_ENV")
    # 設定の検証は DB・保存先に触れる前に済ませる。モードも DB に接続する前に判定する
    seed = modeljson.load_signing_seed(env.get("MODEL_SIGNING_KEY"))
    pubkeys = modeljson.parse_pubkeys(env.get("MODEL_VERIFY_PUBKEY"))
    own = modeljson.public_key_of(seed)
    if pubkeys and own not in pubkeys:
        raise UsageError(
            "SIGNING_KEY_NOT_TRUSTED", "MODEL_SIGNING_KEY の公開鍵が MODEL_VERIFY_PUBKEY に無い（bot が検証できない署名になる）"
        )
    blob, prefix = open_store(env.get("MODEL_STORE_URI"))
    for name in ("DB_URL", "DB_USER", "DB_PASSWORD"):
        if not env.get(name):
            raise UsageError("DB_ENV_MISSING", f"{name} が未設定")
    if app_env == "prod" and env["DB_USER"] != "mltrain":
        raise UsageError("DB_USER_INVALID", "本番の DB_USER は mltrain（最小権限）")
    mode = read_mode(env.get("OPS_MODE_SOURCE"), app_env)
    if mode == "UNKNOWN":
        return _result(ExitCode.TEMPFAIL, "MODE_UNKNOWN")
    if mode != "NORMAL":
        return _result(ExitCode.OK, f"MODE_{mode}")

    term = Terminator()
    term.install()
    registry = MySqlRegistry(env["DB_URL"], env["DB_USER"], env["DB_PASSWORD"])
    try:
        deps = JobDeps(
            store=ArtifactStore(blob, prefix), registry=registry, signing_seed=seed, verify_pubkeys=pubkeys or [own],
            git_sha=env.get("GIT_SHA") or env.get("RELEASE_ID") or "unknown", should_terminate=lambda: term.requested,
        )  # fmt: skip
        outcome = run_train(args.tenant_id, args.dataset_id, cfg, gate, deps)
    finally:
        registry.close()
    return _result(ExitCode.OK, outcome.reason, model_id=outcome.model_id, status=outcome.status, gate=outcome.gate_verdict)


if __name__ == "__main__":
    sys.exit(main())
