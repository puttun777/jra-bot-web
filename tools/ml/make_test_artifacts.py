"""モデル成果物のテスト用フィクスチャ（合成）を決定的に生成する。実データ・実モデル・本番鍵は使わない。

  python3 -m tools.ml.make_test_artifacts --out domain/src/test/resources/ml [--check]

- `feature_schema.*.json` は Kotlin（唯一の定義）が書いたものを**読む**（名前の一覧をここに再実装しない）。
- 署名はテスト専用の決定的な鍵（`signing.py`）。Ed25519 は決定的なので、再生成すると同じバイト列になる（`--check` で確認、CI が実行）。
- 正常系のほか、`models/` に『署名は有効だが中身が不正』の成果物（sha256 不一致・次元不一致など）を作る。これらは Kotlin の `ModelLoader` が理由コードつきで拒否することを確認するための
  新設計の仕様テスト（G7）の入力で、旧実装の出力ではない。
"""
import argparse
import json
import pathlib
import sys

from . import canonical, signing

CREATED_AT = "2026-10-24T00:00:00Z"
PRODUCER = "ml-train/test-fixture"


def _weights(n: int, salt: int) -> list:
    return [round((((i * 37 + salt) % 19) - 9) / 40.0, 6) for i in range(n)]


def _model(schema: dict, *, model_id: str, kind: str, bias: float, calibration, tenant_id: int = 1, preprocessing=None, weights=None) -> dict:
    w = _weights(len(schema["names"]), len(model_id)) if weights is None else weights
    m = {
        "model_schema_version": 1,
        "model_id": model_id,
        "tenant_id": tenant_id,
        "kind": kind,
        "algorithm": "logistic_regression",
        "feature_schema": schema,
        "preprocessing": preprocessing,
        "weights": w,
        "bias": bias,
        "calibration": calibration,
        "market_blend": {"alpha_default": 0.25},
        "ensemble": None,
        "training": {"from": "2025-01-17", "to": "2026-01-17", "n_train": 1000, "n_valid": 200, "seed": 42, "synthetic": True},
        "metrics": {"logloss": 0.25, "brier": 0.07, "auc": 0.7, "n": 200, "baseline_market_logloss": 0.26},
        "created_at": CREATED_AT,
        "producer": PRODUCER,
        "sha256": "0" * 64,
    }
    m["sha256"] = canonical.model_sha256(m)
    return m


def _ensemble(*, model_id: str, cal: str, alpha: str) -> dict:
    m = {
        "model_schema_version": 1,
        "model_id": model_id,
        "tenant_id": 1,
        "kind": "ensemble_set",
        "algorithm": "ensemble_set",
        "feature_schema": None,
        "preprocessing": None,
        "weights": None,
        "bias": None,
        "calibration": None,
        "market_blend": {"alpha_default": 0.25},
        "ensemble": {"members": {"calibration": cal, "alpha": alpha}, "beta": 0.35, "coverage_weights": [0.5, 0.3, 0.2]},
        "training": {"synthetic": True},
        "metrics": {"n": 200},
        "created_at": CREATED_AT,
        "producer": PRODUCER,
        "sha256": "0" * 64,
    }
    m["sha256"] = canonical.model_sha256(m)
    return m


def build(resources: pathlib.Path) -> dict:
    """{相対パス: バイト列}。"""
    def schema(name):
        return json.loads((resources / f"feature_schema.{name}.json").read_bytes())

    cal40, alpha35, market5 = schema("calibration40"), schema("alpha35"), schema("market5")
    for s in (cal40, alpha35, market5):
        assert canonical.feature_schema_hash(s) == s["hash"], "feature_schema の hash が内容と一致しない"
    platt = {"type": "platt", "a": 1.1, "b": -0.2}

    models = {
        "cal_test_001": _model(cal40, model_id="cal_test_001", kind="calibration", bias=-1.5, calibration=platt),
        "alpha_test_001": _model(alpha35, model_id="alpha_test_001", kind="alpha", bias=-2.0, calibration=None),
        "legacy_test_001": _model(
            market5, model_id="legacy_test_001", kind="legacy_compat", bias=-1.5, calibration=platt, weights=[0.01, -0.05, -0.3, 1.2, -0.0002]
        ),
        "cal_v2_001": _model(
            cal40,
            model_id="cal_v2_001",
            kind="calibration",
            bias=-1.0,
            calibration=platt,
            preprocessing={"type": "standardize", "mean": [float(i % 7) for i in range(40)], "std": [1.0 + (i % 5) * 0.5 for i in range(40)]},
        ),
        "ens_test_001": _ensemble(model_id="ens_test_001", cal="cal_test_001", alpha="alpha_test_001"),
    }
    good = models["cal_test_001"]

    def variant(**changes):
        m = json.loads(json.dumps(good))
        for k, v in changes.items():
            m[k] = v
        return m

    bad = {}
    # --- 署名は有効だが中身が不正（Kotlin の ModelLoader が理由コードつきで拒否する） ---
    b = variant(sha256="f" * 64)
    bad["bad_sha256"] = (b, "MODEL_HASH_INVALID")
    b = variant(weights=good["weights"][:39])
    b["sha256"] = canonical.model_sha256(b)
    bad["dim_mismatch"] = (b, "INVALID_MODEL_DIMENSION")
    b = variant(feature_schema=dict(good["feature_schema"], hash="sha256:" + "0" * 64))
    b["sha256"] = canonical.model_sha256(b)
    bad["schema_hash_wrong"] = (b, "FEATURE_SCHEMA_MISMATCH")
    b = variant(kind="alpha")  # alpha なのに 40 次元のスキーマ
    b["sha256"] = canonical.model_sha256(b)
    bad["alpha_with_40"] = (b, "FEATURE_SCHEMA_MISMATCH")
    b = variant(tenant_id=2)
    b["sha256"] = canonical.model_sha256(b)
    bad["wrong_tenant"] = (b, "MODEL_INVALID")
    b = variant(model_schema_version=2)
    b["sha256"] = canonical.model_sha256(b)
    bad["version2"] = (b, "MODEL_SCHEMA_MISMATCH")
    b = variant(extra_key="x")
    b["sha256"] = canonical.model_sha256(b)
    bad["unknown_key"] = (b, "MODEL_INVALID")
    b = variant(algorithm="random_forest")
    b["sha256"] = canonical.model_sha256(b)
    bad["wrong_algorithm"] = (b, "MODEL_INVALID")

    out = {}
    expected = {"models": {}, "feature_schemas": {"calibration40": cal40["hash"], "alpha35": alpha35["hash"], "market5": market5["hash"]}}
    seed = signing.TEST_SEED_PRIMARY
    for name, m in models.items():
        raw = canonical.dumps(m)
        out[f"models/{name}.json"] = raw
        out[f"models/{name}.sig"] = signing.sign(seed, raw)
        expected["models"][name] = {"sha256": m["sha256"], "kind": m["kind"], "feature_schema_hash": (m["feature_schema"] or {}).get("hash")}
    for name, (m, reason) in bad.items():
        raw = canonical.dumps(m)
        out[f"models/{name}.json"] = raw
        out[f"models/{name}.sig"] = signing.sign(seed, raw)
        expected["models"][name] = {"reject": reason}
    # 正準形でない（整形された）JSON。署名と sha256 は内容として有効
    pretty = json.dumps(good, sort_keys=True, indent=2, ensure_ascii=False).encode()
    out["models/non_canonical.json"] = pretty
    out["models/non_canonical.sig"] = signing.sign(seed, pretty)
    expected["models"]["non_canonical"] = {"reject": "MODEL_INVALID"}
    # JSON として不正な定数（NaN）/ 範囲外の数値（1e999）を含む。Python の json.dumps(allow_nan=False) では作れないので文字列で作る
    base = canonical.dumps(good).decode()
    first = json.dumps(good["weights"][0])
    for name, token in (("nan_weight", "NaN"), ("inf_weight", "1e999")):
        text = base.replace('"weights":[' + first, '"weights":[' + token, 1).encode()
        assert text != base.encode()
        out[f"models/{name}.json"] = text
        out[f"models/{name}.sig"] = signing.sign(seed, text)
        expected["models"][name] = {"reject": "MODEL_INVALID"}
    # 署名の問題
    raw = canonical.dumps(good)
    out["models/signed_by_other_key.json"] = raw
    out["models/signed_by_other_key.sig"] = signing.sign(signing.TEST_SEED_OTHER, raw)
    expected["models"]["signed_by_other_key"] = {"reject": "MODEL_SIGNATURE_INVALID"}
    out["models/zero_signature.json"] = raw
    out["models/zero_signature.sig"] = bytes(64)
    expected["models"]["zero_signature"] = {"reject": "MODEL_SIGNATURE_INVALID"}
    expected["public_key_primary"] = signing.public_key_b64(signing.TEST_SEED_PRIMARY)
    expected["public_key_other"] = signing.public_key_b64(signing.TEST_SEED_OTHER)
    out["expected.json"] = canonical.dumps(expected) + b"\n"
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--check", action="store_true", help="書き込まず、コミット済みと一致するか確認する")
    args = ap.parse_args(argv)
    root = pathlib.Path(args.out)
    files = build(root)
    bad = []
    for rel, data in sorted(files.items()):
        p = root / rel
        if args.check:
            if not p.exists() or p.read_bytes() != data:
                bad.append(rel)
        else:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
    if args.check:
        extra = sorted(str(p.relative_to(root)) for p in (root / "models").glob("*") if str(p.relative_to(root)) not in files)
        if bad or extra:
            print(f"不一致: {bad} 余分: {extra}", file=sys.stderr)
            return 1
        print(f"一致: {len(files)} ファイル")
    else:
        print(f"生成: {len(files)} ファイル")
    return 0


if __name__ == "__main__":
    sys.exit(main())
