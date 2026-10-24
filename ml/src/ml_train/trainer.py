"""v2 の学習: 標準化 + L2 ロジスティック回帰（lbfgs。決定的）+ Platt 校正 + 直近重み。

- 特徴量は Kotlin が作った行列をそのまま使う（Python で再計算しない）。
- 推論の算術は Kotlin の `LogisticScoring` と同じ（z を ±50 に収める → sigmoid → [1e-6, 1-1e-6] → Platt → [1e-6, 1-1e-6]）。
- `class_weight` は使わない（旧実装の H5: 事前確率の歪みを避ける。ML_DESIGN §4.2）。
- 乱数は使わない（lbfgs は決定的）。シードは記録のために保持し、BLAS のスレッド数は 1 に固定する。
"""

import warnings
from dataclasses import dataclass
from datetime import date

import numpy as np
from numpy.typing import NDArray
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from threadpoolctl import threadpool_limits

from .errors import MlTrainError

Z_LIMIT = 50.0
P_EPS = 1e-6


class TrainingFailed(MlTrainError):
    def __init__(self, reason: str, message: str) -> None:
        super().__init__(reason, message)


def sigmoid(z: NDArray[np.float64]) -> NDArray[np.float64]:
    return np.asarray(1.0 / (1.0 + np.exp(-np.clip(z, -Z_LIMIT, Z_LIMIT))), dtype=np.float64)


def logit(p: NDArray[np.float64]) -> NDArray[np.float64]:
    return np.asarray(np.log(p / (1.0 - p)), dtype=np.float64)


def sample_weights(race_dates: NDArray[np.object_], halflife_days: int) -> NDArray[np.float64]:
    """`w_i = 0.5 ** ((ref − race_date_i).days / halflife)`、ref = train の最大 race_date。train の平均 1 に正規化（ML_DESIGN §11.7）。"""
    if len(race_dates) == 0:
        raise ValueError("train が空")
    ref: date = max(race_dates)
    w = np.array([0.5 ** ((ref - d).days / halflife_days) for d in race_dates], dtype=np.float64)
    m = float(w.mean())
    if not np.isfinite(m) or m <= 0.0:
        raise ValueError("sample_weight が不正")
    return w / m


@dataclass(frozen=True)
class Standardizer:
    mean: NDArray[np.float64]
    std: NDArray[np.float64]

    @staticmethod
    def fit(x: NDArray[np.float64]) -> "Standardizer":
        mean = x.mean(axis=0)
        std = x.std(axis=0)
        # 定数特徴量（std = 0）は 1.0 にする（(x − mean) / 1 = 0。モデルの std は正の有限値が契約）
        std = np.where(std > 1e-12, std, 1.0)
        return Standardizer(mean=mean, std=std)

    def apply(self, x: NDArray[np.float64]) -> NDArray[np.float64]:
        return (x - self.mean) / self.std


@dataclass(frozen=True)
class LinearModel:
    standardizer: Standardizer
    weights: NDArray[np.float64]
    bias: float
    platt: tuple[float, float] | None = None

    def raw_probability(self, x: NDArray[np.float64]) -> NDArray[np.float64]:
        z = self.standardizer.apply(x) @ self.weights + self.bias
        return sigmoid(z)

    def predict(self, x: NDArray[np.float64]) -> NDArray[np.float64]:
        """モデルのみの確率（市場ブレンドなし。Kotlin の `postprocess` で alpha ≥ 0.999 のときと同じ）。"""
        p = np.clip(self.raw_probability(x), P_EPS, 1.0 - P_EPS)
        if self.platt is not None:
            a, b = self.platt
            p = np.clip(sigmoid(a * logit(p) + b), P_EPS, 1.0 - P_EPS)
        return p

    def with_platt(self, a: float, b: float) -> "LinearModel":
        return LinearModel(self.standardizer, self.weights, self.bias, (a, b))


def _fit_lr(
    x: NDArray[np.float64], y: NDArray[np.int64], c: float, max_iter: int, tol: float, w: NDArray[np.float64] | None
) -> LogisticRegression:
    lr = LogisticRegression(C=c, solver="lbfgs", max_iter=max_iter, tol=tol, fit_intercept=True)
    with warnings.catch_warnings():
        warnings.simplefilter("error", ConvergenceWarning)
        try:
            lr.fit(x, y, sample_weight=w)
        except ConvergenceWarning as e:
            raise TrainingFailed("TRAINING_DID_NOT_CONVERGE", "lbfgs が収束しなかった（max_iter を増やすか l2_c を見直す）") from e
        except (ValueError, FloatingPointError) as e:
            raise TrainingFailed("TRAINING_FAILED", f"学習に失敗: {type(e).__name__}") from e
    return lr


def train_linear(
    x: NDArray[np.float64], y: NDArray[np.int64], sample_weight: NDArray[np.float64], *, l2_c: float, max_iter: int, tol: float
) -> LinearModel:
    with threadpool_limits(limits=1):
        st = Standardizer.fit(x)
        lr = _fit_lr(st.apply(x), y, l2_c, max_iter, tol, sample_weight)
    w = np.asarray(lr.coef_[0], dtype=np.float64)
    b = float(lr.intercept_[0])
    if not (np.isfinite(w).all() and np.isfinite(b)):
        raise TrainingFailed("TRAINING_FAILED", "重みが有限でない")
    return LinearModel(st, w, b)


def fit_platt(p: NDArray[np.float64], y: NDArray[np.int64]) -> tuple[float, float]:
    """校正用データで `p' = sigmoid(a · logit(p) + b)` を最尤で当てる（ほぼ無正則化: C = 1e6）。"""
    with threadpool_limits(limits=1):
        z = logit(np.clip(p, P_EPS, 1.0 - P_EPS)).reshape(-1, 1)
        lr = _fit_lr(z, y, 1e6, 1000, 1e-10, None)
    a, b = float(lr.coef_[0][0]), float(lr.intercept_[0])
    if not (np.isfinite(a) and np.isfinite(b)):
        raise TrainingFailed("CALIBRATION_FAILED", "Platt の a・b が有限でない")
    return a, b
