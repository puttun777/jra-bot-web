"""変更ファイルから、CI で実行するジョブを決める（T2.5 後半 / GitHub Actions 無料枠の節約）。

原則: **fail closed**。分類できない・差分を取得できない・予期しないイベントは「全部実行」にする。軽くするのは、
**docs だけ**など、変更が各ジョブの入力に確実に影響しないと分かっているときだけ。

ファイルは先頭から順に規則に当てはめて、タグを付ける（どれにも当たらないファイルは `unknown` = 全部実行）。
タグ → ジョブ:
  docs        ドキュメントのみ（hygiene と最終ゲートだけ）
  kotlin_app  :bot :ingest :dashboard :cloud-gcp  → build、DB 統合（アプリのスイートのみ・MySQL 8.4）
  kotlin_core :domain  → build、DB 統合（persistence + アプリ・8.4）。domain のモデルは Doma の変換・Enum 契約に影響するため
  kotlin_db   :persistence :db-migration  → build、DB 統合（全スイート・8.4 と 9.7）
  build_cfg   Gradle・版数・build-logic・ktlint 設定  → build、DB 統合（全・8.4 + 9.7）、images
  ml_tools    tools/ml（正準 JSON・署名・データセット契約の Python 参照実装）と、その合成成果物 domain/src/test/resources/ml  → ml-tools（Python 3.12。cryptography）。
              合成成果物は Kotlin のテスト（モデルのロード・署名検証）の入力でもあるので kotlin_golden も付ける（build は走るが DB 統合は不要）
  ml_train    ml/（ML 学習 Job。Python。T3.6-B）  → ml-train（Python 3.13: ruff・mypy・単体）。Kotlin⇄Python の結合テスト（:bot の MlTrainInteropIntegrationTest）が ml/ を使うので
              kotlin_app も付ける（build + DB 統合 8.4 のアプリのスイート）。ml/src・requirements は ml-train イメージの中身なので docker も付ける。
              tools/ml の canonical.py・dataset_contract.py（イメージに入る契約の参照実装）も ml_train + docker
  golden      tools/golden  → golden（Python）
  kotlin_golden  tools/golden（.md 以外）・golden-runner  → build（Kotlin と旧 Python の比較は build ジョブの中。DB 統合は不要）。
              :domain の変更は kotlin_core で build に入るので、比較も必ず走る
  release     tools/release・スキーマ・契約・マイグレーション・アプリの build 定義  → release-tools（Python 単体）
  docker      Dockerfile / .dockerignore  → images（ビルド + スモーク）
  connectorj  Connector/J などの DB ドライバ関連の依存設定  → connector/j 26.x 互換性チェック（非ゲート）
  agent       agent/（Windows 取得エージェント。Python）  → agent（Linux, Python 3.12/3.13: lint・型・単体）
  agent_windows  agent/ の Windows 固有部分（DPAPI・ジョブオブジェクト・ロック・起動スクリプト）  → agent-windows（Windows ランナー。課金が 2 倍なのでこの変更とフル検証だけ）
              agent/src・interop・署名ベクトルは Kotlin ingest との結合（AgentInteropIntegrationTest）に使われるので kotlin_app も付ける
  ci          CI 自体（ワークフロー・tools/ci）  → すべて（connector/j 26.x は除く）
実行されなかったジョブは「テストされた」ことにならない（最終ゲートが skipped を成功として数えず、表に『未実施（不要と判定）』と出す）。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field

# (正規表現, タグ) を上から順に評価し、最初に当たった規則を使う（1 ファイル 1 規則）。複数タグを返せる
RULES: list[tuple[str, tuple[str, ...]]] = [
    # --- CI 自体・分類ロジック（安全側: すべて実行）
    (r"^\.github/workflows/compat-connectorj26\.yml$", ("ci", "connectorj")),
    (r"^\.github/(workflows|scripts)/", ("ci",)),
    (r"^tools/ci/", ("ci",)),
    (r"^tools/__init__\.py$", ("ci",)),
    (r"^\.github/dependabot\.yml$", ("release",)),  # 設定ファイルの整合検査（Python 単体）だけで足りる
    (r"^\.github/[^/]*\.md$|^\.github/(ISSUE_TEMPLATE|CODEOWNERS|pull_request_template)", ("docs",)),
    # --- Docker・リリース
    (r"^(Dockerfile|\.dockerignore)$", ("docker", "release")),
    (r"^tools/release/", ("docker", "release")),
    (r"^docs/(schemas|release)/", ("release",)),
    (r"^docs/CONTRACTS\.md$", ("docs", "release")),
    (r"^db-migration/src/main/resources/db/migration/", ("kotlin_db", "release", "docker")),
    (r"^(bot|ingest|dashboard)/build\.gradle\.kts$", ("kotlin_app", "release", "docker")),
    (r"^(bot|ingest|dashboard)/src/main/resources/", ("kotlin_app", "docker")),
    (r"^db-migration/src/main/", ("kotlin_db", "docker")),
    # --- Connector/J・DB ドライバ関連の依存設定（gradle/libs.versions.toml は build_cfg でもある）
    (r"^gradle/libs\.versions\.toml$", ("build_cfg", "connectorj")),
    (r"^(persistence|db-migration)/build\.gradle\.kts$", ("kotlin_db", "connectorj", "docker")),
    # --- Windows エージェント（T3.5）。.md は docs。Windows 固有のファイルだけ Windows ランナーでも検証する
    (r"^agent/.*\.md$", ("docs",)),
    (r"^agent/src/jra_agent/(winapi|secrets_store|lock)\.py$|^agent/src/jra_agent/jvlink/(procutil|supervisor|com_adapter|worker_main)\.py$", ("agent", "agent_windows", "kotlin_app")),
    (r"^agent/(tests/test_winapi_windows\.py|scripts/)", ("agent", "agent_windows")),
    (r"^agent/(src/|interop/|tests/vectors/|pyproject\.toml$|requirements)", ("agent", "kotlin_app")),
    (r"^agent/", ("agent",)),
    # --- ML ツール（T3.6-A）。合成成果物は Kotlin のテストも使う
    (r"^tools/ml/.*\.md$", ("docs",)),
    (r"^tools/ml/(canonical|dataset_contract)\.py$", ("ml_tools", "ml_train", "docker", "kotlin_app")),
    (r"^tools/ml/", ("ml_tools",)),
    (r"^domain/src/test/resources/ml/", ("ml_tools", "ml_train", "kotlin_golden")),
    # --- ML 学習 Job（T3.6-B）。ml/ だけの変更でも Python の学習テストが必ず走る。イメージの中身（src・requirements）が変わったらイメージも
    (r"^ml/.*\.md$", ("docs",)),
    (r"^ml/(src/|requirements[^/]*\.txt$|pyproject\.toml$)", ("ml_train", "kotlin_app", "docker")),
    (r"^ml/", ("ml_train", "kotlin_app")),
    # --- ドキュメント（モジュール内の README なども docs。上の規則に当たらなかったもの）
    (r"^(docs/.*|.*\.md|LICENSE|\.gitignore)$", ("docs",)),
    # --- Kotlin
    (r"^(persistence|db-migration)/", ("kotlin_db",)),
    (r"^domain/", ("kotlin_core",)),
    (r"^(bot|ingest|dashboard|cloud-gcp)/", ("kotlin_app",)),
    # --- ビルド・lint の設定
    (r"^(gradle/|build-logic/|gradlew(\.bat)?$|settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|\.editorconfig$)", ("build_cfg", "docker")),
    # --- ゴールデン（Python のフィクスチャ検証 + Kotlin ランナーとの比較）。フィクスチャやシナリオが変わったら Kotlin 側の比較も必要
    (r"^golden-runner/", ("kotlin_golden",)),
    (r"^tools/golden/.*\.md$", ("docs",)),
    (r"^tools/golden/", ("golden", "kotlin_golden")),
]

FULL_MATRIX = ["8.4", "9.7"]
ALL_SUITES = ["migration", "persistence", "apps"]


@dataclass
class Plan:
    mode: str  # docs | code | full
    reason: str
    run_build: bool = False
    run_golden: bool = False
    run_ml_tools: bool = False
    run_ml_train: bool = False
    run_release: bool = False
    run_images: bool = False
    run_connectorj26: bool = False
    run_agent: bool = False
    run_agent_windows: bool = False
    db_matrix: list[str] = field(default_factory=list)
    db_suites: list[str] = field(default_factory=list)
    files: int = 0

    def outputs(self) -> dict[str, str]:
        b = lambda v: "true" if v else "false"  # noqa: E731
        reason = re.sub(r"[\x00-\x1f\x7f]", " ", self.reason)[:300]  # GITHUB_OUTPUT を壊す改行などを入れない（ファイル名は PR の作者が決める）
        return {
            "mode": self.mode, "reason": reason, "files": str(self.files),
            "run_build": b(self.run_build), "run_golden": b(self.run_golden), "run_ml_tools": b(self.run_ml_tools), "run_ml_train": b(self.run_ml_train), "run_release": b(self.run_release),
            "run_images": b(self.run_images), "run_connectorj26": b(self.run_connectorj26),
            "run_agent": b(self.run_agent), "run_agent_windows": b(self.run_agent_windows),
            "db_matrix": json.dumps(self.db_matrix), "db_suites": ",".join(self.db_suites),
        }


def full_plan(reason: str, files: int = 0, connectorj: bool = False) -> Plan:
    """すべて実行（Connector/J 26.x は `connectorj` が True のときだけ。本番採用が未決で、非ゲートの互換性確認なので）。"""
    return Plan(
        "full", reason, run_build=True, run_golden=True, run_ml_tools=True, run_ml_train=True, run_release=True, run_images=True, run_connectorj26=connectorj,
        run_agent=True, run_agent_windows=True, db_matrix=list(FULL_MATRIX), db_suites=list(ALL_SUITES), files=files,
    )


def tags_for(path: str) -> tuple[str, ...]:
    for pattern, tags in RULES:
        if re.search(pattern, path):
            return tags
    return ("unknown",)


def classify(files: list[str]) -> Plan:
    if not files:
        return full_plan("変更ファイルの一覧が空（取得失敗の可能性）")
    tags: set[str] = set()
    for f in files:
        t = tags_for(f)
        tags.update(t)
    if "unknown" in tags:
        unknown = [f for f in files if tags_for(f) == ("unknown",)]
        return full_plan(f"分類できないファイルがある: {unknown[:3]}", len(files))
    if "ci" in tags:
        return full_plan("CI 設定が変更された", len(files), connectorj="connectorj" in tags)
    if tags == {"docs"}:
        return Plan("docs", "ドキュメントのみ", files=len(files))

    p = Plan("code", "", files=len(files))
    p.run_build = bool(tags & {"kotlin_app", "kotlin_core", "kotlin_db", "build_cfg", "kotlin_golden"})
    if tags & {"kotlin_db", "build_cfg"}:
        p.db_matrix, p.db_suites = list(FULL_MATRIX), list(ALL_SUITES)
    elif tags & {"kotlin_core"}:
        p.db_matrix, p.db_suites = ["8.4"], ["persistence", "apps"]
    elif tags & {"kotlin_app"}:
        p.db_matrix, p.db_suites = ["8.4"], ["apps"]
    p.run_golden = "golden" in tags
    p.run_ml_tools = "ml_tools" in tags
    p.run_ml_train = "ml_train" in tags
    p.run_release = "release" in tags
    p.run_images = "docker" in tags or "build_cfg" in tags
    p.run_connectorj26 = "connectorj" in tags
    p.run_agent = "agent" in tags
    p.run_agent_windows = "agent_windows" in tags
    p.reason = "変更タグ: " + ",".join(sorted(tags))
    return p


# ---------------------------------------------------------------------------------------------------- 差分の取得
def _git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def changed_files(event: str, before: str | None = None, base_sha: str | None = None) -> list[str]:
    """イベントごとの差分。pull_request は merge コミット（HEAD）とその第 1 親（= テストした時点のベース）の差分。"""
    if event == "pull_request":
        parents = _git("rev-list", "--parents", "-n", "1", "HEAD").split()
        a = parents[1] if len(parents) == 3 else base_sha
        if not a:
            raise RuntimeError("PR のベースを特定できない")
        raw = _git("diff", "--name-only", "--no-renames", "-z", a, "HEAD")
    elif event == "push":
        if not before or set(before) == {"0"}:
            raise RuntimeError("push の before が無い（新規ブランチなど）")
        raw = _git("diff", "--name-only", "--no-renames", "-z", before, "HEAD")
    else:
        raise RuntimeError(f"差分を取らないイベント: {event}")
    return [f for f in raw.split("\0") if f]


def plan_for_event(event: str, before: str | None = None, base_sha: str | None = None, files: list[str] | None = None) -> Plan:
    """どんな失敗も「全部実行」に倒す。workflow_dispatch・schedule は常にフル検証（Connector/J 26.x を含む）。"""
    if event in ("workflow_dispatch", "schedule"):
        return full_plan(f"{event}: フル検証", connectorj=True)
    if event not in ("pull_request", "push"):
        return full_plan(f"想定外のイベント {event}")
    try:
        return classify(files if files is not None else changed_files(event, before, base_sha))
    except Exception as e:  # noqa: BLE001 - 判定の失敗でテストを省略しない
        return full_plan(f"変更判定に失敗（fail closed）: {type(e).__name__}: {str(e)[:120]}")


def main() -> int:
    event = os.environ.get("EVENT_NAME", "")
    plan = plan_for_event(event, os.environ.get("BEFORE_SHA"), os.environ.get("BASE_SHA"))
    out = plan.outputs()
    for k, v in out.items():
        print(f"{k}={v}")
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            for k, v in out.items():
                f.write(f"{k}={v}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
