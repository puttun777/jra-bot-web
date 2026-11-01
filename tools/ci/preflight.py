"""push・PR 作成の前に、ローカルで CI 相当の検査を実行する（GitHub Actions の節約。CI を試行錯誤の実行環境にしない）。

使い方（リポジトリのルートで）::

    python3 -m tools.ci.preflight                 # origin/main との差分から必要な検査を選んで実行（失敗しても最後まで実行して一覧表示）
    python3 -m tools.ci.preflight --list          # 選ばれる検査だけを表示（実行しない）
    python3 -m tools.ci.preflight --only kotlin-build,hygiene
    python3 -m tools.ci.preflight --full          # 変更範囲に関わらず全部（CI の full 相当）
    python3 -m tools.ci.preflight --fail-fast

**二重管理を避ける**: 変更範囲 → 検査の選択は CI と同じ [tools.ci.classify] をそのまま使う（`Plan` の `run_*` と `db_matrix`/`db_suites`）。個々の検査のコマンドは
`.github/workflows/ci.yml` と同じ内容で、`tools/ci/tests/test_preflight.py` が『preflight の Gradle タスク・Python モジュールが ci.yml に存在する』ことを検査する
（CI の手順を変えたらこのテストが落ちる）。

**環境の違いを偽らない**: JDK・Python・MySQL の版を実行ごとに記録し、CI（JDK 25 / Python 3.12・3.13 / MySQL 8.4・9.7）と違うものは『差異』として結果に出す。
Windows 固有（agent-windows）・実 IAP・GitHub 固有の検査はローカルで実行できないので『未実施』と表示する。

記録: `build/preflight/last.json`（検証したコミット・ツリー・版・結果）と `build/preflight/<id>.log`（詳細ログ。Git には入れない）。
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

from tools.ci import classify

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "preflight"
CI_JDK = "25"
CI_PYTHONS = {"golden": "3.12", "ml_tools": "3.12", "agent": "3.12/3.13", "ml_train": "3.13", "default": "3.12/3.13"}
LEGACY_COMMIT = "f7dbf69fd8940121782870de902e8c449e6ca149"  # tools/golden/constants.py の PINNED_COMMIT（テストで一致を検査）


@dataclass
class Step:
    id: str
    title: str
    commands: list[list[str]]
    env: dict[str, str] = field(default_factory=dict)
    shell: str | None = None  # bash -c で実行する複数行（commands の代わり）
    needs_docker: bool = False
    needs_jdk25: bool = False
    caveat: str = ""
    verify: list[str] = field(default_factory=list)  # ci.yml の『Verify that …』ステップ名（実行後に同じ検査を行う）


@dataclass
class Result:
    id: str
    title: str
    status: str  # ok | fail | skipped | not_run
    seconds: float = 0.0
    note: str = ""


def sh(*args: str) -> str:
    return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def default_base() -> str:
    for ref in ("origin/main", "main"):
        if subprocess.run(["git", "rev-parse", "--verify", "-q", ref], cwd=ROOT, capture_output=True).returncode == 0:
            return sh("git", "merge-base", "HEAD", ref)
    raise SystemExit("origin/main が見つからない。git fetch origin main を実行してください")


def changed_files(base: str) -> list[str]:
    """base からの差分（コミット済み + 作業ツリー + 未追跡）。CI の pull_request 判定（HEAD とベースの差分）より広い = 安全側。"""
    names = set(filter(None, sh("git", "diff", "--name-only", "--no-renames", base).split("\n")))
    names |= set(filter(None, sh("git", "ls-files", "--others", "--exclude-standard").split("\n")))
    return sorted(names)


def ci_verify_block(step_name: str, nth: int = 0) -> str:
    """ci.yml の run ブロック（『Verify that …』など）をそのまま取り出す（実行テスト数の下限・必須クラス・スキップ 0 の検査。二重管理しない）。
    同名のステップが複数のジョブにある場合は [nth]（0 始まり）で選ぶ。"""
    text = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    found = list(re.finditer(r"- name: " + re.escape(step_name) + r"\n(?:\s+if:[^\n]*\n)?\s+run: \|\n((?:(?:\s{10}[^\n]*)?\n)+)", text))
    if len(found) <= nth:
        raise SystemExit(f"ci.yml に『{step_name}』（{nth + 1} 番目）が見つからない")
    m = found[nth]
    lines = [ln[10:] if ln.strip() else "" for ln in m.group(1).split("\n")]
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------- 環境の検出
def find_jdk25() -> str | None:
    cands = [os.environ.get("PREFLIGHT_JAVA_HOME", ""), *sorted(glob.glob("/opt/jdk25*/jdk-25*")), *sorted(glob.glob("/usr/lib/jvm/*25*"))]
    for c in cands:
        if c and Path(c, "bin", "java").exists():
            return c
    return None


def java_version(home: str | None) -> str:
    exe = str(Path(home, "bin", "java")) if home else "java"
    try:
        out = subprocess.run([exe, "-version"], capture_output=True, text=True).stderr
        m = re.search(r'version "([^"]+)"', out)
        return m.group(1) if m else "?"
    except OSError:
        return "none"


def docker_ok() -> bool:
    try:
        return subprocess.run(["docker", "info"], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def ensure_docker() -> bool:
    """Docker が動いていなければ（root で dockerd があれば）起動を試みる。クラウドセッションでは dockerd を手で起動する必要がある。"""
    if docker_ok():
        return True
    if shutil.which("dockerd") and hasattr(os, "geteuid") and os.geteuid() == 0:
        OUT.mkdir(parents=True, exist_ok=True)
        subprocess.Popen(["dockerd"], stdout=open(OUT / "dockerd.log", "w"), stderr=subprocess.STDOUT, start_new_session=True)
        for _ in range(30):
            time.sleep(1)
            if docker_ok():
                return True
    return False


def venv(name: str, requirements: list[str], python: str = "python3", extra: list[str] | None = None) -> str:
    """`build/preflight/venv-<name>` を作り、固定された requirements を入れて python のパスを返す。"""
    d = OUT / f"venv-{name}"
    py = d / "bin" / "python"
    stamp = d / ".reqs"
    wanted = "|".join(f"{r}:{(ROOT / r).stat().st_mtime_ns}" for r in requirements) + "|" + " ".join(extra or [])
    if not py.exists():
        subprocess.run([python, "-m", "venv", str(d)], check=True)
    if not stamp.exists() or stamp.read_text() != wanted:
        for r in requirements:
            subprocess.run([str(py), "-m", "pip", "install", "-q", "--no-input", "-r", str(ROOT / r)], check=True)
        if extra:
            subprocess.run([str(py), "-m", "pip", "install", "-q", "--no-input", *extra], check=True)
        stamp.write_text(wanted)
    return str(py)


# ---------------------------------------------------------------------------------------------- 検査の定義
def steps_for(plan: classify.Plan, files: list[str]) -> list[Step]:
    s: list[Step] = []
    s.append(Step("diff-check", "git diff --check（空白・競合マーカー）", [["git", "diff", "--check"]]))
    s.append(Step("hygiene", "秘密・実データ・生成物の混入検査（CI の repo-hygiene と同一）", [["bash", ".github/scripts/check-repo-hygiene.sh"]]))
    s.append(Step("classify-self", "変更分類ロジック・CI スクリプトの単体試験（tools.ci）", [["python3", "-m", "tools.ci.run_tests", "--min-tests", "100"]]))
    s.append(Step("consistency", "整合検査（Flyway 版・スキーマ版・必須環境変数・スモーク設定・infra 契約）", [["python3", "-m", "tools.ci.consistency"]]))
    if plan.run_build:
        s.append(
            Step(
                "kotlin-build",
                "Kotlin: ./gradlew build + checkModuleDependencies + ktlintCheck（JDK 25）",
                [["./gradlew", "build", "--stacktrace"], ["./gradlew", "checkModuleDependencies", "ktlintCheck"]],
                verify=["Verify that the cloud-gcp unit tests actually ran", "Verify that the app and persistence unit tests actually ran"],
                env={"LC_ALL": "C.UTF-8", "LANG": "C.UTF-8"},
                needs_jdk25=True,
            )
        )
        s.append(
            Step(
                "kotlin-golden",
                "旧新ゴールデン: Kotlin ランナーと旧 Python の比較",
                [
                    ["python3", "-m", "tools.golden.verify"],
                    ["./gradlew", ":golden-runner:run", "--stacktrace", f"--args=--fixtures {ROOT}/tools/golden/fixtures --out {OUT}/kotlin_out.json --coverage {OUT}/kotlin_coverage.json"],
                    ["python3", "-m", "tools.golden.kotlin_compare", "--actual", str(OUT / "kotlin_out.json"), "--coverage", str(OUT / "kotlin_coverage.json")],
                ],
                env={"LC_ALL": "C.UTF-8", "LANG": "C.UTF-8"},
                needs_jdk25=True,
            )
        )
    suites = plan.db_suites
    for mysql in plan.db_matrix:
        tasks: list[list[str]] = []
        if "migration" in suites:
            tasks.append(["./gradlew", ":db-migration:integrationTest", "--stacktrace"])
        if "persistence" in suites:
            tasks.append(["./gradlew", ":persistence:integrationTest", "--stacktrace"])
        if "apps" in suites:
            tasks.append(["./gradlew", ":bot:integrationTest", ":ingest:integrationTest", ":dashboard:integrationTest", "--continue", "--stacktrace"])
        verify = [n for key, n in (("migration", "Verify that the integration tests actually ran"), ("persistence", "Verify that the persistence integration tests actually ran"), ("apps", "Verify that the app integration tests actually ran")) if key in suites]
        if tasks:
            s.append(
                Step(
                    f"db-it-{mysql}",
                    f"MySQL {mysql} 統合試験（{','.join(suites)}）",
                    tasks,
                    env={"LC_ALL": "C.UTF-8", "LANG": "C.UTF-8", "MYSQL_IMAGE": f"mysql:{mysql}"},
                    needs_docker=True,
                    needs_jdk25=True,
                    verify=verify,
                    caveat="AGENT_PYTHON/ML_TRAIN_PYTHON は preflight が venv を用意して渡す（CI と同じ）",
                )
            )
    if plan.run_golden:
        s.append(Step("golden-py", "golden（Python）: 検証・旧実装からの再生成の一致・単体", [], shell="golden", caveat=f"CI は Python {CI_PYTHONS['golden']}（ローカルは {sys.version.split()[0]}）。旧実装は ../jra-bot（固定コミット {LEGACY_COMMIT[:7]}）を読み取りだけで使う"))
    if plan.run_agent:
        s.append(Step("agent", "agent（Python）: ruff・format・mypy・pytest", [], shell="agent", caveat=f"CI は Python {CI_PYTHONS['agent']}"))
    if plan.run_ml_train:
        s.append(Step("ml-train", "ml-train（Python）: ruff・format・mypy・pytest・pip check", [], shell="ml-train", caveat=f"CI は Python {CI_PYTHONS['ml_train']}"))
    if plan.run_ml_tools:
        s.append(Step("ml-tools", "ml tools（Python）: 単体・合成成果物の再生成の一致", [], shell="ml-tools", caveat=f"CI は Python {CI_PYTHONS['ml_tools']}"))
    app_runtime_changed = any(re.match(r"^(bot|ingest|dashboard|cloud-gcp|persistence|domain)/src/main/", f) for f in files)
    if plan.run_images or app_runtime_changed:
        # CI は kotlin のアプリ/ドメイン変更だけでは images を実行しない（節約）。起動設定の食い違い（必須環境変数・Bean の解決）を push 前に捕まえるため、ローカルでは実行する
        s.append(Step("images", "Docker 5 イメージのビルド + 起動スモーク", [], shell="images", needs_docker=True, caveat="" if plan.run_images else "CI ではこの変更では実行されない。ローカルのみの追加検査"))
    return s


# ---------------------------------------------------------------------------------------------- 実行
def run_cmd(cmd: list[str], env: dict[str, str], log) -> int:
    log.write(f"$ {' '.join(cmd)}\n")
    log.flush()
    p = subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    return p.returncode


def _junit_dir(name: str) -> Path:
    """pytest の JUnit XML を置く場所（作業ツリーを汚さない。CI の検査ブロックは cwd の pytest.xml を読む）。"""
    d = OUT / f"junit-{name}"
    d.mkdir(parents=True, exist_ok=True)
    return d


def run_shell_step(step: Step, env: dict[str, str], log) -> int:
    if step.shell == "golden":
        legacy = os.environ.get("PREFLIGHT_LEGACY_PATH", str(ROOT.parent / "jra-bot"))
        head = subprocess.run(["git", "-C", legacy, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        if head != LEGACY_COMMIT:
            log.write(f"旧実装 {legacy} の HEAD={head or '不明'} が固定コミット {LEGACY_COMMIT} と違う\n")
            return 2
        py = venv("golden", ["tools/golden/requirements.txt"])
        genv = {**env, "GOLDEN_LEGACY_PATH": legacy, "PYTHONDONTWRITEBYTECODE": "1"}  # 参照元に .pyc を作らない（読み取り専用）
        for c in (
            ["python3", "-B", "-m", "tools.golden.verify"],
            [py, "-B", "-m", "tools.golden.run_tests", "--min-tests", "85", "--max-skipped", "0"],
            [py, "-B", "-m", "tools.golden.generate", "--legacy-path", legacy, "--check"],
        ):
            log.write(f"$ {' '.join(c)}\n")
            log.flush()
            if subprocess.run(c, cwd=ROOT, env=genv, stdout=log, stderr=subprocess.STDOUT).returncode:
                return 1
        # CI の『Comparator smoke test』（比較器が、一致・値のずれ・欠落を正しく区別する）。ok/bad/miss.json を作るので終わったら消す
        log.write("$ verify: Comparator smoke test\n")
        log.flush()
        try:
            rc = subprocess.run(["bash", "-eo", "pipefail", "-c", ci_verify_block("Comparator smoke test (self-comparison passes, perturbed and missing results fail)")], cwd=ROOT, env=genv, stdout=log, stderr=subprocess.STDOUT).returncode
        finally:
            for n in ("ok.json", "bad.json", "miss.json"):
                (ROOT / n).unlink(missing_ok=True)
        return rc
    if step.shell == "ml-tools":
        py = venv("ml-tools", ["tools/ml/requirements.txt"])
        for c in (
            [py, "-B", "-m", "tools.ml.run_tests", "--min-tests", "20", "--max-skipped", "0"],
            [py, "-B", "-m", "tools.ml.make_test_artifacts", "--out", "domain/src/test/resources/ml", "--check"],
        ):
            log.write(f"$ {' '.join(c)}\n")
            log.flush()
            if subprocess.run(c, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT).returncode:
                return 1
        # CI の『Verify the synthetic datasets』（$RUNNER_TEMP/venv/bin/python を使うので、一時ディレクトリに venv への symlink を置く）
        rt = OUT / "rt-ml-tools"
        rt.mkdir(parents=True, exist_ok=True)
        link = rt / "venv"
        if not link.exists():
            link.symlink_to(Path(py).parent.parent)
        log.write("$ verify: synthetic datasets\n")
        log.flush()
        return subprocess.run(["bash", "-eo", "pipefail", "-c", ci_verify_block("Verify the synthetic datasets (contract used by the Stage 3-F2 training job)")], cwd=ROOT, env={**env, "RUNNER_TEMP": str(rt)}, stdout=log, stderr=subprocess.STDOUT).returncode
    if step.shell == "agent":
        py = venv("agent", ["agent/requirements.txt"], extra=["pytest==9.1.1", "pytest-timeout==2.4.0", "ruff==0.17.2", "mypy==2.4.0"])
        env = {**env, "PATH": f"{Path(py).parent}:{env['PATH']}"}
        subprocess.run([py, "-m", "pip", "install", "-q", "--no-input", "--no-deps", "-e", str(ROOT / "agent")], check=True)
        cmds = [["ruff", "check", "."], ["ruff", "format", "--check", "."], ["mypy"], [py, "-m", "pytest", "-q", f"--junitxml={_junit_dir('agent')}/pytest.xml"]]
        for c in cmds:
            log.write(f"$ (agent) {' '.join(c)}\n")
            log.flush()
            if subprocess.run(c, cwd=ROOT / "agent", env=env, stdout=log, stderr=subprocess.STDOUT).returncode:
                return 1
        # CI の『Verify that the tests actually ran』（agent ジョブ = 1 番目。件数・スキップ・失敗）
        log.write("$ verify: agent tests actually ran\n")
        log.flush()
        return subprocess.run(["bash", "-eo", "pipefail", "-c", ci_verify_block("Verify that the tests actually ran", 0)], cwd=_junit_dir("agent"), env=env, stdout=log, stderr=subprocess.STDOUT).returncode
    if step.shell == "ml-train":
        py = venv("ml", ["ml/requirements-dev.txt"] if (ROOT / "ml/requirements-dev.txt").exists() else ["ml/requirements.txt"])
        env = {**env, "PATH": f"{Path(py).parent}:{env['PATH']}"}
        for c in (["pip", "check"], ["ruff", "check", "."], ["ruff", "format", "--check", "."], ["mypy"], [py, "-m", "pytest", "-q", f"--junitxml={_junit_dir('ml')}/pytest.xml"]):
            log.write(f"$ (ml) {' '.join(c)}\n")
            log.flush()
            if subprocess.run(c, cwd=ROOT / "ml", env=env, stdout=log, stderr=subprocess.STDOUT).returncode:
                return 1
        # CI の『Verify that the tests actually ran』（ml-train ジョブ = 2 番目）
        log.write("$ verify: ml-train tests actually ran\n")
        log.flush()
        return subprocess.run(["bash", "-eo", "pipefail", "-c", ci_verify_block("Verify that the tests actually ran", 1)], cwd=_junit_dir("ml"), env=env, stdout=log, stderr=subprocess.STDOUT).returncode
    if step.shell == "images":
        sha = sh("git", "rev-parse", "HEAD")
        for t in ("db-migration", "bot", "ingest", "dashboard", "ml-train"):
            if run_cmd(["docker", "build", "--target", t, "--build-arg", f"GIT_SHA={sha}", "-t", f"jra-bot/{t}:{sha}", "."], env, log):
                return 1
        return run_cmd(["python3", "-m", "tools.release.image_smoke", "--git-sha", sha, "--report-dir", str(OUT / "images")], env, log)
    return 1


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", help="比較元（既定: origin/main とのマージベース）")
    ap.add_argument("--list", action="store_true", help="選ばれる検査を表示して終了")
    ap.add_argument("--only", help="カンマ区切りの検査 ID だけ実行")
    ap.add_argument("--full", action="store_true", help="変更範囲に関わらず全部（CI の full 相当）")
    ap.add_argument("--fail-fast", action="store_true")
    a = ap.parse_args(argv)

    base = a.base or default_base()
    files = changed_files(base)
    plan = classify.full_plan("--full 指定", len(files)) if a.full else classify.classify(files)
    steps = steps_for(plan, files)
    if a.only:
        wanted = set(a.only.split(","))
        steps = [x for x in steps if x.id in wanted]
    jdk = find_jdk25()
    jv = java_version(jdk)
    dirty = bool(sh("git", "status", "--porcelain"))
    print(f"preflight: base={base[:10]} files={len(files)} mode={plan.mode} ({plan.reason})")
    print(f"  JDK: {jv} (CI: Temurin {CI_JDK}) / Python: {sys.version.split()[0]} / docker: {'あり' if docker_ok() else '停止中（実行時に起動を試みる）'} / 作業ツリー: {'未コミットの変更あり' if dirty else 'クリーン'}")
    if a.list:
        for x in steps:
            print(f"  - {x.id}: {x.title}")
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    have_docker = ensure_docker()
    results: list[Result] = []
    for st in steps:
        if st.needs_docker and not have_docker:
            results.append(Result(st.id, st.title, "not_run", note="Docker が使えない"))
            continue
        if st.needs_jdk25 and not (jv.startswith(CI_JDK + ".") or jv == CI_JDK):
            results.append(Result(st.id, st.title, "not_run", note=f"JDK {CI_JDK} が無い（検出: {jv}）。PREFLIGHT_JAVA_HOME で指定"))
            continue
        env = {**os.environ, **st.env}
        if jdk and st.needs_jdk25:
            env["JAVA_HOME"] = jdk
        if st.id.startswith("db-it-"):
            env["AGENT_PYTHON"] = venv("agent-rt", ["agent/requirements.txt"])
            env["ML_TRAIN_PYTHON"] = venv("ml", ["ml/requirements.txt"])
        t0 = time.time()
        print(f"  ▶ {st.id}: {st.title}", flush=True)
        with open(OUT / f"{st.id}.log", "w", encoding="utf-8") as log:
            rc = 0
            if st.shell:
                rc = run_shell_step(st, env, log)
            else:
                for c in st.commands:
                    rc = run_cmd(c, env, log)
                    if rc and not st.id.startswith("db-it-"):
                        break
                # 実行されたテスト数・必須クラス・スキップ 0 の検査（CI と同じ手順。db-it は失敗しても件数検査を行う: CI の always() と同じ）
                if not rc or st.id.startswith("db-it-"):
                    for name in st.verify:
                        log.write(f"$ verify: {name}\n")
                        log.flush()
                        v = subprocess.run(["bash", "-eo", "pipefail", "-c", ci_verify_block(name)], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
                        rc = rc or v
        r = Result(st.id, st.title, "ok" if rc == 0 else "fail", time.time() - t0, st.caveat)
        results.append(r)
        print(f"    {'OK' if rc == 0 else 'FAIL'} ({r.seconds:.0f}s) → build/preflight/{st.id}.log", flush=True)
        if rc and a.fail_fast:
            break

    # 実行できなかった（CI にはあるがローカルに無い）ものを明示する
    not_local = []
    if plan.run_agent_windows:
        not_local.append("agent-windows（Windows ランナー）")
    if plan.run_connectorj26:
        not_local.append("connectorj26（非ゲートの互換性確認）")
    sha = sh("git", "rev-parse", "HEAD")
    record = {
        "head": sha,
        "tree": sh("git", "rev-parse", "HEAD^{tree}"),
        "dirty": dirty,
        "base": base,
        "mode": plan.mode,
        "reason": plan.reason,
        "jdk": jv,
        "python": sys.version.split()[0],
        "results": [r.__dict__ for r in results],
        "not_run_locally": not_local,
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    (OUT / "last.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n== preflight 結果 ==")
    for r in results:
        mark = {"ok": "OK  ", "fail": "FAIL", "not_run": "未実施", "skipped": "skip"}[r.status]
        print(f"  [{mark}] {r.id}: {r.title}" + (f"  ※{r.note}" if r.note else ""))
    for n in not_local:
        print(f"  [未実施] {n}: ローカルでは実行できない（GitHub Actions のみ）")
    bad = [r for r in results if r.status == "fail"]
    notrun = [r for r in results if r.status == "not_run"]
    if dirty:
        print("  注意: 作業ツリーに未コミットの変更がある。記録（build/preflight/last.json）はコミット済みのツリーとは一致しない。push 前にコミットして再実行すること")
    print(f"  記録: build/preflight/last.json（head={sha[:10]}）")
    if bad:
        print(f"\nFAIL {len(bad)} 件 — push しない。ログ: build/preflight/<id>.log")
        return 1
    if notrun:
        print(f"\n未実施 {len(notrun)} 件あり（上記）。『全検証が成功』とは報告しない。")
    return 0


if __name__ == "__main__":
    shutil.which("git") or sys.exit("git が必要")
    sys.exit(main())
