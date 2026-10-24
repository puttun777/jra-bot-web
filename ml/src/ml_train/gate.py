"""品質ゲート。**数値の閾値は未承認で、既定値を持たない**（docs/UNRESOLVED.md）。明示した check だけを評価する。

- 設定が無い／check が 0 個 → NOT_CONFIGURED（fail closed。REJECTED）。暗黙の閾値で CANDIDATE にしない。
- 設定した check が評価できない（UNAVAILABLE。例: alpha に市場ベースラインが無い）→ 不合格（REJECTED）。架空の値で通さない。
- 判定は PASS（全 check が PASS）→ CANDIDATE、それ以外 → REJECTED。ACTIVE にはしない。
"""

from dataclasses import dataclass
from typing import Any

from .config import GateConfig


@dataclass(frozen=True)
class CheckResult:
    name: str
    status: str  # PASS | FAIL | UNAVAILABLE
    observed: float | None
    threshold: float | None
    detail: str = ""

    def to_json(self) -> dict[str, Any]:
        return {"name": self.name, "status": self.status, "observed": self.observed, "threshold": self.threshold, "detail": self.detail}


@dataclass(frozen=True)
class GateResult:
    verdict: str  # PASS | FAIL | NOT_CONFIGURED
    purpose: str
    checks: list[CheckResult]

    @property
    def passed(self) -> bool:
        return self.verdict == "PASS"

    @property
    def reasons(self) -> list[str]:
        if self.verdict == "NOT_CONFIGURED":
            return ["QUALITY_GATE_NOT_CONFIGURED"]
        return [f"{c.name}:{c.status}" for c in self.checks if c.status != "PASS"]

    def to_json(self) -> dict[str, Any]:
        return {"verdict": self.verdict, "purpose": self.purpose, "reasons": self.reasons, "checks": [c.to_json() for c in self.checks]}


def _le(name: str, observed: float | None, threshold: float, what: str) -> CheckResult:
    if observed is None:
        return CheckResult(name, "UNAVAILABLE", None, threshold, f"{what}を計算できない")
    return CheckResult(name, "PASS" if observed <= threshold else "FAIL", observed, threshold)


def _ge(name: str, observed: float | None, threshold: float, what: str) -> CheckResult:
    if observed is None:
        return CheckResult(name, "UNAVAILABLE", None, threshold, f"{what}を計算できない")
    return CheckResult(name, "PASS" if observed >= threshold else "FAIL", observed, threshold)


def evaluate(gate: GateConfig, facts: dict[str, Any]) -> GateResult:
    """[facts] は job が集めた観測値: n_train/n_train_pos/n_valid/n_valid_pos、eval の指標（metrics）、constant_feature_ratio、horse_master_coverage、
    active_logloss（直前の ACTIVE の logloss。無ければ None）。"""
    if not gate.configured:
        return GateResult("NOT_CONFIGURED", gate.purpose, [])
    m = facts["metrics"]
    results: list[CheckResult] = []
    for name in sorted(gate.checks):
        t = float(gate.checks[name])
        if name == "min_train_rows":
            results.append(_ge(name, facts["n_train"], t, "train 行数"))
        elif name == "min_train_positives":
            results.append(_ge(name, facts["n_train_pos"], t, "train の正例数"))
        elif name == "min_valid_rows":
            results.append(_ge(name, facts["n_valid"], t, "valid 行数"))
        elif name == "min_valid_positives":
            results.append(_ge(name, facts["n_valid_pos"], t, "valid の勝ち馬数"))
        elif name == "max_valid_logloss":
            results.append(_le(name, m["logloss"], t, "LogLoss"))
        elif name == "max_valid_brier":
            results.append(_le(name, m["brier"], t, "Brier"))
        elif name == "min_valid_auc":
            results.append(_ge(name, m["auc"], t, "AUC"))
        elif name == "max_logloss_minus_market":
            base = m.get("baseline_market_logloss")
            results.append(_le(name, None if base is None else m["logloss_race_normalized"] - base, t, "市場ベースラインとの LogLoss 差"))
        elif name == "calibration_slope_min":
            results.append(_ge(name, m["calibration_slope"], t, "校正傾き"))
        elif name == "calibration_slope_max":
            results.append(_le(name, m["calibration_slope"], t, "校正傾き"))
        elif name == "max_abs_calibration_intercept":
            ci = m["calibration_intercept"]
            results.append(_le(name, None if ci is None else abs(ci), t, "校正切片"))
        elif name == "max_constant_feature_ratio":
            results.append(_le(name, facts["constant_feature_ratio"], t, "定数特徴量の割合"))
        elif name == "min_horse_master_coverage":
            results.append(_ge(name, facts["horse_master_coverage"], t, "馬マスタのカバレッジ"))
        elif name == "max_logloss_regression_vs_active":
            act = facts.get("active_logloss")
            results.append(
                _le(name, None if act is None else m["logloss"] - act, t, "直前の ACTIVE との LogLoss 差（ACTIVE が無い/指標が無い）")
            )
        else:  # config が検証済みなので到達しない。未知の check を黙って通さない
            results.append(CheckResult(name, "UNAVAILABLE", None, t, "未対応の check"))
    verdict = "PASS" if all(c.status == "PASS" for c in results) else "FAIL"
    return GateResult(verdict, gate.purpose, results)
