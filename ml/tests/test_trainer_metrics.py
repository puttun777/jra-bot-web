import warnings
from datetime import date, timedelta

import numpy as np
import pytest
from sklearn import metrics as skm

from ml_train import metrics as mx
from ml_train import trainer


def test_sample_weights_mean_one_and_halflife():
    dates = np.array([date(2026, 1, 1) + timedelta(days=i) for i in range(400)], dtype=object)
    for hl in (90, 180, 240):
        w = trainer.sample_weights(dates, hl)
        assert w.mean() == pytest.approx(1.0)
        assert w[-1] > w[0]  # 新しいほど重い
        raw_ratio = 0.5 ** (hl / hl)
        assert w[-1 - hl] / w[-1] == pytest.approx(raw_ratio)  # 半減期だけ前の重みは半分
    assert trainer.sample_weights(dates[:1], 90)[0] == pytest.approx(1.0)


def test_standardizer_handles_constant_features():
    x = np.array([[1.0, 5.0], [3.0, 5.0], [5.0, 5.0]])
    st = trainer.Standardizer.fit(x)
    assert st.std[1] == 1.0 and (st.apply(x)[:, 1] == 0).all()
    assert (st.std > 0).all()


def _toy(n=800, seed=0):
    rng = np.random.default_rng(seed)
    x = rng.normal(size=(n, 4))
    p = 1 / (1 + np.exp(-(1.5 * x[:, 0] - 0.5 * x[:, 1] - 2.0)))
    y = (rng.random(n) < p).astype(np.int64)
    return x, y


def test_train_is_reproducible_and_learns():
    x, y = _toy()
    w = np.ones(len(y))
    m1 = trainer.train_linear(x, y, w, l2_c=1.0, max_iter=500, tol=1e-8)
    m2 = trainer.train_linear(x, y, w, l2_c=1.0, max_iter=500, tol=1e-8)
    assert np.array_equal(m1.weights, m2.weights) and m1.bias == m2.bias
    assert m1.weights[0] > 0.8 > abs(m1.weights[2])
    assert mx.auc(y, m1.predict(x)) > 0.7


def test_non_convergence_is_an_error_not_silent():
    x, y = _toy()
    with pytest.raises(trainer.TrainingFailed) as e:
        trainer.train_linear(x, y, np.ones(len(y)), l2_c=1e6, max_iter=10, tol=1e-12)
    assert e.value.reason == "TRAINING_DID_NOT_CONVERGE"


def test_platt_recovers_a_known_miscalibration():
    rng = np.random.default_rng(3)
    p_true = rng.uniform(0.02, 0.5, size=20000)
    y = (rng.random(20000) < p_true).astype(np.int64)
    p_bad = np.clip(trainer.sigmoid(2.0 * trainer.logit(p_true) + 0.5), 1e-6, 1 - 1e-6)  # 過信 + 偏り
    a, b = trainer.fit_platt(p_bad, y)
    assert a == pytest.approx(0.5, abs=0.06) and b == pytest.approx(-0.25, abs=0.12)
    slope, _ = mx.calibration_slope_intercept(y, np.clip(trainer.sigmoid(a * trainer.logit(p_bad) + b), 1e-6, 1 - 1e-6))
    assert slope == pytest.approx(1.0, abs=0.05)


def test_metrics_match_scikit_learn():
    rng = np.random.default_rng(5)
    p = rng.uniform(0.01, 0.99, 500)
    y = (rng.random(500) < p).astype(np.int64)
    assert mx.logloss(y, p) == pytest.approx(skm.log_loss(y, p))
    assert mx.brier(y, p) == pytest.approx(skm.brier_score_loss(y, p))
    assert mx.auc(y, p) == pytest.approx(skm.roc_auc_score(y, p))
    tied = np.round(p, 1)  # 同順位
    assert mx.auc(y, tied) == pytest.approx(skm.roc_auc_score(y, tied))
    assert mx.auc(np.zeros(5, dtype=np.int64), np.arange(5.0)) is None


def test_market_baseline_and_normalization():
    race = np.array(["a", "a", "a", "b", "b"])
    odds = np.array([2.0, 4.0, 4.0, 1.5, 3.0])
    raw, norm = mx.market_probability(odds, race)
    assert raw[0] == pytest.approx(0.5)
    assert norm[:3].sum() == pytest.approx(1.0) and norm[3:].sum() == pytest.approx(1.0)
    y = np.array([1, 0, 0, 0, 1])
    m, unavailable = mx.evaluate(y, np.array([0.5, 0.2, 0.2, 0.3, 0.6]), race, odds)
    assert m["market_baseline_available"] and m["baseline_market_logloss"] > 0 and not unavailable.get("baseline_market_logloss")
    assert m["top1_hit_rate"] == pytest.approx(1.0)


def test_no_baseline_is_never_invented_without_odds():
    race = np.array(["a", "a", "b", "b"])
    m, unavailable = mx.evaluate(np.array([1, 0, 0, 1]), np.array([0.6, 0.4, 0.3, 0.7]), race, None)
    assert m["baseline_market_logloss"] is None and m["market_baseline_available"] is False
    assert "baseline_market_logloss" in unavailable


def test_distribution_and_bins():
    p = np.linspace(0.01, 0.9, 100)
    y = (p > 0.5).astype(np.int64)
    d = mx.distribution(p)
    assert d["quantiles"]["0"] == pytest.approx(0.01) and d["quantiles"]["1"] == pytest.approx(0.9)
    bins = mx.reliability_bins(y, p)
    assert len(bins) == 10 and sum(b["n"] for b in bins) == 100


def test_predict_matches_kotlin_arithmetic_clip():
    st = trainer.Standardizer(np.zeros(1), np.ones(1))
    m = trainer.LinearModel(st, np.array([1.0]), 0.0)
    p = m.predict(np.array([[1000.0], [-1000.0]]))
    assert p[0] == pytest.approx(1 - 1e-6) and p[1] == pytest.approx(1e-6)  # z は ±50 に収まり、さらに [1e-6, 1-1e-6]
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        m.with_platt(1.0, 0.0).predict(np.array([[3.0]]))
