from typing import Any

import pytest

from ml_train import gate as gate_mod
from ml_train.config import GateConfig, TrainConfig
from ml_train.errors import UsageError


def _facts(**over):
    m = {
        "logloss": 0.30,
        "brier": 0.08,
        "auc": 0.70,
        "logloss_race_normalized": 0.30,
        "baseline_market_logloss": 0.32,
        "calibration_slope": 1.02,
        "calibration_intercept": 0.03,
    }
    f = {
        "metrics": m,
        "n_train": 1000,
        "n_train_pos": 120,
        "n_valid": 300,
        "n_valid_pos": 40,
        "constant_feature_ratio": 0.2,
        "horse_master_coverage": 0.9,
        "active_logloss": None,
    }
    f.update(over)
    return f


def test_unconfigured_gate_is_fail_closed():
    r = gate_mod.evaluate(GateConfig.unconfigured(), _facts())
    assert r.verdict == "NOT_CONFIGURED" and not r.passed and r.reasons == ["QUALITY_GATE_NOT_CONFIGURED"]
    assert not gate_mod.evaluate(GateConfig("synthetic_test", {}), _facts()).passed  # check 0 個も同じ


def test_pass_and_fail_with_explicit_thresholds():
    checks = {
        "min_valid_rows": 100,
        "max_valid_logloss": 0.35,
        "min_valid_auc": 0.6,
        "max_logloss_minus_market": 0.0,
        "calibration_slope_min": 0.8,
        "calibration_slope_max": 1.2,
    }
    assert gate_mod.evaluate(GateConfig("synthetic_test", checks), _facts()).passed
    r = gate_mod.evaluate(GateConfig("synthetic_test", {**checks, "max_valid_logloss": 0.2}), _facts())
    assert not r.passed and r.reasons == ["max_valid_logloss:FAIL"]
    r = gate_mod.evaluate(GateConfig("synthetic_test", {"max_logloss_minus_market": -0.05}), _facts())
    assert r.checks[0].status == "FAIL"  # 0.30 - 0.32 = -0.02 > -0.05


def test_unverifiable_check_fails_instead_of_inventing_a_value():
    m = _facts()["metrics"] | {"baseline_market_logloss": None}
    r = gate_mod.evaluate(GateConfig("synthetic_test", {"max_logloss_minus_market": 0.0}), _facts(metrics=m))
    assert r.checks[0].status == "UNAVAILABLE" and not r.passed
    r = gate_mod.evaluate(GateConfig("synthetic_test", {"max_logloss_regression_vs_active": 0.01}), _facts())
    assert r.checks[0].status == "UNAVAILABLE"  # ACTIVE が無い
    r = gate_mod.evaluate(GateConfig("synthetic_test", {"max_logloss_regression_vs_active": 0.01}), _facts(active_logloss=0.295))
    assert r.passed


def test_gate_config_validation():
    ok = GateConfig.from_json({"purpose": "synthetic_test", "checks": {"min_valid_rows": 10}})
    assert ok.configured and len(ok.digest()) == 64
    bads: list[Any] = [
        [],
        {"purpose": "x", "checks": {}},
        {"purpose": "production", "checks": {"nope": 1}},
        {"purpose": "production", "checks": {"min_valid_rows": 1.5}},
        {"purpose": "production", "checks": {"min_valid_rows": True}},
        {"purpose": "production", "checks": {"min_valid_auc": 2}},
        {"purpose": "production", "checks": {"calibration_slope_min": 0.8}},
        {"purpose": "production", "checks": {"calibration_slope_min": 1.3, "calibration_slope_max": 1.2}},
        {"purpose": "production", "checks": {}, "extra": 1},
    ]
    for bad in bads:
        with pytest.raises(UsageError):
            GateConfig.from_json(bad)


def test_train_config_validation_and_legacy_compat_is_separate():
    c = TrainConfig.from_json({"kind": "calibration"})
    assert c.recency_halflife_days == 180 and c.mode == "v2"
    for bad in (
        {"kind": "x"},
        {"kind": "alpha", "unknown": 1},
        {"kind": "alpha", "recency_halflife_days": 100},
        {"kind": "alpha", "seed": True},
        {"kind": "alpha", "l2_c": 0},
        {"kind": "alpha", "calibration_fraction": 1.0},
        {"kind": "alpha", "market_blend_alpha_default": 1.5},
    ):
        with pytest.raises(UsageError):
            TrainConfig.from_json(bad)
    with pytest.raises(UsageError) as e:
        TrainConfig.from_json({"kind": "calibration", "mode": "legacy_compat"})
    assert e.value.reason == "LEGACY_COMPAT_TRAINING_NOT_IMPLEMENTED"
