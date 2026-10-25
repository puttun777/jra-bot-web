"""学習 Job の本体: データセット検証 → 学習 → 評価 → 品質ゲート → モデル JSON・署名・成果物保存 → レジストリ登録（CANDIDATE / REJECTED）。

不変条件（テストで確認）:
- 検証に失敗したデータセットでは学習も保存も登録もしない。
- 署名・sha256 の検証（書き込んだ成果物の読み戻し）に失敗したら登録しない。成果物はこの実行が作ったものだけ消す。
- 登録は CANDIDATE / REJECTED だけ。ACTIVE への昇格・既存行の更新/削除は無い。既存 ACTIVE は変わらない。
- model_id は (tenant, データセット, 設定, ゲート設定, 種別) から決定的に決まる。同じ入力の再実行は ALREADY_REGISTERED（冪等。重複行を作らない）。
- SIGTERM は確定（レジストリ登録）の前なら成果物を片付けて中断（exit 75）。登録後は完了させる。
"""

import hashlib
import logging
import math
import platform
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

import numpy as np
import scipy
import sklearn

from tools.ml import canonical

from . import PRODUCER, modeljson
from . import dataset as ds_mod
from . import gate as gate_mod
from . import metrics as mx
from .config import GateConfig, TrainConfig
from .errors import DatasetInsufficientError, DatasetInvalidError, MlTrainError, Terminated, TransientError, UsageError
from .logsetup import log
from .registry import DuplicateModel, NewModel, Registry
from .storage import ArtifactConflict, ArtifactStore, Written
from .trainer import fit_platt, sample_weights, train_linear


@dataclass
class JobDeps:
    store: ArtifactStore
    registry: Registry
    signing_seed: bytes
    verify_pubkeys: list[bytes]
    clock: Callable[[], datetime] = lambda: datetime.now(UTC)
    git_sha: str = "unknown"
    should_terminate: Callable[[], bool] = lambda: False


@dataclass(frozen=True)
class JobOutcome:
    reason: str  # CANDIDATE_REGISTERED | MODEL_REJECTED | ALREADY_REGISTERED
    model_id: str
    status: str
    gate_verdict: str | None = None


def derive_model_id(tenant_id: int, kind: str, csv_sha256: str, cfg: TrainConfig, gate: GateConfig) -> str:
    """決定的な model_id（`[A-Za-z0-9_-]{1,64}`）。"""
    key = canonical.dumps({"t": tenant_id, "k": kind, "d": csv_sha256, "c": cfg.to_json(), "g": gate.digest(), "p": PRODUCER})
    return f"m{kind[0]}-{hashlib.sha256(key).hexdigest()[:32]}"


def _check(deps: JobDeps) -> None:
    if deps.should_terminate():
        raise Terminated


def _libs() -> dict[str, str]:
    return {"python": platform.python_version(), "numpy": np.__version__, "scipy": scipy.__version__, "scikit-learn": sklearn.__version__}


def _split_valid(ds: ds_mod.Dataset, fraction: float) -> tuple[np.ndarray, np.ndarray]:
    """valid を日付順に校正用（前半）と評価用（後半）に分ける（同じ日は分けない）。校正に使ったデータで評価しない。"""
    valid_idx = np.flatnonzero(~ds.is_train)
    dates = sorted({ds.race_date[i] for i in valid_idx})
    k = min(max(1, math.ceil(len(dates) * fraction)), len(dates) - 1)
    calib_dates = set(dates[:k])
    mask = np.array([ds.race_date[i] in calib_dates for i in valid_idx])
    calib, ev = valid_idx[mask], valid_idx[~mask]
    for name, idx in (("校正用", calib), ("評価用", ev)):
        pos = int(ds.y[idx].sum())
        if pos == 0 or pos == len(idx):
            raise DatasetInsufficientError(f"valid の{name}部分が片方のクラスしか含まない（positives={pos}, rows={len(idx)}）")
    return calib, ev


def _verify_written(
    deps: JobDeps, tenant_id: int, model_id: str, raw: bytes, sig: bytes, row_sha: str, schema_hash: str, kind: str
) -> modeljson.VerifiedModelJson:
    stored = deps.store.read_model(tenant_id, model_id)
    if stored is None or stored[0] != raw or stored[1] != sig:
        raise modeljson.ModelVerificationError("MODEL_STORAGE_ERROR", "書き込んだ成果物を読み戻せない/内容が違う")
    return modeljson.verify_model_bytes(
        raw,
        sig,
        deps.verify_pubkeys,
        tenant_id=tenant_id,
        model_id=model_id,
        expected_kind=kind,
        expected_schema_hash=schema_hash,
        expected_sha256=row_sha,
    )


def run_train(tenant_id: int, dataset_id: str, cfg: TrainConfig, gate: GateConfig, deps: JobDeps) -> JobOutcome:
    # --- 1. データセット（検証に失敗したら以降を一切しない）
    blobs = deps.store.read_dataset(tenant_id, dataset_id)
    if blobs is None:
        raise DatasetInvalidError("データセットが見つからない（manifest が無い）")
    ds = ds_mod.load(blobs[1], blobs[0], tenant_id=tenant_id, dataset_id=dataset_id)
    log("dataset_verified", rows=len(ds.y), n_train=int(ds.is_train.sum()), schema=ds.schema_hash)
    if ds.kind != cfg.kind:
        raise UsageError("CONFIG_KIND_MISMATCH", f"設定の kind={cfg.kind} がデータセットの種別 {ds.kind} と違う")
    ds_mod.require_trainable(ds)
    _check(deps)

    # --- 2. 冪等性: 同じ入力で登録済みならそのまま返す（成果物の整合は確認する）
    model_id = derive_model_id(tenant_id, ds.kind, ds.csv_sha256, cfg, gate)
    existing = deps.registry.find(tenant_id, model_id)
    if existing is not None:
        stored = deps.store.read_model(tenant_id, model_id)
        if stored is None:
            raise MlTrainError("REGISTERED_ARTIFACT_MISSING", "登録済みなのに成果物が無い")
        modeljson.verify_model_bytes(
            stored[0], stored[1], deps.verify_pubkeys, tenant_id=tenant_id, model_id=model_id, expected_kind=ds.kind,
            expected_schema_hash=existing.feature_schema_hash, expected_sha256=existing.sha256,
        )  # fmt: skip
        log("already_registered", model_id=model_id, status=existing.status)
        return JobOutcome("ALREADY_REGISTERED", model_id, existing.status)

    # --- 3. 学習・校正・評価
    tr = np.flatnonzero(ds.is_train)
    calib_idx, eval_idx = _split_valid(ds, cfg.calibration_fraction)
    w = sample_weights(ds.race_date[tr], cfg.recency_halflife_days)
    base = train_linear(ds.X[tr], ds.y[tr], w, l2_c=cfg.l2_c, max_iter=cfg.max_iter, tol=cfg.tol)
    _check(deps)
    a, b = fit_platt(base.predict(ds.X[calib_idx]), ds.y[calib_idx])
    model = base.with_platt(a, b)
    odds = ds.X[eval_idx, ds.schema_names.index("odds")] if ds.has_odds else None
    metrics, unavailable = mx.evaluate(ds.y[eval_idx], model.predict(ds.X[eval_idx]), ds.race_id[eval_idx], odds)
    uncal = {
        "logloss": mx.logloss(ds.y[eval_idx], base.predict(ds.X[eval_idx])),
        "brier": mx.brier(ds.y[eval_idx], base.predict(ds.X[eval_idx])),
    }
    train_m = {"logloss": mx.logloss(ds.y[tr], model.predict(ds.X[tr])), "auc": mx.auc(ds.y[tr], model.predict(ds.X[tr]))}
    log("trained", n_train=len(tr), n_calibration=len(calib_idx), n_eval=len(eval_idx))
    _check(deps)

    # --- 4. 品質ゲート（設定が無ければ fail closed）
    active = deps.registry.active(tenant_id, ds.kind)
    active_logloss = None
    if active is not None and active.metrics and isinstance(active.metrics.get("logloss"), int | float):
        active_logloss = float(active.metrics["logloss"])
    facts = {
        "metrics": metrics,
        "n_train": len(tr), "n_train_pos": int(ds.y[tr].sum()),
        "n_valid": int((~ds.is_train).sum()), "n_valid_pos": int(ds.y[~ds.is_train].sum()),
        "constant_feature_ratio": mx.constant_feature_ratio(ds.X[tr]),
        "horse_master_coverage": float(ds.manifest["quality"]["has_horse_master_ratio"]),
        "active_logloss": active_logloss,
    }  # fmt: skip
    verdict = gate_mod.evaluate(gate, facts)
    status = "CANDIDATE" if verdict.passed else "REJECTED"
    log("gate_evaluated", verdict=verdict.verdict, status=status, reasons=verdict.reasons, purpose=gate.purpose)

    # --- 5. モデル JSON・署名・レポート
    now = deps.clock().astimezone(UTC)
    created_at = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    rng = ds.manifest["range"]
    training = {
        "mode": cfg.mode, "dataset_id": dataset_id, "dataset_sha256": ds.csv_sha256, "dataset_manifest_sha256": ds.manifest_sha256,
        "from": rng["from"], "to": rng["to"],
        "n_train": len(tr), "n_valid": int((~ds.is_train).sum()), "n_calibration": len(calib_idx), "n_eval": len(eval_idx),
        "seed": cfg.seed, "l2_c": cfg.l2_c, "max_iter": cfg.max_iter, "tol": cfg.tol, "class_weight": "none",
        "recency_halflife_days": cfg.recency_halflife_days, "calibration_fraction": cfg.calibration_fraction,
        "git_sha": deps.git_sha, "libs": _libs(), "gate_purpose": gate.purpose, "gate_digest": gate.digest(), "gate_verdict": verdict.verdict,
        "feature_schema_hash": ds.schema_hash,
    }  # fmt: skip
    model_metrics = {
        k: metrics[k]
        for k in (
            "n",
            "n_positive",
            "logloss",
            "brier",
            "auc",
            "logloss_race_normalized",
            "baseline_market_logloss",
            "calibration_slope",
            "calibration_intercept",
        )
    }
    model_metrics["gate_verdict"] = verdict.verdict
    doc = modeljson.build_model(
        model_id=model_id, tenant_id=tenant_id, kind=ds.kind, feature_schema=ds.manifest["feature_schema"],
        weights=list(model.weights), bias=model.bias, mean=list(model.standardizer.mean), std=list(model.standardizer.std),
        platt=(a, b), market_blend_alpha_default=cfg.market_blend_alpha_default, training=training, metrics=model_metrics,
        created_at=created_at, producer=PRODUCER,
    )  # fmt: skip
    raw = canonical.dumps(doc)
    sig = modeljson.sign(deps.signing_seed, raw)
    report = {
        "report_schema_version": 1, "model_id": model_id, "tenant_id": tenant_id, "kind": ds.kind, "status": status, "created_at": created_at,
        "config": cfg.to_json(), "gate": verdict.to_json(), "dataset": ds_mod.dataset_summary(ds),
        "metrics_eval": modeljson.clean(metrics), "metrics_eval_uncalibrated": modeljson.clean(uncal), "metrics_train": modeljson.clean(train_m),
        "unavailable_metrics": unavailable, "platt": {"a": modeljson._f(a), "b": modeljson._f(b)},
        "active_reference": {"model_id": active.model_id if active else None, "logloss": active_logloss},
        "limitations": [
            "品質ゲートの本番閾値は未承認。purpose=synthetic_test の結果は本番採用の根拠にならない",
            "市場ベースラインはデータセットに含まれる行だけでレース内正規化した近似",
            "実データでの精度は未検証",
        ],
        "libs": _libs(), "producer": PRODUCER,
    }  # fmt: skip
    report_bytes = canonical.dumps(modeljson.clean(report)) + b"\n"
    row_sha = doc["sha256"]
    # 自分が署名したものを検証できなければ保存も登録もしない
    modeljson.verify_model_bytes(
        raw,
        sig,
        deps.verify_pubkeys,
        tenant_id=tenant_id,
        model_id=model_id,
        expected_kind=ds.kind,
        expected_schema_hash=ds.schema_hash,
        expected_sha256=row_sha,
    )
    _check(deps)

    # --- 6. 保存 → 読み戻し検証 → 登録（失敗したらこの実行が作ったものだけ消す）
    written = Written()
    try:
        deps.store.write_model(tenant_id, model_id, raw, sig, report_bytes, written)
        verified = _verify_written(deps, tenant_id, model_id, raw, sig, row_sha, ds.schema_hash, ds.kind)
        _check(deps)  # 確定（登録）の前なら中断できる
        deps.registry.insert(
            NewModel(
                tenant_id=tenant_id, model_id=model_id, kind=ds.kind, schema_version=1, feature_schema_hash=ds.schema_hash,
                artifact_uri=deps.store.artifact_uri(tenant_id, model_id), sha256=verified.sha256, signature_sha256=verified.signature_sha256,
                status=status, trained_from=rng["from"], trained_to=rng["to"], n_train=len(tr),
                metrics=modeljson.clean({**model_metrics, "gate_reasons": verdict.reasons, "gate_purpose": gate.purpose}), created_at=now,
            )
        )  # fmt: skip
    except BaseException as e:
        failed = deps.store.cleanup(written)
        log("cleanup", level=logging.WARNING, removed=len(written.created) == 0, leftovers=len(failed), cause=type(e).__name__)
        if isinstance(e, DuplicateModel):
            raise MlTrainError("MODEL_ALREADY_REGISTERED", "並行して同じモデルが登録された") from e
        if isinstance(e, ArtifactConflict | MlTrainError | TransientError):
            raise
        if isinstance(e, Exception):
            raise MlTrainError("TRAIN_JOB_FAILED", f"想定外の失敗: {type(e).__name__}") from e
        raise
    log("registered", model_id=model_id, status=status)
    return JobOutcome("CANDIDATE_REGISTERED" if status == "CANDIDATE" else "MODEL_REJECTED", model_id, status, verdict.verdict)
