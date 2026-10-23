"""リポジトリ（CONTRACTS・Gradle・Flyway のファイル）から release-manifest の整合検査に使う事実を取り出す。実行時の値ではなくソースの値。"""
from __future__ import annotations

import re
from pathlib import Path

# 実行アプリ（REQUIRED_SCHEMA_VERSION を jar に焼き込む）。db-migration はスキーマ版を要求しない（適用する側）
APPS = ("dashboard", "ingest", "bot")
# CONTRACTS §10 の images のキー。ml-train は Stage 3 の実装後（現時点でビルドできるイメージが無い）
IMAGE_NAMES = ("dashboard", "ingest", "bot", "db-migration", "ml-train")
BUILDABLE_IMAGES = ("dashboard", "ingest", "bot", "db-migration")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def contract_version(root: Path) -> str:
    """docs/CONTRACTS.md の先頭の「状態: **x.y.z（…」から取り出す。"""
    text = (root / "docs" / "CONTRACTS.md").read_text(encoding="utf-8")
    m = re.search(r"^状態: \*\*(\d+\.\d+\.\d+)", text, re.M)
    if not m:
        raise ValueError("docs/CONTRACTS.md から contract_version を取り出せない")
    return m.group(1)


def required_schema_versions(root: Path) -> dict[str, str]:
    """各アプリの build.gradle.kts の extra["requiredSchemaVersion"]（jar に焼き込まれる値の出どころ）。"""
    result: dict[str, str] = {}
    for app in APPS:
        text = (root / app / "build.gradle.kts").read_text(encoding="utf-8")
        m = re.search(r'extra\["requiredSchemaVersion"\]\s*=\s*"(\d+(?:\.\d+)*)"', text)
        if not m:
            raise ValueError(f"{app}/build.gradle.kts に requiredSchemaVersion が無い")
        result[app] = m.group(1)
    return result


def common_required_schema_version(root: Path) -> str:
    """manifest の required_schema_version は 1 つ。アプリ間で食い違っていれば、最大値（リリースが必要とする最小のスキーマ版）を返す。"""
    versions = required_schema_versions(root)
    return max(versions.values(), key=version_key)


def flyway_versions(root: Path) -> list[str]:
    """db-migration の Versioned マイグレーション（V<版>__<説明>.sql）の版を昇順で返す。"""
    directory = root / "db-migration" / "src" / "main" / "resources" / "db" / "migration"
    versions = []
    for p in directory.glob("V*__*.sql"):
        m = re.fullmatch(r"V(\d+(?:[._]\d+)*)__.+\.sql", p.name)
        if not m:
            raise ValueError(f"マイグレーションのファイル名が不正: {p.name}")
        versions.append(m.group(1).replace("_", "."))
    if not versions:
        raise ValueError("Versioned マイグレーションが無い")
    return sorted(versions, key=version_key)


def version_key(version: str) -> tuple[int, ...]:
    """版は数値の組として比較する（10 > 9）。"""
    return tuple(int(x) for x in version.split("."))


def release_key(release: str) -> tuple[int, ...]:
    """release（例 2026.11.0）の大小比較。"""
    return tuple(int(x) for x in release.split("."))
