"""合成データセット（Kotlin の `DatasetExporter` と同じ出力契約: ML_DESIGN §11.7）を Python で作る。実データ・実 DB は使わない。"""

import gzip
import hashlib
import json
import math
import pathlib
from datetime import date, timedelta
from typing import Any

import numpy as np

from tools.ml import canonical

RES = pathlib.Path(__file__).resolve().parents[2] / "domain" / "src" / "test" / "resources" / "ml"


def schema(kind: str) -> dict[str, Any]:
    name = {"calibration": "calibration40", "alpha": "alpha35"}[kind]
    return json.loads((RES / f"feature_schema.{name}.json").read_text())  # type: ignore[no-any-return]


def make_dataset(
    *,
    kind: str = "calibration",
    tenant_id: int = 1,
    dataset_id: str = "ds-synth-1",
    n_days: int = 24,
    races_per_day: int = 6,
    horses: int = 8,
    seed: int = 7,
    signal: float = 1.2,
    valid_fraction: float = 0.25,
    start: date = date(2026, 1, 3),
    one_class: bool = False,
    mutate: Any = None,
) -> tuple[bytes, bytes]:
    """(csv_gz, manifest_raw)。`mutate(rows, manifest)` で中身を変えたあと、ハッシュを整合させ直す。"""
    rng = np.random.default_rng(seed)
    sch = schema(kind)
    names: list[str] = sch["names"]
    rows: list[dict[str, Any]] = []
    for d in range(n_days):
        day = start + timedelta(days=d)
        for r in range(1, races_per_day + 1):
            s = rng.normal(size=horses)
            p_true = np.exp(signal * s)
            p_true /= p_true.sum()
            winner = int(rng.choice(horses, p=p_true)) if not one_class else -1
            p_mkt = np.exp(0.8 * s + rng.normal(scale=0.5, size=horses))
            p_mkt /= p_mkt.sum()
            odds = np.maximum(1.1, np.round(0.8 / p_mkt, 1))
            pop = (np.argsort(np.argsort(odds)) + 1).astype(float)
            for h in range(horses):
                vals: dict[str, float] = {
                    "odds": float(odds[h]), "popularity": float(pop[h]), "log_odds": math.log(max(float(odds[h]), 0.1)),
                    "inv_popularity": 1.0 / max(float(pop[h]), 1.0), "odds_popularity_interaction": float(odds[h] * pop[h]),
                    "has_horse_master": 1.0, "has_past_runs": 1.0, "age_years": float(3 + (h % 4)), "past_n": 5.0,
                    "past_win_rate": float(np.clip(0.1 + 0.05 * s[h] + rng.normal(scale=0.03), 0, 1)),
                    "past_place_rate": float(np.clip(0.3 + 0.08 * s[h] + rng.normal(scale=0.05), 0, 1)),
                    "past_avg_finish": float(5.0 - s[h] + rng.normal(scale=0.5)),
                    "days_since_last_run": float(14 + 7 * (h % 5)), "distance_km": 1.6 if r % 2 else 2.0, "is_turf": 1.0 if r % 2 else 0.0,
                    "is_dirt": 0.0 if r % 2 else 1.0, "distance_place_rate": float(np.clip(0.3 + 0.05 * s[h], 0, 1)),
                }  # fmt: skip
                for k in range(16):
                    vals[f"pedigree_hash_{k}"] = float(((h * 7 + k * 3) % 5) - 2) if k % 2 == 0 else 0.0
                rows.append(
                    {
                        "race_date": day, "race_id": f"{day:%Y%m%d}05{r:02d}", "umaban": h + 1,
                        "as_of": f"{day.isoformat()}T05:50:00Z", "values": [vals.get(n, 0.0) for n in names],
                        "label": 1 if h == winner else 0,
                    }
                )  # fmt: skip
    manifest_extra: dict[str, Any] = {}
    if mutate is not None:
        mutate(rows, manifest_extra)
    return _finalize(rows, sch, names, tenant_id, dataset_id, valid_fraction, manifest_extra)


def _finalize(
    rows: list[dict[str, Any]],
    sch: dict[str, Any],
    names: list[str],
    tenant_id: int,
    dataset_id: str,
    valid_fraction: float,
    extra: dict[str, Any],
) -> tuple[bytes, bytes]:
    rows.sort(key=lambda r: (r["race_date"], r["race_id"], r["umaban"]))
    dates = sorted({r["race_date"] for r in rows})
    n_train_days = max(1, min(len(dates) - 1, math.ceil(len(dates) * (1 - valid_fraction)))) if len(dates) > 1 else len(dates)
    train_dates = set(dates[:n_train_days])
    header = ["tenant_id", "race_id", "umaban", "race_date", "as_of", *names, "label", "split"]
    lines = [",".join(header)]
    for r in rows:
        split = "train" if r["race_date"] in train_dates else "valid"
        lines.append(
            ",".join(
                [
                    str(tenant_id),
                    r["race_id"],
                    str(r["umaban"]),
                    r["race_date"].isoformat(),
                    r["as_of"],
                    *[repr(float(v)) for v in r["values"]],
                    str(r["label"]),
                    split,
                ]
            )
        )
    text = ("\n".join(lines) + "\n").encode()
    n = len(rows)
    n_valid = sum(1 for r in rows if r["race_date"] not in train_dates)
    arr = np.array([r["values"] for r in rows]) if rows else np.zeros((0, len(names)))
    constant = [names[j] for j in range(len(names)) if n and bool((arr[:, j] == arr[0, j]).all())]
    manifest: dict[str, Any] = {
        "dataset_schema_version": 1, "dataset_id": dataset_id, "tenant_id": tenant_id, "created_at": "2026-02-01T00:00:00Z", "producer": "export-dataset/0",
        "feature_schema": sch, "columns": header,
        "csv": {"encoding": "utf-8", "newline": "lf", "compression": "gzip", "float_format": "python-repr", "sha256": hashlib.sha256(text).hexdigest(), "bytes": len(text)},
        "rows": n, "range": {"from": dates[0].isoformat(), "to": dates[-1].isoformat()},
        "label": {"definition": "finish == 1", "positives": sum(r["label"] for r in rows)},
        "as_of_policy": {"lead_seconds": 600, "odds": "latest snapshot with ts <= as_of"},
        "split": {
            "method": "time_ordered_by_race_date", "valid_fraction": valid_fraction, "n_train": n - n_valid, "n_valid": n_valid,
            "valid_starts_at": min((r["race_date"] for r in rows if r["race_date"] not in train_dates), default=None),
        },
        "builder": {"mode": "point_in_time", "master_timeline": "ZERO_CUMULATIVE_WHEN_NEWER", "legacy_compat_used": False},
        "quality": {
            "has_horse_master_ratio": 1.0, "has_past_runs_ratio": 1.0, "constant_features": constant,
            "flags": {"master_newer_than_as_of": 0, "odds_missing": 0, "source_returned_future_runs": 0},
            "skipped": {"as_of_not_before_post_time": 0, "no_odds_at_as_of": 0, "no_result": 0, "post_time_unknown": 0, "race_date_unknown": 0, "race_not_found": 0},
        },
    }  # fmt: skip
    if manifest["split"]["valid_starts_at"] is not None:
        manifest["split"]["valid_starts_at"] = manifest["split"]["valid_starts_at"].isoformat()
    manifest.update(extra)
    manifest["sha256"] = canonical.sha256_hex(manifest)
    return gzip.compress(text, mtime=0), canonical.dumps(manifest) + b"\n"
