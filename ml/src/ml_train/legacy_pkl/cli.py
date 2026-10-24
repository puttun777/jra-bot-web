"""`ml-train convert-pkl`: **利用者本人のローカル PC 専用**（クラウドでは実行しない。実 pkl をクラウドに置かない）。

python -m ml_train convert-pkl --pkl OLD.pkl --out-dir OUT --tenant-id 1 --model-id legacy-001 --schema-dir domain/src/test/resources/ml --confirm-own-local-file
（MODEL_SIGNING_KEY: ローカルの鍵。出力は OUT/{model_id}.json と .sig と .conversion.json。リポジトリ・クラウドに置かない）
"""

import argparse
from collections.abc import Mapping
from datetime import UTC, datetime
from pathlib import Path

from tools.ml import canonical

from .. import modeljson
from ..errors import ExitCode, UsageError
from ..logsetup import log
from .convert import ConversionRefused, KnownSchemas, convert, report_json, sha256_of
from .unpickle import UnpickleRefused, load_legacy_model


def add_arguments(c: argparse.ArgumentParser) -> None:
    c.add_argument("--schema-dir", default="domain/src/test/resources/ml", help="Kotlin が書いた feature_schema.*.json の置き場")
    c.add_argument("--confirm-own-local-file", action="store_true", help="自分の PC の自分のファイルを、ローカルで読むことを確認する")


def run(args: argparse.Namespace, env: Mapping[str, str]) -> int:
    if not getattr(args, "confirm_own_local_file", False):
        raise UsageError("CONFIRMATION_REQUIRED", "--confirm-own-local-file が必要（利用者本人のローカルのファイルだけを読む）")
    if env.get("APP_ENV") == "prod" or env.get("OPS_MODE_SOURCE") or env.get("K_SERVICE") or env.get("CLOUD_RUN_JOB"):
        raise UsageError("LOCAL_ONLY", "クラウドの実行環境では pkl を読まない（convert-pkl は利用者のローカル専用）")
    if args.tenant_id < 1:
        raise UsageError("USAGE", "--tenant-id は 1 以上")
    if not modeljson.MODEL_ID_RE.fullmatch(args.model_id):
        raise UsageError("USAGE", "--model-id の形式が不正")
    seed = modeljson.load_signing_seed(env.get("MODEL_SIGNING_KEY"))
    pkl = Path(args.pkl)
    try:
        data = pkl.read_bytes()
    except OSError as e:
        raise UsageError("PKL_UNREADABLE", "pkl を読めない") from e
    try:
        shell = load_legacy_model(data)
    except UnpickleRefused as e:
        raise ConversionRefused(f"PICKLE_{e.reason}", str(e)) from e
    created_at = args.created_at or datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    conv = convert(
        shell, schemas=KnownSchemas.load(Path(args.schema_dir)), model_id=args.model_id, tenant_id=args.tenant_id,
        created_at=created_at, source_sha256=sha256_of(data),
    )  # fmt: skip
    raw = canonical.dumps(conv.model)
    sig = modeljson.sign(seed, raw)
    # 変換結果の検証: Kotlin の ModelLoader と同じ契約で読み戻す
    modeljson.verify_model_bytes(
        raw, sig, [modeljson.public_key_of(seed)], tenant_id=args.tenant_id, model_id=args.model_id, expected_kind=conv.model["kind"]
    )
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, content in (
        (f"{args.model_id}.json", raw),
        (f"{args.model_id}.sig", sig),
        (f"{args.model_id}.conversion.json", (report_json(conv) + "\n").encode()),
    ):
        target = out / name
        try:
            with target.open("xb") as f:  # 上書きしない
                f.write(content)
        except FileExistsError as e:
            raise UsageError("OUTPUT_EXISTS", "出力ファイルが既にある（上書きしない）") from e
    for w in conv.warnings:
        log("convert_warning", message=w)
    log(
        "JOB_RESULT",
        exit_code=ExitCode.OK,
        reason="CONVERTED",
        model_id=args.model_id,
        kind=conv.model["kind"],
        dims=len(conv.model["weights"]),
    )
    return ExitCode.OK
