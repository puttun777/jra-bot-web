"""最終集約チェック（`ci gate`）。分岐保護の required check はこのジョブだけにする。

- 実行が必要と判定されたジョブは **success** でなければ失敗（skipped・cancelled・failure はすべて失敗）。
- 不要と判定されたジョブは skipped（または実行して success）でなければ失敗。実行して失敗したものは失敗。
- 変更判定ジョブ（changes）が成功していなければ、他の skipped を信用できないので失敗（fail closed）。
- skipped を「テスト成功」として表示しない: 要約には『未実施（変更内容から不要と判定）』と書く。
- connector/j 26.x（非ゲートの互換性確認）は結果を表示するだけで、ゲートの成否に影響しない。
"""
from __future__ import annotations

import json
import os
import sys

# ジョブ名 → 実行が必要か（changes の outputs から）。ここに無いジョブは ci.yml の needs に入れない（テストで検査）
REQUIRED = {
    "build": lambda o: o["run_build"] == "true",
    "release-tools": lambda o: o["run_release"] == "true",
    "golden": lambda o: o["run_golden"] == "true",
    "db-integration": lambda o: o["db_matrix"] not in ("[]", ""),
    "images": lambda o: o["run_images"] == "true",
    "agent": lambda o: o["run_agent"] == "true",
    "agent-windows": lambda o: o["run_agent_windows"] == "true",
}
INFORMATIONAL = ("connectorj26",)
# 常に値がある出力。db_suites は DB ジョブが無いとき空文字で、GitHub は空文字の出力を needs から落とす（D-102）ので別扱い
OUTPUT_KEYS = ("mode", "reason", "run_build", "run_golden", "run_release", "run_images", "run_connectorj26", "run_agent", "run_agent_windows", "db_matrix")


def evaluate(needs: dict) -> tuple[bool, list[str], list[str]]:
    """(成功か, 失敗の理由, 要約の行)。needs は GitHub の `toJSON(needs)`。"""
    problems: list[str] = []
    lines: list[str] = []
    changes = needs.get("changes")
    if not isinstance(changes, dict) or changes.get("result") != "success":
        return False, ["変更判定ジョブ(changes)が成功していない。スキップされたジョブを信用できない"], ["| changes | - | " + str((changes or {}).get("result")) + " | NG |"]
    outputs = changes.get("outputs") or {}
    missing = [k for k in OUTPUT_KEYS if k not in outputs]
    if outputs.get("db_matrix") not in (None, "[]", "") and not outputs.get("db_suites"):
        missing.append("db_suites")  # DB ジョブを走らせるのにスイートが分からない → 安全側で失敗
    if missing or outputs.get("mode") not in ("docs", "code", "full"):
        return False, [f"変更判定の出力が不正: 欠落={missing} mode={outputs.get('mode')!r}"], []
    expected_any = False
    for job, needed in REQUIRED.items():
        node = needs.get(job)
        if not isinstance(node, dict) or "result" not in node:
            problems.append(f"{job}: needs に結果が無い（ゲートの設定漏れ）")
            lines.append(f"| {job} | - | (結果なし) | NG |")
            continue
        result, expected = node["result"], needed(outputs)
        expected_any |= expected
        if expected and result == "success":
            verdict = "実行して成功"
        elif expected:
            verdict = "NG"
            problems.append(f"{job}: 実行が必要だが結果が {result}")
        elif result == "skipped":
            verdict = "未実施（変更内容から不要と判定。テストされていない）"
        elif result == "success":
            verdict = "不要だったが実行して成功"
        else:
            verdict = "NG"
            problems.append(f"{job}: 実行されて結果が {result}")
        lines.append(f"| {job} | {'必要' if expected else '不要'} | {result} | {verdict} |")
    if outputs["mode"] != "docs" and not expected_any:
        problems.append("docs 以外の変更なのに実行するジョブが無い（判定の不整合。fail closed）")
    for job in INFORMATIONAL:
        node = needs.get(job) or {}
        lines.append(f"| {job} (非ゲート) | {'必要' if outputs.get('run_connectorj26') == 'true' else '不要'} | {node.get('result', '-')} | 参考（ゲートに影響しない） |")
    return not problems, problems, lines


def main() -> int:
    outputs: dict = {}
    try:
        needs = json.loads(os.environ["NEEDS_JSON"])
        outputs = (needs.get("changes") or {}).get("outputs") or {}
        ok, problems, lines = evaluate(needs)
    except Exception as e:  # noqa: BLE001 - 評価できなければ失敗
        ok, problems, lines = False, [f"ゲートの評価に失敗: {type(e).__name__}: {e}"], []
    summary = ["## CI gate", "", f"判定: **{'成功' if ok else '失敗'}**  mode=`{outputs.get('mode')}`  理由: {outputs.get('reason')}", "",
               "| ジョブ | 判定 | 結果 | 扱い |", "|---|---|---|---|", *lines, ""]
    summary += [f"- ❌ {p}" for p in problems]
    text = "\n".join(summary)
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    for p in problems:
        print(f"::error::{p}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
