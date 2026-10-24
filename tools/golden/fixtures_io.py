"""フィクスチャの組み立て・保存・読込・manifest。"""
import hashlib
import json
from pathlib import Path

from . import constants as C, jsonutil
from .scenarios import Case


def build_rules(case: Case):
    return {
        "default": {"float": {"mode": "abs_tol", "abs_tol": case.default_abs_tol}},
        "overrides": [dict(o) for o in case.overrides],
        "strict": {"types": True, "key_set": True, "list_order": True, "nonfinite": "reject"},
    }


def build_fixture(case: Case, expected_output):
    return {
        "fixture_id": f"golden/{case.case_id}/v1",
        "case_id": case.case_id,
        "schema_version": C.SCHEMA_VERSION,
        "source_repository": C.SOURCE_REPOSITORY,
        "source_commit": C.PINNED_COMMIT,
        "source_function": list(case.source_functions),
        "description": case.description,
        "scenario_ids": list(case.scenario_ids),
        "data_classification": "synthetic",
        "input": {"calls": case.calls},
        "expected_output": expected_output,
        "execution_parameters": {
            "seed": C.SEED,
            "fixed_time": C.FIXED_TIME,
            "clock": "not_used (対象関数は現在時刻を読まない。datetime.now を使う経路は対象外)",
            "timezone": C.TIMEZONE,
            "cwd": "empty temporary directory",
            "network": "blocked",
            "ml_cfg": (
                "per call (input.calls[].args.ml_cfg). 合成 SQLite + 合成モデルの pickle（サンドボックスの一時ディレクトリ。リポジトリには入らない）。"
                "datetime.now は固定時刻（input.calls[].args.now）" if getattr(case, "ml", False) else {"enabled": False}
            ),
            "libraries": {"numpy": C.PINNED_NUMPY},
        },
        "comparison_rules": build_rules(case),
        "known_defects": list(case.known_defects),
        "legacy_behavior_note": "expected_output は旧実装が実際に返した値（欠陥候補を含む）。新仕様の期待値ではない。",
        "generator": {"tool": "tools/golden", "tool_version": C.TOOL_VERSION},
    }


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def fixture_filename(case_id):
    return f"{case_id}.json"


def build_manifest(files: dict):
    """files: {filename: bytes}"""
    return {
        "schema_version": C.SCHEMA_VERSION,
        "source_repository": C.SOURCE_REPOSITORY,
        "source_commit": C.PINNED_COMMIT,
        "generator": {"tool": "tools/golden", "tool_version": C.TOOL_VERSION},
        "fixtures": [
            {"case_id": name[:-5], "path": name, "sha256": sha256_bytes(data)}
            for name, data in sorted(files.items())
        ],
    }


def render_all(fixtures: dict):
    """fixtures: {case_id: fixture dict} → {filename: bytes}（manifest 含む）"""
    files = {fixture_filename(cid): jsonutil.dumps(fx).encode("utf-8") for cid, fx in fixtures.items()}
    files[C.MANIFEST_NAME] = jsonutil.dumps(build_manifest(files)).encode("utf-8")
    return files


def load_dir(directory: Path):
    """directory の *.json（manifest 除く）を読む。{filename: (bytes, parsed)}"""
    out = {}
    for p in sorted(Path(directory).glob("*.json")):
        if p.name == C.MANIFEST_NAME:
            continue
        data = p.read_bytes()
        out[p.name] = (data, jsonutil.loads(data.decode("utf-8")))
    return out


def load_manifest(directory: Path):
    return jsonutil.loads((Path(directory) / C.MANIFEST_NAME).read_text(encoding="utf-8"))
