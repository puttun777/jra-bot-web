"""データセットの検証つき読込み。**契約の検証は tools/ml/dataset_contract.py を再利用**（再実装しない）。検証に失敗したデータセットでは学習を始めない。

追加で確認すること（契約の外）: manifest のテナント・ID が引数と一致、gzip 展開後の大きさが manifest の宣言を超えない（展開爆弾）、
スキーマの種別（calibration=40/alpha=35。特徴量名の一覧は持たず、manifest の feature_schema から読む）、train/valid の構造的な十分さ。
"""

import csv
import gzip
import io
import zlib
from dataclasses import dataclass
from datetime import date
from typing import Any

import numpy as np
from numpy.typing import NDArray

from tools.ml import canonical, dataset_contract

from .errors import DatasetInsufficientError, DatasetInvalidError

MAX_CSV_BYTES = 1 << 30  # 1 GiB。manifest が宣言する非圧縮サイズの上限


@dataclass(frozen=True)
class Dataset:
    tenant_id: int
    dataset_id: str
    manifest: dict[str, Any]
    manifest_sha256: str
    csv_sha256: str
    schema_names: list[str]
    schema_hash: str
    kind: str
    race_id: NDArray[np.str_]
    umaban: NDArray[np.int64]
    race_date: NDArray[np.object_]  # datetime.date
    X: NDArray[np.float64]
    y: NDArray[np.int64]
    is_train: NDArray[np.bool_]

    @property
    def n_features(self) -> int:
        return len(self.schema_names)

    @property
    def has_odds(self) -> bool:
        return "odds" in self.schema_names


def kind_for_schema(names: list[str]) -> str:
    """スキーマの次元から種別を決める（calibration=40 で odds を含む / alpha=35 で含まない）。それ以外は未対応。"""
    if len(names) == 40 and names[0] == "odds":
        return "calibration"
    if len(names) == 35 and "odds" not in names:
        return "alpha"
    raise DatasetInvalidError(f"未対応の feature_schema（{len(names)} 次元）。学習できるのは calibration(40) と alpha(35)")


def _bounded_gunzip(csv_gz: bytes, declared: int) -> bytes:
    if declared > MAX_CSV_BYTES:
        raise DatasetInvalidError("manifest が宣言する CSV のサイズが上限を超えている")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(csv_gz)) as f:
            data = f.read(declared + 1)
    except (OSError, EOFError, zlib.error) as e:
        raise DatasetInvalidError("gzip として展開できない") from e
    if len(data) > declared:
        raise DatasetInvalidError("gzip の展開後サイズが manifest の宣言を超えている（展開爆弾の疑い）")
    return data


def load(csv_gz: bytes, manifest_raw: bytes, *, tenant_id: int, dataset_id: str) -> Dataset:
    try:
        manifest = dataset_contract.load_manifest(manifest_raw)
        csv_decl = manifest["csv"]["bytes"]
        if not isinstance(csv_decl, int) or csv_decl < 0:
            raise DatasetInvalidError("manifest.csv.bytes が不正")
        _bounded_gunzip(csv_gz, csv_decl)
        summary = dataset_contract.verify(csv_gz, manifest_raw)
    except dataset_contract.ContractError as e:
        raise DatasetInvalidError(str(e)) from e
    except (ValueError, KeyError, TypeError, UnicodeDecodeError) as e:
        raise DatasetInvalidError(f"データセットを読めない: {type(e).__name__}") from e

    if manifest["tenant_id"] != tenant_id:
        raise DatasetInvalidError("manifest の tenant_id が引数と違う（テナント越境）")
    if manifest["dataset_id"] != dataset_id:
        raise DatasetInvalidError("manifest の dataset_id が引数と違う")

    schema = manifest["feature_schema"]
    names: list[str] = schema["names"]
    kind = kind_for_schema(names)
    text = gzip.decompress(csv_gz).decode("utf-8")
    rows = list(csv.reader(io.StringIO(text)))
    body = rows[1:]
    n, d = len(body), len(names)
    f0 = len(dataset_contract.META_COLUMNS)
    if n != summary["rows"]:
        raise DatasetInvalidError("行数が一致しない")
    X = np.empty((n, d), dtype=np.float64)
    y = np.empty(n, dtype=np.int64)
    train = np.empty(n, dtype=np.bool_)
    race_id = np.empty(n, dtype=object)
    umaban = np.empty(n, dtype=np.int64)
    race_date = np.empty(n, dtype=object)
    for i, r in enumerate(body):
        X[i, :] = [float(v) for v in r[f0 : f0 + d]]
        y[i] = int(r[-2])
        train[i] = r[-1] == "train"
        race_id[i] = r[1]
        umaban[i] = int(r[2])
        race_date[i] = date.fromisoformat(r[3])
    if not np.isfinite(X).all():  # 契約の検証で済んでいるが、数値配列としても確認する（多重防御）
        raise DatasetInvalidError("特徴量に有限でない値がある")
    return Dataset(
        tenant_id=tenant_id,
        dataset_id=dataset_id,
        manifest=manifest,
        manifest_sha256=manifest["sha256"],
        csv_sha256=manifest["csv"]["sha256"],
        schema_names=names,
        schema_hash=schema["hash"],
        kind=kind,
        race_id=race_id.astype(np.str_),
        umaban=umaban,
        race_date=race_date,
        X=X,
        y=y,
        is_train=train,
    )


def require_trainable(ds: Dataset) -> None:
    """構造的に学習・評価できない入力を拒否する（閾値ではない。サンプル数の下限は品質ゲートの設定で決める）。"""
    tr, va = ds.is_train, ~ds.is_train
    if tr.sum() == 0 or va.sum() == 0:
        raise DatasetInsufficientError("train または valid が空")
    for name, mask in (("train", tr), ("valid", va)):
        pos = int(ds.y[mask].sum())
        if pos == 0 or pos == int(mask.sum()):
            raise DatasetInsufficientError(f"{name} が片方のクラスしか含まない（positives={pos}, rows={int(mask.sum())}）")
    if len({d for d in ds.race_date[va]}) < 2:
        raise DatasetInsufficientError("valid の日数が 2 日未満（校正用と評価用に分けられない）")


def dataset_summary(ds: Dataset) -> dict[str, Any]:
    q = ds.manifest["quality"]
    return {
        "dataset_id": ds.dataset_id,
        "csv_sha256": ds.csv_sha256,
        "manifest_sha256": ds.manifest_sha256,
        "rows": len(ds.y),
        "n_train": int(ds.is_train.sum()),
        "n_valid": int((~ds.is_train).sum()),
        "feature_schema_hash": ds.schema_hash,
        "range": ds.manifest["range"],
        "has_horse_master_ratio": q["has_horse_master_ratio"],
        "has_past_runs_ratio": q["has_past_runs_ratio"],
        "constant_features": q["constant_features"],
        "flags": q["flags"],
        "skipped": q["skipped"],
        "canonical_manifest_check": canonical.sha256_hex({k: v for k, v in ds.manifest.items() if k != "sha256"}) == ds.manifest_sha256,
    }
