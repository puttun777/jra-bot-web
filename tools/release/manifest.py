"""release-manifest（CONTRACTS §10）の検証と生成。

- 本番用 `release-manifest.json`（スキーマ `docs/schemas/release-manifest.v1.json`）: registry の digest 付き参照だけを受け付ける。
  タグだけ・ローカル image ID・仮値・未生成のイメージ・未検証のロールバック範囲は拒否する。
- ドライラン成果物 `manifest-preview.json`（スキーマ `release-manifest-preview.v1.json`）: push していないので digest が無い。
  `deployable: false` と `blockers` を必ず持ち、本番スキーマには適合しない。
生成は決定的（同じ入力から同じバイト列）。built_at は入力（コミット時刻）で、実行時刻を使わない。
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from . import jsonschema_lite, repo_facts

SCHEMA_DIR = Path("docs") / "schemas"
PRODUCTION_SCHEMA = "release-manifest.v1.json"
PREVIEW_SCHEMA = "release-manifest-preview.v1.json"
ROLLBACK_REGISTRY = Path("docs") / "release" / "rollback-verified.json"
SCHEMA_VERSION = 1
AGENT_PROTOCOL = 1
MODEL_SCHEMA_VERSIONS = [1]  # ML_DESIGN §5.2: model_schema_version 1
SUPPORTED_CONTRACT_PREFIX = "0.4."  # スキーマの contract_version の範囲と同じ
PREVIEW_NOTES = (
    "DRY RUN ONLY. Not deployable: images were built locally and not pushed (no registry digest), "
    "rollback range is unverified, and ml-train is not implemented (Stage 3)."
)


def load_schema(root: Path, name: str) -> dict:
    return json.loads((root / SCHEMA_DIR / name).read_text(encoding="utf-8"))


def _is_placeholder_digest(hex64: str) -> bool:
    return len(set(hex64)) <= 2 or hex64 in ("deadbeef" * 8, "0123456789abcdef" * 4)


def _semantic_common(doc: dict, root: Path | None, expect_git_sha: str | None) -> list[str]:
    errors: list[str] = []
    if expect_git_sha is not None and doc.get("git_sha") != expect_git_sha:
        errors.append(f"git_sha が期待値と違う（{doc.get('git_sha')} != {expect_git_sha}）")
    if root is None:
        return errors
    try:
        if doc["contract_version"] != repo_facts.contract_version(root):
            errors.append(f"contract_version が docs/CONTRACTS.md と違う（{doc['contract_version']} != {repo_facts.contract_version(root)}）")
        if not doc["contract_version"].startswith(SUPPORTED_CONTRACT_PREFIX):
            errors.append("contract_version が未対応の範囲")
        apps = repo_facts.required_schema_versions(root)
        if len(set(apps.values())) != 1:
            errors.append(f"アプリ間で requiredSchemaVersion が食い違っている: {apps}")
        expected_required = repo_facts.common_required_schema_version(root)
        if doc["required_schema_version"] != expected_required:
            errors.append(f"required_schema_version が各アプリに焼き込まれた値と違う（{doc['required_schema_version']} != {expected_required}）")
        versions = repo_facts.flyway_versions(root)
        if doc["flyway"]["latest_version"] != versions[-1]:
            errors.append(f"flyway.latest_version が実際のマイグレーションの最新版と違う（{doc['flyway']['latest_version']} != {versions[-1]}）")
        if doc["flyway"]["baseline"] != versions[0]:
            errors.append(f"flyway.baseline が最初の版と違う（{doc['flyway']['baseline']} != {versions[0]}）")
        if repo_facts.version_key(doc["required_schema_version"]) > repo_facts.version_key(doc["flyway"]["latest_version"]):
            errors.append("required_schema_version が flyway.latest_version より新しい（適用できない版を要求している）")
        if doc["model_schema_versions"] != MODEL_SCHEMA_VERSIONS:
            errors.append(f"model_schema_versions が未対応（{doc['model_schema_versions']}）")
    except (KeyError, TypeError, ValueError) as e:  # スキーマ違反で構造が壊れている場合は、スキーマ側のエラーが主
        errors.append(f"整合検査を完了できない: {e}")
    return errors


def _rollback_registry(root: Path) -> list[dict]:
    path = root / ROLLBACK_REGISTRY
    if not path.exists():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    return [e for e in data.get("verified", []) if isinstance(e, dict)]


def validate_production(doc: Any, root: Path | None = None, expect_git_sha: str | None = None) -> list[str]:
    """本番用 manifest の違反を返す（空なら適合）。root を渡すとリポジトリの実際の値との整合も検査する。"""
    if not isinstance(doc, dict):
        return ["トップレベルがオブジェクトではない"]
    errors: list[str] = []
    if doc.get("kind") == "release-manifest-preview" or "deployable" in doc or "blockers" in doc:
        errors.append("プレビュー成果物（manifest-preview.json）は本番用 release-manifest.json として使えない")
    schema_root = root or repo_facts.repo_root()
    errors += jsonschema_lite.validate(doc, load_schema(schema_root, PRODUCTION_SCHEMA))
    if errors:
        return errors  # 構造が壊れていれば意味の検査はしない
    for name, ref in doc["images"].items():
        digest = ref.rsplit("@sha256:", 1)[1]
        if _is_placeholder_digest(digest):
            errors.append(f"images.{name}: digest が仮値に見える（実際のレジストリ digest ではない）")
    errors += _semantic_common(doc, root, expect_git_sha)
    min_app = doc["rollback"]["min_app_release"]
    if repo_facts.release_key(min_app) > repo_facts.release_key(doc["release"]):
        errors.append("rollback.min_app_release が release より新しい")
    elif min_app != doc["release"] and root is not None:
        verified = [
            e for e in _rollback_registry(root)
            if e.get("app_release") == min_app and e.get("target_flyway_version") == doc["flyway"]["latest_version"] and e.get("evidence")
        ]
        if not verified:
            errors.append(f"rollback.min_app_release={min_app} は検証済みの記録（{ROLLBACK_REGISTRY}）が無い。検証済みの値は release 自身だけ")
    return errors


def validate_preview(doc: Any, root: Path | None = None, expect_git_sha: str | None = None) -> list[str]:
    if not isinstance(doc, dict):
        return ["トップレベルがオブジェクトではない"]
    schema_root = root or repo_facts.repo_root()
    errors = jsonschema_lite.validate(doc, load_schema(schema_root, PREVIEW_SCHEMA))
    if errors:
        return errors
    if "@sha256:" in json.dumps(doc):
        errors.append("プレビューにレジストリ digest 形式の値がある（push していないので digest は存在しない）")
    for name, img in doc["images"].items():
        if img["built"] and not img["local_image_id"]:
            errors.append(f"images.{name}: built=true なのに local_image_id が無い")
        if not img["built"] and (img["local_image_id"] or not img["reason"]):
            errors.append(f"images.{name}: 未ビルドなのに local_image_id がある、または reason が無い")
    unbuilt = [n for n, i in doc["images"].items() if not i["built"]]
    expected_blockers = {"IMAGES_NOT_PUSHED", "ROLLBACK_RANGE_UNVERIFIED"}
    if unbuilt:
        expected_blockers.add("IMAGE_NOT_BUILT")
    if doc["feature_schema_hash"] is None:
        expected_blockers.add("FEATURE_SCHEMA_HASH_UNDEFINED")
    missing = expected_blockers - set(doc["blockers"])
    if missing:
        errors.append(f"blockers に必要な理由が無い: {sorted(missing)}")
    errors += _semantic_common(doc, root, expect_git_sha)
    return errors


def dumps(doc: dict) -> str:
    """決定的な出力（キー順は生成順のまま、インデント 2、末尾に改行 1 つ）。"""
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def build_preview(
    root: Path, git_sha: str, release: str, built_at: str, local_images: dict[str, dict], python_version: str | None = None
) -> dict:
    """ドライラン用 manifest。local_images は {name: {"local_tag": str, "image_id": "sha256:…"}}（smoke の出力）。"""
    versions = repo_facts.flyway_versions(root)
    images: dict[str, dict] = {}
    for name in repo_facts.IMAGE_NAMES:
        local = local_images.get(name)
        if local:
            images[name] = {"built": True, "local_tag": local["local_tag"], "local_image_id": local["image_id"], "registry_digest": None, "reason": None}
        else:
            reason = "ml-train is not implemented yet (Stage 3: T3.6)" if name == "ml-train" else "image was not built in this run"
            images[name] = {"built": False, "local_tag": None, "local_image_id": None, "registry_digest": None, "reason": reason}
    blockers = ["IMAGES_NOT_PUSHED", "ROLLBACK_RANGE_UNVERIFIED", "FEATURE_SCHEMA_HASH_UNDEFINED"]
    if any(not i["built"] for i in images.values()):
        blockers.append("IMAGE_NOT_BUILT")
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": "release-manifest-preview",
        "deployable": False,
        "blockers": sorted(blockers),
        "release": release,
        "git_sha": git_sha,
        "built_at": built_at,
        "contract_version": repo_facts.contract_version(root),
        "agent_protocol": AGENT_PROTOCOL,
        "images": images,
        "flyway": {"latest_version": versions[-1], "baseline": versions[0]},
        "required_schema_version": repo_facts.common_required_schema_version(root),
        "rollback": {"min_app_release": None, "verified": False},
        "model_schema_versions": list(MODEL_SCHEMA_VERSIONS),
        "feature_schema_hash": None,
        "runtime": {"jdk": "25", "python": python_version},
        "notes": PREVIEW_NOTES,
    }


def build_production(
    root: Path,
    git_sha: str,
    release: str,
    built_at: str,
    registry_images: dict[str, str],
    min_app_release: str,
    feature_schema_hash: str,
    python_version: str,
    notes: str = "",
) -> dict:
    """本番用 manifest。入力は実際にレジストリへ push した後に得た digest 付き参照だけ。検証に通らなければ例外（出力しない）。"""
    versions = repo_facts.flyway_versions(root)
    doc = {
        "schema_version": SCHEMA_VERSION,
        "release": release,
        "git_sha": git_sha,
        "built_at": built_at,
        "contract_version": repo_facts.contract_version(root),
        "agent_protocol": AGENT_PROTOCOL,
        "images": {name: registry_images.get(name, "") for name in repo_facts.IMAGE_NAMES},
        "flyway": {"latest_version": versions[-1], "baseline": versions[0]},
        "required_schema_version": repo_facts.common_required_schema_version(root),
        "rollback": {"min_app_release": min_app_release},
        "model_schema_versions": list(MODEL_SCHEMA_VERSIONS),
        "feature_schema_hash": feature_schema_hash,
        "runtime": {"jdk": "25", "python": python_version},
        "notes": notes,
    }
    errors = validate_production(doc, root, expect_git_sha=git_sha)
    if errors:
        raise ValueError("本番用 manifest を生成できない:\n- " + "\n- ".join(errors))
    return doc


_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def require_git_sha(value: str) -> str:
    if not _SHA_RE.fullmatch(value):
        raise ValueError("git_sha は 40 桁の小文字 16 進のコミット SHA")
    return value
